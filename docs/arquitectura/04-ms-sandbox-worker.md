# `ms-sandbox` — El worker

> **Tema 06 — Sandbox / Runtime.**
> El worker es donde está toda la ingeniería del servicio: es el único componente con estado, el único que puede morir a mitad de algo, y el único de quien depende que un alumno reciba respuesta.
> La API es un CRUD. Los flags del contenedor son quince líneas que se escriben una vez. **Esto no.**

Complemento de `03-ms-sandbox-ejecucion.md`, que cubre el aislamiento, el contrato con T05 y el origen de los tests.

> **Revisado después de [D1](./README.md).** El worker ya no habla la API de Docker: le pide al **ejecutor**, por un socket Unix, con un `POST /ejecutar` que no lleva ni un parámetro de configuración. Las secciones que más cambiaron son la [6](#6-paso-1--ejecutar), la [12](#12-cómo-le-habla-al-ejecutor) y la [13](#13-el-janitor--el-que-se-fue-y-el-que-queda). El contrato del otro lado de esa frontera está en [`08-spec-ejecutor.md`](./08-spec-ejecutor.md); el estado de las decisiones, en [`README.md`](./README.md).

---

## Índice

**Parte I — Entender el worker**
1. [Qué es exactamente un worker](#1-qué-es-exactamente-un-worker)
2. [Por qué existe: la versión ingenua y por qué se rompe](#2-por-qué-existe-la-versión-ingenua-y-por-qué-se-rompe)
3. [Lo que los separa: la cola](#3-lo-que-los-separa-la-cola)
4. [El worker son tres pasos](#4-el-worker-son-tres-pasos)
5. [Paso 0 — recibir el job](#5-paso-0--recibir-el-job)
6. [Paso 1 — ejecutar](#6-paso-1--ejecutar)
7. [Qué se rompe si el worker muere](#7-qué-se-rompe-si-el-worker-muere)
8. [Anatomía del proceso](#8-anatomía-del-proceso)
9. [El modelo mental en una frase](#9-el-modelo-mental-en-una-frase)

**Parte II — Construirlo**

10. [Topología: ¿mismo proceso que la API?](#10-topología-mismo-proceso-que-la-api)
11. [Concurrencia: el límite no son los threads](#11-concurrencia-el-límite-no-son-los-threads)
12. [Cómo le habla al ejecutor](#12-cómo-le-habla-al-ejecutor)
13. [El janitor — el que se fue y el que queda](#13-el-janitor--el-que-se-fue-y-el-que-queda)
14. [Apagado ordenado](#14-apagado-ordenado)
15. [Esquema: `ejecucion` y `outbox`](#15-esquema-ejecucion-y-outbox)
16. [Qué mide el worker](#16-qué-mide-el-worker)
17. [Cómo se prueba](#17-cómo-se-prueba)

---
---

# Parte I — Entender el worker

## 1. Qué es exactamente un worker

"Worker" se usa para tres cosas distintas y conviene fijar cuál es acá:

> **El worker es un *proceso* — un `java -jar` corriendo en su propio contenedor.**

No es un thread, no es una clase, no es un `@Async`. Es un programa separado de la API, que **no escucha HTTP** y al que **nadie llama por su nombre**. Está suscripto a una cola del broker, y trabaja cuando el broker le manda algo.

Adentro tiene varios threads, pero eso es su anatomía interna ([§8](#8-anatomía-del-proceso)), no su definición.

---

## 2. Por qué existe: la versión ingenua y por qué se rompe

Supongamos que no hay worker. T05 hace el POST y se ejecuta ahí mismo:

```java
@PostMapping("/ejecuciones")
public Resultado ejecutar(@RequestBody Entrega e) {
    var c = docker.crear(...);
    docker.copiar(c, e.archivos());
    docker.arrancar(c);
    docker.esperar(c, 10_000);        // ← el thread HTTP parado 10 segundos
    return normalizar(docker.leerReporte(c));
}
```

Funciona con un alumno en una notebook. Se rompe de cuatro formas:

### a) Se agotan los threads HTTP

Cada request ocupa un thread 10 segundos sin hacer nada más que esperar. Con 30 entregas simultáneas no quedan threads libres ni para responder `/health`. El servicio **parece caído estando perfecto**.

### b) Nada limita la concurrencia

30 requests ⇒ 30 contenedores ⇒ **30 × 512 MB = 15 GB y 30 cores**. El host se muere, y con él las 30 ejecuciones — no solo las que sobraban.

### c) Los reintentos hacen espiral

T05 tiene timeout de 8 s, el sandbox tarda 10, T05 reintenta. Ahora hay dos contenedores para la misma entrega. Más carga → más lentitud → más timeouts → más reintentos. Se retroalimenta hasta caerse.

### d) Un deploy pierde entregas

El proceso se reinicia con 6 ejecuciones en curso. No quedó registro de que existieran. Seis alumnos ven un error y tienen que volver a entregar.

### La raíz es una sola

> **Recibir un pedido es rápido (milisegundos). Ejecutarlo es lento (segundos). Si se atan al mismo hilo, el lento gobierna al rápido.**

El worker existe para **separarlos en el tiempo**.

---

## 3. Lo que los separa: la cola

La cola es una **cola de RabbitMQ**. La API publica el job y se olvida; el worker consume a su ritmo.

```
T05 ──POST──► [ API ]                                        ~5 ms
                 │
                 ├─ INSERT INTO ejecucion (estado='ENCOLADA')
                 │  + INSERT INTO outbox            ← misma transacción
                 │
                 └─ responde 202 { ejecucionId }

              [ RELAY ] ─ lee outbox ─► publish ─► ┌────────────────┐
                                                   │  sandbox.jobs  │  ← la cola
                                                   └───────┬────────┘
                                    ┌──────────────────────┴──────────┐
                              [ WORKER 1 ]                      [ WORKER 2 ]   ~5 s c/u
```

La tabla `ejecucion` **no desaparece**: sigue siendo el registro de estado y resultado que T05 consulta por `GET /ejecuciones/{id}`. Lo que cambia es que ya no *es* la cola — es el registro del trabajo que la cola reparte.

Tres cosas concretas que compra:

| | |
|---|---|
| **Absorbe el pico** | Llegan 30 entregas juntas: la API publica 30 mensajes y responde `202` a las 30. Los workers las procesan de a 6. Nadie recibe un error; todos esperan un poco. **Eso es backpressure**: la cola crece en lugar de que se caiga el host |
| **Sobrevive a reinicios** | Cola `durable`, mensajes `persistent`, `publisher confirms`. Se reinicia la API, el worker, el broker, o los tres, y el trabajo se hace igual. Es la diferencia entre *"el alumno pierde la entrega"* y *"el alumno espera 40 segundos"* |
| **Desacopla velocidades** | La API acepta mucho más rápido de lo que el worker ejecuta, y está bien. Es el punto entero del diseño |

> **Ojo con los defaults:** una cola de RabbitMQ **no es durable si no se la declara así**, y un mensaje **no sobrevive al reinicio del broker si no se lo publica `persistent`**. Los dos son opt-in. Una cola no durable con mensajes transitorios es una cola en RAM: un `docker restart` del broker se lleva las entregas de todos.

### Por qué RabbitMQ y no Kafka

Conviene decirlo explícito, porque es la pregunta de defensa obvia.

| | RabbitMQ | Kafka |
|---|---|---|
| Modelo | Cola con *competing consumers* | Log particionado |
| Paralelismo | Se agregan consumers y listo | Atado al número de particiones |
| Ack | **Por mensaje**, en cualquier orden | Por *offset*: confirmar el 10 confirma el 1..9 |
| Job lento | Los otros consumers siguen | Bloquea el resto de **su partición** |

El del ack es el decisivo. Las ejecuciones duran entre 1 y 10 segundos de forma impredecible y **terminan desordenadas**. Con offsets hay que elegir entre confirmar de más —y perder los que quedaron atrás si el worker muere— o quedarse esperando al lento. Con ack por mensaje el problema no existe.

**Kafka es la herramienta correcta para el *fan-out* de eventos del sistema** (`EjecucionFinalizada` hacia varios consumidores independientes, que además pueden releer el histórico). Es la herramienta equivocada para repartir trabajo, y este servicio necesita las dos cosas del mismo broker.

Ese es justamente el argumento que inclina la elección **para todo el sistema**, no solo para el sandbox: para los otros once servicios el broker es solo notificación y cualquiera de los dos sirve; acá no. Ver [`01-panorama-microservicios-backend.md`](./01-panorama-microservicios-backend.md) §4.

### El precio del broker: la doble escritura

Acá hay que ser honestos, porque es lo único que el broker **empeora**.

Con la tabla haciendo de cola, guardar el job y encolarlo eran la **misma transacción**: o pasaban las dos cosas o ninguna. Con broker son dos sistemas distintos y no hay transacción que los abarque:

- Commitea el `INSERT` y se cae la API antes del `publish` → la ejecución queda en `ENCOLADA` y **nadie la va a correr nunca**. El alumno espera para siempre — la falla exacta que este diseño vino a evitar.
- Publica y falla el commit → el worker recibe un mensaje que apunta a una fila que no existe.

**No se arregla con reintentos ni cambiando el orden.** No hay orden bueno: siempre queda una ventana entre las dos escrituras.

Se arregla con **outbox transaccional**:

1. La API escribe el job **y** el mensaje pendiente en la misma transacción de Postgres. Atómico otra vez.
2. Un *relay* lee la tabla `outbox` y publica al broker.
3. Con el `publisher confirm` en la mano, marca la fila como publicada.

Si el relay se cae entre 2 y 3, republica al arrancar: **el mensaje sale al menos una vez, nunca cero.** Eso deja la cola en *at-least-once*, que es justo lo que el resto del diseño ya asume (§7, Falla 2).

> **La regla:** con broker, ninguna escritura de negocio y su publicación pueden vivir en transacciones distintas. El outbox es lo que las vuelve a unir.

Y no es infraestructura de más: `05-ms-sandbox-patrones.md` §6 ya pedía outbox para publicar `EjecucionFinalizada`. Es **el mismo mecanismo y la misma tabla**, usados en las dos puntas del servicio.

---

## 4. El worker son tres pasos

Sacando todo lo demás, el worker completo es esto:

```java
void alRecibir(Job job) {
    var resultado = ejecutar(job);   // 1. ejecutar
    guardar(job, resultado);         // 2. guardar
    ackear(job);                     // 3. confirmar
}
```

**Eso es todo.** No hay bucle: el broker empuja, el worker atiende.

Y el orden de los tres pasos no es negociable — **el ack va último**. Confirmar antes de guardar significa que un worker que muere en el medio se lleva el trabajo hecho y el mensaje ya borrado.

Todo lo que sigue —ack manual, prefetch, DLQ, janitor, watchdog, el guard al escribir— **no es funcionalidad**. Son respuestas a una única pregunta: *¿y si el worker se muere justo acá?*

## 5. Paso 0 — recibir el job

Le puse "paso 0" porque el worker no hace nada para que ocurra. Con la tabla como cola esta era la parte con la sutileza real: había que resolver a mano que dos workers no agarraran el mismo job.

**Con broker el problema desaparece.** RabbitMQ entrega cada mensaje a **un solo** consumer: es *competing consumers* de fábrica. Se levantan tres workers apuntando a la misma cola y se reparten el trabajo sin conocerse ni coordinarse.

El worker ya no *busca* trabajo: lo *recibe*.

```java
@Component
@Profile("worker")
class ConsumidorDeJobs {

    @RabbitListener(queues = "sandbox.jobs", ackMode = "MANUAL")
    void recibir(JobMessage msg, Channel canal, @Header(DELIVERY_TAG) long tag)
            throws IOException {
        try {
            ejecutor.correr(msg);                // §6 — dura segundos
            canal.basicAck(tag, false);          // recién acá el broker lo da por hecho
        } catch (ErrorRecuperable e) {
            canal.basicNack(tag, false, true);   // vuelve a la cola
        } catch (ErrorPermanente e) {
            canal.basicNack(tag, false, false);  // a la DLQ: reintentar no cambia nada
        }
    }
}
```

No hay bucle, no hay `SELECT`, no hay polling cada 200 ms. Pero hay **tres perillas cuyo valor por defecto rompe el servicio**, y son el contenido real de esta sección.

### Perilla 1 — ack manual

La más importante de las tres.

El default de Spring AMQP es `AUTO`: el mensaje se da por entregado **cuando el broker lo manda**, no cuando el worker terminó. Con eso, un worker que muere a los 3 segundos de una ejecución de 8 se lleva el job a la tumba — el broker ya lo borró.

Con `MANUAL`, el mensaje queda *unacked* mientras el worker trabaja. Si la conexión se corta —porque el proceso murió, porque lo mataron, porque se fue la red— **el broker lo devuelve a la cola solo**, y otro worker lo toma.

> **El ack no dice "lo recibí". Dice "lo terminé".** Toda la tolerancia a fallas del worker cuelga de esa distinción.

Esto es exactamente lo que con la tabla como cola había que construir a mano: `lease_hasta`, un heartbeat cada 30 segundos, y un reaper barriendo leases vencidos. **Tres mecanismos reemplazados por un flag.** Es el argumento más fuerte a favor de haber traído el broker.

### Perilla 2 — prefetch

`basicQos(n)`: cuántos mensajes le manda el broker a un consumer sin haber recibido ack.

El default de RabbitMQ es **ilimitado**. Con ese default, un worker que arranca contra una cola de 200 jobs se los lleva **todos** a memoria, y los otros workers ven una cola vacía. Es la Falla 4 de §7, servida por el default.

**`prefetch = slots`.** Con 6 slots de contenedor, el broker nunca manda un séptimo job hasta que se confirme uno.

> **El prefetch es el semáforo.** Con la tabla como cola había que escribir un `Semaphore` y un bucle que solo reclamara con slot libre. Acá es una línea de configuración — y además el backpressure lo ve el broker: los jobs que un worker no toma quedan disponibles para otro, en vez de dormidos en su memoria.

### Perilla 3 — la DLQ

Un mensaje que hace fallar al worker de forma reproducible —un payload malformado, un lenguaje que no existe— vuelve a la cola, lo toma otro worker, falla igual, vuelve a la cola. **Un solo mensaje envenenado puede tener a todos los workers girando en falso**, y de paso tapona la cola para los jobs sanos.

`basicNack(requeue = false)` no lo tira: lo manda a la **dead letter queue**, declarada al crear la cola.

```java
QueueBuilder.durable("sandbox.jobs")
    .deadLetterExchange("sandbox.dlx")
    .deadLetterRoutingKey("sandbox.jobs.muertos")
    .build();
```

La regla para decidir el `requeue`:

| Falla | `requeue` | Por qué |
|---|---|---|
| Docker no responde | `true` | Es del entorno: en un minuto anda |
| El host se quedó sin disco | `true` | Ídem |
| El payload no parsea | `false` | Reintentar mil veces da mil veces lo mismo |
| Lenguaje no soportado | `false` | Ídem |
| Se agotaron los reintentos | `false` | Ya tuvo margen suficiente |

Y hay que **cerrar el círculo**: al mandar el mensaje a la DLQ, la fila de `ejecucion` se marca `ERROR_INTERNO`. Si no, el mensaje queda a salvo en la DLQ pero el alumno sigue viendo `ENCOLADA` para siempre — otra vez la falla que todo esto vino a evitar.

> **Una DLQ que nadie mira es un cementerio.** `sandbox_dlq_profundidad > 0` va con alerta: cada mensaje ahí adentro es una entrega que ningún alumno va a recibir.

---

## 6. Paso 1 — ejecutar

> ## ⚠ Esta sección gana trabajo con el V4 (8‑sep‑2026)
>
> **El reparto de §6 se confirma y se refuerza**: el ejecutor devuelve materia prima, el worker
> produce el veredicto. Bajo el modelo de dos capas eso se vuelve más cierto, no menos, porque el
> contenedor deja de saber qué corrió adentro.
>
> Lo que cambia es que **el mapeo de abajo está cableado a JUnit**, y ya no puede estarlo. La
> guarda que no se negocia —no hay `EXITO` sin `tests > 0` leído del XML de verdad— es la regla
> **D6**, y hoy la aplica en parte el entrypoint. Bajo el sándwich la tapa no sabe leer un XML de
> JUnit, porque no sabe que existe JUnit: **la verificación entera se muda acá**, indexada por el
> `reportFormat` que declara el perfil (`junit-xml`, `pmd-xml`, …).
>
> Eso es **D16**, está abierta, no depende del Grupo 5 y **bloquea el refactor del entrypoint**
> (P9). Es la decisión más urgente que tenemos. Ver
> [`11-impacto-v4-g5.md`](./11-impacto-v4-g5.md) §6.

El modelo mental que más ayuda:

> **El worker nunca ejecuta el código del alumno. Ni lo lee, ni lo compila, ni lo carga. Y desde [D1](./README.md) tampoco le da órdenes a Docker: se las pide al ejecutor.**

El worker es un **capataz**, no el que hace el trabajo. Y después de D1 es un capataz que ni siquiera tiene la llave del depósito: arma los materiales, se los pasa por una ventanilla al único que sí la tiene, y espera el resultado.

De ahí sale la seguridad, ahora por partida doble: el código hostil corre en un proceso que no comparte **nada** con el worker —ni memoria, ni JVM, ni red, ni filesystem— y además el worker **no puede describir** el contenedor donde ese código corre. El peor código imaginable no puede tocar al worker, y el peor worker imaginable no puede pedir un contenedor peligroso.

> **La frontera que cambió.** Este documento describía un worker que hablaba la API de Docker. Ya no: su única dependencia saliente es un `POST /ejecutar` sobre un socket Unix, y el contrato completo está en [`08-spec-ejecutor.md`](./08-spec-ejecutor.md). Lo que sigue distingue explícitamente **lo que el worker hace** de **lo que el worker recibe hecho**.

```
                 │  worker                       │  ejecutor
─────────────────┼───────────────────────────────┼──────────────────────
armar el bundle  │  tar en memoria               │
                 │          │                    │
                 │          ▼                    │
la única llamada │  POST /ejecutar ─────────────►  create ─► start
                 │  socket Unix                  │     │
                 │  X-Ejecucion-Id + tar         │     │  el tar entra por STDIN
                 │                               │     │
                 │  (el thread espera; su        │     ├─ compilar solución
                 │   timeout es mayor que el     │     ├─ compilar tests
                 │   del ejecutor, nunca menor)  │     └─ correr tests
                 │                               │     │
                 │                               │  wait ► inspect ► logs ► rm
                 │                               │     │
                 │  ◄────── 200 + JSON ─────────────┘
                 │  resultado, exitCode,         │
                 │  oomKilled, reporte           │
                 │          │                    │
                 │          ▼                    │
interpretar      │  ¿aprobó? → veredicto         │
                 │          │                    │
                 │          ▼                    │
persistir        │  UPDATE ... WHERE estado=...  │
                 │  + fila de outbox             │
                 │          │                    │
                 │          ▼                    │
confirmar        │  basicAck                     │
```

**Nada de esto toca el filesystem del host, y ahora tampoco el socket.** No hay directorio efímero, no hay `docker cp`, no hay volumen — el bundle entra por `stdin` y el reporte sale por `stdout` ([`03`](./03-ms-sandbox-ejecucion.md) §1.5, el motivo corto es que `docker cp` es incompatible con `--read-only`). Y del lado del worker no hay cliente de Docker, ni flags, ni labels.

### Qué hace el worker y qué recibe hecho

Es la tabla que conviene tener clara antes de escribir una línea, porque casi todo lo que este documento contaba como trabajo del worker **se mudó**:

| | Worker | Ejecutor |
|---|---|---|
| Validar la `ruta` de cada archivo del bundle | ✅ | ❌ (no lo desempaqueta, a propósito) |
| Armar el tar | ✅ | ❌ |
| Elegir imagen, memoria, CPU, red, timeout | ❌ **no puede** | ✅ constantes de compilación |
| Crear, arrancar, esperar, matar y borrar el contenedor | ❌ | ✅ |
| Demultiplexar la salida y separar el bloque de reporte | ❌ | ✅ |
| Parsear el XML de JUnit | ✅ | ❌ (R3.1) |
| **Decidir el veredicto** | ✅ | ❌ (R3.1) |
| Barrer contenedores huérfanos | ❌ ya no ([§13](#13-el-janitor--el-que-se-fue-y-el-que-queda)) | ✅ (spec §10) |
| Limitar cuántas ejecuciones simultáneas hay | ✅ su parte | ✅ la suya, y manda la suya (R9.1) |
| Reintentar | ✅ | ❌ (no reintenta nunca) |

> **La línea que resume el reparto:** el ejecutor devuelve **materia prima**, el worker produce el **veredicto**. Mezclar las dos cosas metería lógica de negocio en el componente privilegiado, que es lo que R3.1 prohíbe explícitamente.

### El trabajo que queda: interpretar la respuesta

Acá hay una trampa que sólo apareció al correr el ejecutor contra la imagen real, y que conviene entender antes de escribir el mapeo: **la respuesta del ejecutor tiene dos capas, y el veredicto sale de la segunda.**

| Capa | Quién la produce | Qué dice |
|---|---|---|
| `resultado`, `exitCode`, `oomKilled` | El **ejecutor** | Cómo terminó el contenedor **como proceso** |
| El contenido de `reporte` | La **imagen del runner** | Qué pasó **adentro**: fase, relojes, y los XML de JUnit |

El campo `reporte` **no es el XML de JUnit**: es un sobre JSON que la imagen emite entre los marcadores del nonce, y que trae los XML adentro como `tar.gz` en base64. El ejecutor no lo interpreta —no debe (R3.1, R3.5)— así que ese trabajo es del worker.

> **Por qué importa:** una ejecución que agota el reloj de CPU llega como `resultado: COMPLETADA` con reporte presente. Desde los campos del ejecutor es indistinguible de una entrega que corrió bien. **El que la clasifica es el `resultado` del sobre.**

**Paso 1 — mirar los campos del ejecutor:**

| Respuesta del ejecutor | Qué hace el worker |
|---|---|
| `RECHAZADA` (`503`) | **Ningún veredicto**: `nack` con requeue y backoff. Ver [§12](#12-cómo-le-habla-al-ejecutor) |
| `ERROR_DAEMON` (`502`) | `ERROR_INTERNO`. **No consume vida ni intento** |
| `TIMEOUT` | `ERROR_INTERNO`, no `TIMEOUT`: si saltó el backstop de 60 s del ejecutor, el que falló es un reloj nuestro de adentro |
| `COMPLETADA` con `oomKilled: true` | `LIMITE_MEMORIA`. Lo mató el cgroup. **Es el camino menos frecuente**: con la JVM bien configurada gana casi siempre el otro, el del paso 2 |
| `COMPLETADA` con `reporteAusente: true` | `ERROR_INTERNO`: el contenedor no emitió sobre. Sin sobre no hay veredicto posible |
| `COMPLETADA` con reporte | **Sigue al paso 2** |

**Paso 2 — leer el `resultado` del sobre:**

| `resultado` del sobre | Veredicto | ¿Consume vida? |
|---|---|---|
| `OK` + XML con `tests > 0`, sin fallas | `EXITO` | — |
| `OK` + XML con fallas | `FALLO_TESTS` | Sí |
| `SALIDA_ANTICIPADA` | `SALIDA_ANTICIPADA` | Sí |
| `TIMEOUT_CPU` / `TIMEOUT_PARED` / `TIMEOUT_COMPILACION` | `TIMEOUT` | Sí |
| `LIMITE_MEMORIA` | `LIMITE_MEMORIA` | Sí — es **el otro camino** de la memoria: la JVM murió antes que el cgroup, así que **`oomKilled` llega en `false`** (R3.4). Medido: el contenedor sale con **25**, no con el 3 de la JVM, porque el entrypoint traduce el código antes de salir. Otro motivo para no leer el exit code |
| `ERROR_COMPILACION` | `ERROR_COMPILACION` | Sí |
| `SUITE_INVALIDA` | `SUITE_INVALIDA` | **No** — la suite es del profesor |
| `VEREDICTO_NO_CONFIABLE` | `VEREDICTO_NO_CONFIABLE` | Abierto → [D7](./README.md) |
| `SIN_REPORTE` / `MUERTO_POR_SENAL` / `BUNDLE_INVALIDO` | `ERROR_INTERNO` | **No** |

> **Y la guarda que no se negocia, encima de todo lo anterior:** aunque el sobre diga `OK`, no hay `EXITO` sin **`tests > 0` leído del XML de verdad**, no del contador que el runner trae ya calculado. El contador es defensa en profundidad; la fuente es el XML.

**Las dos trampas de esta tabla**, las dos verificadas corriendo el pipeline real:

- **`tests > 0` alcanza para no aprobar de más, no para clasificar.** Un `TIMEOUT_CPU` y un `SALIDA_ANTICIPADA` llegan los dos con `tests = 0`. Un worker que sólo mirara esa guarda le diría al alumno "saliste antes de correr los tests" cuando en realidad se le agotó el procesador. No aprueba de más —que es lo que importa— pero **miente sobre la causa**, y el mensaje al alumno es parte del veredicto.
- **El exit code del contenedor no clasifica nada.** El sobre y el exit code pueden discrepar: encontramos un caso donde la imagen informaba `TIMEOUT_CPU` en el sobre y salía con `exit 0`. Se corrigió en el entrypoint, pero la regla de diseño se mantiene por si vuelve a pasar: **el sobre manda.**

### Las cuatro reglas que no se negocian, y quién las garantiza ahora

Las cuatro se verificaron contra Docker real ([`03`](./03-ms-sandbox-ejecucion.md) §1.4). Lo que cambió es **dónde viven**:

- **El veredicto sale del reporte, nunca del exit code.** Sigue siendo del worker, y es la fila `SALIDA_ANTICIPADA` de arriba. El ejecutor colabora sin decidir: devuelve `reporteAusente` como un booleano aparte, en vez de obligar al worker a distinguir "no hubo reporte" de "el reporte vino vacío".
- **La memoria tiene dos caminos.** Sigue siendo del worker. Lo que cambió es que ya no hay que llamar a `docker inspect`: `oomKilled` viene en la respuesta, y la spec **prohíbe** inferirlo del exit code (R3.4).
- **Compilar y correr tests tienen relojes distintos.** Se mudó **entero** al ejecutor y a la imagen: los relojes por fase viven en el entrypoint, y el techo de CPU de todas las fases juntas es el ulimit `cpu` de la spec del contenedor. El worker ya no dimensiona nada de esto.
- **El reloj de pared de afuera es la red, no el mecanismo.** Se mudó al ejecutor (`TIMEOUT_EJECUCION_MS`, 60 s). El worker conserva **un** reloj propio, pero es de otra naturaleza: no le corta el paso al alumno, protege al worker de un ejecutor que no responde → [§12](#12-cómo-le-habla-al-ejecutor).

Y la quinta, que no salió del prototipo sino de la investigación externa ([`hallazgos-investigacion-sandbox.md`](../../hallazgos-investigacion-sandbox.md) §7):

- **El reloj del alumno se mide en tiempo de CPU, no en reloj de pared.** Es lo que hace `isolate` —y por lo tanto Judge0 y Piston— y acá es el `Ulimits[cpu]` de la spec del contenedor. **El motivo no es elegancia: el tiempo de CPU no cuenta el tiempo en que el host le dio el procesador a otra ejecución del pool**, así que la varianza de 1.8–5.2 s que medimos deja de producir `TIMEOUT` intermitentes. Se elimina la causa, no se la acolcha con margen.
  > **Consecuencia para el worker, que sigue vigente y ahora es más simple:** su reloj no se calcula como "suma de los relojes de adentro + margen". El presupuesto de CPU **no tiene cota superior en tiempo de pared** —20 s de CPU pueden tardar 55 s de reloj con el host saturado—, así que el único número contra el que el worker se dimensiona es el **`TIMEOUT_EJECUCION_MS` del ejecutor**, que es de pared y está fijo. Un número, no una suma.

---

## 7. Qué se rompe si el worker muere

Acá aparecen los mecanismos, uno por uno, cada uno respondiendo a una falla concreta.

### Falla 1 — muere después de tomar el job

La que produce *"entregué y nunca me contestó"*, y la que el broker resuelve casi entera.

El worker recibió el mensaje, arrancó el contenedor, y el proceso se murió. **Con ack manual el mensaje nunca se ackeó**, así que del lado del broker sigue *unacked*. Cuando RabbitMQ detecta que la conexión se cortó, lo devuelve a la cola y otro worker lo toma.

**Nadie tiene que darse cuenta de que w1 murió: la conexión que se cae es la señal.**

Comparado con lo que hacía falta sin broker —`lease_hasta`, heartbeat cada 30 s, un reaper barriendo vencidos, y acertarle al timeout del lease— acá es `ackMode = MANUAL` y nada más.

Pero queda **un** caso que la caída de conexión no cubre, y hay que decirlo: el worker **vivo pero colgado** (GC largo, host saturado, deadlock). La conexión sigue abierta, el broker lo ve sano, y el mensaje se queda unacked indefinidamente. Para eso hay dos redes:

- **`consumer_timeout` del broker.** Si un mensaje pasa demasiado tiempo unacked, RabbitMQ **cierra el canal** y lo re-encola. Viene en 30 minutos por defecto, que con ejecuciones de 10 segundos es una eternidad: hay que **bajarlo**, dejándolo cómodamente por encima del timeout máximo de ejecución más el arranque del contenedor.
- **Watchdog propio.** La fila lleva `iniciada_en`; una ejecución en `EN_EJECUCION` mucho más vieja que el timeout máximo se marca `ERROR_INTERNO`. No re-encola —de eso se ocupa el broker— solo evita que el alumno quede esperando una respuesta que no va a llegar.

> **El broker cubre "el worker se murió". No cubre "el worker está vivo pero no avanza".** Esa parte sigue siendo trabajo propio, y es la única deuda real que deja el cambio.

### Falla 2 — la que crea la solución anterior

El worker no murió: se colgó tres minutos por un GC largo o porque el host estaba saturado. Saltó el `consumer_timeout`, el broker re-encoló, otro worker tomó el job, y ahora **los dos lo están ejecutando**. Cuando w1 revive, quiere escribir su resultado.

No se puede evitar el escenario: no hay forma de distinguir *"muerto"* de *"muy lento"*. **Ningún broker lo resuelve** — es exactamente la razón por la que RabbitMQ garantiza *at-least-once* y no *exactly-once*. Se resuelve **al escribir**:

```sql
UPDATE ejecucion
   SET estado = :resultado, resultado = :json, finalizada_en = now()
 WHERE id = :id AND estado = 'EN_EJECUCION';
```

Si afecta 0 filas, alguien ya escribió el resultado → **descarta el suyo y no toca nada**. Gana el primero que escribe; el segundo se entera de que llegó tarde. Es una condición en el `WHERE`, y evita que un worker zombi pise un resultado ya bueno.

> **El que escribe tiene que probar que el trabajo todavía hacía falta. No alcanza con haberlo hecho.**

Juega a favor que ejecutar sea una **función pura**: correrlo dos veces no rompe nada, solo desperdicia un slot. Eso convierte *"exactamente una vez"* (imposible en sistemas distribuidos, con broker o sin él) en *"al menos una vez, escrito una sola"* (fácil), que acá alcanza.

**Y el que llegó tarde igual tiene que ackear.** Si descarta el resultado pero no confirma el mensaje, el mensaje vuelve a la cola y arranca una tercera ejecución del mismo job. Descartar el resultado y ackear son la misma decisión, no dos.

### Falla 3 — muere con el contenedor andando

Este es de otra naturaleza, y por eso hace falta un segundo mecanismo. **Es también el que más cambió con D1**, y para mejor.

El re-encolado del broker arregla **el mensaje**. Pero afuera del broker quedó un contenedor corriendo, quemando un core con el `while(true)` de un alumno. **Ningún re-encolado lo va a ver.** Docker y RabbitMQ son dos mundos que no se hablan: el broker sabe de mensajes sin confirmar, no de procesos vivos en el host.

Lo que cambió es **quién se hace cargo**. El contenedor ya no es del worker: es del ejecutor, que sigue vivo aunque el worker muera, y que además tiene su propio barrido de huérfanos ([`08`](./08-spec-ejecutor.md) §10). Los dos casos posibles:

| Qué murió | Qué pasa con el contenedor |
|---|---|
| **El worker** | El ejecutor no se entera y **termina la ejecución igual**. Escribe la respuesta en un socket que ya nadie lee, borra el contenedor y libera el slot. No queda huérfano: queda trabajo tirado, que es mucho más barato |
| **El ejecutor** | Ahí sí quedan contenedores vivos, y los limpia **él mismo al reiniciar**, que es la razón de que su barrido corra al arrancar |

> **Dos mundos, dos limpiadores — pero el segundo dejó de ser trabajo del worker.** El broker limpia mensajes; los contenedores los limpia el que los creó, que es el único con privilegio para hacerlo.

### Falla 4 — no muere, se ahoga

No hay crash: hay 200 jobs en la cola y el worker se los lleva todos.

Se reprodujo el problema original **adentro del worker**. La cola existía justamente para que fuera a su ritmo, y él la vació de golpe.

Este es el que **el default de RabbitMQ produce solo**: sin `basicQos`, el prefetch es ilimitado. La mitigación es la perilla 2 de [§5](#5-paso-0--recibir-el-job) —`prefetch = slots`— y el principio no cambió:

> **Solo tener en la mano los jobs que se pueden ejecutar ahora.**

Un job que sigue en la cola está a salvo y es de todos: si este worker se cae, otro lo toma en segundos. Un job prefetcheado y dormido en la memoria del worker **se libera recién cuando se corta la conexión**, y hasta entonces ningún otro worker puede tocarlo. Por eso no se acapara.

### Falla 5 — no es el worker, es el mensaje

Un payload malformado hace fallar el parseo, el mensaje vuelve a la cola, lo toma otro worker, falla igual, vuelve a la cola.

**Un solo mensaje envenenado ocupa a todos los workers sin producir nada.** Es una falla que con la tabla como cola no existía —la fila llevaba `intentos`, y a los 3 pasaba a `ERROR_INTERNO`— y que el broker introduce: `basicNack(requeue = true)` no lleva la cuenta de nada por sí solo.

Se cubre con la perilla 3 de [§5](#5-paso-0--recibir-el-job): contar reintentos, mandarlo a la **DLQ** con `requeue = false`, y marcar la fila `ERROR_INTERNO` en el mismo movimiento.

### Resumen: los 9 puntos de falla y sus 2 mitigaciones

Los nueve pasos de antes eran nueve porque seis de ellos eran llamadas a Docker. **Ahora son cinco**, y los seis que faltan no desaparecieron: se los llevó el ejecutor, junto con la responsabilidad de limpiarlos.

| # | Paso | Si el worker muere acá | Mitigación |
|---|---|---|---|
| 1 | Recibe el mensaje | Job en `EN_EJECUCION`, nadie trabajando | **Sin ack → el broker re-encola** |
| 2 | Arma el bundle en memoria | Nada: no tocó nada afuera | — |
| 3 | `POST /ejecutar` en vuelo | El ejecutor termina igual y tira la respuesta. Un slot ocupado hasta 60 s | **Ninguna hace falta**: se libera solo |
| 4 | Lee e interpreta la respuesta | Trabajo hecho, resultado perdido | Se re-ejecuta: es función pura |
| 5 | Persiste el resultado | Resultado perdido | Re-ejecución |

**Un solo mecanismo cubre los cinco casos**, y es gratis: el ack manual lo pone el broker. El janitor —que era el segundo mecanismo y el único que había que escribir— **se fue con el privilegio**.

> Es el mejor resumen de lo que compró D1 del lado del worker: no se agregó una capa, se sacaron cuatro puntos de falla y el componente que había que escribir para cubrirlos. El worker quedó siendo lo que la [§4](#4-el-worker-son-tres-pasos) decía que era: ejecutar, guardar, confirmar.

---

## 8. Anatomía del proceso

Adentro del `java -jar` del worker viven tres cosas:

| Thread | Cuántos | Qué hace |
|---|---|---|
| **Consumers AMQP** | = slots (6) | Cada uno recibe un mensaje, hace su `POST /ejecutar` y espera la respuesta |
| **Watchdog** | 1 | Marca `ERROR_INTERNO` las ejecuciones colgadas mucho más allá del timeout, y purga el `outbox` publicado |
| **Relay del outbox** | 1 | Publica al broker lo que este proceso escribió → [D2](./README.md) |

Seis ejecuciones en vuelo ⇔ seis consumers ocupados, cada uno bloqueado en una respuesta HTTP. Se lee de un vistazo en un thread dump.

**Dos cambios respecto de versiones anteriores de este documento:**

- **El janitor ya no está.** Se fue con el socket de Docker ([§13](#13-el-janitor--el-que-se-fue-y-el-que-queda)). El worker perdió el thread y el componente.
- **El relay del outbox sí está**, y antes decía que no. Corre en **los dos perfiles**, `api` y `worker`, cada uno publicando lo suyo ([D2](./README.md)): el worker también escribe en el outbox —el `EjecucionFinalizada`— y si el relay solo viviera del lado de la API, ese evento esperaría al poll de **otro proceso** para publicarse. En una cascada de seis servicios esa latencia se paga entera.

Y contra la versión sobre la tabla siguen desapareciendo dos threads: el bucle de reclamo (ahora el broker empuja) y el heartbeat (ahora lo hace el ack manual). El reaper de leases desaparece del todo.

---

## 9. El modelo mental en una frase

> **El worker recibe un mensaje que no confirma hasta terminar, le pide a otro proceso que ejecute, y escribe el resultado solo si nadie lo escribió antes. Todo lo demás existe porque puede morirse en cualquier punto de esos tres pasos.**

Tres cosas para retener:

1. **La cola separa "llegó" de "se hizo".** Convierte un pico en espera, no en caída.
2. **Recibir no es quedarse el job.** El mensaje sigue siendo del broker hasta el ack, y de ahí salen el ack manual, el prefetch y el guard al escribir.
3. **El worker pide, nunca ejecuta — y desde [D1](./README.md) tampoco supervisa.** El código del alumno jamás entra a su proceso, y el contenedor donde corre no lo describe él.

---
---

# Parte II — Construirlo

## 10. Topología: ¿mismo proceso que la API?

Decisión con consecuencia de **seguridad**, no solo de despliegue.

| | Todo junto | API y worker separados |
|---|---|---|
| Quién toca el socket de Docker | **Todas** las réplicas de la API | Solo el worker — y desde [D1](./README.md), **ninguno de los dos**: el ejecutor |
| Escalado | Acoplado: más API = más lanzadores | Independiente |
| Complejidad | Un deployable | Dos |

El argumento decisivo: el socket de Docker equivale a root en el host. Si la API —lo único expuesto al Gateway— lo tiene montado, cualquier vulnerabilidad en un endpoint HTTP escala a root. Separados, **la API no tiene acceso a Docker en absoluto**.

**Después de D1 el argumento tiene un escalón más, y es el que conviene dibujar en la defensa:**

| Componente | ¿Expuesto? | ¿Tiene el socket? |
|---|---|---|
| **API** | Sí, al Gateway | No |
| **Worker** | No: consume de la cola | **No** — esto es lo que cambió |
| **Ejecutor** | No: solo por un socket Unix que monta el worker | Sí, y es el único |

Cada salto hacia el privilegio pierde una vía de acceso. Para llegar al socket desde afuera hay que atravesar tres procesos, y el último **no acepta una spec de contenedor** ([`05`](./05-ms-sandbox-patrones.md) §1).

**Punto dulce para el TP:** un repo, una imagen, dos perfiles de Spring, dos despliegues.

```sh
java -jar sandbox.jar --spring.profiles.active=api      # sin socket, expuesto al Gateway
java -jar sandbox.jar --spring.profiles.active=worker   # con socket, sin puerto HTTP público
```

Un build, un pipeline, dos contenedores. **No se llaman entre sí**: la API publica al broker y el worker consume. Ni siquiera necesitan resolverse por nombre — ver `05-ms-sandbox-patrones.md` §8, el worker no se registra en el Service Discovery.

Cada perfil se lleva además una responsabilidad distinta de la infra:

- **El relay del outbox corre en los dos** ([D2](./README.md)), cada uno publicando lo que ese proceso escribió. Es el mismo mecanismo y la misma garantía; lo que se elimina es el salto de proceso entre escribir el evento y publicarlo.
- **El watchdog**, en el perfil `worker`.
- **El janitor ya no existe del lado del worker** ([§13](#13-el-janitor--el-que-se-fue-y-el-que-queda)): el barrido de contenedores es del ejecutor.

**Y hay un tercer deployable**, que este documento no tenía: el **ejecutor**. No es un perfil de Spring ni sale del mismo `jar` — es un proceso aparte, con su propio ciclo de vida, posiblemente en otro lenguaje ([D9](./README.md)), y es el único que monta `/var/run/docker.sock`. El worker lo alcanza por un volumen compartido, no por la red.

---

## 11. Concurrencia: el límite no son los threads

Hay una trampa acá, porque la materia enseña Java 21 y el reflejo es *virtual threads*.

**No sirven de nada en este caso.** Los virtual threads resuelven *muchas* tareas bloqueadas en I/O. El cuello de botella acá no es ese: es **CPU y RAM del host**. Diez mil virtual threads lanzando `javac` simultáneo no hacen nada más que matar la máquina.

El primitivo correcto es **limitar cuántos contenedores hay vivos a la vez**, y con broker eso es configuración del consumer, no código propio:

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: manual
        concurrency: 6          # consumers = slots de contenedor
        max-concurrency: 6      # fijo: que no escale solo
        prefetch: 1             # cada consumer, un job por vez
        default-requeue-rejected: false   # el requeue lo decide el código, no el default
```

`concurrency × prefetch` es el techo de jobs en vuelo en este proceso: 6 × 1 = 6. Threads de plataforma, fijos, tantos como slots. Cada thread mapea a una ejecución en vuelo, y esa legibilidad vale más que cualquier optimización acá.

> **Pero el techo real no es éste.** Desde D1 el límite que manda vive en el ejecutor —`MAX_CONCURRENTES = 8`, `MAX_COLA = 16`— y es deliberado: es el único que ve el total cuando hay varias réplicas de worker, y es el único con privilegio. El del worker sigue valiendo como higiene local; el que protege al host es el de abajo. El detalle, y qué hacer cuando el de abajo dice que no, en [§12](#12-cómo-le-habla-al-ejecutor).

Dos elecciones que parecen detalle y no lo son:

> **`prefetch: 1` en seis consumers, no `prefetch: 6` en uno.** Los dos dan seis jobs en vuelo, pero el primero los reparte entre seis threads que ejecutan en paralelo, y el segundo apila cinco esperando detrás de uno. Con jobs de segundos, la diferencia es toda la latencia.

> **`max-concurrency` igual a `concurrency`.** El escalado automático de consumers de Spring reacciona a la profundidad de la cola, que acá es la señal equivocada: el límite no es el trabajo pendiente sino la CPU y la RAM del host, que no crecen porque crezca la cola. Se escala agregando **réplicas del worker en otros hosts**, no consumers en el mismo proceso. Es la diferencia entre escalar y sobrevender.

### Dimensionamiento

```
Por ejecución:           512 MB RAM  +  2 CPU   durante ~4–8 s
Pool de 6 simultáneas:   ~3 GB RAM   +  hasta 12 cores en pico
```

> **Corrección:** este bloque decía `1 CPU` y `~6 cores`, desalineado con [`03`](./03-ms-sandbox-ejecucion.md) §4.3. Con 1 CPU el camino feliz daba `TIMEOUT`: pasar a 2 bajó el muro de 8.5 s a 5.1 s porque `javac` paraleliza. El techo de cores es el número que hay que mirar al dimensionar el host, y con 2 CPU por ejecución **duplica**.

RF-NFR-03 pide 120 sesiones concurrentes, pero **120 sesiones no son 120 ejecuciones simultáneas**: la gente lee, escribe y piensa. Un pool de 4–6 con cola absorbe el pico.

Lo importante no es acertar el número, es el **comportamiento cuando se llena**: cola acotada, `429` con `Retry-After`, y **tope de jobs en vuelo por alumno** — si uno solo puede encolar 20 ejecuciones, se come el pool él solo. El número se ajusta con la prueba de carga; el mecanismo tiene que estar bien desde el diseño.

---

## 12. Cómo le habla al ejecutor

Es la única dependencia saliente del worker, así que vale escribirla con detalle. **Todo el contenido anterior de esta sección —`docker-java` contra la CLI, el `docker kill` que hay que acordarse de hacer, el truncado de `stdout`— dejó de aplicar con D1.** Se conserva al final lo que sobrevive como lección.

### El cliente: HTTP sobre un socket Unix

```java
// Java 21: HttpClient NO habla sockets Unix (JDK-8377806).
// Del lado del worker alcanza con SocketChannel + un request HTTP a mano,
// o con un cliente que acepte un SocketFactory propio.
var canal = SocketChannel.open(UnixDomainSocketAddress.of("/run/ejecutor/ejecutor.sock"));
```

Tres cosas del request que no son opcionales ([`08`](./08-spec-ejecutor.md) §3.1):

- **`Content-Length` obligatorio.** El ejecutor responde `411` sin él y **no acepta `chunked`**: necesita conocer el tamaño antes de aceptar bytes, para poder rechazar con `413` sin leer el cuerpo.
- **`X-Ejecucion-Id` es un UUID v4**, y es el mismo id de la fila de `ejecucion`. No afecta la spec del contenedor; sirve como etiqueta y como correlación en los logs de los dos lados. Cuando haya que reconstruir qué pasó con una entrega, es la única cuerda que une los dos procesos.
- **El cuerpo es el tar, y nada más.** No hay JSON de parámetros porque no hay parámetros: ése es el punto de D1.

> **Tope de 2 MiB (`MAX_BUNDLE_BYTES`).** Un bundle más grande vuelve `413`, y eso **no es reintentable**: es `ERROR_INTERNO` o, mejor, un `400` que la API debería haber dado antes. Conviene validar el tamaño aguas arriba y no descubrirlo acá.

### El timeout del worker: el único reloj que le queda

La regla es de una línea y es fácil de errar en la dirección peligrosa:

> **El timeout del worker tiene que ser mayor que el del ejecutor. Nunca menor, nunca igual.**

Si fuera menor, el worker abandonaría la espera mientras el contenedor sigue vivo y el ejecutor sigue trabajando: se perdería el resultado de un trabajo que igual se va a hacer, y el slot del ejecutor quedaría ocupado por algo que ya nadie está esperando. Es exactamente el escenario de doble ejecución de la [Falla 2](#falla-2--la-que-crea-la-solución-anterior), producido por una constante mal elegida.

```
TIMEOUT_EJECUCION_MS (ejecutor)   60 s   ← le corta el paso al contenedor
timeout del cliente del worker    75 s   ← protege al worker de un ejecutor mudo
consumer_timeout del broker      >90 s   ← §7, tiene que estar por encima de los dos
```

El reloj del worker **no existe para limitar al alumno** —de eso se ocupan el ulimit de CPU y el backstop de pared, los dos adentro del contenedor— sino para cubrir un caso que ningún reloj de adentro cubre: **el ejecutor colgado o muerto**. Si dispara, el veredicto es `ERROR_INTERNO`, nunca `TIMEOUT`: el problema fue nuestro.

### Los cuatro finales posibles, y qué hace el worker con cada uno

| Respuesta | Qué pasó | Qué hace el worker |
|---|---|---|
| `200` | Terminó, incluyendo `TIMEOUT` — que es un **resultado**, no un error | Interpreta ([§6](#6-paso-1--ejecutar)), escribe, ackea |
| `503` + `Retry-After` | Ejecutor saturado (`MAX_COLA` lleno) | **`nack` con requeue y backoff.** No escribe nada. No cuenta como intento |
| `502` | `ERROR_DAEMON`: Docker no responde | `ERROR_INTERNO`, no consume vida. Cuenta para el circuit breaker |
| Sin respuesta / socket caído / timeout del cliente | El ejecutor está colgado o murió | `ERROR_INTERNO` y, si se puede, reintentar una vez: **ejecutar es una función pura** |
| `400` / `411` / `413` | El worker mandó algo mal | **Bug nuestro.** A la DLQ directo: reintentar no cambia nada |

Las dos filas del medio son las que separan un worker que aguanta un pico de uno que lo convierte en incidente:

> **`503` es "esperá", `502` es "está roto".** Confundirlos tiene dos costos opuestos y los dos caros: tratar el `503` como falla manda entregas sanas a la DLQ en la hora pico; tratar el `502` como espera deja al worker reintentando contra un Docker caído hasta llenar la cola.

### El backpressure ahora tiene dos niveles, y eso es bueno

Antes el único límite era el pool de consumers del worker. Ahora hay dos, y **el de abajo es el que manda** (R9.1: el control de concurrencia vive en el ejecutor, que es quien sabe cuántos contenedores hay vivos, y no se delega al worker):

```
worker    concurrency = 6 consumers × prefetch 1   →  6 jobs en vuelo
ejecutor  MAX_CONCURRENTES = 8, MAX_COLA = 16      →  8 corriendo, 16 esperando
```

Con un solo worker los seis entran holgados y el `503` no aparece nunca. **Aparece cuando se escala horizontalmente**: tres réplicas de worker son 18 jobs en vuelo contra un ejecutor que admite 8 + 16. Por eso el manejo del `503` no es una defensa teórica — es la condición para poder agregar réplicas.

> **Y es la razón de que el límite viva abajo.** Si cada worker se autolimitara y confiáramos en la suma, agregar una réplica sobrevendería el host sin que nada lo note. El ejecutor es el único que ve el total, y es además el único con privilegio: R9.3 lo dice sin vueltas, el límite de concurrencia **es parte del control de seguridad**, no una optimización.

### Lo que sobrevive de la versión anterior

Dos lecciones de cuando el worker hablaba Docker, que **siguen siendo ciertas — solo que ahora son problema del ejecutor**, y conviene saberlas para revisar su código:

- **Matar el proceso local no mata el contenedor.** Abandonar la espera nunca alcanza: hay que `kill` explícito sobre el contenedor. En la spec es R8.2, y por eso `TIMEOUT` implica `kill` + `wait` + `logs` + `delete`, en ese orden.
- **La salida se trunca adentro, no afuera.** Si alguien lee `stdout` por stream sin tope, un `println` en loop llena la memoria del que lee. En la spec son `MAX_SALIDA_BYTES` y el `AttachStdout: false` de §4.1 — el ejecutor no se adjunta a la salida, la drena el log driver.
  > Y la trampa asociada, que vale repetir porque es contraintuitiva: **no truncar con `| head -c`**. Cuando `head` deja de leer, el escritor recibe `SIGPIPE` y **el programa del alumno muere por eso**: una solución correcta que imprime mucho pasaría a fallar. El límite tiene que ser el tamaño del tmpfs y el tope del lector, no una tubería que se corta.

---

## 13. El janitor — el que se fue y el que queda

Esta sección describía un barrido de contenedores adentro del worker:

```sh
docker ps -aq --filter label=sandbox.job --filter "until=10m" | xargs -r docker rm -f
```

**Eso ya no es implementable, y la spec lo dice explícitamente** ([`08`](./08-spec-ejecutor.md) R10.4): un worker sin socket de Docker no puede listar ni borrar contenedores, y las etiquetas ni siquiera coinciden — el ejecutor etiqueta `sandbox: "1"` y `sandbox.ejecucion`, no `sandbox.job` / `sandbox.worker`.

> **La limpieza vive donde vive el privilegio.** El barrido de huérfanos es del ejecutor: al arrancar y cada 5 minutos lista por label y borra lo que pase los 10 minutos de antigüedad. Es el **único** barrido de contenedores del sistema.

Y el argumento de por qué corre al arrancar no cambió, solo cambió de dueño: si el proceso crasheó, sus contenedores siguen ahí quemando CPU y **nadie más los va a limpiar** — el que ensució es el único que sabe que existieron.

### Lo que sí queda del lado del worker

Dos barridos, ninguno de contenedores:

| Qué barre | Por qué |
|---|---|
| **Filas de `outbox` ya publicadas**, por antigüedad | No molestan al relay —su índice es parcial y solo ve lo pendiente— pero una tabla que solo crece termina siendo un problema de backup y de `VACUUM`, no de consultas. Es la clase de deuda que en un TP no se nota y en producción se nota tarde |
| **Ejecuciones colgadas en `EN_EJECUCION`** mucho más allá del timeout máximo | Es el watchdog de [§7](#7-qué-se-rompe-si-el-worker-muere). No re-encola —de eso se ocupa el broker— solo evita que el alumno quede esperando una respuesta que no va a llegar |

El segundo **gana importancia** con la frontera nueva, y conviene decir por qué: antes, un contenedor huérfano y una fila colgada eran síntomas del mismo crash, y el janitor los veía a los dos. Ahora el worker perdió la visibilidad sobre los contenedores, así que **la fila colgada es la única señal que le queda** de que algo se murió a mitad de camino.

> La métrica de huérfanos limpiados ([§16](#16-qué-mide-el-worker)) también se mudó: la exporta el ejecutor. Sigue valiendo lo mismo — si ese número sube, algo se está muriendo y todavía no lo saben.

---

## 14. Apagado ordenado

Relevante para la unidad de CI/CD: en un deploy rolling, el worker recibe SIGTERM con jobs corriendo.

1. Dejar de reclamar jobs nuevos (inmediato).
2. Esperar a los en vuelo, con tope = timeout máximo + margen.
3. Salir.

Con ack manual, el paso 1 es literalmente `stop()` del listener container: deja de recibir mensajes nuevos y los que ya tomó siguen su curso.

Si igual lo matan a la fuerza, los mensajes sin ackear vuelven a la cola cuando se corta la conexión.

**Son dos apagados, no uno, y el orden importa.** El ejecutor tiene el suyo ([`08`](./08-spec-ejecutor.md) R11.7): ante `SIGTERM` deja de aceptar requests, espera a las ejecuciones en vuelo hasta 60 s, borra sus contenedores y sale.

| Se apaga primero | Qué pasa |
|---|---|
| **El worker** | Correcto. El ejecutor termina lo que tiene, no le llega nada nuevo, y sale limpio |
| **El ejecutor** | Los `POST /ejecutar` en vuelo se cortan y los nuevos no conectan. El worker los ve como `ERROR_INTERNO` y **el alumno paga la latencia de un deploy nuestro** |

> En un deploy rolling, **el worker se drena antes que el ejecutor**. Es una línea en el orquestador y evita la única forma en que un deploy puede producir `ERROR_INTERNO` con todo funcionando bien.

> **El apagado ordenado es una optimización, no una garantía. La garantía sigue siendo que el mensaje no se ackea hasta terminar.**

---

## 15. Esquema: `ejecucion` y `outbox`

Con la cola en el broker, `ejecucion` pierde las columnas de reparto de trabajo (`worker_id`, `lease_hasta`) y queda como lo que siempre debió ser: **el registro de estado y resultado que T05 consulta.**

```sql
CREATE TABLE ejecucion (
  id              UUID PRIMARY KEY,
  estado          VARCHAR(24) NOT NULL,      -- ENCOLADA | EN_EJECUCION | EXITO | ...
  idempotency_key VARCHAR(160) NOT NULL,     -- entregaId:intentoNro
  correlation_id  UUID NOT NULL,
  curso_id        UUID NOT NULL,             -- correlación opaca: cancelación por
                                             -- curso archivado. Indexado, no validado

  -- payload de entrada (bundle autocontenido: código + tests)
  solicitud       JSONB NOT NULL,
  solicitud_hash  CHAR(64) NOT NULL,         -- reproducibilidad / auditoría

  intentos        INT NOT NULL DEFAULT 0,    -- redeliveries; al tope, el mensaje va a la DLQ

  -- resultado
  resultado       JSONB,
  recursos        JSONB,                     -- tiempoMs, memoriaPicoKb

  creada_en       TIMESTAMPTZ NOT NULL,
  iniciada_en     TIMESTAMPTZ,
  finalizada_en   TIMESTAMPTZ,

  CONSTRAINT uq_ejecucion_idem UNIQUE (idempotency_key)
);

-- el índice que sostiene el watchdog (§7, Falla 1)
CREATE INDEX ix_ejecucion_colgadas
    ON ejecucion (iniciada_en)
 WHERE estado = 'EN_EJECUCION';

-- cancelación en bloque cuando se archiva un curso
CREATE INDEX ix_ejecucion_curso_pendiente
    ON ejecucion (curso_id)
 WHERE estado = 'ENCOLADA';
```

Es un índice **parcial**: solo indexa las filas que al watchdog le interesan. Con miles de ejecuciones históricas, sigue conteniendo únicamente las que están corriendo ahora.

`uq_ejecucion_idem` es lo que hace que un reintento de red de T05 no dispare una segunda ejecución. **Con broker gana importancia**: es la última línea contra un duplicado que se cuele por el camino de publicación.

La tabla nueva es el **outbox** ([§3](#3-lo-que-los-separa-la-cola)), y la usan las dos puntas del servicio — la API para publicar el job, el worker para publicar `EjecucionFinalizada`:

```sql
CREATE TABLE outbox (
  id             BIGSERIAL PRIMARY KEY,   -- el orden de publicación
  tipo           VARCHAR(64)  NOT NULL,   -- EjecucionSolicitada | EjecucionFinalizada
  routing_key    VARCHAR(128) NOT NULL,
  payload        JSONB        NOT NULL,
  correlation_id UUID         NOT NULL,
  creado_en      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  publicado_en   TIMESTAMPTZ              -- NULL = pendiente
);

CREATE INDEX ix_outbox_pendientes
    ON outbox (id)
 WHERE publicado_en IS NULL;
```

Dos detalles que no son cosméticos:

- **`BIGSERIAL`, no UUID.** El relay publica en orden de `id`, y ese orden tiene que ser el de inserción. Un UUID aleatorio no ordena nada.
- **`publicado_en` nullable + índice parcial.** La tabla acumula todo lo que se publicó alguna vez, pero el índice del relay solo contiene lo pendiente — que en régimen normal son cero o dos filas. Las publicadas se purgan con retención, igual que los artefactos ([§13](#13-el-janitor--el-que-se-fue-y-el-que-queda)).

## 16. Qué mide el worker

| Métrica | Tipo | Para qué |
|---|---|---|
| `rabbitmq_queue_messages_ready{queue="sandbox.jobs"}` | gauge | Profundidad de cola. **La da el broker**, no hay que instrumentarla: la exporta el plugin de Prometheus de RabbitMQ |
| `sandbox_dlq_profundidad` | gauge | **La que se alerta con umbral 0.** Cada mensaje ahí es un alumno que no va a recibir respuesta |
| `sandbox_outbox_pendientes` | gauge | Lag del relay. Si crece, se están aceptando entregas que **no se están encolando**: el broker rechaza o el relay murió |
| `sandbox_slots_ocupados` | gauge | Utilización real vs. consumers configurados |
| `sandbox_espera_en_cola_seconds` | histogram | **La latencia que percibe el alumno** |
| `sandbox_fase_duracion_seconds{fase}` | histogram | `create`/`compile_solucion`/`compile_test`/`test` — **reloj de pared** |
| `sandbox_cpu_pruebas_seconds` | histogram | **La CPU que consumió el código del alumno.** Es contra ésta que se evalúa el límite, y a diferencia de la anterior **es estable entre corridas**. Comparar las dos es lo que muestra la contención del host |
| `sandbox_ejecuciones_total{estado}` | counter | Distribución de veredictos |
| `sandbox_error_interno_total` | counter | Debería ser ~0 |
| `sandbox_redeliveries_total` | counter | Workers muriendo o colgándose |
| `sandbox_ejecutor_rechazos_total` | counter | `503 RECHAZADA`. **Cero es sospechoso y mucho también**: cero con varias réplicas significa que el pool está sobredimensionado; creciendo, que hay que agregar capacidad al ejecutor, no workers |
| `sandbox_ejecutor_latencia_seconds` | histogram | El `POST /ejecutar` de punta a punta, medido por el worker. Contra `sandbox_fase_duracion_seconds` da el costo de la frontera |
| `sandbox_huerfanos_limpiados_total` | counter | Fugas de contenedores. **La exporta el ejecutor**, no el worker: se mudó con el barrido ([§13](#13-el-janitor--el-que-se-fue-y-el-que-queda)) |

Las dos primeras gauges de instrumentación propia —DLQ y outbox— son **las que el broker hace necesarias**, y las dos vigilan el mismo silencio: una entrega aceptada que nunca se va a ejecutar. Sin ellas, el modo de falla del broker es invisible hasta que un alumno reclama.

La de fases vale oro, y ya dio su primer resultado. Este documento **suponía** que el grueso del tiempo era el arranque de la JVM, y que la optimización correcta era AppCDS. Se midió y **es falso**: `java -version` dentro del contenedor tarda 38–82 ms. El tiempo está en `javac` (1.0–2.3 s por invocación, y son dos) y en el escaneo de classpath de JUnit (1.2–2.9 s). El código del alumno, en el camino feliz, corre en microsegundos: **prácticamente todo el reloj es toolchain.**

Los números y las palancas que sí sirven están en [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) §1.4 y §4.2. Lo que importa acá es el método, no el número: la suposición era razonable, la métrica de fases la desmintió en una tarde, y eso es exactamente lo que la unidad de Observability pide mostrar. **Vale más la corrección que el acierto** — dejar escrito qué se creía y qué salió es lo que hace que el hallazgo sea defendible.

Un segundo hallazgo de la misma métrica, que cambia cómo se fija un umbral: la varianza entre corridas idénticas es grande (la fase de tests fue de 1.8 s a 5.2 s con la misma entrega y los mismos límites). Cualquier alerta o timeout **de reloj de pared** sobre estas fases se calibra con **percentiles altos**, nunca con el promedio.

**Y un tercero, que llegó después y corrige al segundo.** La investigación externa mostró que esa varianza no hay que calibrarla: hay que **eliminarla**. Es contención del host, y el tiempo de CPU es inmune a ella por construcción ([`03`](./03-ms-sandbox-ejecucion.md) §1.4d). De ahí sale `sandbox_cpu_pruebas_seconds` en la tabla de arriba, y de ahí sale el mejor uso de las dos métricas juntas:

> **`sandbox_fase_duracion_seconds{fase="test"}` dividido `sandbox_cpu_pruebas_seconds` es un indicador directo de saturación del host.** Si la relación es ~1, cada ejecución tiene el procesador para ella. Si se despega, hay contención — y lo bueno es que ahora eso **degrada la latencia sin cambiar ningún veredicto**, que es exactamente el comportamiento que se quiere. Antes ese mismo fenómeno producía `TIMEOUT` sobre código correcto.

Esto refuerza la moraleja de la sección, y conviene decirlo así en la defensa: la métrica de fases desmintió una suposición (AppCDS), y después **la misma métrica, leída junto a una fuente externa, desmintió la corrección que habíamos hecho**. Calibrar con percentiles altos era tratar el síntoma.

## 17. Cómo se prueba

Lo que hay que verificar del worker no es el camino feliz:

| Prueba | Qué verifica |
|---|---|
| Dos workers, 100 jobs | Cada job se ejecuta una sola vez (valida prefetch + competing consumers) |
| Matar un worker a mitad de un job | El mensaje sin ackear vuelve a la cola y el resultado sale, escrito una sola vez |
| Worker zombi escribe tarde | El `WHERE estado = 'EN_EJECUCION'` descarta el resultado viejo — y el zombi **igual ackea** |
| **Broker caído al aceptar la entrega** | El outbox retiene, el relay republica al volver: cero jobs perdidos. **La prueba que justifica el outbox** |
| Mensaje envenenado | Tras N intentos termina en la DLQ, y la fila queda `ERROR_INTERNO` — no `ENCOLADA` para siempre |
| Cola llena | `429` con `Retry-After`, y **readiness sigue UP** |
| Suite de entregas maliciosas | Loop infinito → `TIMEOUT`; fork bomb → contenida por `--pids-limit`; OOM → `LIMITE_MEMORIA` **por los dos caminos** (`OOMKilled` y `exitCode 3`); intento de red → `SocketException`; `System.exit(0)` → `SALIDA_ANTICIPADA`, **no** aprueba |
| Bundle con `ruta` hostil | `../../x.java` y `/opt/x.java` → `400 RUTA_INVALIDA` **en la API**, sin llegar a Docker |
| **Tar con entrada symlink** | Un symlink `Solucion.java → /etc/passwd` más una entrada regular que escribe sobre él. **No contiene `../`**: la validación de `ruta` no lo ve. Es el vector de CVE-2024-28185 (Judge0) |
| **Tar con hard link** | Entrada apuntando fuera del directorio de extracción. Misma familia, distinto mecanismo |
| **Solución en el paquete de los tests** | El alumno declara `package` reservado y sombrea una clase de soporte del profesor → `400 PAQUETE_RESERVADO`. **Sin esta guarda los tests pasan y el veredicto es falso**, con el contenedor perfectamente aislado |
| **Alumno escribe el reporte** | El código del alumno intenta escribir `/tmp/reports/*.xml`. Debe fallar: si puede, escribe su propio veredicto (el bug de Judge0) |
| **Timeout por CPU vs. por sueño** | `while(true){}` → `TIMEOUT` por `SIGXCPU`. `Thread.sleep(MAX_VALUE)` → `TIMEOUT` por el backstop de pared. **Los dos caminos, porque el segundo no consume CPU** |
| **Camino feliz con el host saturado** | Seis ejecuciones en paralelo: el reloj de pared se estira, el de CPU **no**, y ninguna da `TIMEOUT`. Es la prueba que valida el cambio de reloj |
| Tests de T05 que no compilan | `SUITE_INVALIDA`, y **no** consume vida del alumno |
| Camino feliz con el reloj al límite | Compilar no descuenta del presupuesto del alumno |
| Janitor tras crash simulado | Cero contenedores con el label después del barrido. **Es prueba del ejecutor**, no del worker |
| **Ejecutor devuelve `503`** | El job vuelve a la cola con backoff, **no** a la DLQ, y no se escribe ningún veredicto |
| **Ejecutor devuelve `502`** | `ERROR_INTERNO`, no consume vida, y el circuit breaker cuenta la falla |
| **El socket del ejecutor no responde** | Vence el timeout del cliente → `ERROR_INTERNO`, nunca `TIMEOUT` |
| **El worker muere con un `POST /ejecutar` en vuelo** | El ejecutor termina igual y libera su slot; el mensaje vuelve a la cola y otro worker rehace el trabajo |

La suite maliciosa corriendo en CI es la mejor evidencia para la defensa: no es *"diseñamos un sandbox seguro"*, es *"acá está el ataque y acá está contenido"*.

Cuatro de esas filas salieron de correr un prototipo descartable contra Docker real ([`03`](./03-ms-sandbox-ejecucion.md) §1.4). Las cuatro fallaban con el diseño anterior — y ninguna fallaba de forma ruidosa. La de `ruta` hostil quedaba contenida por casualidad, la de la suite rota le cobraba una vida al alumno equivocado, y la del reloj convertía el camino feliz en `TIMEOUT`. **Todas eran verdes en el papel.**

Las **seis últimas** salieron de la investigación externa, y tienen algo en común que vale la pena señalar en la defensa: **ninguna es un ataque al aislamiento.** Symlinks en el tar, sombreado de paquete, escritura del reporte — las tres atacan **el código nuestro que arma el bundle y lee el resultado**, no el contenedor. Es el hallazgo transversal de la investigación: los cinco escapes documentados en Judge0 y Ares fueron bugs de lógica de la plataforma, no exploits de kernel.

> Por eso conviene enunciarlo así: *"nuestra suite maliciosa no prueba solamente que el contenedor contiene. Prueba que el veredicto no se puede falsear — que es la propiedad de la que depende la nota."*

**Nota de testing:** el ejecutor necesita un Docker real, así que convienen **tres** suites separadas.

1. **Lógica del worker** (ack, requeue, DLQ, transiciones de estado) con Testcontainers de **RabbitMQ + Postgres**, y un **ejecutor falso**: un servidor sobre un socket Unix que devuelve JSON canned. Son unas 60 líneas, y con eso se prueban las cinco filas nuevas de la tabla de arriba sin levantar un solo contenedor. **La frontera de D1 abarató esta suite**: antes había que mockear un cliente de Docker, ahora se responde un HTTP.
2. **Ejecutor** contra el Docker del host, marcada aparte para que no rompa donde no hay demonio. Es la suite de §13 de [`08`](./08-spec-ejecutor.md), y **no es de este documento**: la corre cada implementación del ejecutor contra la misma spec.
3. **Outbox y relay**, con Postgres nomás: se verifica que el mensaje quede pendiente cuando el publish falla, y que se publique al reintentar.

La 1 es la que más valor da por línea escrita, porque casi todo lo de §7 se prueba ahí: matar la conexión AMQP de un consumer es una línea de Testcontainers, y morir a mitad de un job deja de ser un escenario difícil de reproducir.

**Ver también**
- `README.md` — índice, fuente de verdad por tema y estado de todas las decisiones.
- `08-spec-ejecutor.md` — el otro lado de la frontera: contrato, spec del contenedor, constantes.
- `03-ms-sandbox-ejecucion.md` — aislamiento, contrato con T05, origen de los tests, máquina de estados, API.
- `05-ms-sandbox-patrones.md` — los patrones de la unidad de Microservicios aplicados a este servicio.
- `01-panorama-microservicios-backend.md` — mapa de los 12 servicios del curso.
