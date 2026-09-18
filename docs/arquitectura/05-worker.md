# Worker de `ms-sandbox`

> Cubre la cola de trabajo, el consumo con ack manual y DLQ, la llamada al ejecutor, el mapeo de la
> respuesta a los seis estados técnicos, la persistencia del resultado y la emisión de
> `ExecutionCompleted`. El worker se construye desde cero para el MVP: el prototipo en
> [`ms-sandbox/worker/`](../../ms-sandbox/README.md) no es el entregable.

Complemento de [`04-ejecutor.md`](./04-ejecutor.md), que define el protocolo con el que el worker le
pide la ejecución al ejecutor, y de [`06-api.md`](./06-api.md), dueña del esquema de la base de datos
y del outbox del lado de la API.

---

## Decisiones

| # | Decisión | Por qué | Descartado |
|---|---|---|---|
| **D1** | El worker no tiene acceso al socket de Docker: le pide la ejecución al ejecutor por un socket Unix | El worker nunca debe tener el privilegio de crear contenedores | Un proxy del socket que deja la spec del contenedor en manos del cliente |
| **D2** | El relay del outbox corre solo en la `api` y enruta por tipo de mensaje: el trabajo va a la cola interna y `ExecutionCompleted` al bus de eventos de la plataforma | La `api` es dueña del esquema; el worker inserta filas en el outbox pero no publica | — |
| **D20** | El resultado se consulta por polling y se avisa además con `ExecutionCompleted`, que lleva solo `executionId` y `status` por el bus de eventos de la plataforma (Kafka) | La salida puede pesar hasta 8 MiB; el aviso tiene que ser chico. El bus lo mantiene el grupo de notificaciones | — |
| **D22** | El worker devuelve salida cruda y no emite veredicto | Decidir si una entrega aprueba es dominio de T05, no del sandbox | — |
| **D23** | Seis estados técnicos: `QUEUED`, `RUNNING`, `COMPLETED`, `TIMEOUT`, `MEMORY_LIMIT` e `INTERNAL_ERROR` | Sin estados de negocio: `COMPLETED` significa que la herramienta terminó, no que aprobó | — |
| **D24** | El worker escribe `RUNNING` y el estado terminal directamente en la base, antes del `ack` | Evita un canal de resultados de vuelta hacia la `api` | — |
| **D26** | `429` por cola llena (`QUEUE_FULL`, con `Retry-After`) cuando la cantidad de ejecuciones en `QUEUED` alcanza el tope configurado | Saturado no es roto: la API rechaza sin declararse caída | — |

---

## 1. Qué hace el worker

Sin un componente que separe recibir un pedido de ejecutarlo, un pico de entregas rompe el servicio
de cuatro formas: se agotan los hilos que atienden HTTP porque cada uno queda ocupado esperando una
ejecución de varios segundos; nada limita cuántos contenedores hay vivos a la vez, así que un pico
agota la memoria y el CPU del host de golpe; los reintentos de un cliente con timeout corto entran en
espiral, porque más carga produce más lentitud, que produce más timeouts, que produce más reintentos;
y un reinicio en medio de ejecuciones en curso las pierde sin dejar registro, así que los alumnos
afectados tienen que volver a entregar.

El worker existe para separar en el tiempo lo rápido (recibir un pedido) de lo lento (ejecutarlo). Es
un proceso independiente de la API, sin puerto HTTP propio, que consume mensajes de una cola en vez de
atender requests.

Reducido a lo esencial, el worker hace tres cosas por cada job:

1. **Toma el job** de la cola de trabajo.
2. **Lo ejecuta** a través del ejecutor.
3. **Registra el resultado**: lo persiste en la base y confirma el mensaje.

Todo el resto de este documento —ack manual, prefetch, DLQ, watchdog, apagado ordenado— son
respuestas a una sola pregunta: ¿y si el worker se muere justo en medio de esos tres pasos?

### Anatomía del proceso

Adentro del proceso del worker conviven dos tipos de hilo: uno por cada consumer de la cola de
trabajo, tantos como slots de ejecución (6), cada uno bloqueado esperando la respuesta de un
`POST /executions`; y el watchdog ([§11](#11-watchdog-y-apagado-ordenado)), que recorre
periódicamente las ejecuciones estancadas. El relay del outbox no corre acá: vive solo del lado de
la `api` (D2). Con seis ejecuciones en vuelo, un thread dump del worker muestra seis consumers
ocupados y nada más — es la lectura más simple de verificar que el pool está haciendo lo que dice
que hace.

---

## 2. La cola de trabajo

La cola de trabajo es una cola de RabbitMQ. La API publica el job a través de su outbox y se olvida;
el worker consume a su ritmo. El mensaje transporta como mínimo `executionId`; el worker obtiene el
perfil y el bundle de la fuente de verdad persistida, no del propio mensaje.

Tres propiedades no son negociables y **ninguna es el valor por defecto del broker**: hay que
declararlas explícitamente.

| Propiedad | Qué exige | Por qué |
|---|---|---|
| Cola `durable` | Declarar la cola como durable al crearla | Una cola no durable vive solo en RAM: un reinicio del broker se la lleva entera |
| Mensajes `persistent` | Publicar cada mensaje marcado como persistente | Un mensaje no marcado no sobrevive al reinicio del broker aunque la cola sí sea durable |
| `publisher confirms` | Esperar la confirmación del broker antes de dar la publicación por hecha | Sin confirmación, el proceso que publica no sabe si el mensaje realmente llegó |

Con las tres en su lugar, un reinicio de la API, del worker, del broker o de los tres no pierde
trabajo pendiente: el peor caso pasa de "el alumno pierde la entrega" a "el alumno espera unos
segundos más".

### Por qué RabbitMQ y no Kafka para repartir trabajo

La pregunta de defensa obvia tiene una respuesta concreta: el modelo de ack. RabbitMQ confirma **por
mensaje**, en cualquier orden; los demás consumidores siguen aunque uno esté atendiendo un job lento.
Kafka confirma **por offset**: confirmar el mensaje 10 confirma también el 1 al 9, y un job lento
bloquea el resto de su partición. Las ejecuciones del sandbox duran de forma impredecible y terminan
desordenadas, así que el ack por mensaje es el que evita elegir entre confirmar de más —perdiendo lo
que quedó atrás si el worker muere— o quedarse esperando al más lento.

Kafka sigue siendo la herramienta correcta para otra cosa: es el **bus de eventos de la plataforma**
por el que se publica `ExecutionCompleted` (D20, [§7](#7-aviso-de-finalización-hu-10-d20)), mantenido
por el grupo de notificaciones. La cola de trabajo interna y el bus de eventos de la plataforma son
dos sistemas distintos, con responsabilidades distintas, aunque el mismo mecanismo de outbox alimente
a los dos.

### El outbox y el relay no son del worker

La fila de ejecución y el mensaje de outbox nacen en la misma transacción de Postgres, y un proceso
relay separado los publica hacia el broker o hacia el bus según su tipo. Ese relay corre solo en la
`api` (D2): la `api` es la dueña del esquema, y el worker inserta filas en el outbox pero no publica
nada él mismo. El detalle completo del outbox y del relay está en [`06-api.md`](./06-api.md) (outbox y
relay); acá alcanza con saber que el mensaje de trabajo que el worker consume llegó por esa vía, y que
la fila que el worker inserta para `ExecutionCompleted` sale por el mismo mecanismo.

---

## 3. Consumo

El worker recibe cada mensaje con confirmación manual: el mensaje se da por confirmado recién cuando
el worker terminó de procesar el job, nunca al recibirlo. La confirmación no dice "lo recibí", dice
"lo terminé", y de esa distinción cuelga toda la tolerancia a fallas del worker.

La cantidad de mensajes que un worker puede tener sin confirmar (el *prefetch*) se limita a la
cantidad de slots de ejecución disponibles. Con 6 slots de contenedor, un worker nunca tiene más de 6
jobs en vuelo: el broker no le manda un séptimo hasta que confirme uno. Sin ese límite, un worker que
arranca contra una cola con cientos de jobs se los llevaría todos a su memoria, y los demás workers
verían una cola vacía aunque sobre trabajo por hacer.

Un mensaje que hace fallar al worker de forma reproducible —un payload malformado, un perfil que no
existe— vuelve a la cola, lo toma otro worker, falla igual, y así indefinidamente si nada lo corta. La
mitigación es una cola de mensajes fallidos (DLQ) con una política de reintentos acotada: tras agotar
los reintentos, el mensaje se envía a la DLQ sin volver a encolarse.

Ese envío y la escritura del resultado tienen que quedar coordinados: al mandar el mensaje a la DLQ,
la ejecución correspondiente se marca `INTERNAL_ERROR` en el mismo movimiento. Sin esa coordinación el
mensaje queda a salvo en la DLQ, pero el alumno sigue viendo `QUEUED` para siempre — exactamente la
falla que este diseño existe para evitar. `INTERNAL_ERROR` por esta vía no consume vida ni intento del
alumno: la cola de mensajes fallidos es problema del sandbox, no de la entrega.

### Cuándo reencolar y cuándo no

No toda falla de procesamiento merece el mismo tratamiento. La distinción es si un reintento
inmediato tiene alguna chance de cambiar el resultado:

| Falla | ¿Vuelve a la cola? | Por qué |
|---|---|---|
| El ejecutor no responde o está saturado | Sí | Es transitorio: en poco tiempo puede resolverse solo |
| El host se quedó sin recursos momentáneamente | Sí | Ídem |
| El payload del mensaje no parsea | No, a la DLQ | Reintentar mil veces produce mil veces el mismo resultado |
| El perfil solicitado no existe en el catálogo | No, a la DLQ | Ídem |
| Se agotó la cantidad de reintentos permitida | No, a la DLQ | Ya tuvo el margen que le correspondía |

Confundir las dos columnas tiene dos costos opuestos: tratar una falla transitoria como definitiva
manda entregas sanas a la DLQ en medio de un pico; tratar una falla reproducible como transitoria deja
girando en falso a todos los workers contra el mismo mensaje envenenado.

---

## 4. Llamada al ejecutor

Es la única dependencia saliente del worker. El cliente habla HTTP sobre un socket Unix compartido
únicamente con el ejecutor, y hace un único `POST /executions` por ejecución, con `X-Execution-Id`
(el UUID de la fila de ejecución) y `X-Profile` como headers, y el tar del bundle como cuerpo. El
protocolo completo —headers obligatorios, códigos de respuesta, los campos de la respuesta— está
especificado en [`04-ejecutor.md`](./04-ejecutor.md) §3; acá solo se describe lo que le toca al
worker de ese contrato.

### El timeout del cliente

El ejecutor tiene su propio reloj de pared, `EXECUTION_TIMEOUT_MS`, fijado en 70 s
([`04-ejecutor.md`](./04-ejecutor.md) §4.3): si un contenedor no termina en ese plazo, el ejecutor lo
mata y responde `TIMEOUT`. El timeout del cliente HTTP del worker tiene que ser **mayor** a ese
número, nunca menor ni igual — por ejemplo, 80 s. Es un solo número contra el que se dimensiona, no
una suma de relojes internos: el presupuesto de CPU del alumno no tiene cota superior en tiempo de
pared, así que sumar los relojes de adentro no serviría de nada. El único límite que le importa al
worker es el reloj de pared fijo del ejecutor.

Si el timeout del worker fuera menor, el worker abandonaría la espera mientras el contenedor sigue
vivo y el ejecutor sigue trabajando: se perdería un resultado que igual se iba a producir, y encima se
arriesgaría a que otro worker reciba el mismo job por una reentrega y lo ejecute otra vez. El
`consumer_timeout` del broker, a su vez, tiene que quedar por encima del timeout del cliente del
worker — por ejemplo, más de 90 s — para no reencolar un mensaje que el worker todavía está esperando
de forma legítima.

### Los cuatro finales posibles

`result` en la respuesta del ejecutor toma uno de cuatro valores ([`04-ejecutor.md`](./04-ejecutor.md)
§3.2), y cada uno le dice al worker algo distinto sobre qué hacer:

| `result` | Qué significa | Qué hace el worker |
|---|---|---|
| `COMPLETED` | El contenedor terminó por su cuenta, cualquiera sea el `exitCode` | Interpreta la respuesta ([§5](#5-de-la-respuesta-a-los-seis-estados)), persiste, confirma |
| `TIMEOUT` | Venció el reloj de pared del ejecutor; se hizo `kill` | Interpreta igual que arriba: es un resultado, no un error de transporte |
| `DAEMON_ERROR` | El daemon de Docker falló o no respondió (`502`) | `INTERNAL_ERROR`, no consume vida del alumno |
| `REJECTED` | El ejecutor está saturado (`503` con `Retry-After`) | No escribe nada; reintenta con backoff (ver más abajo) |

A eso se suma lo que pasa cuando la respuesta del ejecutor ni siquiera llega: socket caído, timeout
del cliente agotado, o un código fuera de los anteriores (`400`, `411`, `413`, que son bugs del propio
worker al armar el request). Salvo el caso de bug propio —que va directo a la DLQ, porque reintentar
no cambia nada—, el resto se trata como `INTERNAL_ERROR`, sin consumir vida ni intento.

### El backpressure tiene dos niveles

El pool de consumers del worker (concurrencia = slots, 6) es el primer límite, pero no es el que
manda: el ejecutor impone el suyo, `MAX_CONCURRENT` (6 contenedores vivos) y `MAX_QUEUE` (16 en
espera) ([`04-ejecutor.md`](./04-ejecutor.md) §9), y es el único que ve el total cuando hay varias
réplicas de worker corriendo contra el mismo ejecutor. Con un solo worker, sus 6 jobs en vuelo entran
holgados y `REJECTED` no debería aparecer nunca; aparece al escalar horizontalmente, cuando varias
réplicas de worker suman más jobs en vuelo que la capacidad del ejecutor. Por eso el manejo de
`REJECTED` con backoff no es una defensa teórica: es la condición para poder agregar réplicas de
worker sin sobrevender el host.

---

## 5. De la respuesta a los seis estados

El worker no juzga si una entrega aprobó: mapea resultados técnicos a los seis estados de D23. Las
situaciones posibles y el estado de cada una:

| Situación | Estado | Por qué |
|---|---|---|
| La herramienta del perfil terminó, con cualquier resultado: no compila, fallan tests, hay violaciones de PMD o Checkstyle, el reporte queda vacío | `COMPLETED` | El contenedor terminó por su cuenta; el contenido del reporte no cambia el estado técnico (D22) |
| Se agotó el presupuesto de CPU del alumno, incluido el `kill` que dispara el ulimit `cpu` | `TIMEOUT` | Es el reloj del alumno, medido en CPU |
| Venció el respaldo de pared de la fase de tests (un proceso que duerme en vez de consumir CPU) | `TIMEOUT` | El respaldo cubre el caso que el reloj de CPU no ve: quien no consume CPU tampoco dispara el ulimit |
| `oomKilled: true`, o la JVM se quedó sin memoria por su cuenta antes de que el cgroup interviniera | `MEMORY_LIMIT` | Los dos caminos de memoria son reales (HU-07 CA3); `oomKilled` se mira antes que el sobre |
| Venció el reloj de compilación | `INTERNAL_ERROR` | Es costo de plataforma, de pared; no se le cobra al alumno |
| Se disparó el respaldo de pared de la capa 1 de la imagen, o el reloj de pared del ejecutor (`EXECUTION_TIMEOUT_MS`) | `INTERNAL_ERROR` | Si llegó hasta ahí, falló uno de los relojes propios, no el del alumno |
| `DAEMON_ERROR`, socket caído, timeout del cliente, `422` del ejecutor, bundle inválido, sobre de la capa 1 ilegible, paquete de reportes rechazado en modo fail-closed (HU-08 CA3), o un valor del sobre que el worker no reconoce | `INTERNAL_ERROR` | Ninguno de estos casos es responsabilidad de la entrega del alumno |
| `REJECTED` (ejecutor saturado) | Ningún estado nuevo | Se reintenta con backoff ([§4](#4-llamada-al-ejecutor)); no consume vida ni intento |
| Sobrevivieron procesos a la capa 2 | `COMPLETED` | La capa 1 los mata igual; el conteo de procesos sobrevivientes va a logs y a métricas, no cambia el estado |

`oomKilled` se revisa antes que el contenido del sobre: es un campo aparte de la respuesta del
ejecutor, no algo que dependa de interpretar el reporte.

El contenido entre los marcadores del reporte —el sobre de la capa 1— es opaco para el ejecutor y para
esta sección: su formato exacto está especificado en `03-aislamiento.md` (sobre de la capa 1), y este
documento describe únicamente los resultados que produce, en palabras, no los nombres de sus campos.

Lo que el worker guarda como resultado crudo es: `stdout` y `stderr` (con un tope de 65 536 bytes por
flujo), los archivos de reporte producidos por la herramienta del perfil (con un tope de 8 MiB para el
paquete completo) y el `exitCode` de la herramienta, tal como lo produjo. El sandbox no interpreta
ninguno de esos datos (D22): es T05 quien decide qué significan.

**Deuda conocida:** esa salida cruda puede ser falsificada por código de alumno adversarial, porque
corre en el mismo proceso que el runner de la herramienta para el perfil `java21-junit`. El
verificador que cerraría esa brecha queda fuera del MVP (ver [`README.md`](./README.md), "Fuera del
MVP").

---

## 6. Persistencia del resultado (D24)

El worker escribe directamente en la base de datos, sin un canal de resultados de vuelta hacia la API:

1. **Al tomar el job**, intenta la transición `QUEUED → RUNNING` con una actualización condicional:
   solo avanza si la fila todavía está en `QUEUED`.
2. **Al terminar**, persiste el estado terminal, la salida cruda y una fila de outbox con el evento
   `ExecutionCompleted` en **una sola transacción**, y solo si la actualización condicional del estado
   terminal modificó la fila.
3. **Confirma el mensaje recién después del commit.** Nunca antes.

La condición en el `WHERE` de cada actualización es lo que hace que una reentrega —producto de un
worker que se cuelga y otro que retoma el mismo job— nunca duplique la ejecución ni pise un resultado
ya escrito: el primero que escribe gana, y el que llega tarde descarta su resultado sin tocar la fila.
El que llega tarde igual tiene que confirmar su mensaje: descartar el resultado y confirmar son la
misma decisión, porque un mensaje sin confirmar vuelve a la cola y dispara una tercera ejecución del
mismo job.

El usuario de base de datos del worker está limitado a actualizar las columnas de estado, resultado y
marcas temporales de la ejecución, y a insertar en la tabla de outbox. No puede tocar la clave de
idempotencia, las correlaciones ni el perfil: esas columnas son de la API, que es la dueña del
esquema. El modelo de datos completo está en [`06-api.md`](./06-api.md) (modelo de datos).

---

## 7. Aviso de finalización (HU-10, D20)

Cuando una ejecución llega a un estado terminal, el worker inserta en la misma transacción del paso
anterior una fila de outbox con el evento `ExecutionCompleted`. El relay de la API la publica en el
bus de eventos de la plataforma (Kafka).

El evento lleva únicamente `executionId` y `status`, con `executionId` como clave del mensaje. No
lleva la salida: los reportes pueden llegar a 8 MiB, muy por encima del tamaño habitual de un mensaje
del bus. T05 obtiene la salida cruda con `GET /api/sandbox/executions/{id}`, que sigue siendo la
fuente de verdad.

Se emite en **toda** transición a un estado terminal, incluidas las que no pasan por el camino
feliz del worker: el `INTERNAL_ERROR` que marca el envío a la DLQ ([§3](#3-consumo)) y el que marca el
watchdog ([§11](#11-watchdog-y-apagado-ordenado)) también publican su evento.

La entrega es *at least once*, igual que el resto de la mensajería del sandbox: T05 tiene que tolerar
duplicados, identificándolos por `executionId`. Una reentrega del mensaje de trabajo después de
persistido el estado terminal no inserta un segundo evento, porque la inserción ocurre solo cuando la
actualización condicional del [§6](#6-persistencia-del-resultado-d24) modificó la fila.

**Pendiente con el grupo de notificaciones (A9):** el nombre del topic, el formato exacto del sobre
del evento, la autenticación del sandbox como productor, las particiones y la retención.

---

## 8. Qué se rompe si el worker muere

Cada mecanismo de esta sección responde a una falla concreta, no es funcionalidad agregada por su
cuenta.

**Muere después de tomar el job.** Con confirmación manual, el mensaje queda sin confirmar del lado
del broker. Cuando el broker detecta que la conexión se cortó, lo devuelve a la cola y otro worker lo
toma — nadie tiene que darse cuenta de que el primer worker murió, la conexión que se cae es la señal.
El caso que esto no cubre es el worker vivo pero colgado (GC largo, host saturado): la conexión sigue
abierta, el mensaje queda sin confirmar indefinidamente. Para eso están el `consumer_timeout` del
broker ([§4](#4-llamada-al-ejecutor)) y el watchdog propio ([§11](#11-watchdog-y-apagado-ordenado)).

**Dos workers ejecutan el mismo job.** Es la consecuencia del caso anterior cuando el primer worker
revive después de que otro ya tomó el trabajo. No se puede evitar el escenario —ningún broker
distingue "muerto" de "muy lento"—, así que se resuelve al escribir: la actualización condicional del
[§6](#6-persistencia-del-resultado-d24) hace que gane el primero que escribe, y el segundo descarte su
resultado y confirme igual. Ejecutar es una función pura: correrlo dos veces no rompe nada, solo
desperdicia un slot.

**Muere con el contenedor todavía corriendo.** El worker ya no es dueño de ningún contenedor: lo es el
ejecutor, que termina la ejecución igual aunque nadie esté esperando la respuesta, y libera su propio
slot. Si en cambio es el ejecutor el que muere, el barrido de huérfanos de su lado se encarga al
reiniciar ([`04-ejecutor.md`](./04-ejecutor.md) §10). Ningún barrido de contenedores vive del lado del
worker.

**No muere, se ahoga.** Un worker sin límite de mensajes en vuelo puede vaciar una cola de cientos de
jobs a su propia memoria, dejando a los demás workers sin nada que tomar. El prefetch limitado a los
slots ([§3](#3-consumo)) es la mitigación: solo tener en la mano los jobs que se pueden ejecutar ahora.

**No es el worker, es el mensaje.** Un payload malformado hace fallar el procesamiento de forma
reproducible y, sin un límite, ocuparía a todos los workers sin producir nada. La DLQ con reintentos
acotados ([§3](#3-consumo)) corta el ciclo.

---

## 9. Topología

`api`, `worker` y `ejecutor` son tres módulos separados, cada uno con su propia imagen de contenedor y
su propio ciclo de vida — no un único artefacto con perfiles conmutables. La consecuencia es de
seguridad, no solo de despliegue: cuanto más lejos del componente expuesto al Gateway está el
privilegio, menos vías de acceso quedan para llegar a él.

| Componente | ¿Expuesto al Gateway? | ¿Tiene el socket de Docker? |
|---|---|---|
| `api` | Sí | No |
| `worker` | No: consume de la cola de trabajo | No |
| `ejecutor` | No: solo por el socket Unix compartido con el `worker` | Sí, y es el único |

El worker no se registra en el descubrimiento de servicios: no lo llama nadie por su nombre, y él no
llama a nadie por HTTP. Alcanza al ejecutor a través de un volumen compartido, no por la red.

El relay del outbox corre únicamente en la `api` (D2, [§2](#2-la-cola-de-trabajo)): el worker inserta
filas de outbox pero no publica nada por su cuenta.

---

## 10. Concurrencia y dimensionamiento

El límite de concurrencia del worker no son los threads: son los slots de contenedor. El pool corre
6 ejecuciones simultáneas, alineado con `MAX_CONCURRENT` del ejecutor
([`04-ejecutor.md`](./04-ejecutor.md) §4.3). Cada consumer del worker mapea a una ejecución en vuelo,
bloqueado esperando la respuesta del `POST /executions`; no hay virtual threads que agreguen nada acá,
porque el cuello de botella es CPU y memoria del host, no hilos esperando I/O.

Con el perfil de referencia `java21-junit` (512 MB / 2 CPU por ejecución), el pool completo representa
un pico de aproximadamente 3 GB de RAM y hasta 12 cores. Los presupuestos de `java21-pmd` y
`java21-checkstyle` quedan a definir por medición (A2 del [`README.md`](./README.md)).

Dos configuraciones del consumidor de la cola que parecen detalle y no lo son:

- **Prefetch de 1 en seis consumers, no de 6 en uno.** Las dos formas dan seis jobs en vuelo, pero
  la primera los reparte entre seis hilos que ejecutan en paralelo, y la segunda apila cinco jobs
  esperando detrás de uno solo. Con ejecuciones de pocos segundos, esa diferencia es toda la
  latencia que percibe el alumno.
- **La concurrencia del consumidor queda fija, sin escalado automático.** El escalado dinámico de
  consumers reacciona a la profundidad de la cola, que acá es la señal equivocada: el límite real no
  es el trabajo pendiente sino la CPU y la RAM del host, que no crecen porque crezca la cola. Se
  escala agregando réplicas del worker en otros hosts, no consumers dentro del mismo proceso.

---

## 11. Watchdog y apagado ordenado

### Watchdog

Cubre exactamente el caso que el reencolado del broker no ve: el worker vivo pero sin avanzar. Una
ejecución que quedó en `RUNNING` mucho más allá del timeout máximo se marca `INTERNAL_ERROR`, y esa
transición emite su evento `ExecutionCompleted` igual que cualquier otra ([§7](#7-aviso-de-finalización-hu-10-d20)).
El watchdog no reencola nada — de eso se ocupa el broker — solo evita que el alumno quede esperando
una respuesta que no va a llegar.

### Apagado ordenado

Ante `SIGTERM`, con jobs en curso:

1. Dejar de tomar jobs nuevos.
2. Esperar a los que están en vuelo, con un tope igual al timeout máximo más margen.
3. Cerrar el cliente del ejecutor y salir.

Si igual lo matan a la fuerza, los mensajes sin confirmar vuelven a la cola cuando se corta la
conexión — el mismo mecanismo de [§8](#8-qué-se-rompe-si-el-worker-muere), no algo nuevo.

**Son dos apagados, no uno, y el orden importa.** El ejecutor tiene el suyo: ante `SIGTERM`, deja de
aceptar requests, espera hasta `EXECUTION_TIMEOUT_MS` a las ejecuciones en vuelo, borra sus
contenedores y sale ([`04-ejecutor.md`](./04-ejecutor.md) R11.7). En un despliegue con reinicio
gradual, el worker tiene que drenarse **antes** que el ejecutor: si el ejecutor se apaga primero, los
`POST /executions` en vuelo se cortan y el worker los ve como `INTERNAL_ERROR`, y el alumno paga la
latencia de un despliegue propio con todo funcionando bien.

El apagado ordenado es una optimización, no una garantía: la garantía sigue siendo que el mensaje no
se confirma hasta terminar.

---

## 12. Métricas y pruebas

### Métricas

| Métrica | Tipo | Para qué |
|---|---|---|
| Profundidad de la cola de trabajo | gauge | La da el broker directamente |
| Profundidad de la DLQ | gauge | Umbral de alerta en cero: cada mensaje ahí es un alumno que no va a recibir respuesta |
| Filas de outbox pendientes | gauge | Lag del relay: si crece, se están aceptando entregas que no se están encolando o publicando |
| Slots ocupados | gauge | Utilización real contra la cantidad de consumers configurados |
| Espera en cola | histogram | La latencia que percibe el alumno |
| Latencia del `POST /executions` | histogram | El costo de la frontera con el ejecutor, medido del lado del worker |
| Distribución de estados terminales | counter | `COMPLETED` / `TIMEOUT` / `MEMORY_LIMIT` / `INTERNAL_ERROR` |
| `INTERNAL_ERROR` total | counter | Debería mantenerse cerca de cero |
| Reentregas | counter | Señal de workers muriendo o colgándose |
| Rechazos del ejecutor (`REJECTED`) | counter | Cero con varias réplicas sugiere pool sobredimensionado; creciendo, hay que agregar capacidad al ejecutor, no workers |

### Pruebas

| Prueba | Qué verifica |
|---|---|
| Dos workers, cien jobs | Cada job se ejecuta una sola vez (prefetch + competing consumers) |
| Matar un worker a mitad de un job | El mensaje sin confirmar vuelve a la cola y el resultado sale escrito una sola vez |
| Worker que revive y escribe tarde | La actualización condicional descarta el resultado viejo, y el worker tardío confirma igual |
| Broker caído al aceptar la entrega | El outbox retiene, el relay republica al volver: cero jobs perdidos |
| Mensaje envenenado | Tras los reintentos configurados termina en la DLQ, y la fila queda `INTERNAL_ERROR`, no `QUEUED` para siempre |
| Cola de trabajo llena | La API responde `429` con `QUEUE_FULL` y `Retry-After`, sin persistir ni encolar |
| El ejecutor devuelve `REJECTED` | El job se reintenta con backoff, no va a la DLQ, y no se escribe ningún estado |
| El ejecutor devuelve `DAEMON_ERROR` | `INTERNAL_ERROR`, sin consumir vida del alumno |
| El socket del ejecutor no responde | Vence el timeout del cliente y el resultado es `INTERNAL_ERROR`, nunca `TIMEOUT` |
| El worker muere con un `POST /executions` en vuelo | El ejecutor termina igual y libera su slot; el mensaje vuelve a la cola y otro worker rehace el trabajo |
| Watchdog contra una ejecución estancada | Se marca `INTERNAL_ERROR` y se emite el evento correspondiente |
| Emisión de `ExecutionCompleted` por cada camino terminal | Camino feliz, DLQ y watchdog publican el evento con `executionId` y `status`, sin salida |
| Reentrega posterior al estado terminal | No duplica la ejecución, no pisa el resultado y no inserta un segundo evento |

Conviene separar la lógica del worker (ack, requeue, DLQ, transiciones de estado) de la prueba del
ejecutor: la primera puede correr contra un ejecutor de prueba que responde JSON fijo sobre el mismo
socket Unix, sin levantar contenedores; la segunda necesita Docker real y está descripta en
[`04-ejecutor.md`](./04-ejecutor.md) §13.

---

## Abierto

| # | Pregunta | Quién la cierra |
|---|---|---|
| **A9** | Nombre del topic de `ExecutionCompleted`, formato del sobre, autenticación, particiones y retención del bus | Grupo de notificaciones |
| **A11** | Nombres físicos de la cola de trabajo, el exchange, la routing key y la DLQ | Nosotros |
| — | Cantidad exacta de reintentos antes de enviar un mensaje a la DLQ | Nosotros |
| — | Cómo coordinar, en la implementación, la escritura de `INTERNAL_ERROR` con el envío del mensaje a la DLQ | Nosotros |

**Ver también**
- [`README.md`](./README.md) — índice, tabla única de decisiones y estado de lo abierto.
- [`04-ejecutor.md`](./04-ejecutor.md) — el otro lado de la frontera: protocolo, spec del contenedor, constantes.
- [`06-api.md`](./06-api.md) — endpoints, outbox, relay y modelo de datos completo.
