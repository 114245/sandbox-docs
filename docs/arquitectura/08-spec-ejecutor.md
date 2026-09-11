# Ejecutor de `ms-sandbox` — especificación de requerimientos

> **Tema 06 — Sandbox / Runtime.**
> Contrato **independiente del lenguaje** del sidecar ejecutor. Se escribió como fuente de verdad para dos implementaciones paralelas —una en Java 21, otra en Node 22 + TypeScript—; **la implementación en Node quedó de lado**, así que hoy es la spec de una sola. La independencia del lenguaje se conserva a propósito: es lo que hace que §13 siga siendo una suite de aceptación y no la descripción de un programa.

**Cómo se usa este documento.** Todo lo que dice `DEBE` es obligatorio y verificable con un test de §13. `NO DEBE` es una prohibición: si una implementación lo hace, está mal aunque funcione. `DEBERÍA` es una recomendación fuerte que se puede desviar dejando el motivo escrito en el código. Todo valor numérico está en §4. Los de §4.3 son **constantes de compilación**; los cuatro campos que dependen del perfil (§4.4) salen de un **catálogo versionado que escribimos nosotros**. Ninguno de los dos es un parámetro de request: el que llama elige un perfil, no escribe un número.

---

## 0. Cambios desde la primera versión

> ## ✅ Revisión por el V4 del Grupo 5 — cerrada (10‑sep‑2026)
>
> **La mayor parte de esta spec sobrevivió intacta**, y conviene decirlo primero: el ejecutor es
> **transporte, no lenguaje**. Nada de lo que el V4 movió tocó el transporte por socket Unix (§2),
> el contrato HTTP con el worker (§3) salvo por un header, el mecanismo del nonce (§7), los
> timeouts (§8), la concurrencia (§9), la limpieza de huérfanos (§10) ni los invariantes de
> seguridad (§12).
>
> **Lo que quedaba en revisión se resolvió así, y está incorporado abajo como C7–C12:**
>
> | Sección | Cómo cerró |
> |---|---|
> | **§4** constantes | Se resolvió con el **catálogo de perfiles** (C7, C8). La imagen y los límites salen del perfil elegido, pero el perfil es nuestro, versionado e inmutable, y el cliente **elige de un catálogo, no escribe un número**. P1 no se debilitó: cambió de «todo es constante» a «lo variable sale de un conjunto cerrado que sólo nosotros escribimos», y lo prueba `SpecTest#p1_dosPerfilesDifierenSoloEnLosCuatroCamposVariables` (**D18**, **D21**) |
> | **§5** secuencia | El sobre se generalizó, pero **fuera de este documento**: lo arma la capa 1 de la imagen, no el ejecutor. Para el ejecutor el reporte sigue siendo opaco (R3.5), así que `exitCodeJava`, `clasesTest` y `testsEnReporte` nunca fueron campos de esta spec y no hay nada que cambiar acá (**P9**) |
> | **§7** validación del tar | **No la escribe esta spec, y ahora se sabe por qué**: el ejecutor no desempaqueta nada (I7). La regla por tipo de entrada —sin `..`, sin barra inicial, sin enlaces simbólicos ni duros— vive en `capa1.sh`, que es quien extrae. Lo que sí entró acá es el **framing de tres documentos** que hizo falta para que la capa 1 exista (C9) (**P2**) |
> | **§13** aceptación | Actualizada entera en C12. Los casos de symlink y hardlink siguen siendo de la suite de la imagen, no de la del ejecutor |
>
> Ver [`11-impacto-v4-g5.md`](./11-impacto-v4-g5.md) §10 y §11,
> [`Respuesta_G8_a_Propuesta_V4.md`](../../otros/Respuesta_G8_a_Propuesta_V4.md) §2.4 y
> [`../../HANDOFF-opcion1.md`](../../HANDOFF-opcion1.md).

> **Leer esto primero.** La implementación en Java se construyó contra la versión anterior de este documento y **no** cubre lo de abajo. Las dos implementaciones tienen que quedar alineadas con esta versión.

| # | Cambio | Dónde | Impacto |
|---|---|---|---|
| **C1** | **Ulimit `cpu` en la spec del contenedor.** Faltaba, y sin él vuelven los `TIMEOUT` intermitentes que el prototipo ya había resuelto. | §4.1, §4.2, §4.3, A32 | **Rompe el golden test A1**: el archivo de referencia hay que regenerarlo, y las dos implementaciones tienen que producir el nuevo |
| **C2** | **Paso 5b: `inspect` y campo `oomKilled`.** Sin esto el worker no puede distinguir «se quedó sin memoria» de «el código falló», y eso cambia el veredicto que ve el alumno. | §3.1, §5, R5.10, A31 | Un paso más en la secuencia y un campo más en la respuesta |
| **C3** | **R8.5, watchdog sobre la escritura del paso 4.** Sin él, un contenedor que no consume stdin retiene su cupo de concurrencia para siempre. | §8 | Bug encontrado en la implementación Java |
| **C4** | **R7.5 dejó de contradecir a R7.6.** Antes decía «primera aparición» donde R7.6 decía «última»: dos implementaciones podían divergir ante el mismo stream. | §7 | Java ya usa «última»; Node **DEBE** hacer lo mismo |
| **C5** | **`MAX_CUERPO_DAEMON_BYTES` incorporada a §4.3.** La implementación Java tuvo que inventarla porque hacía falta y no estaba. | §4.3 | Valor fijado para las dos |
| **C6** | **§14 con los números reales de Java** y el motivo del desvío. | §14 | Insumo para la decisión de lenguaje |

> **Verificado de punta a punta el 4-sep-2026.** El ejecutor Node corrió contra la imagen real
> `sandbox-runner:1.0.0` con `bundles/ok-suma`: `COMPLETADA`, `exitCode 0`, reporte con 3 tests y
> 0 fallas. Hasta entonces las dos implementaciones solo se habían probado contra una imagen
> busybox de fixture, y eso escondía cuatro divergencias — el nonce sin implementar, `/work` contra
> `/tmp`, el tmpfs sin `uid`/`gid`, y el ejemplo del campo `reporte`. Las cuatro están corregidas
> en esta versión. Detalle en [`README.md`](./README.md) P0.

> **Segunda tanda de cambios — el modelo de dos capas y el catálogo (10‑sep‑2026).** C1–C6 salieron
> de la primera implementación; C7–C12 salen de la Opción 1 del V4 del Grupo 5, ya implementada y
> validada contra la imagen real. La implementación de referencia es la de Java: rama
> `feat/catalogo-perfiles`, 71 tests en verde. **La implementación en Node quedó de lado** y este
> documento dejó de ser un contrato entre dos implementaciones para ser la spec de una.

| # | Cambio | Dónde | Impacto |
|---|---|---|---|
| **C7** | **Catálogo de perfiles.** La imagen y los límites del contenedor dejan de ser constantes de compilación y salen del **perfil** que elige el request. El perfil es nuestro, versionado, inmutable y cargado al arrancar. | §3.1, §3.3, §4.4, A34–A37 (§13.8) | **P1 cambia de forma, no de fondo.** El cliente elige de un conjunto cerrado; sigue sin poder escribir un número |
| **C8** | **Header `X-Perfil: <id>@<version>`.** Nuevo, obligatorio. `400` si falta o está malformado; **`422` nuevo** si el formato es válido pero la clave no está en el catálogo. | §3.1, §3.3 | Un header más y un código de estado más |
| **C9** | **Modelo de dos capas y framing de tres documentos.** El stdin pasa de dos documentos (nonce y tar) a **tres** (nonce, largo + guion de la capa 2, y tar), según R7.2. El entrypoint pasa a ser `capa1.sh`. | §4.1, §5, §7 | Es el cambio de fondo del V4: la evaluación (capa 2) la escribe el Grupo 5, el aislamiento (capa 1) lo escribimos nosotros |
| **C10** | **La respuesta pasó de 10 a 13 campos**: `perfilId`, `perfilVersion` y `perfilHash` al final. El hash del guion se **calcula al cargar**, nunca se declara. | §3.1 | El orden importa: se agregaron al final para que el test que fija el orden se extienda en vez de reescribirse |
| **C11** | **R11.4 derogada.** Prohibía `docker-java` y el argumento era débil. | §11.4, §14 | El ejecutor está portado a `docker-java` 3.4.1 |
| **C12** | **§13 y §14 con lo que realmente se implementó y midió**, incluida la parte de §13 que quedó **parcial** a propósito. | §13, §14 | Insumo para R14.1 |


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

Están acá para que **ninguna implementación los agregue por iniciativa propia**:

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
X-Perfil: <perfilId>@<version>
Content-Length: <n>

<bytes del tar, sin comprimir>
```

- `X-Ejecucion-Id` **DEBE** estar presente y ser un UUID válido. Se usa como etiqueta del contenedor y como correlación en los logs. **NO** afecta la spec.
- `X-Perfil` **DEBE** estar presente y hacer *match* exacto con `^[a-z0-9-]+@[0-9]+$` (C7, C8). Es la clave de búsqueda en el catálogo de §4.4 y lo **único** del request que influye en la spec del contenedor —y sólo eligiendo, nunca escribiendo, los cuatro campos que §4.4 enumera—. Ver R3.6.
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
  "salidaTruncada": false,
  "perfilId": "java21-junit",
  "perfilVersion": 3,
  "perfilHash": "9f86d081...c9e2f0"
}
```

**R3.7** — Los tres campos del perfil **DEBEN** ir al final y en ese orden. No es cosmético: el orden de los campos de la respuesta está fijado por un test (§13.6, A36), y agregarlos al final es lo que permite que ese test se **extienda** en vez de reescribirse. Un campo nuevo en el medio obliga a reescribir la afirmación entera, y una afirmación reescrita ya no prueba lo mismo que probaba.

| Campo | Tipo | Significado |
|---|---|---|
| `resultado` | enum §3.2 | Cómo terminó la ejecución **como proceso**. No es el veredicto académico. |
| `exitCode` | int \| null | Código de salida del contenedor. `null` si no llegó a terminar. |
| `oomKilled` | bool | `State.OOMKilled` del `inspect` (§5, paso 5b). Ver R3.4. |
| `duracionMs` | int | Desde antes de `create` hasta después de `wait`. |
| `stdout` / `stderr` | string | Salida demultiplexada, **sin** el bloque de reporte. |
| `reporte` | string \| null | Contenido entre los marcadores de §7, **opaco para el ejecutor**. `null` si no apareció. Ver R3.5. |
| `reporteAusente` | bool | `true` si no se encontró un bloque de reporte válido. |
| `salidaTruncada` | bool | `true` si el ejecutor recortó algún stream por `MAX_SALIDA_BYTES`. |
| `perfilId` | string \| null | Perfil con el que se ejecutó (§4.4). `null` sólo en `RECHAZADA` y `ERROR_DAEMON`, donde puede no haberse llegado a resolver. |
| `perfilVersion` | int \| null | Versión del perfil. Junto con `perfilId` forma la clave que mandó el request. |
| `perfilHash` | string \| null | SHA-256 del guion de la capa 2, en hexadecimal. **Lo calcula el ejecutor al cargar el catálogo; NO se lee de ningún campo declarado.** Ver R3.6. |

**R3.1** — El ejecutor **NO DEBE** emitir un veredicto académico (aprobado/desaprobado). Devuelve materia prima; el worker decide. Mezclar las dos cosas metería lógica de negocio en el componente privilegiado.

**R3.5** — El ejecutor **NO DEBE** interpretar el contenido de `reporte`. Con la imagen de referencia ese contenido **no es el XML de JUnit**: es un sobre JSON del runner (`schema`, `fase`, `resultado`, `recursos`, y los XML adentro como `tar.gz` en base64), porque JUnit escribe más de un archivo y el worker necesita los tiempos medidos adentro del contenedor. El ejemplo de arriba muestra un `<testsuite>` por brevedad y **eso induce a error**: el campo transporta lo que la imagen ponga entre los marcadores, y quien lo sabe leer es el worker. Cambiar la forma del sobre no toca al ejecutor.

**R3.4** — `oomKilled` **DEBE** venir del `inspect` del paso 5b y **NO DEBE** inferirse del `exitCode`. El motivo está medido: con la JVM bien configurada el que se queda sin memoria es la JVM y no el cgroup, así que el out-of-memory llega como **`exitCode: 3` con `OOMKilled: false`**, y no como el `137` que uno esperaría. Los dos caminos existen y el worker necesita los dos datos para mapearlos. Un `exitCode` sin `oomKilled` deja al worker sin poder distinguir «se quedó sin memoria» de «el código del alumno falló», y eso cambia el veredicto que ve el alumno.

**R3.6** — `perfilHash` **DEBE** calcularse sobre los bytes del guion en el momento de cargar el catálogo, y **NO DEBE** leerse de un campo del JSON del perfil. Un hash declarado en el archivo es un hash que puede mentir: describe lo que quien escribió el archivo dice que puso, no lo que efectivamente se ejecutó. Este campo existe para que el worker pueda, meses después, reconstruir **con qué código exacto** se evaluó una entrega; si el dato es autodeclarado no sirve para eso, que es lo único para lo que sirve.

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
| `400` | Falta `X-Ejecucion-Id` o no es un UUID, **o** falta `X-Perfil` o no respeta `^[a-z0-9-]+@[0-9]+$` |
| `422` | `X-Perfil` bien formado pero **la clave no está en el catálogo** (§4.4) |
| `411` | Falta `Content-Length` |
| `413` | Bundle mayor a `MAX_BUNDLE_BYTES` |
| `503` | Cola llena (`resultado: "RECHAZADA"`), con header `Retry-After` |
| `502` | `ERROR_DAEMON` |

**R3.8** — La diferencia entre `400` y `422` **DEBE** respetarse: `400` es «el pedido está mal escrito», `422` es «el pedido está bien escrito y pide algo que no existe». Al worker le importa porque son fallas distintas: un `400` es un bug del worker y hay que arreglarlo en el código; un `422` es un desajuste de despliegue —el worker conoce un perfil que este ejecutor todavía no tiene cargado— y se arregla desplegando, no recompilando. Colapsar los dos en `400` esconde exactamente el caso que más va a pasar al agregar un perfil nuevo.

**R3.9** — El orden de validación **DEBE** ser: `X-Ejecucion-Id`, después `X-Perfil`, después `Content-Length`. El `413` de `MAX_BUNDLE_BYTES` se responde **sin leer un solo byte del cuerpo**, y por eso todo lo que se valida sobre headers tiene que estar antes.

**R3.2** — Los mensajes de error **NO DEBEN** incluir rutas del host, versiones del daemon ni el cuerpo de la respuesta de Docker. Un mensaje corto y un id de correlación.

### 3.4 `GET /salud`

Devuelve `200` con `{"daemon": "ok", "enVuelo": 3, "enCola": 0}`. **DEBE** consultar `GET /_ping` del daemon con timeout corto y devolver `503` si falla.

**R3.3** — Saturación **no** es enfermedad. Con la cola llena, `/salud` sigue devolviendo `200`: si devolviera `503` el orquestador reiniciaría el ejecutor justo cuando más se lo necesita.

---

## 4. La spec del contenedor

Es el corazón del componente. Todo esto es **constante en el código fuente**, salvo los cuatro
campos que salen del perfil (§4.4) y que están marcados uno por uno abajo.

> **Qué le pasó a P1 con el catálogo de perfiles (C7).** Antes P1 se leía «ningún byte de la spec
> proviene de quien llama». Ahora hay cuatro campos que dependen del header `X-Perfil`, y conviene
> ser exacto en vez de tranquilizador: **el que llama no escribe ninguno de esos cuatro valores,
> los elige de un conjunto cerrado que escribimos nosotros**. La diferencia es la que hay entre un
> menú y un formulario. Un request no puede pedir 8 GiB de memoria; puede pedir el perfil
> `java21-junit@4`, y qué significa eso lo decidió un archivo versionado en nuestro repo, revisado
> como se revisa código. §4.4 explica por qué eso conserva la propiedad que importa, y
> `SpecTest#p1_dosPerfilesDifierenSoloEnLosCuatroCamposVariables` es el test que lo prueba: dos
> perfiles cualesquiera producen un `create` idéntico salvo esos cuatro campos.

### 4.1 JSON de `POST /containers/create`

Lo que varía entre ejecuciones son el nombre, el label y los **cuatro campos del perfil**, marcados
con `←` abajo. Nada más.

```json
{
  "Image": "<perfil.imagen>",                        ← del perfil (§4.4)
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
    "sandbox.ejecucion": "<X-Ejecucion-Id>"
  },
  "HostConfig": {
    "NetworkMode": "none",
    "ReadonlyRootfs": true,
    "Tmpfs": { "/work": "rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000" },
    "Memory": "<perfil.limites.memoriaMb * 1048576>",  ← del perfil (§4.4)
    "MemorySwap": "<idéntico a Memory>",               ← del perfil (§4.4)
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
      { "Name": "cpu",    "Soft": "<perfil.limites.cpuS>", "Hard": "<idem>" },   ← del perfil (§4.4)
      { "Name": "nofile", "Soft": 256,      "Hard": 256 },
      { "Name": "nproc",  "Soft": 128,      "Hard": 128 },
      { "Name": "fsize",  "Soft": 33554432, "Hard": 33554432 }
    ]
  }
}
```

Y el nombre del contenedor, en la query: `?name=sandbox-<X-Ejecucion-Id>`.

> **Lo de arriba es la spec, no el byte a byte del cable (C11).** El cuerpo que se manda de verdad
> lo serializa `docker-java`, y difiere en dos cosas que **no cambian nada para el daemon**: incluye
> los campos nulos de su modelo, y manda `RestartPolicy` como `{"Name":""}` en vez de `{"Name":"no"}`
> —`""` es la ausencia de política, que es lo que pone `docker run` sin `--restart`—. El archivo de
> referencia de A1 tiene esos bytes, no éstos. La diferencia está anotada acá a propósito: si el
> golden y el documento se contradicen sin explicación, el que se termina ignorando es el golden.

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
| `Entrypoint` = `capa1.sh` | **La capa 1** (C9). Es nuestro código y es el `PID 1` del contenedor: extrae el tar, corre la capa 2 del perfil, recoge el buzón y arma el sobre. La evaluación —qué es compilar, qué es un test, qué significa aprobar— vive en la capa 2, que la escribe el Grupo 5 y llega por stdin. La detección de procesos sobrevivientes vive acá, en la capa que la capa 2 **no controla**: no se puede prevenir, se puede detectar, y la detección tiene que vivir donde el evaluado no llega. |
| `Image` del perfil | Un perfil es un lenguaje y su toolchain. Pedir que todos los lenguajes entren en una imagen única fue siempre lo que hacía inviable el catálogo. |
| `Memory` / `MemorySwap` del perfil | Compilar Java no cuesta lo mismo que correr un script. Un techo único obliga a dimensionar por el peor caso y desperdiciarlo en todos los demás. Sigue sin haber swap: los dos valores son **siempre iguales entre sí**. |
| `Ulimits[cpu]` del perfil | Mismo argumento, en tiempo de CPU. El techo duro `CPU_MAX_S` y la relación de R4.1 los hace cumplir el catálogo al **arrancar**, no cada ejecución. |
| `uid`/`gid` en las opciones del tmpfs | **Sin esto el contenedor no arranca.** Docker crea el tmpfs como `root`; con `mode=0700` y sin `uid`/`gid`, el proceso —que corre como `1000:1000`— no puede escribir en su único directorio escribible, y el entrypoint muere con `Permission denied` antes de leer el bundle. Encontrado corriendo el ejecutor contra la imagen real: las suites de aceptación no lo ven porque su imagen de fixture corre como root. |

### 4.3 Constantes

| Constante | Valor | Nota |
|---|---|---|
| `MAX_BUNDLE_BYTES` | `2097152` (2 MiB) | Tope del cuerpo del request |
| `TIMEOUT_EJECUCION_MS` | `60000` | Reloj de pared del ejecutor, desde `start`. Es la **red de última instancia**, no el mecanismo |
| `TIMEOUT_DAEMON_MS` | `5000` | Por llamada a la API de Docker (salvo `wait`) |
| `MAX_SALIDA_BYTES` | `1048576` (1 MiB) | Por cada stream, tras demultiplexar |
| `MAX_FRAME_BYTES` | `1048576` (1 MiB) | Tope de un frame individual (§6) |
| `MAX_CUERPO_DAEMON_BYTES` | `16777216` (16 MiB) | Tope de una respuesta del daemon leída entera en memoria. Con `max-size: 8m` el log no debería acercarse; existe para que un daemon anómalo no nos haga crecer sin límite |
| `MAX_CONCURRENTES` | `8` | Contenedores simultáneos |
| `MAX_COLA` | `16` | Esperando turno; por encima, `503` |
| `INTERVALO_BARRIDO_MS` | `300000` (5 min) | Limpieza de huérfanos |
| `EDAD_HUERFANO_MS` | `600000` (10 min) | Antigüedad para considerar huérfano |
| `VERSION_API_DOCKER` | `v1.43` | Fijada en el path |
| `MEMORIA_MAX_MB` | `1024` | **Techo duro de cualquier perfil** (C7). Ningún perfil del catálogo puede declarar más |
| `CPU_MAX_S` | `30` | **Techo duro de cualquier perfil** (C7). Ídem, para el ulimit `cpu` |
| `MAX_SCRIPT_BYTES` | `262144` (256 KiB) | Tope del guion de la capa 2 dentro de un perfil (§7, C9) |

> **`TIMEOUT_CPU_SEGUNDOS` se eliminó (C7).** Era el ulimit `cpu` como constante única. Ahora ese
> valor sale de `limites.cpuS` del perfil, y lo que quedó como constante es el **techo**
> (`CPU_MAX_S`) que ningún perfil puede pasar. No es que el límite se aflojó: pasó de ser un
> número a ser un intervalo con tope, y quien elige adentro del intervalo somos nosotros al
> escribir el perfil, no el que llama.

**R4.1** — El `cpuS` de **cada perfil del catálogo** **DEBE** ser holgadamente menor que
`TIMEOUT_EJECUCION_MS` expresado en segundos. Con `NanoCpus` = 1 CPU, el tiempo de CPU nunca supera
al de pared, así que si los dos números se acercan el reloj de pared dispara primero **siempre** y
el límite de CPU queda decorativo — que es justo el problema que C1 vino a arreglar. La relación se
verifica al **cargar el catálogo**, perfil por perfil, y su violación hace fallar el **arranque del
proceso**, nunca una ejecución individual (§4.4). Al revés, el reloj de pared tiene que ser generoso
precisamente porque **el presupuesto de CPU no tiene cota superior en tiempo de pared**: un proceso
puede consumir 20 s de CPU en 55 s de reloj si la máquina está saturada.

**R4.2** — Los relojes **por fase** (compilar, correr los tests) **NO DEBEN** vivir acá. Viven en la
capa 2 del perfil, que es la única que sabe qué es una fase. El ulimit `cpu` de §4.1 es el techo de
todas juntas, y el ejecutor no sabe ni tiene que saber cómo se reparte adentro.

### 4.4 El catálogo de perfiles

Es el mecanismo con el que C7 mantiene P1 mientras admite más de un lenguaje.

**R4.3** — El catálogo **DEBE** ser un directorio de sólo lectura, un archivo JSON por perfil,
nombrado `<perfilId>@<version>.json`. Esa misma cadena `<perfilId>@<version>` es la clave de
búsqueda y el valor exacto del header `X-Perfil`.

```json
{
  "perfilId": "java21-junit",
  "version": 3,
  "imagen": "sandbox-runner:2.0.0-capa1",
  "script": "#!/bin/sh\n... el guion completo de la capa 2, escapado en una sola cadena JSON ...",
  "reportFormat": "junit-xml",
  "limites": { "memoriaMb": 512, "cpuS": 20 }
}
```

**R4.4** — El catálogo **DEBE** cargarse entero al arrancar el proceso y **NO DEBE** releerse ni
recargarse en caliente. Un catálogo que cambia mientras el proceso corre convierte «con qué se
evaluó esta entrega» en una pregunta sin respuesta estable, y `perfilHash` (R3.6) existe justamente
para que esa pregunta tenga respuesta.

**R4.5** — Las validaciones del catálogo **DEBEN** hacer fallar el **arranque del proceso**, nunca
una ejecución individual. Es el mismo criterio que ya tenía el ejecutor con su guion único, ahora
generalizado a un directorio. Un perfil inválido es un error de despliegue, y un error de despliegue
tiene que ser ruidoso e inmediato, no un `500` intermitente que aparece recién cuando alguien pide
ese perfil. Fallan el arranque:

| Causa | Motivo |
|---|---|
| JSON inválido | Trivial |
| `limites.memoriaMb` > `MEMORIA_MAX_MB` | El techo de §4.3 |
| `limites.cpuS` > `CPU_MAX_S` | Ídem |
| `limites.cpuS` demasiado cerca del reloj de pared | R4.1, perfil por perfil |
| `script` mayor a `MAX_SCRIPT_BYTES` | El guion de la capa 2 viaja por stdin (§7) |

**R4.6** — De un perfil salen **exactamente cuatro** campos del `create`: `Image`, `Memory`,
`MemorySwap` y `Ulimits[cpu]`. Todo el resto de §4.1 es idéntico sea cual sea el perfil. Esto es
verificable y está verificado: el test compara el `create` de dos perfiles distintos y exige que
difieran **sólo** en esos cuatro campos. Es lo que sostiene P1 e I2 ahora que la spec dejó de ser
literalmente constante, y es más fuerte que el golden fijo que reemplaza, porque el golden probaba
que un JSON no cambiaba y esto prueba que **el conjunto de lo que puede cambiar es cerrado**.

**R4.7** — El campo `reportFormat` es **opaco para el ejecutor**: viaja al worker y no se
interpreta acá. Saber leer un reporte es conocimiento de evaluación, y el ejecutor no lo tiene
(R3.1, R3.5).

---

## 5. Secuencia de ejecución

Todas las rutas van prefijadas con `/{VERSION_API_DOCKER}`. **R5.0** — La versión **DEBE** ir explícita en el path; **NO DEBE** usarse el default del daemon.

**R5.12** — Al arrancar, antes de aceptar conexiones o programar el barrido, el ejecutor **DEBE**
confirmar contra el daemon que `MinAPIVersion <= VERSION_API_DOCKER <= ApiVersion` (`GET /version`).
El mínimo que acepta cada build del Engine varía — un `create` con `v1.43` fue rechazado contra
29.2.1 y anduvo contra 29.7.2 (`MinAPIVersion 1.40`) — así que fijar la versión (R5.0) no alcanza
para saber si el daemon la soporta. Si el daemon no responde, o responde rechazando la propia
llamada versionada (un Engine cuyo rango no la incluye puede devolver `400` a `/v1.43/version`,
«too old» o «too new» según de qué lado quede),
o su rango no incluye `VERSION_API_DOCKER`, el arranque **DEBE** fallar de inmediato, sin
reintentos: el mismo criterio de R4.5 para el catálogo — un error de despliegue tiene que ser
ruidoso e inmediato, nunca aparecer recién en la primera ejecución de un alumno.

**R5.13** — El único transporte permitido en producción es `unix://` o `npipe://`; el arranque
**DEBE** fallar de inmediato (antes de conectar a Docker) si `DOCKER_HOST` es cualquier otra cosa,
incluido `tcp://`, `http://`, `https://`, vacío o malformado. El paso 4 de esta misma sección
(«escribir y cerrar el socket adjunto entero») es lo que le da EOF a stdin del contenedor, y
docker-java no ofrece una media-clausura ordenada: ese cierre es **abortivo** (termina en
`request.abort()` del transporte httpclient5). Sobre TCP, un cierre abortivo llega al otro lado
como un *reset* (RST), y un RST hace que el kernel receptor descarte los bytes que ya llegaron
pero que la aplicación todavía no leyó — la entrega no es atómica con la escritura, y `Entrada`
sólo puede reportar los bytes que la librería leyó y flusheó de este lado, no los que el otro
lado efectivamente conservó.

Se midió con un daemon de mentira por TCP en loopback (200 KB de tar, 24 hilos quemando CPU,
150 ejecuciones): Windows truncó 115/150 y Linux 44/150; incluso un stdin chico de 56 bytes se
perdió entero 2/300 veces en reposo y 31/300 bajo carga. Contra Docker real por *unix socket*
(Linux) llegaron 60/60 completos, y por *named pipe* (Windows) 39/40 (más un `ERROR_DAEMON` sin
explicar, no truncamiento). De ahí el corte: `unix://` y `npipe://` son los transportes donde la
medición no mostró pérdida, y son además los dos que Windows y Linux exponen de forma nativa
(§5, nota de por qué docker-java y no el cliente a mano). Esto no arregla la causa de fondo —
seguiría haciendo falta una media-clausura ordenada, que docker-java no expone—, sólo la saca de
producción restringiendo el transporte a donde la medición no la mostró.

| # | Llamada | Notas |
|---|---|---|
| 1 | `POST /containers/create?name=sandbox-<id>` | Cuerpo de §4.1. `Content-Type: application/json` |
| 2 | `POST /containers/<id>/attach?stream=1&stdin=1` | **Antes de `start`.** Devuelve `101` |
| 3 | `POST /containers/<id>/start` | |
| 4 | Escribir en el socket adjunto y **cerrarlo entero** | Los **tres documentos** de §7: nonce, largo + guion de la capa 2, y el tar |
| 5 | `POST /containers/<id>/wait?condition=not-running` | Con `TIMEOUT_EJECUCION_MS`; si vence → `POST /containers/<id>/kill` y después igual `wait` |
| 5b | `GET /containers/<id>/json` | Solo para leer `State.OOMKilled` (R5.10) |
| 6 | `GET /containers/<id>/logs?stdout=1&stderr=1` | Respuesta HTTP normal. Se lee entera y se demultiplexa en memoria |
| 7 | `DELETE /containers/<id>?force=1&v=1` | En un bloque de limpieza garantizada |

**R5.1** — El paso 2 **DEBE** ejecutarse antes del paso 3. Si el contenedor arranca antes de que estemos conectados, puede intentar leer stdin sin nadie del otro lado.

**R5.2** — Escritura y lectura **NUNCA** ocurren simultáneamente sobre el mismo socket. El socket del paso 2 es de una sola dirección: se escribe y se cierra. Esta es la decisión de diseño que hace que el componente sea chico; una implementación que la viole está fuera de spec aunque funcione.

**R5.3** — El paso 7 **DEBE** ejecutarse siempre: en el camino feliz, en timeout, y ante cualquier excepción. Si el `DELETE` falla, se registra y se confía en el barrido de §10.

**R5.4** — El paso 6 **DEBE** ocurrir antes del paso 7. Borrado el contenedor, los logs no existen más.

**R5.10** — El paso 5b **DEBE** ejecutarse después del `wait` y antes del `DELETE`, con `TIMEOUT_DAEMON_MS`. De la respuesta se lee **únicamente** `State.OOMKilled`; el resto del `inspect` se ignora. Si la llamada falla, **NO DEBE** abortarse la ejecución: se devuelve `oomKilled: false` y se registra el fallo, porque el resultado ya está y perderlo por un dato de diagnóstico sería peor. En `TIMEOUT` el paso 5b se ejecuta igual: un contenedor que fue matado por el límite de memoria y además llegó al reloj es un caso real.

**R5.11** — El EOF de stdin **DEBE** producirse cerrando la conexión adjunta, no con una media
clausura del socket. El contenedor se crea con `StdinOnce: true` (§4.1), así que cerrar la conexión
**es** el EOF. Es una consecuencia de la spec, y por eso se puede depender de ella: sin EOF la capa
1 se cuelga leyendo el tar hasta el reloj de pared, y la media clausura no está disponible en todos
los clientes. El cierre **DEBE** ocurrir en un bloque de limpieza garantizada apenas la entrega
termina o vence su tope (R8.5).

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

**R7.2** — El stdin del contenedor **DEBE** ser exactamente estos **tres documentos pegados, sin
separadores** (C9), y después el cierre:

```
<nonce>\n                 32 caracteres hex, minúscula
<n>\n                     cuántos BYTES mide el guion de la capa 2, en decimal ASCII
<guion de n bytes>        la capa 2 del perfil (§4.4), opaca para el ejecutor
<tar>                     el bundle: "todo lo que quede del stream"
```

**R7.9** — El largo del guion **DEBE** ir adelante, y **NO DEBE** usarse ninguna marca de fin. El
motivo es que el guion lo escribe el Grupo 5: es texto arbitrario y puede contener cualquier línea,
incluida la que eligiéramos como separador. Un separador de texto es una apuesta a que el contenido
no lo contiene, y acá el contenido es de otro equipo. Con el largo adelante no hay alfabeto que
adivinar: son `n` bytes opacos. Es `Content-Length`, y por el mismo motivo por el que HTTP lo usa.

**R7.10** — Usar el **nonce como delimitador** del guion está **PROHIBIDO**. Se lo mostraría a la
capa 2, que es exactamente lo que el nonce existe para evitar (R7.3, I5). Una capa 2 que conoce el
nonce puede falsificar el bloque de reporte, y la capa 2 es código de otro equipo corriendo en el
mismo contenedor que el alumno.

**R7.11** — El largo se cuenta en **BYTES, no en caracteres**. Un acento en un comentario del guion
mueve el número. Es un bug que no aparece con un guion ASCII y revienta con el primer comentario
escrito en castellano.

**R7.3** — El nonce **NO DEBE** pasarse por variable de entorno, por argumento, ni por archivo. Una variable de entorno es recuperable desde el proceso del alumno leyendo `/proc/1/environ`, aunque el entrypoint la borre: el `unset` cambia la memoria del shell, no el snapshot del kernel.

**R7.4** — La capa 1 de la imagen (`capa1.sh`, §4.2) lee el nonce en una variable de shell **no exportada**, lo consume antes de invocar a la capa 2 —que por lo tanto nunca lo ve— y emite el reporte entre marcadores:

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

**R10.2** — El barrido **NO DEBE** borrar contenedores en vuelo de esta misma instancia. El filtro por edad alcanza si `EDAD_HUERFANO_MS` es holgadamente mayor que `TIMEOUT_EJECUCION_MS` — con los valores de §4.3, **10 veces mayor** (600 s contra 60 s). Antes de C1 la relación era de 20 veces, con el reloj de pared en 30 s; al subirlo a 60 s el margen se redujo a la mitad y **sigue alcanzando**, pero conviene tener el número escrito: si el reloj de pared vuelve a subir, éste es el otro número que hay que mirar.

**R10.3** — Un fallo del barrido se registra y no afecta las ejecuciones en curso.

**R10.4** — Este es el **único** barrido del sistema. `04-ms-sandbox-worker.md` §13 describe un *janitor* dentro del worker, con las etiquetas `sandbox.job` y `sandbox.worker`: ese componente deja de ser implementable en el momento en que el worker pierde el socket de Docker, y sus etiquetas no son las de §4.1. La limpieza vive donde vive el privilegio.

---

## 11. Requisitos no funcionales

**R11.1 — Imagen.** Base *distroless*: sin shell y sin gestor de paquetes. Aplica igual a Java y a Node.

**R11.2 — Usuario.** El proceso corre como usuario no-root, en un grupo con acceso al socket de Docker.

**R11.3 — Dependencias.** Solo se admiten dependencias que **no** parseen bytes controlados por un atacante. Una librería de JSON es aceptable: el JSON de la spec lo serializamos nosotros y el que parseamos viene del daemon, que ya es parte de la base de confianza.

**R11.4 — ~~Prohibición explícita de clientes de Docker.~~ DEROGADA (C11).**

> **Qué decía y por qué se cayó.** Decía que **NO DEBE** usarse `docker-java`, `dockerode` ni
> equivalentes, porque esas librerías ponen, en el mismo proceso que tiene el socket, un objeto con
> un setter equivalente a `withPrivileged(true)`, y eso reintroduciría la superficie que este
> componente existe para eliminar.
>
> **El argumento es débil, y conviene decir exactamente por qué.** Quien ya puede ejecutar código
> en este proceso tiene el socket de Docker y puede mandarle a mano el JSON que quiera: la librería
> no agrega **capacidad**, agrega **comodidad**. Un `withPrivileged(true)` disponible en el
> classpath no es una superficie nueva, es la misma superficie con un nombre más corto. Prohibir la
> librería sólo obligaba a reescribirla peor.
>
> **Lo que sí costaba era el golden test A1/A2**, y ése era el motivo real para desconfiar: si el
> cuerpo del `create` lo arma la librería, deja de haber un JSON nuestro que comparar. **Se
> recuperó entero.** `CreateContainerCmdImpl` *es* el modelo del cuerpo del pedido, y se serializa
> con el mismo `ObjectMapper` con el que la librería lo manda; A1 compara esos bytes contra el
> archivo de referencia y `EjecucionTest#elCuerpoDeCreateEsElGolden` compara **los bytes que el
> daemon recibió de verdad por el socket**. La spec del archivo es la spec del cable.
>
> **Lo que sí quedó como costo real** es el tamaño del artefacto: el fat jar pasó de ~2 MB a
> **21 MB**, porque `docker-java-core` arrastra guava, commons-compress, commons-lang3, commons-io
> y bouncycastle, que este ejecutor no usa. Se puede podar con exclusiones, verificándolas una por
> una contra el arranque real del cliente. **Está pendiente** y es una deuda anotada, no un
> problema resuelto: R11.3 sigue vigente y cada una de esas dependencias es superficie que no
> auditamos.

**R11.5 — Tamaño del código propio.** Objetivo: **por debajo de 400 líneas** sin contar tests. No es una métrica cosmética: la corrección de un control de seguridad se establece leyéndolo, no probándolo, y eso solo es viable si es chico (Saltzer & Schroeder, 1975, *economía de mecanismo*).

> **Incumplida, y por más que antes: 1064 líneas efectivas** (§14). El objetivo se fijó en 400 y la
> primera implementación dio 869; el port a `docker-java` no lo bajó y el catálogo de perfiles lo
> subió. El argumento de Saltzer & Schroeder no cambia por eso —sigue siendo cierto que un control
> de seguridad se audita leyéndolo—, pero un objetivo que se incumple por 2,6× y se deja escrito
> como objetivo deja de ser un requisito y pasa a ser una decoración. Lo que corresponde es
> **R14.1**: si de verdad se puede leer entero, que se lea y quede la evidencia. «Podemos leerlo»
> es una hipótesis; «lo leímos» es un hecho.

**R11.6 — Logs del ejecutor.** Por ejecución: id, duración de cada paso, bytes escritos, bytes leídos por stream, resultado. **NO DEBEN** registrarse el contenido del bundle, la salida del alumno ni el nonce.

**R11.7 — Apagado ordenado.** Ante `SIGTERM`: dejar de aceptar requests, esperar a que terminen las ejecuciones en vuelo hasta `TIMEOUT_EJECUCION_MS`, borrar sus contenedores, salir.

---

## 12. Invariantes de seguridad verificables

Cada una tiene un test en §13. Son las afirmaciones que se sostienen en la defensa.

| # | Invariante |
|---|---|
| **I1** | El JSON de `create` es byte a byte idéntico entre ejecuciones, salvo el label `sandbox.ejecucion` y el `name`. |
| **I2** | Ningún campo del request **escribe** un valor de la spec. Lo único que el request influye es **cuál perfil del catálogo se elige** (`X-Perfil`), y un perfil sólo puede mover los cuatro campos de R4.6. |
| **I8** | Dos perfiles cualesquiera del catálogo producen un `create` idéntico salvo `Image`, `Memory`, `MemorySwap` y `Ulimits[cpu]`. |
| **I9** | La capa 2 nunca ve el nonce: lo consume la capa 1 antes de invocarla. |
| **I3** | Un traversal exitoso en el tar igual falla al escribir, porque el rootfs es de solo lectura. |
| **I4** | El contenedor del alumno no tiene red ni acceso al socket de Docker. |
| **I5** | El código del alumno no puede producir el bloque de reporte. |
| **I6** | Todo contenedor creado termina borrado, por la vía normal o por el barrido. |
| **I7** | El ejecutor nunca desempaqueta ni interpreta el tar. |

---

## 13. Criterios de aceptación

Cada test es una afirmación binaria. **Esta sección dice también lo que quedó sin cubrir y por
qué**: una suite de aceptación que sólo enumera lo verde no es evidencia, es publicidad.

> **Estado al 10‑sep‑2026.** La implementación de referencia (Java, rama `feat/catalogo-perfiles`)
> corre **71 tests en verde**, en tres corridas consecutivas. La implementación en Node quedó de
> lado, así que donde este documento decía «las dos implementaciones corren la misma suite» ahora
> hay una sola, y A2 quedó sin contraparte (ver 13.1).

### 13.1 Golden test de la spec — el más importante

**A1** — Serializar el JSON de `create` para tres ids distintos y compararlo contra un archivo de referencia versionado, normalizando solo `name` y el label. Debe ser idéntico. Este test es lo que convierte I1 y I2 de afirmación en evidencia, y **DEBE** fallar si alguien agrega un parámetro a `POST /ejecutar` que toque la spec.

> **C1 rompe este test a propósito.** Al agregar el ulimit `cpu`, el archivo de referencia queda viejo: se regenera **una vez**, se revisa el diff a ojo (tiene que ser exactamente una entrada nueva en `Ulimits`) y se comparte entre las dos implementaciones. Que A1 falle ante un cambio de la spec es el mecanismo funcionando; que no fallara sería la alarma.

**A2** — ~~El archivo de referencia es el mismo para Java y para Node.~~ **Sin contraparte.** Con
la implementación Node de lado, el archivo dejó de ser un contrato **entre** implementaciones y pasó
a ser el golden de una sola. Además ya no sería byte a byte el mismo: el cuerpo que arma
`docker-java` incluye los campos nulos del modelo y manda `RestartPolicy` como `{"Name":""}` en vez
de `{"Name":"no"}` (para el daemon es lo mismo: `""` es la ausencia de política). Se anota como
pérdida real, no como test que pasa.

> **A1 y el catálogo (C7).** El golden fijo único dejó de tener sentido en el momento en que hay
> más de un perfil: ahora `spec-create-referencia.json` es el golden **del perfil de referencia**
> `java21-junit@4`, y lo que cubre la generalidad es A34 (R4.6), que compara dos perfiles distintos
> entre sí. El par es más fuerte que el golden solo: uno fija los bytes exactos de un caso, el otro
> fija que **el conjunto de lo que puede variar es cerrado**.

**A1b** — El cuerpo del `create` que **el daemon recibió por el socket** es idéntico al que produce
la serialización de A1. Este test existe porque, con la librería armando el pedido (C11), «el JSON
que serializamos» y «el JSON que se mandó» dejaron de ser obviamente lo mismo. Sin él, A1 prueba
una intención; con él, prueba un hecho.

### 13.2 Camino feliz

**A3** — Una entrega que compila y pasa: `resultado: COMPLETADA`, `exitCode: 0`, `reporte` no vacío, `reporteAusente: false`.
**A4** — Una entrega que no compila: `COMPLETADA`, `exitCode != 0`, el error de compilación aparece en `stderr`.
**A5** — Una entrega que falla tests: `COMPLETADA`, `exitCode != 0`, el reporte tiene la falla.

### 13.3 Protocolo

**A6** — *El test que valida §7.* Escribir los **tres documentos** de R7.2 y cerrar; el contenedor
debe reconstruir el nonce, el guion completo y el tar completo, **byte a byte**. Si falla, §7 hay
que rediseñarla y **este test bloquea todo lo demás**. ✅ Corre contra un **daemon de Docker real**
—nativo en Windows, por `npipe`—, no contra un doble.
**A7** — Verificar que una salida **sin enmarcar** es `ERROR_DAEMON`. ⚠️ **Cambió de mecanismo**:
ya no se mira el `Content-Type` de `logs`, porque con la librería (C11) esa respuesta no pasa por
nuestras manos. Se detecta por **tipo de frame**: `docker-java` entrega la salida no enmarcada como
frames `RAW` y el acumulador los rechaza. La guarda que importaba —no adivinar que eso es stdout—
sigue cerrada; el camino por el que se llega a ella es otro.
> **A8–A12 quedaron PARCIALES a propósito, y hay que decir qué se perdió.** El desenmarcado dejó
> de ser código nuestro: lo hace `docker-java` (C11). Los tests que alimentaban a nuestro
> demultiplexor con streams sintéticos corruptos ya no tienen a quién alimentar. Concretamente:
> **R6.2** (largo de frame fuera de rango ⇒ `ERROR_DAEMON`) ya no tiene tope propio, y **R6.5**
> (encabezado o payload truncado) ahora corta y devuelve lo que llegó, **en silencio**. Lo que sí
> se conservó es la guarda de A7. Esto es deuda de auditoría, no un detalle de implementación: son
> tres afirmaciones de §6 que el documento sigue haciendo y la suite ya no prueba.

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

**A32** — *(C1)* El JSON de `create` contiene el ulimit `cpu` con el `cpuS` del perfil, y ese valor
es menor que `TIMEOUT_EJECUCION_MS / 1000` (R4.1). Lo segundo es un assert sobre **`CPU_MAX_S`**, el
techo que el catálogo le hace cumplir a cualquier perfil, no sobre una constante única: protege
contra que alguien suba el presupuesto de CPU sin subir el reloj de pared y deje el límite
decorativo sin que nada falle. Cambió el sujeto del assert, no la afirmación.

**A33** — *(C3)* Contra un contenedor de prueba que arranca y **no** consume stdin, con un bundle mayor al buffer del pipe: la escritura del paso 4 abandona al vencer su tope, el cupo de concurrencia se libera, y el resultado es `TIMEOUT` con el cierre registrado como incompleto (R8.5). Sin este test el bug es invisible: la suite queda verde y el ejecutor se traba en producción.

### 13.7 Tests del catálogo y del modelo de dos capas *(C7–C10)*

**A34** — *(C7, R4.6)* El `create` de **dos perfiles distintos** difiere **exactamente** en `Image`,
`Memory`, `MemorySwap` y `Ulimits[cpu]`, y en nada más. Es el test que sostiene P1 e I2 ahora que la
spec dejó de ser literalmente constante, y el que reemplaza al golden fijo único como afirmación
general.

**A35** — *(C7, R4.5)* ✅ Cada causa de rechazo del catálogo tiene que hacer fallar el **arranque
del proceso**: JSON inválido, `memoriaMb` sobre `MEMORIA_MAX_MB`, `cpuS` sobre `CPU_MAX_S`,
violación de R4.1, y guion sobre `MAX_SCRIPT_BYTES`. Una por una. Lo que el test tiene que afirmar
no es que el perfil se rechaza, sino **cuándo**: al arrancar, no al ejecutar.

> **Cómo quedó (`CatalogoTest`).** Los tests ejercitan `Catalogo.cargar`, que en `Main` corre antes
> de conectar con Docker y de abrir el socket: si tira, el proceso nunca atiende una request. Cada
> causa se prueba sola, con su valor límite (el techo exacto se acepta, uno más se rechaza) y
> afirmando el mensaje de **esa** causa, no uno genérico: con `cpuS = CPU_MAX_S + 1` también se
> viola R4.1, así que un test que buscara sólo «cpuS» seguiría en verde aunque se borrara el techo.
> Se agregan un archivo inválido entre válidos (el catálogo entero no carga), que sólo se leen
> `*.json`, y los campos obligatorios faltantes. Verificado con mutaciones: desactivar el techo de
> memoria, el de CPU o el cálculo del hash pone en rojo el test correspondiente.
>
> **La causa «violación de R4.1» hoy es inalcanzable, y hay que decirlo.** `Catalogo` valida
> `cpuS > CPU_MAX_S` antes que R4.1, y con `CPU_MAX_S = 30` y 60 s de reloj de pared ningún
> `cpuS ≤ 30` cumple `cpuS * 2 > 60`. La rama existe y no se puede disparar: lo que la sostiene es
> la relación entre constantes, y eso es lo que fija el test
> (`a35_r4_1EsInalcanzableHoyConLasConstantesActuales`). Si alguien sube `CPU_MAX_S` por encima de
> 30, ese test se pone en rojo y avisa que falta uno que dispare la excepción de verdad.

**A36** — *(C8, C10)* `X-Perfil` ausente o malformado ⇒ `400`; bien formado y fuera del catálogo ⇒
`422`; y la respuesta trae los **trece campos en el orden exacto** de §3.1, con los tres del perfil
al final.

**A37** — *(C7, R3.6)* ✅ `perfilHash` tiene que ser el SHA-256 de los bytes del guion, calculado
al cargar, y un campo `hash` declarado en el JSON del perfil **no** debe leerse. R3.6 dice que este
campo existe para que el worker pueda reconstruir con qué código exacto se evaluó una entrega.

> **Cómo quedó.** El test calcula el hash por su cuenta, sin reusar el código bajo prueba, sobre un
> guion con `ñ` (bytes ≠ caracteres). Y resuelve una ambigüedad de esta misma frase: «no debe
> leerse» podía significar «se ignora». En el código **no se ignora: hace fallar la carga**. Jackson
> rechaza por defecto las propiedades desconocidas y el registro del perfil no tiene un campo
> `hash`, así que un perfil que lo declara no carga, como si fuera JSON inválido. Cumple la
> propiedad que importa —un hash declarado nunca llega a ser `perfilHash`— y además es la lectura
> más estricta: un campo con un nombre mal escrito tampoco pasa en silencio.

**A38** — *(C9, R7.11)* ✅ **parcial.** El largo se cuenta en bytes y no en caracteres: el test usa
un guion con una `ñ` y afirma que el número que viaja es el de bytes —y, explícitamente, que **no**
es el de caracteres—. Lo que **falta** es el otro caso de R7.9: un guion que contenga, en su texto,
la línea que uno elegiría como separador. Es el motivo por el que el largo va adelante, y hoy es
argumento sin test.

**A39** — *(C9)* Los nueve bundles de referencia corren contra la **imagen real de dos capas**
—`sandbox-runner:2.0.0-capa1`, no el fixture de busybox— con el ejecutor de por medio, y reproducen
la tabla de resultados **sin divergencias**. Incluye los dos casos hostiles que antes sólo se habían
confirmado invocando la imagen a mano.

> **El resultado más interesante de A39, y conviene no perderlo.** En los casos de reporte hostil la
> capa 2 termina diciendo «todo bien» (`exitEval=0`) y la capa 1 **sobrescribe el veredicto igual**,
> con código interno `30`, porque detectó 6 procesos vivos después de que la evaluación dijo haber
> terminado. Ése es el argumento entero del modelo de dos capas funcionando dentro de un test: no se
> puede **prevenir** que la capa 2 mienta, se puede **detectar**, y la detección tiene que vivir en
> la capa que la capa 2 no controla.

**A40** — *(R5.12)* ✅ El arranque tiene que fallar si el daemon no soporta `VERSION_API_DOCKER`:
contra un daemon de prueba compatible pasa; contra uno cuyo rango excluye la versión falla con un
mensaje que nombra la causa y ambas versiones; contra uno que rechaza la propia llamada
`/v1.43/version` con `400` ("client version ... is too old") falla como incompatibilidad y no como
inalcanzable; y contra un daemon inexistente falla como inalcanzable. La comparación numérica
`MinAPIVersion <= 1.43 <= ApiVersion` vive en una función pura y package-private, probada aparte de
cualquier daemon: bordes inclusivos, por debajo del mínimo, por encima del máximo, la trampa
numérica-vs-texto (`"1.9"` ordena después de `"1.43"` como string pero es menor como versión), y
entradas malformadas.

> **Cómo quedó (`DockerVersionTest`, `ProtocoloIT`).** `DaemonDePrueba` gana una ruta `/version`
> configurable (`ApiVersion`, `MinAPIVersion`, y un modo que devuelve `400` con el mensaje de "too
> old"). `DockerVersionTest` cubre la función pura y los cuatro escenarios contra el daemon de
> mentira; `ProtocoloIT` agrega un test contra Docker real, que se saltea igual que el resto de la
> suite si no hay daemon escuchando. Verificado con mutación: anular la mitad `MinAPIVersion` de la
> comparación pone en rojo el caso de rango incompatible.

**A41** — *(R5.13)* ✅ El arranque tiene que fallar si `DOCKER_HOST` no es `unix://` ni `npipe://`.
`unix://` y `npipe://` se aceptan; `tcp://`, `http://`, `https://`, vacío, `null`, texto sin
esquema reconocible y variantes de mayúsculas (`TCP://`) se rechazan con un mensaje que nombra la
causa (el cierre abortivo del canal adjunto y el reset que produce sobre TCP). La comparación vive
en una función pura y package-private (`Docker.validarTransporte`), en el mismo estilo que
`versionCompatible` de A40, y se llama desde `Main` **antes** de `Docker.conectar` — a propósito
no adentro de `conectar`, porque los tests siguen necesitando conectar contra el daemon de prueba,
que es TCP.

> **Sobre el daemon de prueba y la intermitencia que R5.13 explica.** `DaemonDePrueba` habla TCP
> (ver su propio javadoc: docker-java resuelve el socket Unix con JNA sólo en Linux/macOS, así que
> un socket Unix de Java en Windows no le sirve para probar el cliente real). Eso significa que la
> suite sigue ejercitando, a propósito, el único transporte que R5.13 prohíbe en producción — y es
> exactamente ahí donde el cierre abortivo puede llegar antes de que el kernel del otro lado
> termine de entregar los bytes ya escritos, lo que hacía intermitentes bajo carga a
> `EjecucionTest#elNonceEsDistintoCadaVez` y `EjecucionTest#framingDeTresDocumentos`. La solución no
> es "arreglar" el cierre (seguiría siendo abortivo: no hay media-clausura en docker-java) sino
> sacar esa carrera de la medición: `Docker` gana un hook de prueba (`alCerrarAdjunto`, `null` en
> producción, cero cambio de comportamiento) que corre justo antes de cerrar el canal adjunto, y
> `DaemonDePrueba` gana `esperarRecepcion(total)`, que cuenta bytes recibidos en el attach a medida
> que llegan y deja que el test espere la confirmación antes de que el ejecutor cierre. Verificado
> con un test de estrés temporal (200 KB de tar, 24 hilos quemando CPU, 150 y 300 iteraciones): con
> el hook, 100% de las recepciones coincidieron con el tamaño esperado; sin él, la misma corrida
> truncó 96/150. El seam no cambia nada de producción — ahí el transporte ya está restringido por
> R5.13 a donde la medición no mostró pérdida.

### 13.8 Cobertura real, incluido lo que falta

| Grupo | Estado |
|---|---|
| A1, A1b, A32, A34 — la spec del contenedor y sus goldens | ✅ |
| A36 — `X-Perfil` (`400`/`422`) y los trece campos en orden | ✅ |
| A6, A39 — framing y los nueve bundles contra la imagen real | ✅ |
| A7 — salida sin enmarcar | ✅ por tipo de frame (cambió el mecanismo) |
| A21, A26, A27, A31, A33 | ✅ |
| **A38** — framing con guion adverso | ⚠️ **parcial**: el conteo en bytes sí, el guion con la línea separadora no |
| **A8–A12** — corrupción del stream | ⚠️ **parcial**: R6.2 y R6.5 sin cobertura propia |
| **A35, A37** — validaciones del catálogo y `perfilHash` | ✅ `CatalogoTest`, 19 tests. La causa R4.1 es inalcanzable con las constantes actuales y se fija como relación, no como excepción |
| **A40** — verificación de versión del daemon al arrancar | ✅ `DockerVersionTest` (comparación pura + daemon de prueba), y contra Docker real en `ProtocoloIT` |
| **A41** — rechazo de transporte `tcp://` al arrancar | ✅ `DockerTransporteTest` (comparación pura, sin daemon) |
| **A2** — golden compartido con Node | ❌ **sin contraparte**: la implementación Node quedó de lado |
| **A3–A5, A13–A20, A22–A25, A28–A30** | ❌ **fuera del alcance acordado**: son tests de la **imagen**, no del ejecutor. A13–A20 en particular verifican la extracción del tar, que ocurre en `capa1.sh` (I7: el ejecutor no desempaqueta nada) |

**R13.1** — Lo que falta **NO DEBE** presentarse como una misma «cobertura pendiente». Cada caso
tiene un motivo distinto y una salida distinta:

| Falta | Qué es | Cómo se salda |
|---|---|---|
| **A38** (mitad) | **Deuda**, barata | Un caso más con un guion que contenga la línea separadora |
| **A8–A12** | **Deuda o cambio de requisito** | Tests contra el acumulador, **o** aceptar por escrito que R6.2 y R6.5 dejan de ser requisitos ahora que el desenmarcado es de la librería |
| **A2** | **Decisión ya tomada** | Nada: hay una sola implementación |
| **A3–A5, A13–A20, A22–A25, A28–A30** | **Cambio de dueño** | Su lugar es la suite de la imagen, no la del ejecutor |

Meterlas en la misma bolsa es lo que después se defiende mal: «faltan tests» invita a que pregunten
por el peor de los cinco, y sólo uno de los cinco es realmente un agujero.

---

## 14. Dimensionamiento

Estimación para presupuestar el trabajo, no un compromiso. Las dos últimas columnas son **líneas
efectivas medidas**, sin comentarios ni blancos: `Java v1` es la primera implementación (cliente
HTTP a mano, una sola capa), `Java hoy` es la de la rama `feat/catalogo-perfiles`.

| Módulo | Java est. | Node est. | Java v1 | **Java hoy** | Qué hace |
|---|---|---|---|---|---|
| Cliente HTTP sobre socket Unix | 200–250 | 20–30 | 271 | **295** | `Http` (108) para hablarle al worker + `Docker` (187) para las llamadas de §5 sobre `docker-java` |
| Spec del contenedor | 60 | 50 | 90 | **74** | Constantes + serialización |
| Demultiplexador | 60 | 60 | 53 | **47** | Lo que quedó de nuestro lado de §6 |
| Extracción del reporte | — | — | 27 | **27** | §7.5–§7.8 |
| Orquestación de la secuencia | 80 | 80 | 137 | **115** | Los 7 pasos, timeouts, limpieza garantizada |
| Servidor HTTP + cola | 70 | 50 | 217 | **230** | `/ejecutar`, `/salud`, semáforo, validación de `X-Perfil` |
| Barrido de huérfanos | 40 | 40 | 26 | **26** | §10 |
| Cableado y frontera | — | — | 48 | **80** | `Main`, `Motor`, `Log`, `ErrorDaemon`, `Constantes` |
| **Entrada** *(C9, nuevo)* | — | — | — | **62** | El framing de tres documentos, R7.2, R7.9–R7.11, R8.5 |
| **Catálogo** *(C7, nuevo)* | — | — | — | **108** | §4.4: carga y valida el catálogo al arrancar |
| **Total propio** | **~510–560** | **~300–310** | **869** | **1064** | Sin tests |

**La estimación se quedó corta por 300 líneas, y el motivo importa para la comparación.** Contaba
una sola vez el costo de los sockets Unix, del lado del cliente. En realidad se paga **dos** veces:
`java.net.http.HttpClient` no habla sockets Unix (JDK-8377806), y `com.sun.net.httpserver` solo
bindea `InetSocketAddress`, así que también hay que escribir a mano el **servidor**. Por eso
`Servidor` triplica lo estimado. En Node los dos lados vienen en la librería estándar.

**El port a `docker-java` (C11) no achicó el código, y conviene decir por qué en vez de dejarlo
como una sorpresa.** Se esperaba bajar de 869 a ~530. Salió 1064. Tres motivos, ninguno sorpresa
retrospectiva:

- **El port en sí es casi neutro.** Se fueron `ClienteDocker` (174) y el demultiplexor (53), y
  entraron `Docker` (187) y `Acumulador` (47). La librería ahorra el HTTP crudo, pero hay que
  reconstruirle encima la semántica que no trae: el EOF de stdin (R5.11), el tope por llamada y la
  guarda contra la salida sin enmarcar (A7).
- **`Http` no se fue.** La estimación lo contaba como ahorro y estaba equivocada: `Http` lo usa
  `Servidor` para hablar con el worker, que no tiene nada que ver con Docker. Sacar la librería de
  un lado no toca el otro lado.
- **Lo que creció es funcionalidad nueva, no plomería**: `Entrada` (62) es el framing de tres
  documentos y `Catalogo` (108) es el catálogo de perfiles. Juntos son 170 líneas que antes no
  existían porque la funcionalidad tampoco.

Lo que sí se ahorró es la orquestación, que bajó de 137 a 115: ya no hay watchdogs propios, ni hilos
para acotar el `wait`, ni escritura manual de stdin.

**R14.0** — ~~Cuando la implementación en Node esté, la comparación DEBE hacerse sobre esta misma
tabla.~~ **Sin contraparte**: la implementación en Node quedó de lado (§13). La columna `Node est.`
se conserva como lo que es —una estimación que nunca se contrastó— y **NO DEBE** citarse como si
fuera una medición.

**R14.2** — El objetivo de R11.5 (< 400 líneas) **no se cumplió y ya no va a cumplirse**: 1064
líneas es 2,6× el objetivo. Corresponde una de dos cosas, y hay que elegir explícitamente: bajar el
alcance del componente, o **cambiar el objetivo dejando escrito el argumento nuevo**. Lo que no
corresponde es dejar el número en 400 y las líneas en 1064, porque un requisito que todos saben que
no se cumple deja de disciplinar nada.

**R14.1** — Terminadas las implementaciones, **DEBE** hacerse una revisión línea por línea de la que
se elija, por dos personas que no la escribieron, con las observaciones anotadas y versionada en el
repo. El argumento de que elegimos un lenguaje que el equipo puede auditar solo vale si efectivamente
lo auditamos: «podemos leerlo» es una hipótesis, «lo leímos» es evidencia. **Con R11.5 incumplida
por 2,6×, ésta es la única garantía de auditabilidad que le queda al componente, y sigue pendiente.**

---

## 15. Referencias

- Saltzer & Schroeder, *The Protection of Information in Computer Systems*, Proc. IEEE 63(9), 1975 — economía de mecanismo, mínimo privilegio.
- Provos, Friedl & Honeyman, *Preventing Privilege Escalation*, USENIX Security 2003 — la privsep de OpenSSH; el ejecutor tiene la misma estructura: un proceso privilegiado que atiende **un menú fijo** de pedidos.
- Docker Engine API `v1.43` — `containers/create`, `attach`, `wait`, `logs`.
- JDK-8377806, *HTTP over Unix Domain Sockets*; JEP 380, *Unix domain socket channels*.
- CVE-2025-45582 — GNU tar ≤ 1.35, traversal en dos extracciones; el motivo de no reutilizar `/work`.
- CVE-2025-52565 — runc; solo afecta contenedores con `Tty: true`.
- Documentos hermanos: `03-ms-sandbox-ejecucion.md` (por qué la entrega va por stdin), `05-ms-sandbox-patrones.md` (por qué ejecutor y no proxy), `11-impacto-v4-g5.md` (el análisis del V4 que originó C7–C12).
- `ms-sandbox/imagenes/capa1/capa1.sh` — **la capa 1**: el entrypoint de §4.1, quien extrae el tar, invoca a la capa 2 y arma el sobre. Las reglas de extracción que esta spec **no** fija (I7) viven ahí.
- `../../HANDOFF-opcion1.md` — el plan de la Opción 1 y la tabla de resultados de los nueve bundles (A39).
- `ms-sandbox/ejecutor/README.md` — la implementación de referencia: dónde vive cada cosa, qué se perdió con el port y las dos intermitencias que aparecieron al cerrar la validación.
