# `ms-sandbox` — El worker

> **Tema 06 — Sandbox / Runtime.**
> El worker es donde está toda la ingeniería del servicio: es el único componente con estado, el único que puede morir a mitad de algo, y el único de quien depende que un alumno reciba respuesta.
> La API es un CRUD. Los flags del contenedor son quince líneas que se escriben una vez. **Esto no.**

Complemento de `03-ms-sandbox-ejecucion.md`, que cubre el aislamiento, el contrato con T05 y el origen de los tests.

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
12. [Cómo le habla a Docker](#12-cómo-le-habla-a-docker)
13. [El janitor](#13-el-janitor)
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

El modelo mental que más ayuda:

> **El worker nunca ejecuta el código del alumno. Ni lo lee, ni lo compila, ni lo carga. Solo mueve archivos y le da órdenes a Docker.**

El worker es un **capataz**, no el que hace el trabajo. Contrata a un obrero descartable (el contenedor), le pasa los materiales, mira el reloj, retira el resultado y después **incendia el taller**.

De ahí sale la seguridad: el código hostil corre en un proceso que no comparte **nada** con el worker — ni memoria, ni JVM, ni red, ni filesystem. El peor código imaginable no puede tocarlo, porque el worker nunca lo tuvo adentro.

```
armar el bundle en memoria (tar)
        │
        ▼
docker create -i  (con todos los límites y los dos relojes)
        │
        ▼
docker start -ai  ──── el tar va por STDIN ────► el script lo desempaqueta
        │                                        en el tmpfs
        │
        ├── compilar solución   (reloj de plataforma)
        ├── compilar tests      (reloj de plataforma)
        └── correr tests        (reloj del ALUMNO)
        │
        ▼
el reporte vuelve por STDOUT, codificado
        │
   ┌────┴─────────────────────┐
termina solo          el worker se cansa (red de última instancia)
   │                          │
   │                    docker kill
   └────┬─────────────────────┘
        │
docker inspect  (exit code + OOMKilled)
docker rm -f
```

**Nada de esto toca el filesystem del host.** No hay directorio efímero, no hay `docker cp`, no hay volumen. El bundle entra por `stdin` y el reporte sale por `stdout` — el detalle de por qué está en [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) §1.5, y el motivo corto es que `docker cp` es incompatible con `--read-only`.

Cuatro reglas que no se negocian, las cuatro verificadas contra Docker real ([`03`](./03-ms-sandbox-ejecucion.md) §1.4):

- **El veredicto sale del reporte, nunca del exit code.** `System.exit(0)` en el código del alumno sale con exit `0` y cero tests corridos: leyendo el exit code, aprueba. La guarda es exigir el XML **con `tests > 0`**.
- **`docker inspect` desambigua el `137`, pero no alcanza para la memoria.** Con la JVM bien configurada, el OOM llega como `exitCode: 3` y `OOMKilled: false`. Hay que mapear los dos caminos.
- **Compilar y correr tests tienen relojes distintos.** El reloj que propone T05 cubre solo la fase de tests; compilar es costo de plataforma. Un reloj único convertía el camino feliz en `TIMEOUT`.
- **El reloj de pared del worker es la red, no el mecanismo.** El reloj que le corta el paso al alumno vive **adentro** del contenedor, uno por fase. Si el de afuera dispara, el problema es del sandbox: eso es `ERROR_INTERNO`, no `TIMEOUT`.

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

Este es de otra naturaleza, y por eso hace falta un segundo mecanismo.

El re-encolado del broker arregla **el mensaje**. Pero afuera del broker quedó un contenedor corriendo, quemando un core con el `while(true)` de un alumno. **Ningún re-encolado lo va a ver.** Docker y RabbitMQ son dos mundos que no se hablan: el broker sabe de mensajes sin confirmar, no de procesos vivos en el host.

Por eso todo se etiqueta al crearlo (`--label sandbox.job=abc-123`) y hay un barrido que borra los huérfanos → [§13](#13-el-janitor).

> **Dos mundos, dos limpiadores: el broker limpia mensajes, el janitor limpia contenedores.** El primero es gratis; el segundo hay que escribirlo.

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

| # | Paso | Si el worker muere acá | Mitigación |
|---|---|---|---|
| 1 | Recibe el mensaje | Job en `EN_EJECUCION`, nadie trabajando | **Sin ack → el broker re-encola** |
| 2 | Arma el bundle en memoria | Nada: no tocó nada afuera | — |
| 3 | `docker create` | Contenedor huérfano (sin consumir aún) | **Janitor** |
| 4 | Escribe el bundle en el `stdin` | Ídem | Janitor |
| 5 | `docker start` | **Contenedor huérfano quemando CPU** | Janitor (el caso caro) |
| 6 | Espera con timeout | Ídem | Janitor |
| 7 | Lee el reporte del `stdout` | Trabajo hecho, resultado perdido | Se re-ejecuta: es función pura |
| 8 | `docker rm` | Contenedor muerto sin borrar | Janitor |
| 9 | Persiste el resultado | Resultado perdido | Re-ejecución |

El paso 2 dejó de ser un punto de falla al mover el bundle a `stdin`: ya no hay directorio efímero en el host que quede huérfano. **Dos mecanismos cubren los nueve casos** — y uno de los dos ya no hay que escribirlo. El ack manual lo pone el broker; el janitor sigue siendo trabajo propio, porque **Docker y el broker tampoco se hablan**. Con esos dos, el worker resiste morir en cualquier punto.

---

## 8. Anatomía del proceso

Adentro del `java -jar` del worker viven tres cosas:

| Thread | Cuántos | Qué hace |
|---|---|---|
| **Consumers AMQP** | = slots (6) | Cada uno recibe un mensaje y supervisa su contenedor de punta a punta |
| **Janitor** | 1 | Al arrancar y cada pocos minutos, barre huérfanos |
| **Watchdog** | 1 | Marca `ERROR_INTERNO` las ejecuciones colgadas mucho más allá del timeout |

Seis contenedores vivos ⇔ seis consumers ocupados. Se lee de un vistazo en un thread dump.

Contra la versión sobre la tabla **desaparecen dos threads**: el bucle de reclamo (ahora el broker empuja, no hay que preguntar cada 200 ms) y el heartbeat (ahora el ack manual hace ese trabajo). El reaper de leases desaparece del todo.

*(El relay del outbox vive del lado de la **API**, no del worker: publica lo que la API escribió. Ver [§3](#3-lo-que-los-separa-la-cola).)*

---

## 9. El modelo mental en una frase

> **El worker recibe un mensaje que no confirma hasta terminar, supervisa un contenedor desechable, y escribe el resultado solo si nadie lo escribió antes. Todo lo demás existe porque puede morirse en cualquier punto de esos tres pasos.**

Tres cosas para retener:

1. **La cola separa "llegó" de "se hizo".** Convierte un pico en espera, no en caída.
2. **Recibir no es quedarse el job.** El mensaje sigue siendo del broker hasta el ack, y de ahí salen el ack manual, el prefetch y el guard al escribir.
3. **El worker supervisa, nunca ejecuta.** El código del alumno jamás entra a su proceso.

---
---

# Parte II — Construirlo

## 10. Topología: ¿mismo proceso que la API?

Decisión con consecuencia de **seguridad**, no solo de despliegue.

| | Todo junto | API y worker separados |
|---|---|---|
| Quién toca el socket de Docker | **Todas** las réplicas de la API | Solo el worker |
| Escalado | Acoplado: más API = más lanzadores | Independiente |
| Complejidad | Un deployable | Dos |

El argumento decisivo: el socket de Docker equivale a root en el host. Si la API —lo único expuesto al Gateway— lo tiene montado, cualquier vulnerabilidad en un endpoint HTTP escala a root. Separados, **la API no tiene acceso a Docker en absoluto**.

**Punto dulce para el TP:** un repo, una imagen, dos perfiles de Spring, dos despliegues.

```sh
java -jar sandbox.jar --spring.profiles.active=api      # sin socket, expuesto al Gateway
java -jar sandbox.jar --spring.profiles.active=worker   # con socket, sin puerto HTTP público
```

Un build, un pipeline, dos contenedores. **No se llaman entre sí**: la API publica al broker y el worker consume. Ni siquiera necesitan resolverse por nombre — ver `05-ms-sandbox-patrones.md` §8, el worker no se registra en el Service Discovery.

Cada perfil se lleva además una responsabilidad distinta de la infra: el **relay del outbox** corre en el perfil `api` (publica lo que la API escribió); el **janitor** y el **watchdog**, en el perfil `worker`.

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

`concurrency × prefetch` es el techo de jobs en vuelo en este proceso: 6 × 1 = 6. Threads de plataforma, fijos, tantos como slots. Cada thread mapea a un contenedor vivo, y esa legibilidad vale más que cualquier optimización acá.

Dos elecciones que parecen detalle y no lo son:

> **`prefetch: 1` en seis consumers, no `prefetch: 6` en uno.** Los dos dan seis jobs en vuelo, pero el primero los reparte entre seis threads que ejecutan en paralelo, y el segundo apila cinco esperando detrás de uno. Con jobs de segundos, la diferencia es toda la latencia.

> **`max-concurrency` igual a `concurrency`.** El escalado automático de consumers de Spring reacciona a la profundidad de la cola, que acá es la señal equivocada: el límite no es el trabajo pendiente sino la CPU y la RAM del host, que no crecen porque crezca la cola. Se escala agregando **réplicas del worker en otros hosts**, no consumers en el mismo proceso. Es la diferencia entre escalar y sobrevender.

### Dimensionamiento

```
Por ejecución:           512 MB RAM  +  1 CPU  durante ~3–8 s
Pool de 6 simultáneas:   ~3 GB RAM   +  ~6 cores en pico
```

RF-NFR-03 pide 120 sesiones concurrentes, pero **120 sesiones no son 120 ejecuciones simultáneas**: la gente lee, escribe y piensa. Un pool de 4–6 con cola absorbe el pico.

Lo importante no es acertar el número, es el **comportamiento cuando se llena**: cola acotada, `429` con `Retry-After`, y **tope de jobs en vuelo por alumno** — si uno solo puede encolar 20 ejecuciones, se come el pool él solo. El número se ajusta con la prueba de carga; el mecanismo tiene que estar bien desde el diseño.

---

## 12. Cómo le habla a Docker

| | `docker-java` (librería) | CLI por `ProcessBuilder` |
|---|---|---|
| `inspect` tipado (`OOMKilled`) | Sí, cómodo | Parsear `--format '{{.State.OOMKilled}}'` |
| Depuración | Hay que reproducir con código | **Se copia el comando y se pega en una terminal** |
| Dependencias | Una librería más, con su versión | Ninguna |

**Se recomienda la CLI**, por un motivo tanto práctico como pedagógico: los flags que están en el código son literalmente los flags que van en el informe, y cualquier falla se reproduce a mano en dos segundos. En un grupo de 11 donde no todos van a tocar el ejecutor, esa transparencia importa.

### El error que cuesta horas

Si se usa `docker start -a`, matar el proceso local **no mata el contenedor**: queda corriendo huérfano.

```java
Process p = new ProcessBuilder(comando).start();
boolean termino = p.waitFor(limiteMs + MARGEN_MS, MILLISECONDS);

if (!termino) {
    docker("kill", containerId);       // ← esto es lo que realmente detiene
    p.waitFor(5, SECONDS);
    p.destroyForcibly();               // ← esto solo limpia el proceso local
    return Resultado.timeout();
}
```

**Siempre `docker kill {id}`, nunca solo `process.destroy()`.**

### La salida se trunca adentro, no afuera

Si el worker lee `stdout` del contenedor por stream, un alumno con un `println` en loop llena la memoria **del worker**: el aislamiento se rompe justo en el borde.

Solución: el script de arranque redirige a un archivo dentro del `tmpfs`, que ya está topeado por `--tmpfs ...,size=128m`. Al terminar emite solo el primer bloque, por `stdout`, junto con el reporte.

```sh
java -jar /opt/junit/junit.jar execute ... \
     > /tmp/reports/j-out.txt 2> /tmp/reports/j-err.txt
```

**No usar `| head -c 65536`:** cuando `head` deja de leer, el escritor recibe SIGPIPE y **el programa del alumno muere por eso**. Una solución correcta que imprime mucho pasaría a fallar. El límite tiene que ser el tamaño del tmpfs, no una tubería que se corta.

---

## 13. El janitor

Sin esto, cada crash deja basura y el host se degrada hasta morir.

Etiquetar todo al crear:

```
--label sandbox.job=abc-123 --label sandbox.worker=w1
```

Y barrer al arrancar y cada pocos minutos:

```sh
docker ps -aq --filter label=sandbox.job \
             --filter "until=10m"        | xargs -r docker rm -f
```

El barrido de directorios del host que estaba acá **ya no hace falta**: con el bundle entrando por `stdin` y el reporte saliendo por `stdout`, el worker no escribe nada en el filesystem del host. El janitor barre contenedores, no archivos.

**Correrlo al arranque es lo importante:** si el worker crasheó, sus contenedores siguen ahí quemando CPU y **nadie más los va a limpiar** — el que ensució es el único que sabe que existieron. Al levantarse, lo primero que hace es barrer lo que dejó su vida anterior.

Cada huérfano encontrado va a una métrica. Si ese número sube, algo se está muriendo y todavía no lo saben.

**Y una purga que no es de contenedores:** las filas de `outbox` ya publicadas ([§15](#15-esquema-ejecucion-y-outbox)) se borran por antigüedad en el mismo barrido. No molestan al relay —su índice es parcial y solo ve lo pendiente— pero una tabla que solo crece termina siendo un problema de backup y de `VACUUM`, no de consultas. Es la clase de deuda que en un TP no se nota y en producción se nota tarde.

---

## 14. Apagado ordenado

Relevante para la unidad de CI/CD: en un deploy rolling, el worker recibe SIGTERM con jobs corriendo.

1. Dejar de reclamar jobs nuevos (inmediato).
2. Esperar a los en vuelo, con tope = timeout máximo + margen.
3. Salir.

Con ack manual, el paso 1 es literalmente `stop()` del listener container: deja de recibir mensajes nuevos y los que ya tomó siguen su curso.

Si igual lo matan a la fuerza, los mensajes sin ackear vuelven a la cola cuando se corta la conexión.

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
- **`publicado_en` nullable + índice parcial.** La tabla acumula todo lo que se publicó alguna vez, pero el índice del relay solo contiene lo pendiente — que en régimen normal son cero o dos filas. Las publicadas se purgan con retención, igual que los artefactos ([§13](#13-el-janitor)).

## 16. Qué mide el worker

| Métrica | Tipo | Para qué |
|---|---|---|
| `rabbitmq_queue_messages_ready{queue="sandbox.jobs"}` | gauge | Profundidad de cola. **La da el broker**, no hay que instrumentarla: la exporta el plugin de Prometheus de RabbitMQ |
| `sandbox_dlq_profundidad` | gauge | **La que se alerta con umbral 0.** Cada mensaje ahí es un alumno que no va a recibir respuesta |
| `sandbox_outbox_pendientes` | gauge | Lag del relay. Si crece, se están aceptando entregas que **no se están encolando**: el broker rechaza o el relay murió |
| `sandbox_slots_ocupados` | gauge | Utilización real vs. consumers configurados |
| `sandbox_espera_en_cola_seconds` | histogram | **La latencia que percibe el alumno** |
| `sandbox_fase_duracion_seconds{fase}` | histogram | `create`/`compile_solucion`/`compile_test`/`test` |
| `sandbox_ejecuciones_total{estado}` | counter | Distribución de veredictos |
| `sandbox_error_interno_total` | counter | Debería ser ~0 |
| `sandbox_redeliveries_total` | counter | Workers muriendo o colgándose |
| `sandbox_huerfanos_limpiados_total` | counter | Fugas de contenedores |

Las dos primeras gauges de instrumentación propia —DLQ y outbox— son **las que el broker hace necesarias**, y las dos vigilan el mismo silencio: una entrega aceptada que nunca se va a ejecutar. Sin ellas, el modo de falla del broker es invisible hasta que un alumno reclama.

La de fases vale oro, y ya dio su primer resultado. Este documento **suponía** que el grueso del tiempo era el arranque de la JVM, y que la optimización correcta era AppCDS. Se midió y **es falso**: `java -version` dentro del contenedor tarda 38–82 ms. El tiempo está en `javac` (1.0–2.3 s por invocación, y son dos) y en el escaneo de classpath de JUnit (1.2–2.9 s). El código del alumno, en el camino feliz, corre en microsegundos: **prácticamente todo el reloj es toolchain.**

Los números y las palancas que sí sirven están en [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) §1.4 y §4.2. Lo que importa acá es el método, no el número: la suposición era razonable, la métrica de fases la desmintió en una tarde, y eso es exactamente lo que la unidad de Observability pide mostrar. **Vale más la corrección que el acierto** — dejar escrito qué se creía y qué salió es lo que hace que el hallazgo sea defendible.

Un segundo hallazgo de la misma métrica, que cambia cómo se fija un umbral: la varianza entre corridas idénticas es grande (la fase de tests fue de 1.8 s a 5.2 s con la misma entrega y los mismos límites). Cualquier alerta o timeout sobre estas fases se calibra con **percentiles altos**, nunca con el promedio.

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
| Tests de T05 que no compilan | `SUITE_INVALIDA`, y **no** consume vida del alumno |
| Camino feliz con el reloj al límite | Compilar no descuenta del presupuesto del alumno |
| Janitor tras crash simulado | Cero contenedores con el label después del barrido |

La suite maliciosa corriendo en CI es la mejor evidencia para la defensa: no es *"diseñamos un sandbox seguro"*, es *"acá está el ataque y acá está contenido"*.

Las últimas cuatro filas de la tabla no estaban acá antes: salieron de correr un prototipo descartable contra Docker real ([`03`](./03-ms-sandbox-ejecucion.md) §1.4). Las cuatro fallaban con el diseño anterior — y ninguna fallaba de forma ruidosa. La de `ruta` hostil quedaba contenida por casualidad, la de la suite rota le cobraba una vida al alumno equivocado, y la del reloj convertía el camino feliz en `TIMEOUT`. **Todas eran verdes en el papel.**

**Nota de testing:** el ejecutor necesita un Docker real, así que convienen **tres** suites separadas.

1. **Lógica del worker** (ack, requeue, DLQ, transiciones de estado) con Testcontainers de **RabbitMQ + Postgres**, sin ejecutar código de verdad: el ejecutor va mockeado.
2. **Ejecutor** contra el Docker del host, marcada aparte para que no rompa donde no hay demonio.
3. **Outbox y relay**, con Postgres nomás: se verifica que el mensaje quede pendiente cuando el publish falla, y que se publique al reintentar.

La 1 es la que más valor da por línea escrita, porque casi todo lo de §7 se prueba ahí: matar la conexión AMQP de un consumer es una línea de Testcontainers, y morir a mitad de un job deja de ser un escenario difícil de reproducir.

**Ver también**
- `03-ms-sandbox-ejecucion.md` — aislamiento, contrato con T05, origen de los tests, máquina de estados, API.
- `05-ms-sandbox-patrones.md` — los patrones de la unidad de Microservicios aplicados a este servicio.
- `01-panorama-microservicios-backend.md` — mapa de los 12 servicios del curso.
