# Ejecutor de `ms-sandbox` — especificación de requerimientos

> **Tema 06 — Sandbox / Runtime.**
> Contrato **independiente del lenguaje** del sidecar ejecutor. Este documento es la única fuente de verdad para dos implementaciones paralelas —una en Java 21, otra en Node 22 + TypeScript— que deben ser **funcionalmente indistinguibles** y pasar la misma suite de aceptación (§13).

**Cómo se usa este documento.** Todo lo que dice `DEBE` es obligatorio y verificable con un test de §13. `NO DEBE` es una prohibición: si una implementación lo hace, está mal aunque funcione. `DEBERÍA` es una recomendación fuerte que se puede desviar dejando el motivo escrito en el código. Todo valor numérico está en §4 y es una **constante de compilación**, nunca un parámetro de request.

---

## 0. Cambios desde la primera versión

> **Leer esto primero.** La implementación en Java se construyó contra la versión anterior de este documento y **no** cubre lo de abajo. Las dos implementaciones tienen que quedar alineadas con esta versión.

| # | Cambio | Dónde | Impacto |
|---|---|---|---|
| **C1** | **Ulimit `cpu` en la spec del contenedor.** Faltaba, y sin él vuelven los `TIMEOUT` intermitentes que el prototipo ya había resuelto. | §4.1, §4.2, §4.3, A32 | **Rompe el golden test A1**: el archivo de referencia hay que regenerarlo, y las dos implementaciones tienen que producir el nuevo |
| **C2** | **Paso 5b: `inspect` y campo `oomKilled`.** Sin esto el worker no puede distinguir «se quedó sin memoria» de «el código falló», y eso cambia el veredicto que ve el alumno. | §3.1, §5, R5.10, A31 | Un paso más en la secuencia y un campo más en la respuesta |
| **C3** | **R8.5, watchdog sobre la escritura del paso 4.** Sin él, un contenedor que no consume stdin retiene su cupo de concurrencia para siempre. | §8 | Bug encontrado en la implementación Java |
| **C4** | **R7.5 dejó de contradecir a R7.6.** Antes decía «primera aparición» donde R7.6 decía «última»: dos implementaciones podían divergir ante el mismo stream. | §7 | Java ya usa «última»; Node **DEBE** hacer lo mismo |
| **C5** | **`MAX_CUERPO_DAEMON_BYTES` incorporada a §4.3.** La implementación Java tuvo que inventarla porque hacía falta y no estaba. | §4.3 | Valor fijado para las dos |
| **C6** | **§14 con los números reales de Java** y el motivo del desvío. | §14 | Insumo para la decisión de lenguaje |

**Sobre C1 y el golden test.** A1 compara contra un archivo de referencia versionado. Al agregar el ulimit `cpu` ese archivo queda viejo **a propósito**: es exactamente el mecanismo funcionando. Se regenera una vez, se revisa a ojo el diff, y las dos implementaciones lo comparten. Un cambio en la spec del contenedor que **no** rompa A1 sería la señal de alarma.

**Fuera del alcance de este documento, anotado para no perderlo.** Cuando el ejecutor exista, el worker **DEBE** tratar un `503 RECHAZADA` (§3.3) como *reintentable*: `nack` con requeue y backoff, nunca como fallo del job. Con 120 sesiones concurrentes en el pico, los rechazos por saturación ocurren por diseño; si el worker los toma como error, una ráfaga manda entregas a la DLQ. Eso se implementa en el worker, no acá.

---

## 1. Qué es y qué no es

El **ejecutor** es el único proceso del sistema que tiene montado `/var/run/docker.sock`. Recibe una entrega de un alumno, la corre adentro de un contenedor descartable y devuelve el resultado crudo.

**Su única razón de existir es esta propiedad, y todo el resto del documento está subordinado a ella:**

> **P1 — Invariante de spec fija.** Ningún byte de la spec del contenedor proviene de quien llama. La configuración de seguridad del contenedor está escrita en el código fuente del ejecutor y es idéntica en todas las ejecuciones.

Si una implementación agrega un parámetro que modifique la spec —memoria, imagen, timeout, red, lo que sea— deja de ser un ejecutor y pasa a ser un proxy de la API de Docker, que es exactamente el diseño que este componente descarta. El costo de P1 es real y se acepta: **cambiar el límite de memoria requiere recompilar y redesplegar el ejecutor.**

### Alcance

| El ejecutor **sí** | El ejecutor **no** |
|---|---|
| Crea, arranca, espera, lee y borra un contenedor | Interpreta el contenido de la entrega |
| Aplica la spec de seguridad fija | Decide si el alumno aprobó |
| Separa `stdout`, `stderr` y el bloque de reporte | Parsea el XML de JUnit |
| Impone timeout, concurrencia y limpieza | Persiste nada: no tiene base de datos ni estado entre requests |
| Devuelve el resultado crudo al worker | Habla con RabbitMQ, con Postgres ni con la API pública |

### No-requerimientos, con motivo

Están acá para que **ninguna de las dos implementaciones los agregue por iniciativa propia**:

- **NO DEBE validar ni desempaquetar el tar de la entrega.** Extraer el tar significaría parsear input hostil adentro del proceso privilegiado, que es lo que el diseño evita. La validación estructural del bundle es responsabilidad del worker, aguas arriba. El ejecutor solo impone un tope de bytes (§4.3).
- **NO DEBE reintentar una ejecución fallida.** El reintento es decisión del worker, que es quien tiene el contexto de la entrega.
- **NO DEBE cachear ni reutilizar contenedores entre ejecuciones.** Un contenedor por entrega, `/work` siempre fresco. Reutilizar reactiva una familia de ataques de extracción de tar en dos pasos (CVE-2025-45582).
- **NO DEBE exponer ningún endpoint que permita elegir imagen, límites, red o comando.**

---

## 2. Transporte y superficie de red

**R2.1** — El ejecutor **DEBE** escuchar en un **socket Unix** ubicado en un volumen compartido únicamente con el worker. Ruta: `/run/ejecutor/ejecutor.sock`, permisos `0660`.

**R2.2** — El ejecutor **NO DEBE** escuchar en un puerto TCP en la configuración por defecto. Un endpoint HTTP sin autenticación en una red de Docker es alcanzable por todos los contenedores de esa red; el socket Unix reduce el conjunto de quienes pueden hablarle a "quien tenga el volumen montado".

**R2.3** — Si por una limitación del entorno hiciera falta TCP, **DEBE** ser sobre una red interna dedicada exclusivamente a worker + ejecutor (`internal: true`), y **DEBE** exigir un secreto compartido en un header. Esta variante se documenta como degradada.

**R2.4** — El contenedor del alumno **nunca** puede alcanzar al ejecutor: corre con `NetworkMode: none` (§4.1) y sin el socket montado. Esto es consecuencia de la spec, no algo que el ejecutor deba verificar en runtime.

---

## 3. Contrato HTTP hacia el worker

### 3.1 `POST /ejecutar`

Única operación del servicio.

**Request**

```
POST /ejecutar HTTP/1.1
Content-Type: application/octet-stream
X-Ejecucion-Id: <uuid v4>
Content-Length: <n>

<bytes del tar, sin comprimir>
```

- `X-Ejecucion-Id` **DEBE** estar presente y ser un UUID válido. Se usa como etiqueta del contenedor y como correlación en los logs. **NO** afecta la spec.
- El cuerpo es el tar de la entrega, opaco para el ejecutor.
- **DEBE** rechazarse con `413` si `Content-Length` supera `MAX_BUNDLE_BYTES` (§4.3), sin leer el cuerpo.
- **DEBE** rechazarse con `411` si no viene `Content-Length`. No se acepta `Transfer-Encoding: chunked` en la entrada: necesitamos conocer el tamaño antes de aceptar bytes.

**Response `200`**

```json
{
  "ejecucionId": "3f2b...",
  "resultado": "COMPLETADA",
  "exitCode": 0,
  "oomKilled": false,
  "duracionMs": 4172,
  "stdout": "...",
  "stderr": "...",
  "reporte": "<testsuite ...>...</testsuite>",
  "reporteAusente": false,
  "salidaTruncada": false
}
```

| Campo | Tipo | Significado |
|---|---|---|
| `resultado` | enum §3.2 | Cómo terminó la ejecución **como proceso**. No es el veredicto académico. |
| `exitCode` | int \| null | Código de salida del contenedor. `null` si no llegó a terminar. |
| `oomKilled` | bool | `State.OOMKilled` del `inspect` (§5, paso 5b). Ver R3.4. |
| `duracionMs` | int | Desde antes de `create` hasta después de `wait`. |
| `stdout` / `stderr` | string | Salida demultiplexada, **sin** el bloque de reporte. |
| `reporte` | string \| null | Contenido entre los marcadores de §7. `null` si no apareció. |
| `reporteAusente` | bool | `true` si no se encontró un bloque de reporte válido. |
| `salidaTruncada` | bool | `true` si el ejecutor recortó algún stream por `MAX_SALIDA_BYTES`. |

**R3.1** — El ejecutor **NO DEBE** emitir un veredicto académico (aprobado/desaprobado). Devuelve materia prima; el worker decide. Mezclar las dos cosas metería lógica de negocio en el componente privilegiado.

**R3.4** — `oomKilled` **DEBE** venir del `inspect` del paso 5b y **NO DEBE** inferirse del `exitCode`. El motivo está medido: con la JVM bien configurada el que se queda sin memoria es la JVM y no el cgroup, así que el out-of-memory llega como **`exitCode: 3` con `OOMKilled: false`**, y no como el `137` que uno esperaría. Los dos caminos existen y el worker necesita los dos datos para mapearlos. Un `exitCode` sin `oomKilled` deja al worker sin poder distinguir «se quedó sin memoria» de «el código del alumno falló», y eso cambia el veredicto que ve el alumno.

### 3.2 Enum `resultado`

| Valor | Cuándo | `exitCode` |
|---|---|---|
| `COMPLETADA` | El contenedor terminó por su cuenta dentro del timeout | el real |
| `TIMEOUT` | Venció `TIMEOUT_EJECUCION_MS`; se hizo `kill` | `null` |
| `ERROR_DAEMON` | El daemon de Docker falló o no respondió | `null` |
| `RECHAZADA` | Saturación: la cola está llena (§9) | `null` |

`COMPLETADA` con `exitCode != 0` es normal: significa que la compilación o los tests fallaron. Eso lo interpreta el worker.

### 3.3 Códigos de estado

| Código | Caso |
|---|---|
| `200` | Ejecución terminada — incluyendo `TIMEOUT`, que es un resultado, no un error |
| `400` | Falta `X-Ejecucion-Id` o no es un UUID |
| `411` | Falta `Content-Length` |
| `413` | Bundle mayor a `MAX_BUNDLE_BYTES` |
| `503` | Cola llena (`resultado: "RECHAZADA"`), con header `Retry-After` |
| `502` | `ERROR_DAEMON` |

**R3.2** — Los mensajes de error **NO DEBEN** incluir rutas del host, versiones del daemon ni el cuerpo de la respuesta de Docker. Un mensaje corto y un id de correlación.

### 3.4 `GET /salud`

Devuelve `200` con `{"daemon": "ok", "enVuelo": 3, "enCola": 0}`. **DEBE** consultar `GET /_ping` del daemon con timeout corto y devolver `503` si falla.

**R3.3** — Saturación **no** es enfermedad. Con la cola llena, `/salud` sigue devolviendo `200`: si devolviera `503` el orquestador reiniciaría el ejecutor justo cuando más se lo necesita.

---

## 4. La spec del contenedor

Es el corazón del componente. Todo esto es **constante en el código fuente**.

### 4.1 JSON de `POST /containers/create`

Lo único que varía entre ejecuciones son las dos marcas señaladas.

```json
{
  "Image": "sandbox-runner:<TAG_FIJO>",
  "Entrypoint": ["/opt/sandbox/entrypoint.sh"],
  "Cmd": [],
  "User": "1000:1000",
  "WorkingDir": "/work",
  "Env": [],
  "OpenStdin": true,
  "StdinOnce": true,
  "AttachStdin": true,
  "AttachStdout": false,
  "AttachStderr": false,
  "Tty": false,
  "NetworkDisabled": true,
  "Labels": {
    "sandbox": "1",
    "sandbox.ejecucion": "<X-Ejecucion-Id>"
  },
  "HostConfig": {
    "NetworkMode": "none",
    "ReadonlyRootfs": true,
    "Tmpfs": { "/work": "rw,noexec,nosuid,nodev,size=64m,mode=0700" },
    "Memory": 536870912,
    "MemorySwap": 536870912,
    "MemorySwappiness": 0,
    "NanoCpus": 1000000000,
    "PidsLimit": 128,
    "CapDrop": ["ALL"],
    "CapAdd": [],
    "SecurityOpt": ["no-new-privileges:true"],
    "Privileged": false,
    "AutoRemove": false,
    "Binds": [],
    "Mounts": [],
    "Devices": [],
    "RestartPolicy": { "Name": "no" },
    "LogConfig": {
      "Type": "json-file",
      "Config": { "max-size": "8m", "max-file": "1" }
    },
    "Ulimits": [
      { "Name": "cpu",    "Soft": 20,       "Hard": 20 },
      { "Name": "nofile", "Soft": 256,      "Hard": 256 },
      { "Name": "nproc",  "Soft": 128,      "Hard": 128 },
      { "Name": "fsize",  "Soft": 33554432, "Hard": 33554432 }
    ]
  }
}
```

Y el nombre del contenedor, en la query: `?name=sandbox-<X-Ejecucion-Id>`.

### 4.2 Por qué cada campo, para la defensa

| Campo | Motivo |
|---|---|
| `NetworkMode: none` | **La defensa central contra el alumno.** Sin red no hay exfiltración ni descarga de payloads. |
| `ReadonlyRootfs: true` | Innegociable. Es lo que hace que un path traversal exitoso en el tar igual falle en el `write`. |
| `Tmpfs /work` con `noexec` | Único punto escribible, muere con el contenedor. `noexec` no molesta: los `.class` los lee la JVM, no los ejecuta el kernel. |
| `size=64m` en el tmpfs | Acota la bomba de descompresión; sus páginas se cobran al cgroup de memoria. |
| `Memory` = `MemorySwap` | Sin swap. Con swap, el límite de memoria deja de ser un límite de tiempo. |
| `PidsLimit` | Fork bomb. `Runtime.exec` existe. |
| `CapDrop: ALL` + `no-new-privileges` | Sin capacidades y sin poder recuperarlas vía setuid. |
| `Tty: false` | Necesario para que la salida venga multiplexada (§6). Además cierra CVE-2025-52565, que solo afecta contenedores con consola. |
| `AutoRemove: false` | **Obligatorio.** Con `true` el contenedor puede desaparecer antes de que leamos `logs`. La limpieza es explícita (§10). |
| `LogConfig` explícito | `logs` solo funciona con `json-file` o `local`. Fijarlo evita un acoplamiento oculto al `daemon.json` del host. |
| `AttachStdout/Stderr: false` | No nos adjuntamos a la salida: la drena el log driver. Elimina el riesgo de deadlock por buffer lleno. |
| `Ulimits[cpu]` | **Tiempo de CPU, no de pared.** El límite que le corta el paso al alumno se mide en CPU consumida porque *no cuenta el tiempo en que el host le dio el procesador a otra ejecución del pool*: es lo que elimina los `TIMEOUT` intermitentes por varianza del pool, en vez de acolcharlos con margen. Al agotarse, el kernel manda `SIGXCPU`. Es lo mismo que hace `isolate`, y por lo tanto Judge0 y Piston. |
| `Binds`/`Mounts`/`Devices` vacíos explícitos | Se escriben aunque sean vacíos, para que el golden test de §13.1 los cubra. |

### 4.3 Constantes

| Constante | Valor | Nota |
|---|---|---|
| `MAX_BUNDLE_BYTES` | `2097152` (2 MiB) | Tope del cuerpo del request |
| `TIMEOUT_EJECUCION_MS` | `60000` | Reloj de pared del ejecutor, desde `start`. Es la **red de última instancia**, no el mecanismo |
| `TIMEOUT_CPU_SEGUNDOS` | `20` | Ulimit `cpu` del contenedor: techo de CPU de **todas** las fases juntas. Los relojes por fase viven en el entrypoint de la imagen (`ulimit -t`), no acá |

**R4.1** — `TIMEOUT_CPU_SEGUNDOS` **DEBE** ser holgadamente menor que `TIMEOUT_EJECUCION_MS` expresado en segundos. Con `NanoCpus` = 1 CPU, el tiempo de CPU nunca supera al de pared, así que si los dos números se acercan el reloj de pared dispara primero **siempre** y el límite de CPU queda decorativo — que es justo el problema que C1 viene a arreglar. La relación 20 s de CPU contra 60 s de pared deja al contenedor margen para consumir su presupuesto completo aun con el host bajo contención. Al revés, el reloj de pared tiene que ser generoso precisamente porque **el presupuesto de CPU no tiene cota superior en tiempo de pared**: un proceso puede consumir 20 s de CPU en 55 s de reloj si la máquina está saturada.
| `TIMEOUT_DAEMON_MS` | `5000` | Por llamada a la API de Docker (salvo `wait`) |
| `MAX_SALIDA_BYTES` | `1048576` (1 MiB) | Por cada stream, tras demultiplexar |
| `MAX_FRAME_BYTES` | `1048576` (1 MiB) | Tope de un frame individual (§6) |
| `MAX_CUERPO_DAEMON_BYTES` | `16777216` (16 MiB) | Tope de una respuesta del daemon leída entera en memoria. Con `max-size: 8m` el log no debería acercarse; existe para que un daemon anómalo no nos haga crecer sin límite |
| `MAX_CONCURRENTES` | `8` | Contenedores simultáneos |
| `MAX_COLA` | `16` | Esperando turno; por encima, `503` |
| `INTERVALO_BARRIDO_MS` | `300000` (5 min) | Limpieza de huérfanos |
| `EDAD_HUERFANO_MS` | `600000` (10 min) | Antigüedad para considerar huérfano |
| `VERSION_API_DOCKER` | `v1.43` | Fijada en el path |

---

## 5. Secuencia de ejecución

Todas las rutas van prefijadas con `/{VERSION_API_DOCKER}`. **R5.0** — La versión **DEBE** ir explícita en el path; **NO DEBE** usarse el default del daemon.

| # | Llamada | Notas |
|---|---|---|
| 1 | `POST /containers/create?name=sandbox-<id>` | Cuerpo de §4.1. `Content-Type: application/json` |
| 2 | `POST /containers/<id>/attach?stream=1&stdin=1` | **Antes de `start`.** Devuelve `101` |
| 3 | `POST /containers/<id>/start` | |
| 4 | Escribir en el socket adjunto y **cerrarlo entero** | Primero el nonce, después el tar (§7) |
| 5 | `POST /containers/<id>/wait?condition=not-running` | Con `TIMEOUT_EJECUCION_MS`; si vence → `POST /containers/<id>/kill` y después igual `wait` |
| 5b | `GET /containers/<id>/json` | Solo para leer `State.OOMKilled` (R5.10) |
| 6 | `GET /containers/<id>/logs?stdout=1&stderr=1` | Respuesta HTTP normal. Se lee entera y se demultiplexa en memoria |
| 7 | `DELETE /containers/<id>?force=1&v=1` | En un bloque de limpieza garantizada |

**R5.1** — El paso 2 **DEBE** ejecutarse antes del paso 3. Si el contenedor arranca antes de que estemos conectados, puede intentar leer stdin sin nadie del otro lado.

**R5.2** — Escritura y lectura **NUNCA** ocurren simultáneamente sobre el mismo socket. El socket del paso 2 es de una sola dirección: se escribe y se cierra. Esta es la decisión de diseño que hace que el componente sea chico; una implementación que la viole está fuera de spec aunque funcione.

**R5.3** — El paso 7 **DEBE** ejecutarse siempre: en el camino feliz, en timeout, y ante cualquier excepción. Si el `DELETE` falla, se registra y se confía en el barrido de §10.

**R5.4** — El paso 6 **DEBE** ocurrir antes del paso 7. Borrado el contenedor, los logs no existen más.

**R5.10** — El paso 5b **DEBE** ejecutarse después del `wait` y antes del `DELETE`, con `TIMEOUT_DAEMON_MS`. De la respuesta se lee **únicamente** `State.OOMKilled`; el resto del `inspect` se ignora. Si la llamada falla, **NO DEBE** abortarse la ejecución: se devuelve `oomKilled: false` y se registra el fallo, porque el resultado ya está y perderlo por un dato de diagnóstico sería peor. En `TIMEOUT` el paso 5b se ejecuta igual: un contenedor que fue matado por el límite de memoria y además llegó al reloj es un caso real.

### 5.1 Requisitos del cliente HTTP contra el socket

**R5.5** — HTTP/1.1, con header `Host: localhost`.
**R5.6** — **NO DEBE** seguir redirecciones.
**R5.7** — **DEBE** soportar `Transfer-Encoding: chunked` en las respuestas del daemon (`logs` la usa).
**R5.8** — En el paso 2 **DEBE** enviar `Upgrade: tcp` y `Connection: Upgrade`, esperar `101` y quedarse con el socket crudo. Cualquier otro código es `ERROR_DAEMON`.
**R5.9** — Si el cliente HTTP entrega, junto con la respuesta del `101`, bytes ya leídos del stream (el parámetro `head` del evento `upgrade` en Node, y su equivalente en cualquier buffer de lectura anticipada), esos bytes **DEBEN** anteponerse al stream. Ignorarlos se come el primer bloque de datos y produce un bug intermitente.

---

## 6. Demultiplexado de la salida

`logs` y `attach` sin TTY devuelven los dos streams entrelazados en frames con un encabezado de 8 bytes:

```
 byte 0      bytes 1-3     bytes 4-7            bytes 8..8+N
┌─────────┬─────────────┬──────────────────┬──────────────────────┐
│  tipo   │   padding   │  largo (uint32,  │       payload        │
│ 1=out   │   (ceros)   │   big-endian)    │      (N bytes)       │
│ 2=err   │             │                  │                      │
└─────────┴─────────────┴──────────────────┴──────────────────────┘
```

**R6.1** — Antes de demultiplexar, **DEBE** verificarse que el `Content-Type` de la respuesta sea `application/vnd.docker.multiplexed-stream`. Si es `application/vnd.docker.raw-stream`, el contenedor tiene TTY y la salida **no** viene enmarcada: eso es `ERROR_DAEMON`, no un caso a manejar. Es un assert de dos líneas que convierte una corrupción silenciosa en un error inmediato.

**R6.2** — Si el largo declarado supera `MAX_FRAME_BYTES`, **DEBE** abortarse la lectura con `ERROR_DAEMON`. **NO DEBE** reservarse el buffer antes de validar el largo: el campo es un `uint32` y admite valores de hasta 4 GiB.

**R6.3** — Un frame con largo `0` es válido: payload vacío, seguir leyendo. **NO DEBE** trabar el bucle ni tratarse como fin de stream.

**R6.4** — Un tipo distinto de `1` o `2` **DEBE** ser error fatal. **NO DEBE** asumirse `stdout` por defecto: adivinar es como se corrompe un resultado en silencio.

**R6.5** — Los frames pueden llegar partidos, y un mismo frame puede venir en varios trozos de red. La lectura **DEBE** acumular en un buffer hasta tener el encabezado completo, y después hasta tener el payload completo.

**R6.6** — Una línea de texto puede cruzar dos frames, y un frame puede traer varias líneas. **NO DEBE** parsearse por líneas adentro de un frame: se concatena todo el stream primero y recién ahí se separa.

**R6.7** — Si un stream supera `MAX_SALIDA_BYTES`, **DEBE** conservarse el **principio** y descartarse el resto, marcando `salidaTruncada: true`. El reporte y los errores de compilación están al principio.

---

## 7. Nonce, entrada y separación del reporte

El código del alumno escribe en el mismo `stdout` por donde viaja el reporte de tests. Sin un separador que el alumno no pueda producir, un `println` con el formato del reporte falsifica el resultado. Ninguna defensa de aislamiento cubre esto: no es una fuga, es una falsificación por un canal legítimo.

**R7.1** — El ejecutor **DEBE** generar, por ejecución, un nonce aleatorio criptográficamente seguro de 16 bytes, en hexadecimal minúscula (32 caracteres).

**R7.2** — El nonce **DEBE** viajar como **primera línea de stdin**, terminada en `\n`, seguida inmediatamente por los bytes del tar. Es decir, el paso 4 de §5 escribe: `<nonce>\n` + `<tar>` y después cierra.

**R7.3** — El nonce **NO DEBE** pasarse por variable de entorno, por argumento, ni por archivo. Una variable de entorno es recuperable desde el proceso del alumno leyendo `/proc/1/environ`, aunque el entrypoint la borre: el `unset` cambia la memoria del shell, no el snapshot del kernel.

**R7.4** — El entrypoint de la imagen lee esa primera línea en una variable de shell **no exportada**, y emite el reporte entre marcadores:

```
---SANDBOX-<nonce>-INICIO---
<contenido del reporte>
---SANDBOX-<nonce>-FIN---
```

**R7.5** — El ejecutor **DEBE** extraer el bloque entre la **última** aparición del marcador de inicio y la **primera** del marcador de fin **posterior a ella**, buscando la cadena exacta con **su** nonce. Ese bloque **DEBE** removerse de `stdout` antes de devolverlo. Si no aparece, `reporte: null` y `reporteAusente: true`.

**R7.6** — La regla de «la última de inicio, la primera de fin posterior a ella» no es una preferencia estética. Un alumno no puede producir el nonce, pero fijar el criterio elimina la ambigüedad de raíz y, sobre todo, **evita que dos implementaciones diverjan** ante el mismo stream.

**R7.7** — La extracción del reporte ocurre **después** del truncado de R6.7, sobre el texto ya recortado. Extraer primero exigiría bufferear el stream sin tope, que es justo lo que R6.7 evita. Consecuencia esperada: si el reporte quedó fuera de los bytes conservados, la respuesta trae `reporteAusente: true` **y** `salidaTruncada: true`, y el worker distingue el caso por esa combinación.

**R7.8** — De `stdout` se remueve desde el primer carácter del marcador de inicio hasta el último del de fin, más el salto de línea inmediatamente posterior si existe. Del contenido del reporte se recorta el salto que sigue al marcador de inicio y el que precede al de fin, y nada más.

> **Verificación obligatoria.** El mecanismo depende de que `read` en el shell del entrypoint no consuma más de la primera línea del descriptor, dejando el resto para `tar`. Es el comportamiento esperado en `dash` y `busybox sh` porque leen de a un byte sobre descriptores no posicionables, pero **hay que probarlo** (§13.3) antes de dar por buena esta sección.

---

## 8. Timeouts

**R8.1** — El reloj de `TIMEOUT_EJECUCION_MS` arranca después de que `start` responde.

**R8.2** — Al vencer: `POST /kill`, después `wait` de nuevo con un timeout corto, después `logs` (la salida parcial se devuelve igual: sirve para diagnosticar), después `delete`. Resultado `TIMEOUT`, `exitCode: null`.

**R8.3** — Cada llamada a la API de Docker distinta de `wait` **DEBE** tener `TIMEOUT_DAEMON_MS`. Un daemon colgado no debe colgar al ejecutor.

**R8.4** — Un `TIMEOUT` es indistinguible, desde afuera, de un `while(true)` del alumno y de un half-close mal hecho. Por eso los logs del ejecutor **DEBEN** registrar cuántos bytes se escribieron en stdin y si el cierre se completó, en cada ejecución.

**R8.5** — La escritura del paso 4 **DEBE** tener su propio tope de tiempo. Si el contenedor no consume stdin, el buffer del pipe se llena (unas decenas de KiB) y la escritura queda bloqueada indefinidamente: el reloj de `TIMEOUT_EJECUCION_MS` todavía no se está evaluando, porque eso ocurre recién en el paso 5. Sin este tope, un contenedor que arranca y no lee retiene su cupo de concurrencia para siempre, y con `MAX_CONCURRENTES` casos así el ejecutor deja de atender sin que `/salud` lo note. Al vencer: abandonar la escritura, registrar el cierre como incompleto y seguir con el paso 5, que resolverá en `TIMEOUT`.

---

## 9. Concurrencia y saturación

**R9.1** — Como máximo `MAX_CONCURRENTES` contenedores vivos a la vez. El control **DEBE** estar en el ejecutor, que es quien sabe cuántos hay, y no delegarse al worker.

**R9.2** — Hasta `MAX_COLA` requests esperando turno. Por encima: `503` inmediato con `Retry-After: 5` y `resultado: "RECHAZADA"`.

**R9.3** — El límite de concurrencia **es parte del control de seguridad**, no una optimización. Sin él, un worker comprometido tumba el host pidiendo ejecuciones, y con 120 sesiones concurrentes en el pico probablemente lo tumbe sin estar comprometido.

**R9.4** — El ejecutor **NO DEBE** mantener estado entre requests más allá del semáforo y la cola en memoria. Reiniciarlo no pierde nada que importe.

---

## 10. Limpieza de huérfanos

**R10.1** — Al arrancar, y cada `INTERVALO_BARRIDO_MS`, **DEBE** listar `GET /containers/json?all=1&filters={"label":["sandbox=1"]}` y borrar los que superen `EDAD_HUERFANO_MS`.

**R10.2** — El barrido **NO DEBE** borrar contenedores en vuelo de esta misma instancia. El filtro por edad alcanza si `EDAD_HUERFANO_MS` es holgadamente mayor que `TIMEOUT_EJECUCION_MS` — con los valores de §4.3, 20 veces mayor.

**R10.3** — Un fallo del barrido se registra y no afecta las ejecuciones en curso.

**R10.4** — Este es el **único** barrido del sistema. `04-ms-sandbox-worker.md` §13 describe un *janitor* dentro del worker, con las etiquetas `sandbox.job` y `sandbox.worker`: ese componente deja de ser implementable en el momento en que el worker pierde el socket de Docker, y sus etiquetas no son las de §4.1. La limpieza vive donde vive el privilegio.

---

## 11. Requisitos no funcionales

**R11.1 — Imagen.** Base *distroless*: sin shell y sin gestor de paquetes. Aplica igual a Java y a Node.

**R11.2 — Usuario.** El proceso corre como usuario no-root, en un grupo con acceso al socket de Docker.

**R11.3 — Dependencias.** Solo se admiten dependencias que **no** parseen bytes controlados por un atacante. Una librería de JSON es aceptable: el JSON de la spec lo serializamos nosotros y el que parseamos viene del daemon, que ya es parte de la base de confianza.

**R11.4 — Prohibición explícita de clientes de Docker.** **NO DEBE** usarse `docker-java`, `dockerode` ni equivalentes. El motivo no es el tamaño: esas librerías ponen, en el mismo proceso que tiene el socket, un objeto con un setter equivalente a `withPrivileged(true)`. Reintroducen exactamente la superficie que este componente existe para eliminar.

**R11.5 — Tamaño del código propio.** Objetivo: **por debajo de 400 líneas** sin contar tests. No es una métrica cosmética: la corrección de un control de seguridad se establece leyéndolo, no probándolo, y eso solo es viable si es chico (Saltzer & Schroeder, 1975, *economía de mecanismo*).

**R11.6 — Logs del ejecutor.** Por ejecución: id, duración de cada paso, bytes escritos, bytes leídos por stream, resultado. **NO DEBEN** registrarse el contenido del bundle, la salida del alumno ni el nonce.

**R11.7 — Apagado ordenado.** Ante `SIGTERM`: dejar de aceptar requests, esperar a que terminen las ejecuciones en vuelo hasta `TIMEOUT_EJECUCION_MS`, borrar sus contenedores, salir.

---

## 12. Invariantes de seguridad verificables

Cada una tiene un test en §13. Son las afirmaciones que se sostienen en la defensa.

| # | Invariante |
|---|---|
| **I1** | El JSON de `create` es byte a byte idéntico entre ejecuciones, salvo el label `sandbox.ejecucion` y el `name`. |
| **I2** | Ningún campo del request influye en ningún campo de la spec. |
| **I3** | Un traversal exitoso en el tar igual falla al escribir, porque el rootfs es de solo lectura. |
| **I4** | El contenedor del alumno no tiene red ni acceso al socket de Docker. |
| **I5** | El código del alumno no puede producir el bloque de reporte. |
| **I6** | Todo contenedor creado termina borrado, por la vía normal o por el barrido. |
| **I7** | El ejecutor nunca desempaqueta ni interpreta el tar. |

---

## 13. Criterios de aceptación

Las dos implementaciones corren esta misma suite. Cada test es una afirmación binaria.

### 13.1 Golden test de la spec — el más importante

**A1** — Serializar el JSON de `create` para tres ids distintos y compararlo contra un archivo de referencia versionado, normalizando solo `name` y el label. Debe ser idéntico. Este test es lo que convierte I1 y I2 de afirmación en evidencia, y **DEBE** fallar si alguien agrega un parámetro a `POST /ejecutar` que toque la spec.

> **C1 rompe este test a propósito.** Al agregar el ulimit `cpu`, el archivo de referencia queda viejo: se regenera **una vez**, se revisa el diff a ojo (tiene que ser exactamente una entrada nueva en `Ulimits`) y se comparte entre las dos implementaciones. Que A1 falle ante un cambio de la spec es el mecanismo funcionando; que no fallara sería la alarma.

**A2** — El archivo de referencia es el mismo para Java y para Node. Si las dos implementaciones no producen el mismo JSON, una de las dos está mal.

### 13.2 Camino feliz

**A3** — Una entrega que compila y pasa: `resultado: COMPLETADA`, `exitCode: 0`, `reporte` no vacío, `reporteAusente: false`.
**A4** — Una entrega que no compila: `COMPLETADA`, `exitCode != 0`, el error de compilación aparece en `stderr`.
**A5** — Una entrega que falla tests: `COMPLETADA`, `exitCode != 0`, el reporte tiene la falla.

### 13.3 Protocolo

**A6** — *El test que valida §7.* Imagen con entrypoint `sh -c 'read N; tar -tf - && echo "FIN $N"'`. Escribir nonce + tar, cerrar. Debe imprimir `FIN <nonce>` y el listado completo del tar. Si falla, §7 hay que rediseñarla y **este test bloquea todo lo demás**.
**A7** — Verificar que la respuesta de `logs` trae `application/vnd.docker.multiplexed-stream`.
**A8** — Con un tar armado para que la salida llegue en muchos frames chicos, verificar que el texto reconstruido es exacto.
**A9** — Alimentar al demuxer con un stream sintético que declare un frame de 4 GiB: debe abortar sin reservar memoria.
**A10** — Stream sintético con un frame de largo 0 en el medio: debe procesarse sin trabarse.
**A11** — Stream sintético con tipo de stream `7`: debe ser error fatal, no asumirse stdout.
**A12** — Stream sintético donde una línea cruza dos frames: el texto reconstruido no debe tener cortes espurios.

### 13.4 Entrada hostil

Los tars se construyen a mano (`tarfile` de Python permite armar cualquier entrada). Cada test verifica **dos** cosas: que la extracción falla o ignora la entrada, **y** que el archivo objetivo no cambió. La salida de cada uno se guarda como evidencia para la defensa.

| Test | Tar | Se espera |
|---|---|---|
| **A13** | `../../opt/junit/junit.jar` | Rechazado por tar; el JAR intacto |
| **A14** | symlink `Solucion.java → /etc/passwd`, después archivo con ese nombre | Sin escritura a través del enlace |
| **A15** | hard link a `/opt/junit/junit.jar` | Falla |
| **A16** | symlink a directorio + archivo "adentro" | Falla — la forma de CVE-2025-45582 |
| **A17** | nombre absoluto `/etc/cron.d/pwn` | Sin escritura fuera de `/work` |
| **A18** | entrada de tipo device | No se crea (no somos root) |
| **A19** | header PAX que pisa el nombre | Rechazado |
| **A20** | nombre con `\n` y texto que imita un reporte | No ensucia `stdout` ni el bloque de reporte |

**A21** — Código de alumno que imprime `---SANDBOX-<hex al azar>-INICIO---` y un reporte falso: `reporte` debe seguir siendo el verdadero. (Invariante I5.)
**A22** — Código de alumno que lee `/proc/1/environ` y lo imprime: el nonce no debe aparecer.
**A23** — Código de alumno que intenta abrir un socket: falla. (Invariante I4.)
**A24** — Código de alumno que escribe hasta llenar `/work`: el contenedor muere por límite, no el host.
**A25** — `while(true) System.out.println(...)`: `resultado: TIMEOUT`, `salidaTruncada: true`, y el principio de la salida conservado.

### 13.5 Operación

**A26** — `MAX_CONCURRENTES + MAX_COLA + 1` requests simultáneos: exactamente uno recibe `503`.
**A27** — Bundle de `MAX_BUNDLE_BYTES + 1`: `413` sin haber leído el cuerpo.
**A28** — Matar el ejecutor entre `start` y `delete`; al reiniciarlo, el barrido borra el contenedor. (Invariante I6.)
**A29** — Detener el daemon de Docker: `POST /ejecutar` responde `502` dentro de `TIMEOUT_DAEMON_MS`, y `/salud` responde `503`.
**A30** — `SIGTERM` con una ejecución en vuelo: termina o se mata, el contenedor queda borrado, y el proceso sale.

### 13.6 Tests de los cambios de §0

**A31** — *(C2)* Contra un daemon de prueba que devuelve `State.OOMKilled: true` en el `inspect`, la respuesta trae `oomKilled: true`. Y contra uno que hace fallar el `inspect`, la respuesta trae `oomKilled: false` **y** el resto del resultado intacto: el fallo del paso 5b no aborta la ejecución (R5.10).

**A32** — *(C1)* El JSON de `create` contiene el ulimit `cpu` con `TIMEOUT_CPU_SEGUNDOS`, y ese valor es menor que `TIMEOUT_EJECUCION_MS / 1000` (R4.1). Lo segundo es un assert sobre las constantes, no sobre el JSON: protege contra que alguien suba el presupuesto de CPU sin subir el reloj de pared y deje el límite decorativo sin que nada falle.

**A33** — *(C3)* Contra un contenedor de prueba que arranca y **no** consume stdin, con un bundle mayor al buffer del pipe: la escritura del paso 4 abandona al vencer su tope, el cupo de concurrencia se libera, y el resultado es `TIMEOUT` con el cierre registrado como incompleto (R8.5). Sin este test el bug es invisible: la suite queda verde y el ejecutor se traba en producción.

---

## 14. Dimensionamiento

Estimación para presupuestar el trabajo, no un compromiso.

Estimación inicial y, en la columna de la derecha, lo que efectivamente salió en Java (líneas efectivas, sin comentarios ni blancos).

| Módulo | Java est. | Node est. | **Java real** | Qué hace |
|---|---|---|---|---|
| Cliente HTTP sobre socket Unix | 200–250 | 20–30 | **271** | Request, respuesta, `chunked`, upgrade a `101` |
| Spec del contenedor | 60 | 50 | **90** | Constantes + serialización |
| Demultiplexador | 60 | 60 | **53** | El bucle de §6 |
| Extracción del reporte | — | — | **27** | §7.5–§7.8 |
| Orquestación de la secuencia | 80 | 80 | **137** | Los 7 pasos, timeouts, limpieza garantizada |
| Servidor HTTP + cola | 70 | 50 | **217** | `/ejecutar`, `/salud`, semáforo |
| Barrido de huérfanos | 40 | 40 | **26** | §10 |
| Cableado y frontera | — | — | **48** | `Main`, `Motor`, `Log`, `ErrorDaemon` |
| **Total propio** | **~510–560** | **~300–310** | **869** | Sin tests |

**La estimación de Java se quedó corta por 300 líneas, y el motivo importa para la comparación.** Contaba una sola vez el costo de los sockets Unix, del lado del cliente. En realidad se paga **dos** veces: `java.net.http.HttpClient` no habla sockets Unix (JDK-8377806), y `com.sun.net.httpserver` solo bindea `InetSocketAddress`, así que también hay que escribir a mano el **servidor**. Por eso `Servidor` triplica lo estimado. En Node los dos lados vienen en la librería estándar.

**R14.0** — Cuando la implementación en Node esté, la comparación **DEBE** hacerse sobre esta misma tabla, con líneas efectivas medidas igual. El objetivo de R11.5 (< 400 líneas) no se cumplió en Java; si Node tampoco lo alcanza, el que hay que revisar es el objetivo, no las implementaciones.

**R14.1** — Terminadas las dos implementaciones, **DEBE** hacerse una revisión línea por línea de la que se elija, por dos personas que no la escribieron, con las observaciones anotadas y versionada en el repo. El argumento de que elegimos un lenguaje que el equipo puede auditar solo vale si efectivamente lo auditamos: "podemos leerlo" es una hipótesis, "lo leímos" es evidencia.

---

## 15. Referencias

- Saltzer & Schroeder, *The Protection of Information in Computer Systems*, Proc. IEEE 63(9), 1975 — economía de mecanismo, mínimo privilegio.
- Provos, Friedl & Honeyman, *Preventing Privilege Escalation*, USENIX Security 2003 — la privsep de OpenSSH; el ejecutor tiene la misma estructura: un proceso privilegiado que atiende **un menú fijo** de pedidos.
- Docker Engine API `v1.43` — `containers/create`, `attach`, `wait`, `logs`.
- JDK-8377806, *HTTP over Unix Domain Sockets*; JEP 380, *Unix domain socket channels*.
- CVE-2025-45582 — GNU tar ≤ 1.35, traversal en dos extracciones; el motivo de no reutilizar `/work`.
- CVE-2025-52565 — runc; solo afecta contenedores con `Tty: true`.
- Documentos hermanos: `03-ms-sandbox-ejecucion.md` (por qué la entrega va por stdin), `05-ms-sandbox-patrones.md` (por qué ejecutor y no proxy).
