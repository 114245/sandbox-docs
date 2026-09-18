# Ejecutor de `ms-sandbox`: especificación

> Contrato del sidecar ejecutor: el protocolo con el worker, la especificación fija del contenedor,
> los timeouts, la concurrencia y los criterios de aceptación. Esta es la spec del ejecutor del MVP;
> el prototipo en [`ms-sandbox/ejecutor/`](../../ms-sandbox/ejecutor/README.md) implementa una
> versión anterior (1 CPU, encabezado de perfil con versión, identificadores en castellano) y no es
> el entregable.

## Decisiones

| # | Decisión | Por qué | Descartado |
|---|---|---|---|
| **D1** | El ejecutor es un sidecar que arma él mismo la spec del contenedor. El worker no habla con Docker | El worker nunca tiene acceso al socket de Docker | Un proxy del socket que deja la spec en manos del cliente |
| **D3** | Versión de la API de Docker fija en `v1.43` | El mínimo de la API varía entre builds del Engine; verificado contra Docker real | — |
| **D4** | El worker llega al ejecutor por socket Unix, y el ejecutor a Docker sólo por `unix://` o `npipe://`. TCP está prohibido | El cierre abortivo del canal adjunto por TCP puede truncar `stdin` | TCP: medido con un daemon de mentira, truncó hasta el 76% de las recepciones bajo carga |
| **D5** | El bundle entra al contenedor por `stdin`, como un tar, nunca por `docker cp` ni por red | Saca del camino el endpoint más peligroso de Docker | Montar una carpeta del host; `docker cp` (falla contra el rootfs de solo lectura, y aunque se permitiera, el montaje de `/work` tapa lo copiado) |
| **D9** | El ejecutor se implementa en Java 21 | — | Una implementación paralela en Node |
| **D15** | 2 CPU por contenedor en `java21-junit` | Medido de punta a punta: el mismo bundle tarda 3,3 s con 2 CPU y 8,4 s con 1 | 1 CPU |
| **D17** | Catálogo estático de tres perfiles (`java21-junit`, `java21-pmd`, `java21-checkstyle`), cargado al arranque, sin CRUD y sin versión en el identificador | Alcanza para el MVP y no expone administración | CRUD dinámico de perfiles |
| **D18** | Los límites viven en el perfil, nunca en el request. Presupuestos separados de compilación y de tests | T05 no puede pedir más recursos de los que el sandbox decide dar | — |

---

## 1. Qué es y qué no es

El **ejecutor** es el único proceso del sistema que tiene montado `/var/run/docker.sock`. Recibe una
entrega de un alumno, la corre adentro de un contenedor descartable y devuelve el resultado crudo.

Su única razón de existir es esta propiedad, y todo el resto del documento está subordinado a ella:

> **P1 — Invariante de spec fija.** Ningún byte de la spec del contenedor proviene de quien llama. La
> configuración de seguridad del contenedor está escrita en el código fuente del ejecutor y es
> idéntica en todas las ejecuciones.

Si una implementación agrega un parámetro que modifique la spec —memoria, imagen, timeout, red, lo
que sea— deja de ser un ejecutor y pasa a ser un proxy de la API de Docker, que es exactamente el
diseño que este componente descarta (D1). El costo de P1 es real y se acepta: **cambiar el límite de
memoria requiere recompilar y redesplegar el ejecutor**, o —con el catálogo de perfiles de §4.4—
publicar una nueva versión del perfil, que sigue siendo un artefacto nuestro, versionado y revisado
como se revisa código.

### Alcance

| El ejecutor **sí** | El ejecutor **no** |
|---|---|
| Crea, arranca, espera, lee y borra un contenedor | Interpreta el contenido de la entrega |
| Aplica la spec de seguridad fija | Decide si el alumno aprobó |
| Separa `stdout`, `stderr` y el bloque de reporte | Parsea el reporte de la herramienta del perfil |
| Impone timeout, concurrencia y limpieza | Persiste nada: no tiene base de datos ni estado entre requests |
| Devuelve el resultado crudo al worker | Habla con la cola de trabajo, con la base de datos ni con la API pública |

### No-requerimientos, con motivo

Están acá para que ninguna implementación los agregue por iniciativa propia:

- **NO DEBE validar ni desempaquetar el tar de la entrega.** Extraer el tar significaría parsear
  input hostil adentro del proceso privilegiado, que es lo que el diseño evita. La validación
  estructural del bundle (HU-06, T-06-09 a T-06-11) es responsabilidad de otro componente, aguas
  arriba o dentro de la capa 1 de la imagen (ver [`03-aislamiento.md`](./03-aislamiento.md)). El
  ejecutor sólo impone un tope de bytes (§4.3).
- **NO DEBE reintentar una ejecución fallida.** El reintento es decisión del worker, que es quien
  tiene el contexto de la entrega.
- **NO DEBE cachear ni reutilizar contenedores entre ejecuciones.** Un contenedor por entrega,
  `/work` siempre fresco. Reutilizar reactiva una familia de ataques de extracción de tar en dos
  pasos (CVE-2025-45582).
- **NO DEBE exponer ningún endpoint que permita elegir imagen, límites, red o comando.**

---

## 2. Transporte y superficie de red

**R2.1** — El ejecutor **DEBE** escuchar al worker en un **socket Unix** ubicado en un volumen
compartido únicamente con él. Ruta: `/run/ejecutor/ejecutor.sock`, permisos `0660`. Para hablar con
Docker, el transporte permitido es `unix://` o `npipe://` (R5.13).

**R2.2** — El ejecutor **NO DEBE** escuchar en un puerto TCP ni usar `tcp://`, `http://` o `https://`
para hablar con Docker. Un endpoint HTTP en una red de Docker amplía la superficie de ataque; además,
el cierre abortivo del canal adjunto trunca `stdin` sobre TCP (R5.13).

**R2.3** — TCP no tiene variante degradada de producción. Si el entorno no ofrece `unix://` o
`npipe://` para Docker, el ejecutor **DEBE** fallar al arrancar (E36).

**R2.4** — El contenedor del alumno **nunca** puede alcanzar al ejecutor: corre con
`NetworkMode: none` (§4.1) y sin el socket montado. Esto es consecuencia de la spec, no algo que el
ejecutor deba verificar en runtime.

---

## 3. Contrato HTTP hacia el worker

### 3.1 `POST /executions`

Única operación del servicio.

**Request**

```
POST /executions HTTP/1.1
Content-Type: application/octet-stream
X-Execution-Id: <uuid v4>
X-Profile: <profileId>
Content-Length: <n>

<bytes del tar, sin comprimir>
```

- `X-Execution-Id` **DEBE** estar presente y ser un UUID válido. Se usa como etiqueta del contenedor
  y como correlación en los logs. **NO** afecta la spec.
- `X-Profile` **DEBE** estar presente y hacer *match* exacto con `^[a-z0-9-]+$`. Es la clave de
  búsqueda en el catálogo de §4.4 y lo **único** del request que influye en la spec del contenedor —y
  sólo eligiendo, nunca escribiendo, los cinco campos que §4.4 enumera—. Ver R3.6.
- El cuerpo es el tar de la entrega, opaco para el ejecutor.
- **DEBE** rechazarse con `413` si `Content-Length` supera `MAX_BUNDLE_BYTES` (§4.3), sin leer el
  cuerpo.
- **DEBE** rechazarse con `411` si no viene `Content-Length`. No se acepta `Transfer-Encoding:
  chunked` en la entrada: necesitamos conocer el tamaño antes de aceptar bytes.

**Response `200`**

```json
{
  "executionId": "3f2b...",
  "result": "COMPLETED",
  "exitCode": 0,
  "oomKilled": false,
  "durationMs": 4172,
  "stdout": "...",
  "stderr": "...",
  "report": "<testsuite ...>...</testsuite>",
  "reportMissing": false,
  "outputTruncated": false,
  "profileId": "java21-junit",
  "profileVersion": 3,
  "profileHash": "9f86d081...c9e2f0"
}
```

**R3.7** — Los tres campos del perfil **DEBEN** ir al final y en ese orden. No es cosmético: el orden
de los campos de la respuesta está fijado por un test (E40), y agregarlos al final es lo que permite
que ese test se **extienda** en vez de reescribirse. Un campo nuevo en el medio obliga a reescribir la
afirmación entera, y una afirmación reescrita ya no prueba lo mismo que probaba.

| Campo | Tipo | Significado |
|---|---|---|
| `result` | enum §3.2 | Cómo terminó la ejecución **como proceso**. No es el veredicto académico |
| `exitCode` | int \| null | Código de salida del contenedor. `null` si no llegó a terminar |
| `oomKilled` | bool | `State.OOMKilled` del `inspect` (§5, paso 5b). Ver R3.4 |
| `durationMs` | int | Desde antes de `create` hasta después de `wait` |
| `stdout` / `stderr` | string | Salida demultiplexada, **sin** el bloque de reporte |
| `report` | string \| null | Contenido entre los marcadores de §7, **opaco para el ejecutor**. `null` si no apareció. Ver R3.5 |
| `reportMissing` | bool | `true` si no se encontró un bloque de reporte válido |
| `outputTruncated` | bool | `true` si el ejecutor recortó algún stream por `MAX_OUTPUT_BYTES` |
| `profileId` | string \| null | Perfil con el que se ejecutó (§4.4). `null` sólo en `REJECTED` y `DAEMON_ERROR`, donde puede no haberse llegado a resolver |
| `profileVersion` | int \| null | Versión del perfil, tal como la declara su archivo en el catálogo (§4.4). No forma parte de la clave de búsqueda: la elige `X-Profile` sólo por `profileId` |
| `profileHash` | string \| null | SHA-256 del guion de la capa 2, en hexadecimal. **Lo calcula el ejecutor al cargar el catálogo; NO se lee de ningún campo declarado.** Ver R3.6 |

**R3.1** — El ejecutor no emite ningún veredicto, y tampoco lo emite nadie del lado del sandbox
(D22, el sandbox devuelve salida cruda y no emite veredicto): devuelve materia prima y T05 la
interpreta. Mezclar las dos cosas metería lógica de negocio en el componente privilegiado. El worker
mapea los resultados técnicos de esta respuesta a los seis estados técnicos del contrato con T05 (ver
[`05-worker.md`](./05-worker.md)); no juzga si la entrega aprobó.

**R3.5** — El ejecutor **NO DEBE** interpretar el contenido de `report`. Con la imagen de referencia
ese contenido no es directamente el reporte de la herramienta: es el sobre que arma la capa 1 de la
imagen, opaco para el ejecutor y especificado en
[`03-aislamiento.md`](./03-aislamiento.md) (el sobre de la capa 1). El ejemplo de arriba muestra un
`<testsuite>` por brevedad y eso induce a error: el campo transporta lo que la imagen ponga entre los
marcadores, y quien lo sabe leer es el worker. Cambiar la forma del sobre no toca al ejecutor.

**R3.4** — `oomKilled` **DEBE** venir del `inspect` del paso 5b y **NO DEBE** inferirse del
`exitCode`. El motivo está medido: con la JVM bien configurada el que se queda sin memoria es la JVM y
no el cgroup, así que el agotamiento de memoria llega como **`exitCode: 3` con `OOMKilled: false`**, y
no como el `137` que uno esperaría. Los dos caminos existen (HU-07, CA3) y el worker necesita los dos
datos para mapearlos a `MEMORY_LIMIT`. Un `exitCode` sin `oomKilled` deja al worker sin poder
distinguir «se quedó sin memoria» de «el código del alumno falló».

**R3.6** — `profileHash` **DEBE** calcularse sobre los bytes del guion en el momento de cargar el
catálogo, y **NO DEBE** leerse de un campo del JSON del perfil. Un hash declarado en el archivo es un
hash que puede mentir: describe lo que quien escribió el archivo dice que puso, no lo que
efectivamente se ejecutó. Este campo existe para que el worker pueda, meses después, reconstruir con
qué código exacto se evaluó una entrega; si el dato es autodeclarado no sirve para eso, que es lo
único para lo que sirve.

### 3.2 Enum `result`

| Valor | Cuándo | `exitCode` |
|---|---|---|
| `COMPLETED` | El contenedor terminó por su cuenta dentro del timeout | el real |
| `TIMEOUT` | Venció `EXECUTION_TIMEOUT_MS`; se hizo `kill` | `null` |
| `DAEMON_ERROR` | El daemon de Docker falló o no respondió | `null` |
| `REJECTED` | Saturación: la cola está llena (§9) | `null` |

`COMPLETED` con `exitCode != 0` es normal: significa que la compilación, los tests o el análisis
estático fallaron. Eso lo interpreta el worker, nunca el ejecutor.

### 3.3 Códigos de estado

| Código | Caso |
|---|---|
| `200` | Ejecución terminada — incluyendo `TIMEOUT`, que es un resultado, no un error |
| `400` | Falta `X-Execution-Id` o no es un UUID, **o** falta `X-Profile` o no respeta `^[a-z0-9-]+$` |
| `422` | `X-Profile` bien formado pero **la clave no está en el catálogo** (§4.4) |
| `411` | Falta `Content-Length` |
| `413` | Bundle mayor a `MAX_BUNDLE_BYTES` |
| `503` | Cola llena (`result: "REJECTED"`), con header `Retry-After` |
| `502` | `DAEMON_ERROR` |

**R3.8** — La diferencia entre `400` y `422` **DEBE** respetarse: `400` es «el pedido está mal
escrito», `422` es «el pedido está bien escrito y pide algo que no existe». Al worker le importa
porque son fallas distintas: un `400` es un bug del worker y hay que arreglarlo en el código; un `422`
es un desajuste de despliegue —el worker conoce un perfil que este ejecutor todavía no tiene cargado—
y se arregla desplegando, no recompilando. Colapsar los dos en `400` esconde exactamente el caso que
más va a pasar al agregar un perfil nuevo.

**R3.9** — El orden de validación **DEBE** ser: `X-Execution-Id`, después `X-Profile`, después
`Content-Length`. El `413` de `MAX_BUNDLE_BYTES` se responde **sin leer un solo byte del cuerpo**, y
por eso todo lo que se valida sobre headers tiene que estar antes.

**R3.2** — Los mensajes de error **NO DEBEN** incluir rutas del host, versiones del daemon ni el
cuerpo de la respuesta de Docker. Un mensaje corto y un id de correlación.

### 3.4 `GET /health`

Devuelve `200` con `{"daemon": "ok", "inFlight": 3, "queued": 0}`. **DEBE** consultar `GET /_ping` del
daemon con timeout corto y devolver `503` si falla.

**R3.3** — Saturación **no** es enfermedad. Con la cola llena, `/health` sigue devolviendo `200`: si
devolviera `503` el orquestador reiniciaría el ejecutor justo cuando más se lo necesita.

---

## 4. La spec del contenedor

Es el corazón del componente. Todo esto es **constante en el código fuente**, salvo los cinco campos
que salen del perfil (§4.4) y que están marcados uno por uno abajo.

El que llama no escribe ninguno de esos cinco valores: los **elige** de un conjunto cerrado que
escribimos nosotros. La diferencia es la que hay entre un menú y un formulario. Un request no puede
pedir 8 GiB de memoria; puede pedir el perfil `java21-junit`, y qué significa eso lo decidió un
archivo versionado en nuestro repo, revisado como se revisa código. §4.4 explica por qué eso conserva
la propiedad que importa.

### 4.1 JSON de `POST /containers/create`

Lo que varía entre ejecuciones son el nombre, el label y los **cinco campos del perfil**, marcados con
`←` abajo. Nada más.

```json
{
  "Image": "<perfil.imagen>",                         ← del perfil (§4.4)
  "Entrypoint": ["/opt/sandbox/capa1.sh"],
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
    "sandbox.execution": "<X-Execution-Id>"
  },
  "HostConfig": {
    "NetworkMode": "none",
    "ReadonlyRootfs": true,
    "Tmpfs": { "/work": "rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000" },
    "Memory": "<perfil.limites.memoryMb * 1048576>",   ← del perfil (§4.4)
    "MemorySwap": "<idéntico a Memory>",                ← del perfil (§4.4)
    "MemorySwappiness": 0,
    "NanoCpus": "<perfil.limites.cpus * 1000000000>",   ← del perfil (§4.4)
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
      { "Name": "cpu",    "Soft": "<perfil.limites.cpuS>", "Hard": "<idem>" },  ← del perfil (§4.4)
      { "Name": "nofile", "Soft": 256,      "Hard": 256 },
      { "Name": "nproc",  "Soft": 128,      "Hard": 128 },
      { "Name": "fsize",  "Soft": 33554432, "Hard": 33554432 }
    ]
  }
}
```

Y el nombre del contenedor, en la query: `?name=sandbox-<X-Execution-Id>`.

### 4.2 Por qué cada campo, para la defensa

| Campo | Motivo |
|---|---|
| `NetworkMode: none` | **La defensa central contra el alumno** (HU-06, CA1). Sin red no hay exfiltración ni descarga de payloads |
| `ReadonlyRootfs: true` | Innegociable. Es lo que hace que un path traversal exitoso en el tar igual falle en el `write` |
| `Tmpfs /work` con `noexec` | Único punto escribible, muere con el contenedor. `noexec` no molesta: los `.class` los lee la JVM, no los ejecuta el kernel |
| `size=64m` en el tmpfs | Acota la bomba de descompresión; sus páginas se cobran al cgroup de memoria |
| `Memory` = `MemorySwap` | Sin swap. Con swap, el límite de memoria deja de ser un límite de tiempo |
| `PidsLimit` | Fork bomb. `Runtime.exec` existe |
| `CapDrop: ALL` + `no-new-privileges` | Sin capacidades y sin poder recuperarlas vía setuid |
| `Tty: false` | Necesario para que la salida venga multiplexada (§6). Además cierra CVE-2025-52565, que solo afecta contenedores con consola |
| `AutoRemove: false` | **Obligatorio.** Con `true` el contenedor puede desaparecer antes de que leamos `logs`. La limpieza es explícita (§10) |
| `LogConfig` explícito | `logs` solo funciona con `json-file` o `local`. Fijarlo evita un acoplamiento oculto al `daemon.json` del host |
| `AttachStdout/Stderr: false` | No nos adjuntamos a la salida: la drena el log driver. Elimina el riesgo de deadlock por buffer lleno |
| `Ulimits[cpu]` | **Tiempo de CPU, no de pared, y por proceso, no agregado (A1).** Al agotarse, el kernel manda `SIGXCPU`. `RLIMIT_CPU` se hereda a los hijos pero su contador no: cada programa nuevo arranca en cero, así que este ulimit es un freno anti-descontrol por fase, no el presupuesto del alumno. El presupuesto del alumno es el reloj de CPU que aplica la capa 2 sólo a la fase de tests; el techo agregado real de todo el contenedor es `EXECUTION_TIMEOUT_MS` multiplicado por las CPU del perfil. Medir en CPU y no en pared sigue siendo lo que elimina los `TIMEOUT` intermitentes por varianza del pool, en vez de acolcharlos con margen — eso no cambia. Es lo mismo que hace `isolate`, y por lo tanto Judge0 y Piston |
| `Binds`/`Mounts`/`Devices` vacíos explícitos | Se escriben aunque sean vacíos, para que el golden test de §13 los cubra |
| `Entrypoint` = `capa1.sh` | El entrypoint es nuestro código y es el `PID 1` del contenedor: extrae el tar, corre la capa 2 del perfil, recoge el buzón de reportes y arma el sobre. La evaluación —qué es compilar, qué es un test, qué significa aprobar— vive en la capa 2 del perfil, que llega por stdin. La detección de procesos sobrevivientes vive en la capa 1, que la capa 2 **no controla**: no se puede prevenir, se puede detectar, y la detección tiene que vivir donde el evaluado no llega |
| `Image` del perfil | Un perfil es un lenguaje y su toolchain. Pedir que todos los perfiles entren en una imagen única fue siempre lo que hacía inviable el catálogo |
| `Memory` / `MemorySwap` del perfil | Compilar Java no cuesta lo mismo que correr un análisis estático. Un techo único obliga a dimensionar por el peor caso y desperdiciarlo en todos los demás. Sigue sin haber swap: los dos valores son siempre iguales entre sí |
| `NanoCpus` del perfil | HU-07 fija 2 CPU de referencia para `java21-junit` (D15), medido de punta a punta contra 1 CPU. Cada perfil declara los suyos; ver R4.1 sobre por qué esto no vuelve decorativo al `Ulimits[cpu]` |
| `Ulimits[cpu]` del perfil | Mismo argumento: un freno por fase, en tiempo de CPU, no un presupuesto agregado (A1). El techo duro `MAX_CPU_S` y la relación de R4.1 los hace cumplir el catálogo al **arrancar**, no cada ejecución |
| `uid`/`gid` en las opciones del tmpfs | **Sin esto el contenedor no arranca.** Docker crea el tmpfs como `root`; con `mode=0700` y sin `uid`/`gid`, el proceso —que corre como `1000:1000`— no puede escribir en su único directorio escribible, y el entrypoint muere con `Permission denied` antes de leer el bundle |

### 4.3 Constantes

| Constante | Valor | Nota |
|---|---|---|
| `MAX_BUNDLE_BYTES` | `2097152` (2 MiB) | Tope del cuerpo del request |
| `EXECUTION_TIMEOUT_MS` | `70000` | Reloj de pared del ejecutor, desde `start`. Es la **red de última instancia**, no el mecanismo principal: las fases de `java21-junit` (20 s de compilación, 10 s de CPU de tests, 30 s de respaldo de pared) viven en la capa 2, y la capa 1 tiene su propio backstop de 55 s —mayor que 20 + 30— antes de que este reloj llegue a intervenir. Ver `03-aislamiento.md` |
| `DAEMON_TIMEOUT_MS` | `5000` | Por llamada a la API de Docker (salvo `wait`) |
| `MAX_OUTPUT_BYTES` | `1048576` (1 MiB) | Por cada stream, tras demultiplexar (§6, R6.7) |
| `MAX_DAEMON_BODY_BYTES` | `16777216` (16 MiB) | Tope de una respuesta del daemon leída entera en memoria. Con `max-size: 8m` el log no debería acercarse; existe para que un daemon anómalo no nos haga crecer sin límite |
| `MAX_CONCURRENT` | `6` | Contenedores simultáneos; igual al tamaño del pool de slots del worker |
| `MAX_QUEUE` | `16` | Esperando turno; por encima, `503` |
| `SWEEP_INTERVAL_MS` | `300000` (5 min) | Limpieza de huérfanos |
| `ORPHAN_AGE_MS` | `600000` (10 min) | Antigüedad para considerar huérfano |
| `DOCKER_API_VERSION` | `v1.43` | Fijada en el path (D3) |
| `MAX_MEMORY_MB` | `1024` | **Techo duro de cualquier perfil.** Ningún perfil del catálogo puede declarar más |
| `MAX_CPU_S` | `30` | **Techo duro de cualquier perfil**, para el ulimit `cpu`. Es el techo del freno por fase (A1), no un presupuesto: el techo agregado real de la ejecución es `EXECUTION_TIMEOUT_MS` multiplicado por las CPU del perfil |
| `MAX_SCRIPT_BYTES` | `262144` (256 KiB) | Tope del guion de la capa 2 dentro de un perfil (§7) |

**R4.1** — El `cpuS` de **cada perfil del catálogo** **DEBE** ser holgadamente menor que
`EXECUTION_TIMEOUT_MS` expresado en segundos. Un proceso de un solo hilo no puede consumir más de 1
segundo de CPU por cada segundo de reloj, sea cual sea el `NanoCpus` asignado; por eso, si los dos
números se acercan, el reloj de pared dispara primero **siempre** y el límite de CPU queda decorativo.
Un proceso multihilo con 2 CPU, en cambio, puede llegar a consumir hasta 2 segundos de CPU por cada
segundo de reloj, lo que sólo adelanta el disparo del límite de CPU respecto del de pared, nunca lo
atrasa. La relación se verifica al **cargar el catálogo**, perfil por perfil, y su violación hace
fallar el **arranque del proceso**, nunca una ejecución individual (§4.4).

**A1 — cerrada.** El ulimit `cpu` se aplica **por proceso**: `RLIMIT_CPU` se hereda a los hijos, pero
el contador no, y cada programa nuevo arranca en cero. Con varias fases dentro de una misma
ejecución (en `java21-junit`, dos invocaciones de `javac` y la JVM de los tests) el consumo total de
CPU puede llegar a varias veces el valor del `ulimit`. El techo agregado real de una ejecución no es
este ulimit: es `EXECUTION_TIMEOUT_MS` multiplicado por las CPU del perfil (70 s × 2 CPU para
`java21-junit`). El `Ulimits[cpu]` de §4.1 es entonces un freno anti-descontrol por fase, no el
presupuesto del alumno; el presupuesto del alumno lo aplica la capa 2, en tiempo de CPU, sólo sobre
la fase de tests. Detalle completo en [`03-aislamiento.md`](./03-aislamiento.md) (relojes).

**R4.2** — Los relojes **por fase** (compilación, tests) **NO DEBEN** vivir acá. Viven en la capa 2
del perfil, que es la única que sabe qué es una fase. El ulimit `cpu` de §4.1 es un freno
anti-descontrol por fase, no el techo de todas juntas (A1), y el ejecutor no sabe ni tiene que saber
cómo se reparte el tiempo adentro.

### 4.4 El catálogo de perfiles

Es el mecanismo con el que el ejecutor mantiene P1 mientras admite más de un lenguaje o herramienta.
Los tres perfiles del MVP (`java21-junit`, `java21-pmd`, `java21-checkstyle`) y sus roles y contenido
mínimo de bundle están en [`03-aislamiento.md`](./03-aislamiento.md) (catálogo de perfiles); esta sección describe el formato
del catálogo en sí, que es interno al ejecutor.

**R4.3** — El catálogo **DEBE** ser un directorio de sólo lectura, un archivo JSON por perfil,
nombrado `<profileId>.json`. Esa misma cadena `profileId` es la clave de búsqueda y el valor exacto
del header `X-Profile`.

```json
{
  "profileId": "java21-junit",
  "version": 3,
  "image": "sandbox-runner:2.0.0-capa1",
  "script": "#!/bin/sh\n... el guion completo de la capa 2, escapado en una sola cadena JSON ...",
  "limits": { "memoryMb": 512, "cpus": 2, "cpuS": 20 }
}
```

**R4.4** — El catálogo **DEBE** cargarse entero al arrancar el proceso y **NO DEBE** releerse ni
recargarse en caliente. Un catálogo que cambia mientras el proceso corre convierte «con qué se evaluó
esta entrega» en una pregunta sin respuesta estable, y `profileHash` (R3.6) existe justamente para que
esa pregunta tenga respuesta.

**R4.5** — Las validaciones del catálogo **DEBEN** hacer fallar el **arranque del proceso**, nunca una
ejecución individual (HU-07, CA5). Un perfil inválido es un error de despliegue, y un error de
despliegue tiene que ser ruidoso e inmediato, no un `500` intermitente que aparece recién cuando
alguien pide ese perfil. Fallan el arranque:

| Causa | Motivo |
|---|---|
| JSON inválido | Trivial |
| `version` ausente | T-07-02 exige que cada perfil declare versión |
| `limits.memoryMb` ausente, cero, negativa o mayor a `MAX_MEMORY_MB` | HU-07 CA5 y el techo de §4.3 |
| `limits.cpus` ausente, cero o negativa | HU-07 CA5 |
| `limits.cpuS` ausente, cero, negativa o mayor a `MAX_CPU_S` | Ídem |
| `limits.cpuS` demasiado cerca del reloj de pared | R4.1, perfil por perfil |
| `script` mayor a `MAX_SCRIPT_BYTES` | El guion de la capa 2 viaja por stdin (§7) |

**R4.6** — De un perfil salen **exactamente cinco** campos del `create`: `Image`, `Memory`,
`MemorySwap`, `NanoCpus` y `Ulimits[cpu]`. Todo el resto de §4.1 es idéntico sea cual sea el perfil.
Esto es verificable y está verificado (E37): el test compara el `create` de dos perfiles distintos y
exige que difieran **sólo** en esos cinco campos. Es lo que sostiene P1 e I2 ahora que la spec dejó de
ser literalmente constante.

---

## 5. Secuencia de ejecución

Todas las rutas van prefijadas con `/{DOCKER_API_VERSION}`. **R5.0** — La versión **DEBE** ir
explícita en el path; **NO DEBE** usarse el default del daemon.

**R5.12** — Al arrancar, antes de aceptar conexiones o programar el barrido, el ejecutor **DEBE**
confirmar contra el daemon que `MinAPIVersion <= DOCKER_API_VERSION <= ApiVersion` (`GET /version`).
El mínimo que acepta cada build del Engine varía, así que fijar la versión (R5.0) no alcanza para
saber si el daemon la soporta. Si el daemon no responde, o responde rechazando la propia llamada
versionada, o su rango no incluye `DOCKER_API_VERSION`, el arranque **DEBE** fallar de inmediato, sin
reintentos: mismo criterio que R4.5 para el catálogo.

**R5.13** — El único transporte permitido en producción es `unix://` o `npipe://`; el arranque **DEBE**
fallar de inmediato (antes de conectar a Docker) si `DOCKER_HOST` es cualquier otra cosa, incluido
`tcp://`, `http://`, `https://`, vacío o malformado. El paso 4 de esta misma sección («escribir y
cerrar el socket adjunto entero») es lo que le da EOF a stdin del contenedor, y ese cierre es
**abortivo**: no hay media-clausura ordenada disponible en el cliente HTTP contra el socket. Sobre
TCP, un cierre abortivo llega al otro lado como un *reset*, y un reset hace que el kernel receptor
descarte bytes que ya llegaron pero que la aplicación todavía no leyó. Medido con un daemon de mentira
por TCP en loopback, bajo carga, se perdió una fracción relevante de las escrituras; contra Docker
real por *unix socket* y por *named pipe* no se observó pérdida. De ahí el corte del transporte (D4).

| # | Llamada | Notas |
|---|---|---|
| 1 | `POST /containers/create?name=sandbox-<id>` | Cuerpo de §4.1. `Content-Type: application/json` |
| 2 | `POST /containers/<id>/attach?stream=1&stdin=1` | **Antes de `start`.** Devuelve `101` |
| 3 | `POST /containers/<id>/start` | |
| 4 | Escribir en el socket adjunto y **cerrarlo entero** | Los **tres documentos** de §7: nonce, largo + guion de la capa 2, y el tar |
| 5 | `POST /containers/<id>/wait?condition=not-running` | Con `EXECUTION_TIMEOUT_MS`; si vence → `POST /containers/<id>/kill` y después igual `wait` |
| 5b | `GET /containers/<id>/json` | Solo para leer `State.OOMKilled` (R5.10) |
| 6 | `GET /containers/<id>/logs?stdout=1&stderr=1` | Respuesta HTTP normal. Se lee entera y se demultiplexa en memoria |
| 7 | `DELETE /containers/<id>?force=1&v=1` | En un bloque de limpieza garantizada |

**R5.1** — El paso 2 **DEBE** ejecutarse antes del paso 3. Si el contenedor arranca antes de que
estemos conectados, puede intentar leer stdin sin nadie del otro lado.

**R5.2** — Escritura y lectura **NUNCA** ocurren simultáneamente sobre el mismo socket. El socket del
paso 2 es de una sola dirección: se escribe y se cierra. Esta es la decisión de diseño que hace que el
componente sea chico; una implementación que la viole está fuera de spec aunque funcione.

**R5.3** — El paso 7 **DEBE** ejecutarse siempre: en el camino feliz, en timeout, y ante cualquier
excepción. Si el `DELETE` falla, se registra y se confía en el barrido de §10.

**R5.4** — El paso 6 **DEBE** ocurrir antes del paso 7. Borrado el contenedor, los logs no existen más.

**R5.10** — El paso 5b **DEBE** ejecutarse después del `wait` y antes del `DELETE`, con
`DAEMON_TIMEOUT_MS`. De la respuesta se lee **únicamente** `State.OOMKilled`; el resto del `inspect`
se ignora. Si la llamada falla, **NO DEBE** abortarse la ejecución: se devuelve `oomKilled: false` y
se registra el fallo, porque el resultado ya está y perderlo por un dato de diagnóstico sería peor. En
`TIMEOUT` el paso 5b se ejecuta igual: un contenedor que fue matado por el límite de memoria y además
llegó al reloj es un caso real.

**R5.11** — El EOF de stdin **DEBE** producirse cerrando la conexión adjunta, no con una media
clausura del socket. El contenedor se crea con `StdinOnce: true` (§4.1), así que cerrar la conexión
**es** el EOF: sin él la capa 1 se cuelga leyendo el tar hasta el reloj de pared. El cierre **DEBE**
ocurrir en un bloque de limpieza garantizada apenas la entrega termina o vence su tope (R8.5).

### 5.1 Requisitos del cliente HTTP contra el socket

**R5.5** — HTTP/1.1, con header `Host: localhost`.
**R5.6** — **NO DEBE** seguir redirecciones.
**R5.7** — **DEBE** soportar `Transfer-Encoding: chunked` en las respuestas del daemon (`logs` la
usa).
**R5.8** — En el paso 2 **DEBE** enviar `Upgrade: tcp` y `Connection: Upgrade`, esperar `101` y
quedarse con el socket crudo. Cualquier otro código es `DAEMON_ERROR`.
**R5.9** — Si el cliente HTTP entrega, junto con la respuesta del `101`, bytes ya leídos del stream,
esos bytes **DEBEN** anteponerse al stream. Ignorarlos se come el primer bloque de datos y produce un
bug intermitente.

---

## 6. Demultiplexado de la salida

`logs` y `attach` sin TTY devuelven los dos streams entrelazados en frames con un encabezado de 8
bytes:

```
 byte 0      bytes 1-3     bytes 4-7            bytes 8..8+N
┌─────────┬─────────────┬──────────────────┬──────────────────────┐
│  tipo   │   padding   │  largo (uint32,  │       payload        │
│ 1=out   │   (ceros)   │   big-endian)    │      (N bytes)       │
│ 2=err   │             │                  │                      │
└─────────┴─────────────┴──────────────────┴──────────────────────┘
```

**R6.1** — Antes de demultiplexar, **DEBE** verificarse que la respuesta corresponde a un stream
multiplexado. Si el contenedor tiene TTY, la salida **no** viene enmarcada: eso es `DAEMON_ERROR`, no
un caso a manejar.

**R6.2** — El campo de largo es un `uint32` y admite valores de hasta 4 GiB, y ningún largo declarado
**DEBE** hacer que se reserve memoria proporcional a él. Esto tiene que estar garantizado por
construcción, leyendo el payload en trozos de un buffer fijo sin importar el largo que el encabezado
declare. *Punto abierto de implementación:* un largo fuera de rango puede terminar en error inmediato
o, si el frame corrupto es lo último del stream, en fin de stream silencioso —el comportamiento
depende del cliente HTTP elegido y de qué haya bufferizado el transporte en ese instante—; el tope
real y determinístico de memoria por stream es `MAX_OUTPUT_BYTES` (R6.7), que es propio del ejecutor y
no depende de esto.

**R6.3** — Un frame con largo `0` es válido: payload vacío, seguir leyendo. **NO DEBE** trabar el
bucle ni tratarse como fin de stream.

**R6.4** — Un tipo distinto de `1` o `2` **DEBE** ser error fatal. **NO DEBE** asumirse `stdout` por
defecto: adivinar es como se corrompe un resultado en silencio.

**R6.5** — Los frames pueden llegar partidos, y un mismo frame puede venir en varios trozos de red; un
encabezado o un payload puede quedar **truncado** si la conexión termina a mitad de un frame, incluso
con un cuerpo HTTP perfectamente válido y cerrado. Cuando la lectura de un encabezado o un payload se
topa con el fin del stream, **NO DEBE** tratarse como error fatal: se devuelve lo ya leído y se
termina. Esto **no** activa `outputTruncated`: ese campo es del tope propio de R6.7 y sólo se enciende
cuando el ejecutor corta por exceso de bytes, nunca cuando es el stream el que se acorta solo. La
consecuencia práctica: si el corte se llevó puesto el bloque del reporte, la respuesta sale con
`reportMissing: true` y `outputTruncated: false` —la misma combinación que una entrega que nunca
produjo reporte—, así que este truncamiento en particular no es distinguible por el worker de «no
hubo reporte». R7.7 sólo describe la combinación (`reportMissing: true` **y** `outputTruncated: true`)
para el truncamiento de R6.7; éste es un truncamiento distinto, más arriba en la cadena, y punto
ciego.

**R6.6** — Una línea de texto puede cruzar dos frames, y un frame puede traer varias líneas. **NO
DEBE** parsearse por líneas adentro de un frame: se concatena todo el stream primero y recién ahí se
separa.

**R6.7** — Si un stream supera `MAX_OUTPUT_BYTES`, **DEBE** conservarse el **principio** y descartarse
el resto, marcando `outputTruncated: true`. El reporte y los errores de compilación están al
principio.

---

## 7. Nonce, entrada y separación del reporte

El código del alumno escribe en el mismo `stdout` por donde viaja el reporte de tests. Sin un
separador que el alumno no pueda producir, un `println` con el formato del reporte falsifica el
resultado. Ninguna defensa de aislamiento cubre esto: no es una fuga, es una falsificación por un
canal legítimo.

**R7.1** — El ejecutor **DEBE** generar, por ejecución, un nonce aleatorio criptográficamente seguro
de 16 bytes, en hexadecimal minúscula (32 caracteres).

**R7.2** — El stdin del contenedor **DEBE** ser exactamente estos **tres documentos pegados, sin
separadores**, y después el cierre:

```
<nonce>\n                 32 caracteres hex, minúscula
<n>\n                     cuántos BYTES mide el guion de la capa 2, en decimal ASCII
<guion de n bytes>        la capa 2 del perfil (§4.4), opaca para el ejecutor
<tar>                     el bundle: "todo lo que quede del stream"
```

**Un ejemplo, de punta a punta.** El worker arma el tar en memoria a partir de `src/tp/Solucion.java`
(del alumno) y `test/tp/SolucionTest.java` (del perfil), valida que ninguna ruta sea absoluta ni
contenga `..`, y lo manda como cuerpo del `POST /executions`. El ejecutor no lo toca: genera el nonce
—por ejemplo `a610e9d2bb0e6e196ed1c258dcc3630a`—, calcula el largo del guion de la capa 2 del perfil
resuelto por `X-Profile`, y escribe los tres documentos uno detrás del otro en el socket adjunto antes
de cerrarlo. Adentro del contenedor, `capa1.sh` lee la primera línea (el nonce) en una variable de
shell no exportada, lee la segunda línea y esa cantidad exacta de bytes (el guion), y le deja el resto
del descriptor —el tar completo— a `tar` para extraer. Al terminar la capa 2, `capa1.sh` envuelve el
reporte así:

```
---SANDBOX-a610e9d2bb0e6e196ed1c258dcc3630a-INICIO---
{ …el reporte… }
---SANDBOX-a610e9d2bb0e6e196ed1c258dcc3630a-FIN---
```

y el ejecutor recorta ese bloque de `stdout` antes de devolver la respuesta. El alumno no puede
producir esas marcas porque nunca ve el nonce: su código corre después de que la capa 1 ya lo consumió.

**R7.9** — El largo del guion **DEBE** ir adelante, y **NO DEBE** usarse ninguna marca de fin. El
guion de la capa 2 es texto arbitrario y puede contener cualquier línea, incluida la que se elegiría
como separador. Un separador de texto es una apuesta a que el contenido no lo contiene. Con el largo
adelante no hay alfabeto que adivinar: son `n` bytes opacos, igual que `Content-Length` en HTTP.

**R7.10** — Usar el **nonce como delimitador** del guion está **PROHIBIDO**. Se lo mostraría a la capa
2, que es exactamente lo que el nonce existe para evitar (R7.3, I5). Una capa 2 que conoce el nonce
puede falsificar el bloque de reporte.

**R7.11** — El largo se cuenta en **BYTES, no en caracteres**. Un acento en un comentario del guion
mueve el número. Es un bug que no aparece con un guion ASCII y revienta con el primer comentario
escrito en castellano.

**R7.3** — El nonce **NO DEBE** pasarse por variable de entorno, por argumento, ni por archivo. Una
variable de entorno es recuperable desde el proceso del alumno leyendo `/proc/1/environ`, aunque el
entrypoint la borre: el `unset` cambia la memoria del shell, no el snapshot del kernel.

**R7.4** — La capa 1 de la imagen (`capa1.sh`, §4.2) lee el nonce en una variable de shell **no
exportada**, lo consume antes de invocar a la capa 2 —que por lo tanto nunca lo ve— y emite el reporte
entre los marcadores del ejemplo de arriba.

**R7.5** — El ejecutor **DEBE** extraer el bloque entre la **última** aparición del marcador de inicio
y la **primera** del marcador de fin **posterior a ella**, buscando la cadena exacta con **su** nonce.
Ese bloque **DEBE** removerse de `stdout` antes de devolverlo. Si no aparece, `report: null` y
`reportMissing: true`.

**R7.6** — La regla de «la última de inicio, la primera de fin posterior a ella» no es una preferencia
estética. Un alumno no puede producir el nonce, pero fijar el criterio elimina la ambigüedad de raíz.

**R7.7** — La extracción del reporte ocurre **después** del truncado de R6.7, sobre el texto ya
recortado. Extraer primero exigiría bufferear el stream sin tope, que es justo lo que R6.7 evita.
Consecuencia esperada: si el reporte quedó fuera de los bytes conservados, la respuesta trae
`reportMissing: true` **y** `outputTruncated: true`, y el worker distingue el caso por esa
combinación.

**R7.8** — De `stdout` se remueve desde el primer carácter del marcador de inicio hasta el último del
de fin, más el salto de línea inmediatamente posterior si existe. Del contenido del reporte se recorta
el salto que sigue al marcador de inicio y el que precede al de fin, y nada más.

**El contenido entre los marcadores es un sobre opaco para el ejecutor.** Su formato exacto —qué
información trae, cómo distingue un fallo de compilación de un timeout de la capa 2— es un contrato
entre la capa 1 de la imagen (que lo emite) y el worker (que lo interpreta), y está especificado en
[`03-aislamiento.md`](./03-aislamiento.md) (el sobre de la capa 1). El ejecutor sólo sabe delimitarlo
y removerlo de `stdout`.

---

## 8. Timeouts

**R8.1** — El reloj de `EXECUTION_TIMEOUT_MS` arranca después de que `start` responde.

**R8.2** — Al vencer: `POST /kill`, después `wait` de nuevo con un timeout corto, después `logs` (la
salida parcial se devuelve igual: sirve para diagnosticar), después `delete`. Resultado `TIMEOUT`,
`exitCode: null`.

**R8.3** — Cada llamada a la API de Docker distinta de `wait` **DEBE** tener `DAEMON_TIMEOUT_MS`. Un
daemon colgado no debe colgar al ejecutor.

**R8.4** — Un `TIMEOUT` es indistinguible, desde afuera, de un `while(true)` del alumno y de un
half-close mal hecho. Por eso los logs del ejecutor **DEBEN** registrar cuántos bytes se escribieron
en stdin y si el cierre se completó, en cada ejecución.

**R8.5** — La escritura del paso 4 **DEBE** tener su propio tope de tiempo. Si el contenedor no
consume stdin, el buffer del pipe se llena (unas decenas de KiB) y la escritura queda bloqueada
indefinidamente: el reloj de `EXECUTION_TIMEOUT_MS` todavía no se está evaluando, porque eso ocurre
recién en el paso 5. Sin este tope, un contenedor que arranca y no lee retiene su cupo de concurrencia
para siempre, y con `MAX_CONCURRENT` casos así el ejecutor deja de atender sin que `/health` lo note.
Al vencer: abandonar la escritura, registrar el cierre como incompleto y seguir con el paso 5, que
resolverá en `TIMEOUT`.

---

## 9. Concurrencia y saturación

**R9.1** — Como máximo `MAX_CONCURRENT` contenedores vivos a la vez. El control **DEBE** estar en el
ejecutor, que es quien sabe cuántos hay, y no delegarse al worker.

**R9.2** — Hasta `MAX_QUEUE` requests esperando turno. Por encima: `503` inmediato con
`Retry-After: 5` y `result: "REJECTED"`.

**R9.3** — El límite de concurrencia **es parte del control de seguridad**, no una optimización. Sin
él, un worker comprometido podría agotar los recursos del host pidiendo ejecuciones sin límite.

**R9.4** — El ejecutor **NO DEBE** mantener estado entre requests más allá del semáforo y la cola en
memoria. Reiniciarlo no pierde nada que importe.

---

## 10. Limpieza de huérfanos

**R10.1** — Al arrancar, y cada `SWEEP_INTERVAL_MS`, **DEBE** listar
`GET /containers/json?all=1&filters={"label":["sandbox=1"]}` y borrar los que superen
`ORPHAN_AGE_MS`.

**R10.2** — El barrido **NO DEBE** borrar contenedores en vuelo de esta misma instancia. El filtro por
edad alcanza si `ORPHAN_AGE_MS` es holgadamente mayor que `EXECUTION_TIMEOUT_MS` — con los valores de
§4.3, unas **8,5 veces mayor** (600 s contra 70 s). Conviene tener el número escrito: si el reloj de
pared vuelve a subir, éste es el otro número que hay que mirar.

**R10.3** — Un fallo del barrido se registra y no afecta las ejecuciones en curso.

**R10.4** — Este es el **único** barrido del sistema que actúa sobre contenedores `sandbox`. Un
componente distinto puede limpiar otros recursos (colas, filas de base de datos), pero la limpieza de
contenedores vive donde vive el privilegio: sólo el ejecutor tiene el socket de Docker.

---

## 11. Requisitos no funcionales

**R11.1 — Imagen.** Base *distroless*: sin shell y sin gestor de paquetes.

**R11.2 — Usuario.** El proceso corre como usuario no-root, en un grupo con acceso al socket de
Docker.

**R11.3 — Dependencias.** Solo se admiten dependencias que **no** parseen bytes controlados por un
atacante. Una librería de JSON es aceptable: el JSON de la spec lo serializamos nosotros y el que
parseamos viene del daemon, que ya es parte de la base de confianza. Un cliente de Docker de terceros
es aceptable por el mismo motivo: quien ya puede ejecutar código en este proceso tiene el socket y
puede mandarle a mano el JSON que quiera, así que la librería no agrega capacidad, agrega comodidad.

**R11.6 — Logs del ejecutor.** Por ejecución: id, duración de cada paso, bytes escritos, bytes leídos
por stream, resultado. **NO DEBEN** registrarse el contenido del bundle, la salida del alumno ni el
nonce.

**R11.7 — Apagado ordenado.** Ante `SIGTERM`: dejar de aceptar requests, esperar a que terminen las
ejecuciones en vuelo hasta `EXECUTION_TIMEOUT_MS`, borrar sus contenedores, salir.

---

## 12. Invariantes de seguridad verificables

Cada una tiene un test en §13. Son las afirmaciones que se sostienen en la defensa.

| # | Invariante |
|---|---|
| **I1** | El JSON de `create` es byte a byte idéntico entre ejecuciones, salvo el label `sandbox.execution` y el `name` |
| **I2** | Ningún campo del request **escribe** un valor de la spec. Lo único que el request influye es **cuál perfil del catálogo se elige** (`X-Profile`), y un perfil sólo puede mover los cinco campos de R4.6 |
| **I3** | Un traversal exitoso en el tar igual falla al escribir, porque el rootfs es de solo lectura |
| **I4** | El contenedor del alumno no tiene red ni acceso al socket de Docker |
| **I5** | El código del alumno no puede producir el bloque de reporte |
| **I6** | Todo contenedor creado termina borrado, por la vía normal o por el barrido |
| **I7** | El ejecutor nunca desempaqueta ni interpreta el tar |
| **I8** | Dos perfiles cualesquiera del catálogo producen un `create` idéntico salvo `Image`, `Memory`, `MemorySwap`, `NanoCpus` y `Ulimits[cpu]` |
| **I9** | La capa 2 nunca ve el nonce: lo consume la capa 1 antes de invocarla |

---

## 13. Criterios de aceptación

Cada test es una afirmación binaria, renumerada de forma contigua como `E1`, `E2`, … para no chocar
con los identificadores de decisión (`A1`, `A2`, …) de [`README.md`](./README.md).

### 13.1 Golden test de la spec — el más importante

**E1** — Serializar el JSON de `create` para tres ids distintos y compararlo contra un archivo de
referencia versionado, normalizando solo `name` y el label. Debe ser idéntico. Este test es lo que
convierte I1 y I2 de afirmación en evidencia, y **DEBE** fallar si alguien agrega un parámetro a
`POST /executions` que toque la spec.

**E2** — El cuerpo del `create` que **el daemon recibió por el socket** es idéntico al que produce la
serialización de E1. Sin este test, E1 prueba una intención; con él, prueba un hecho.

### 13.2 Camino feliz

**E3** — Una entrega que compila y pasa: `result: COMPLETED`, `exitCode: 0`, `report` no vacío,
`reportMissing: false`.
**E4** — Una entrega que no compila: `COMPLETED`, `exitCode != 0`, el error de compilación aparece en
`stderr`.
**E5** — Una entrega que falla tests: `COMPLETED`, `exitCode != 0`, el reporte tiene la falla.
**E6** — Los bundles de referencia de cada uno de los tres perfiles del catálogo corren contra la
imagen real correspondiente y reproducen los resultados esperados, sin divergencias.

### 13.3 Protocolo

**E7** — *El test que valida §7.* Escribir los **tres documentos** de R7.2 y cerrar; el contenedor
debe reconstruir el nonce, el guion completo y el tar completo, **byte a byte**, contra un daemon de
Docker real. Si falla, §7 hay que rediseñarla y este test bloquea todo lo demás.
**E8** — El largo del guion se cuenta en bytes y no en caracteres: con un guion cuyo texto contiene una
`ñ`, el número que viaja es el de bytes, no el de caracteres (R7.11). Además, un guion que contiene en
su propio texto la línea que un protocolo ingenuo basado en texto elegiría como separador (un
marcador de reporte falso, un *magic* de tar) no rompe el framing: nonce, guion y tar llegan byte a
byte igual, porque el protocolo nunca busca una marca de fin, cuenta bytes (R7.9).
**E9** — Verificar que una salida **sin enmarcar** es `DAEMON_ERROR`, detectada por tipo de frame: la
guarda que importa es no adivinar que eso es stdout.
**E10** — Con un tar armado para que la salida llegue en muchos frames chicos, verificar que el texto
reconstruido es exacto.
**E11** — Alimentar al cliente con un frame que declara 4 GiB: **no** se reserva memoria proporcional
al largo declarado y el desenlace es inmediato (ver R6.2).
**E12** — Un frame de largo 0 en el medio no traba el bucle.
**E13** — Un tipo de stream inválido es error fatal; no se asume stdout.
**E14** — Una línea que cruza dos frames se reconstruye sin cortes espurios.
**E15** — Contra un daemon de prueba que devuelve `State.OOMKilled: true` en el `inspect`, la
respuesta trae `oomKilled: true`. Contra uno que hace fallar el `inspect`, la respuesta trae
`oomKilled: false` **y** el resto del resultado intacto: el fallo del paso 5b no aborta la ejecución
(R3.4, R5.10).

### 13.4 Entrada hostil

Los tars se construyen a mano. Cada test verifica **dos** cosas: que la extracción falla o ignora la
entrada, **y** que el archivo objetivo no cambió. La salida de cada uno se guarda como evidencia.
Estos casos son de la validación estructural del bundle y de la capa 1 de la imagen (HU-06, T-06-09 a
T-06-12), no de la spec de creación del contenedor; se listan acá porque ejercitan el sistema de punta
a punta, con el ejecutor de por medio.

| Test | Tar | Se espera |
|---|---|---|
| **E16** | `../../opt/junit/junit.jar` | Rechazado por tar; el JAR intacto |
| **E17** | symlink `Solucion.java → /etc/passwd`, después archivo con ese nombre | Sin escritura a través del enlace |
| **E18** | hard link a un archivo protegido | Falla |
| **E19** | symlink a directorio + archivo "adentro" | Falla — la forma de CVE-2025-45582 |
| **E20** | nombre absoluto `/etc/cron.d/pwn` | Sin escritura fuera de `/work` |
| **E21** | entrada de tipo device | No se crea (no somos root) |
| **E22** | header PAX que pisa el nombre | Rechazado |
| **E23** | nombre con `\n` y texto que imita un reporte | No ensucia `stdout` ni el bloque de reporte |

**E24** — Código de alumno que imprime `---SANDBOX-<hex al azar>-INICIO---` y un reporte falso:
`report` debe seguir siendo el verdadero (invariante I5).
**E25** — Código de alumno que lee `/proc/1/environ` y lo imprime: el nonce no debe aparecer.
**E26** — Código de alumno que intenta abrir un socket: falla (invariante I4).
**E27** — Código de alumno que escribe hasta llenar `/work`: el contenedor muere por límite, no el
host.
**E28** — `while(true) System.out.println(...)`: `result: TIMEOUT`, `outputTruncated: true`, y el
principio de la salida conservado.

### 13.5 Operación

**E29** — `MAX_CONCURRENT + MAX_QUEUE + 1` requests simultáneos: exactamente uno recibe `503`.
**E30** — Bundle de `MAX_BUNDLE_BYTES + 1`: `413` sin haber leído el cuerpo.
**E31** — Matar el ejecutor entre `start` y `delete`; al reiniciarlo, el barrido borra el contenedor
(invariante I6).
**E32** — Detener el daemon de Docker: `POST /executions` responde `502` dentro de
`DAEMON_TIMEOUT_MS`, y `/health` responde `503`.
**E33** — `SIGTERM` con una ejecución en vuelo: termina o se mata, el contenedor queda borrado, y el
proceso sale.
**E34** — Contra un contenedor de prueba que arranca y **no** consume stdin, con un bundle mayor al
buffer del pipe: la escritura del paso 4 abandona al vencer su tope, el cupo de concurrencia se
libera, y el resultado es `TIMEOUT` con el cierre registrado como incompleto (R8.5).
**E35** — El arranque falla si el daemon no soporta `DOCKER_API_VERSION`: contra un daemon compatible
pasa; contra uno cuyo rango excluye la versión, falla con un mensaje que nombra la causa y ambas
versiones; contra uno que rechaza la propia llamada de versión, falla como incompatibilidad y no como
inalcanzable; contra un daemon inexistente, falla como inalcanzable (R5.12).
**E36** — El arranque falla si `DOCKER_HOST` no es `unix://` ni `npipe://`: esos dos se aceptan;
`tcp://`, `http://`, `https://`, vacío, malformado y variantes de mayúsculas se rechazan con un
mensaje que nombra la causa (R5.13).

### 13.6 Catálogo de perfiles

**E37** — *(R4.6)* El `create` de **dos perfiles distintos** difiere **exactamente** en `Image`,
`Memory`, `MemorySwap`, `NanoCpus` y `Ulimits[cpu]`, y en nada más.

**E38** — El JSON de `create` contiene el ulimit `cpu` con el `cpuS` del perfil, y ese valor es menor
que `EXECUTION_TIMEOUT_MS / 1000` (R4.1). Este test verifica la relación entre los dos números, no
que el ulimit sea el presupuesto agregado de la ejecución: esa lectura está cerrada por A1 en contra.

**E39** — *(R4.5)* Cada causa de rechazo del catálogo tiene que hacer fallar el **arranque del
proceso**: JSON inválido, `version` ausente, `memoryMb` o `cpus` en cero/negativos/ausentes,
`memoryMb` sobre `MAX_MEMORY_MB`, `cpuS` sobre `MAX_CPU_S`, violación de R4.1, y guion sobre
`MAX_SCRIPT_BYTES`. Cada causa se prueba sola, con su valor límite (el techo exacto se acepta, uno más
se rechaza) y afirmando el mensaje de **esa** causa, no uno genérico.

**E40** — *(R3.7)* `X-Profile` ausente o malformado ⇒ `400`; bien formado y fuera del catálogo ⇒
`422`; y la respuesta trae los **trece campos en el orden exacto** de §3.1, con los tres del perfil al
final.

**E41** — *(R3.6)* `profileHash` tiene que ser el SHA-256 de los bytes del guion, calculado al
cargar, y un campo de hash declarado en el JSON del perfil **no** debe leerse: si el catálogo lo
rechaza por defecto en vez de ignorarlo en silencio, también cumple la propiedad que importa.

---

## 14. Dimensionamiento

Estimación para presupuestar la operación, no un compromiso. El pool del MVP corre hasta
`MAX_CONCURRENT` = 6 ejecuciones simultáneas. Para `java21-junit` (512 MB / 2 CPU por ejecución, D15),
eso representa un pico de **~3 GB de RAM y hasta 12 cores**, con **~4–8 s** por ejecución. Los
presupuestos de `java21-pmd` y `java21-checkstyle` quedan a definir por medición (A2); al no ejecutar
código del alumno, se espera que pesen menos, pero no hay referencia previa de esas herramientas bajo
este aislamiento.

---

## Abierto

| # | Pregunta | Quién la cierra |
|---|---|---|
| **A2** | Memoria, CPU y timeouts de `java21-pmd` y `java21-checkstyle` (§14) | Nosotros, por medición |

Además, sin ser preguntas cerradas por un identificador propio:

- **Catálogo de imágenes base** (D21 del README): quién las nombra, las versiona y aprueba una nueva,
  y si la base pasa a Alpine (el entrypoint necesita un shell).
- **Comportamiento exacto ante un largo de frame fuera de rango** (R6.2): el desenlace —error
  inmediato o fin de stream silencioso— depende de qué cliente HTTP se use y de cómo bufferice el
  transporte en cada caso; no hay, ni puede haberlo con esta arquitectura, un tope propio
  determinístico distinto de `MAX_OUTPUT_BYTES`.
- **Componente exacto que hace la validación estructural del bundle** (lista blanca de tipos de
  entrada del tar, HU-06): sigue sin decidirse si vive en el worker, en la capa 1 de la imagen, o en
  ambos como defensa en profundidad (T-06-11).
