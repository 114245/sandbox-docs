# Patrones de `ms-sandbox`

> Los patrones de la unidad de Microservicios, aplicados a este servicio: cuáles aplican, cómo, y
> cuáles no aplican y por qué. Pensado para la defensa.

## Veredicto rápido

| Patrón | ¿Aplica? | Dónde | Argumento en una línea |
|---|---|---|---|
| **Sidecar** | Sí, el mejor caso del tema | El ejecutor | Reemplaza la API de Docker en vez de filtrarla |
| **Health Check** | Sí, con un giro propio | `GET /actuator/health` de la `api`, `GET /health` del ejecutor | Saturado no es enfermo: `429`/`503` con `readiness: UP` |
| **Circuit Breaker** | Diseñado, fuera del MVP | Llamadas del worker al ejecutor | Ninguna historia del MVP lo incluye |
| **DDD** | Sí, con matiz | Subdominio genérico + anti-corruption layer | Traduce el sobre de la capa 1 a los seis estados técnicos |
| **Rate Limit** | Sí | Tope por alumno y profundidad de cola | Concurrencia, no frecuencia |
| **EDA** | Sí | RabbitMQ (trabajo) y Kafka (evento) | Dos brokers, dos roles, un mismo outbox |
| **SAGA** | Parcial | Un paso de la coreografía | Sin efectos propios que compensar |
| **Service Discovery** | Sí, con una observación fina | Worker y ejecutor no se registran | El registro es para que te encuentren; a ellos no los llama nadie por nombre |
| **API Gateway** | Dado, con decisiones propias | Qué expone la `api` y qué no | Solo la `api` es alcanzable; el resto queda detrás |
| **BFF** | No aplica | — | El consumidor es otro microservicio, no una pantalla |

---

## Sidecar

Es el mejor caso de aplicación del patrón en el servicio, porque resuelve un problema de seguridad
real y específico, no uno genérico.

**El problema.** El worker necesita lanzar contenedores, y alguien tiene que hablar con
`/var/run/docker.sock`. Ese socket es la API HTTP completa del daemon, sin modelo de autorización
propio: no hay usuarios, roles ni scopes. Quien puede hablarle puede pedir cualquier cosa, incluido
un contenedor privilegiado con el filesystem del host montado.

**La solución: el ejecutor no filtra la API de Docker, la reemplaza.** El worker le manda al
ejecutor un único verbo — "corré este tar con este perfil" (`POST /executions`) — y nada más: ni
imagen, ni memoria, ni timeout, ni red. El ejecutor arma la spec completa del contenedor con
constantes de su propio código fuente (D1); el que llama solo elige un perfil de un catálogo
cerrado, y un perfil únicamente puede mover cinco campos (`04-ejecutor.md` §4.4). Esa propiedad
tiene nombre y sostiene todo el diseño: **P1, la spec del contenedor es idéntica en todas las
ejecuciones** (`04-ejecutor.md` §1).

**La alternativa descartada: un proxy del socket con lista blanca.** Filtrar por path y método
HTTP es viable con herramientas de estantería, pero el filtrado se rompe exactamente donde importa:
un proxy que solo mira el path y el método no puede negar campos del cuerpo del request, como
`Privileged: true` o un bind del filesystem del host. Aceptar `POST /containers/create` —que hay
que aceptar, porque es el trabajo del worker— deja esos campos en manos de quien llama. El ejecutor
no tiene ese problema porque no acepta una spec de contenedor: no hay nada que parsear del lado del
atacante.

**El residual.** El ejecutor sigue siendo el proceso con el socket, así que comprometerlo sigue
siendo comprometer el host — eso no lo cierra ningún diseño de sidecar. Lo que cambia es quién
puede llegar a él y qué puede pedirle: el contenedor del alumno no lo alcanza (sin red, sin el
socket montado); el worker sí lo alcanza, pero solo puede pedir "corré este tar", nunca una spec
distinta. El riesgo de kernel compartido entre contenedores queda como deuda documentada, no
cerrada, junto con la decisión pendiente de si conviene sumar más adelante un proxy del motor de
contenedores con lista blanca de operaciones (T-06-13, `03-aislamiento.md` §10).

**Por qué es un sidecar de manual.** Vive junto al worker, en la misma unidad de despliegue y con
ciclo de vida propio; agrega una capacidad transversal —acceso mediado a la infraestructura— sin
tocar código del worker; se puede reemplazar o endurecer sin recompilar el worker; y hace al worker
testeable contra un ejecutor de prueba, sin levantar contenedores. Que el ejecutor esté en Java 21
y no en el mismo runtime del worker no es una inconsistencia: es parte del patrón, y la elección de
lenguaje concreta es D9 (`01-contexto.md` §4).

**Cómo se traduce esto a la escalera de privilegios.** El sidecar es la pieza que hace posible que
ningún componente acumule las dos propiedades peligrosas a la vez —estar expuesto al Gateway y
tener el socket de Docker (`02-arquitectura.md` §2)—. Sin él, esa separación no se podría sostener:
si el worker tuviera el socket, la única forma de aislar el privilegio del componente expuesto
sería no tener worker, y toda la ejecución quedaría en la `api`, bloqueando el `202` que sostiene
el contrato asíncrono con T05 (`01-contexto.md` §2).

**Qué invariantes hace verificables.** La spec fija del contenedor (P1) no es solo una intención de
diseño: `04-ejecutor.md` §12 y §13 listan invariantes verificables con test propio, entre ellos que
el JSON de creación del contenedor es byte a byte idéntico entre ejecuciones salvo el label y el
nombre (I1), y que ningún campo del request escribe un valor de la spec (I2). Un patrón de sidecar
sin esa verificación quedaría en la categoría de intención, no de garantía.

## Health Check

La distinción entre `liveness` y `readiness` sostiene la diferencia entre "saturado" y "roto", y
acá importa de verdad porque el servicio se satura con frecuencia por diseño: un pool de seis
slots se llena rápido bajo un pico de entregas.

**Docker no va en `liveness`.** Si el daemon se cae y eso se reporta como `liveness: DOWN`, el
orquestador reinicia el proceso — que sigue sin Docker — y entra en un ciclo de reinicios que no
arregla nada. La conectividad con el motor de contenedores es una condición de `readiness`, no de
vida del proceso (`06-api.md` §9).

**Un pool saturado no está enfermo.** Si un componente saturado se reporta como `readiness: DOWN`
y se desregistra, la carga se corre a los demás, que se saturan a su vez, y cae en cascada todo el
servicio por estar funcionando a pleno. La respuesta correcta a la saturación es rechazar con
información para el cliente — `429 QUEUE_FULL` con `Retry-After` en la `api`, `503` con
`Retry-After` en el ejecutor — nunca declararse caído:

| Situación | `readiness` | Respuesta |
|---|---|---|
| Cola de trabajo llena | `UP` | `429 QUEUE_FULL` + `Retry-After` |
| Ejecutor con la cola de contenedores llena | `UP` | `503` con `Retry-After`, `result: REJECTED` |
| Base de datos caída | `DOWN` | `503` |
| Catálogo de perfiles vacío o sin conectividad a Docker | `DOWN` | `503` |

**La ejecución canaria es una mejora opcional, no parte del MVP.** Correr un job trivial conocido
por todo el pipeline real —crear contenedor, ejecutar, leer el sobre— detectaría una clase de
fallas que un chequeo que solo pinguea la base nunca ve: una imagen mal construida donde todo
compila pero nada corre. Está identificada como mejora (T-09-08, `README.md`), no como requisito
del alcance mínimo.

**Qué chequea cada `/health` del MVP.** El de la `api` verifica que el catálogo de perfiles no esté
vacío y que la conectividad con el motor de contenedores responda, además de la base de datos
(`06-api.md` §9); el del ejecutor consulta `GET /_ping` del daemon con timeout corto
(`04-ejecutor.md` §3.4). Los dos comparten la misma regla de fondo: una dependencia externa caída
se reporta como tal, pero la saturación propia del pool nunca se confunde con esa caída.

## Circuit Breaker

**Diseñado, fuera del alcance del MVP.** Ninguna historia de usuario del MVP lo incluye
(`README.md`, "Fuera del MVP"), y por eso no está implementado. Vale la pena documentar dónde
aplicaría y por qué, porque es una pregunta de defensa previsible.

**Dónde aplicaría:** sobre las llamadas del worker al ejecutor (`POST /executions`), no sobre
ningún otro servicio de la plataforma — el sandbox casi no llama a nadie más.

**Por qué haría falta.** Si el daemon de Docker está caído o degradado, cada `POST /executions`
falla o tarda hasta agotar su timeout. Sin un breaker, cada mensaje de la cola de trabajo intenta
la llamada, falla lento, agota sus reintentos y termina en la DLQ. En poco tiempo el worker
convierte trabajo válido en mensajes fallidos, sin haber ganado nada por reintentar contra una
infraestructura que no va a responder mejor la próxima vez.

**Cómo se comportaría con Resilience4j.** El circuito se abriría tras una racha de
`DAEMON_ERROR` o de fallas de conexión al ejecutor, y mientras esté abierto el worker dejaría de
tomar jobs nuevos de la cola en vez de tomarlos y fallarlos: los mensajes quedan pendientes, no se
marcan como fallidos. Un intento periódico en estado semiabierto decidiría si volver a cerrarse.
Es una distinción importante: `REJECTED` (el ejecutor saturado, pero funcionando) no debería contar
para el breaker — es backpressure normal bajo carga, no una señal de infraestructura caída; el
error que sí cuenta es `DAEMON_ERROR` o la ausencia de respuesta del ejecutor.

**La idea de un breaker del otro lado sigue siendo válida:** T05 debería tener su propio breaker
hacia el sandbox, con un fallback ya coherente con el resto del diseño — si el sandbox no responde,
aceptar la entrega igual y dejar la ejecución pendiente, nunca bloquear al alumno por una falla de
un servicio aguas abajo.

**Por qué se puede diferir sin comprometer la robustez del MVP.** Las dos redes de seguridad que
ya existen sin el breaker —la DLQ con reintentos acotados y el watchdog sobre ejecuciones
estancadas (`05-worker.md` §3 y §11)— evitan que una falla del daemon deje ejecuciones colgadas
para siempre; lo que el breaker agregaría es evitar que esas ejecuciones lleguen a intentarse en
primer lugar mientras el daemon está caído, ahorrando reintentos que de todos modos van a fallar.
Es una optimización de eficiencia sobre un mecanismo ya seguro, no una condición de corrección: por
eso puede quedar fuera del MVP sin dejar un agujero de confiabilidad, y por eso se documenta acá en
vez de implementarse sin una historia que lo pida.

## DDD

**El matiz honesto primero.** El sandbox tiene poco dominio propio: no es un subdominio *core*,
es un subdominio genérico — existen soluciones de mercado para el mismo problema (ejecutar código
de forma aislada y devolver un resultado). Eso no lo hace menos valioso, lo ubica: no encapsula
ninguna ventaja competitiva de negocio, encapsula una capacidad técnica. Tiene, a cambio, el
bounded context más limpio de los doce servicios: no comparte una sola entidad con nadie, y no
sabe qué es un alumno, un curso ni una moneda.

**La mejor pieza de DDD del servicio es la capa anticorrupción.** El worker traduce el sobre que
produce la capa 1 de la imagen —un formato propio, versionado como `sandbox.layer1/v3`— a los seis
estados técnicos del contrato con T05 (`QUEUED`, `RUNNING`, `COMPLETED`, `TIMEOUT`,
`MEMORY_LIMIT`, `INTERNAL_ERROR`). Esa traducción es la anti-corruption layer: si el formato del
sobre cambia de versión, o si algún día cambia la herramienta que arma el reporte, el efecto queda
contenido en esa traducción y no se propaga a T05 ni al resto de la plataforma. El detalle completo
del mapeo está en `05-worker.md` §5.

**Lo poco de lenguaje ubicuo que sí tiene.** Un puñado de términos propios recorre los documentos
normativos sin ser vocabulario del dominio educativo: ejecución, perfil, bundle, sobre, salida
cruda. Son conceptos de infraestructura de ejecución, no entidades académicas, y esa es
precisamente la prueba de que el bounded context no importó nada del dominio de T05: ni siquiera
tomó prestado su vocabulario.

**Qué protege la capa anticorrupción, en concreto.** El worker nunca reenvía el sobre crudo tal
cual a la base o a T05 sin pasar por la traducción a los seis estados: si mañana cambia el número
de versión del sobre (`sandbox.layer1/v4`, por ejemplo) o cambia qué campos trae, el contrato con
T05 —los seis estados técnicos— no tiene por qué cambiar con él. Es la misma razón por la que
`profileHash` se calcula en el ejecutor y no se lee de un campo declarado (`04-ejecutor.md`
R3.6): la fuente de verdad de lo que pasó adentro del contenedor es interna, y lo que sale hacia
afuera es una traducción controlada, nunca el dato crudo reenviado sin criterio.

## Rate Limit

El matiz es concurrencia, no frecuencia: el recurso escaso del sandbox es CPU física, que no se
multiplexa como un request HTTP. Por eso el control que importa no es cuántos requests llegan por
segundo, sino cuántas ejecuciones tiene un alumno en vuelo al mismo tiempo.

Dos mecanismos, con `429` en los dos casos (D26):

| Control | Qué limita | Código |
|---|---|---|
| Tope por alumno | Ejecuciones en `QUEUED` o `RUNNING` simultáneas de un mismo `studentId` | `429 STUDENT_LIMIT_EXCEEDED` |
| Profundidad de cola | Ejecuciones totales en `QUEUED`, antes de aceptar una nueva | `429 QUEUE_FULL` con `Retry-After` |

El tope por alumno es el que de verdad protege el pool: con seis slots de ejecución, un alumno que
encola muchas entregas seguidas puede dejar a los demás esperando. Es una cuestión de reparto
justo entre alumnos, no de defensa contra abuso. El detalle de ambos códigos de error está en
`06-api.md` §4.

**Por qué el recurso no se multiplexa como un request HTTP.** Un servidor web atiende miles de
requests concurrentes multiplexando I/O; un contenedor de ejecución consume CPU física real durante
toda su vida, y esa CPU no se reparte de forma gratuita entre más ejecuciones simultáneas —
repartirla entre más solo hace que cada una tarde más, que es exactamente el problema que D15 midió
al comparar 1 CPU contra 2 CPU por contenedor (`03-aislamiento.md` §Decisiones). Por eso limitar
requests por segundo en el Gateway no alcanza: el cuello de botella no es cuántas veces se llama a
la API, es cuántos contenedores están vivos a la vez, y ese número lo fijan los slots, no el
Gateway.

**El mismo control existe en el ejecutor, con otro nombre.** `MAX_CONCURRENT` y `MAX_QUEUE`
(`04-ejecutor.md` §4.3 y §9) son la versión del ejecutor del mismo problema: cuántos contenedores
pueden estar vivos a la vez, y cuántos requests pueden esperar turno antes de que el ejecutor
rechace con `503`. Con una sola réplica de worker los dos límites casi nunca se tocan; se vuelven
relevantes al escalar horizontalmente, cuando varias réplicas de worker pueden sumar más trabajo en
vuelo que la capacidad total del ejecutor (`05-worker.md` §4).

## EDA

El sandbox usa dos brokers con roles distintos, alimentados por el mismo mecanismo de outbox:

| | RabbitMQ (cola de trabajo interna) | Kafka (bus de eventos de la plataforma) |
|---|---|---|
| Rol | Reparte ejecuciones entre workers | Publica el hecho `ExecutionCompleted` hacia el resto de la plataforma |
| Patrón de consumo | Competing consumers, ack por mensaje | Fan-out: todos los interesados lo reciben |
| Si nadie procesa | Reintentos acotados, después DLQ | Se pierde, y está bien: es un aviso, no trabajo pendiente |
| Quién lo mantiene | El propio sandbox | El grupo de notificaciones |

RabbitMQ confirma por mensaje, en cualquier orden, lo que permite que un job lento no bloquee a los
demás; Kafka confirma por offset, adecuado para un log que varios consumidores releen de forma
independiente. Ninguno de los dos reemplaza al otro: se usa cada uno donde su modelo de
confirmación encaja (`05-worker.md` §2).

**El mismo outbox transaccional alimenta a los dos.** La `api` inserta la fila del mensaje de
trabajo en la misma transacción en la que crea la ejecución; el worker inserta la fila de
`ExecutionCompleted` en la misma transacción en la que persiste el estado terminal. El relay, que
corre solo en la `api`, lee esa tabla y publica cada mensaje según su tipo (D2). Es entrega *at
least once*, no *exactly once*: la idempotencia de entrada (`Idempotency-Key`) y las transiciones
condicionales del worker son la contraparte obligatoria de esa garantía — sin ellas, un mensaje
publicado dos veces dispararía una segunda ejecución o pisaría un resultado ya escrito
(`06-api.md` §5 y §6).

**Como consumidor, el sandbox es más chico que como productor.** No consume eventos de otros
servicios de la plataforma en el alcance del MVP: no hay ninguna historia que le pida reaccionar a
un evento externo. Toda la complejidad de EDA del servicio está del lado de producir: el mensaje de
trabajo hacia RabbitMQ y `ExecutionCompleted` hacia Kafka, los dos por outbox.

**Por qué el broker de trabajo no puede ser el mismo mecanismo que el bus de eventos**, aunque los
dos corran sobre el mismo outbox: RabbitMQ confirma por mensaje y permite competing consumers con
reintentos y DLQ por mensaje individual, que es exactamente lo que necesita repartir trabajo entre
varios workers; un tema de Kafka confirma por offset, pensado para que varios consumidores
independientes relean el mismo log completo. Forzar el reparto de trabajo sobre un modelo de
offsets significaría que un job lento bloquea la partición entera detrás de él — el problema
inverso al que la cola de trabajo tiene que resolver.

## SAGA

El sandbox es un paso de una coreografía más amplia, no un orquestador ni un participante con
compensación propia. Ejecutar código no deja efectos que deshacer: el contenedor se destruye al
terminar y no queda ningún estado que revertir del lado del sandbox.

**El sandbox no emite ningún veredicto que dispare una consecuencia económica o de progreso.** Esa
saga —intento, vidas, progreso del alumno— es propiedad de T05 y del resto de la plataforma; el
sandbox nunca decide si una entrega aprueba ni activa ninguna acreditación.

**Lo que el sandbox aporta a que esa saga, más amplia, sea sana:**

- **Idempotencia en la entrada.** `Idempotency-Key` evita que un reintento de red de T05 dispare
  una segunda ejecución del mismo intento.
- **Entrega *at least once* sin pérdida.** El outbox transaccional garantiza que ninguna ejecución
  aceptada se pierde entre la `api` y el worker.
- **Ninguna ejecución queda colgada sin resolver.** El watchdog y la DLQ garantizan que toda
  ejecución llega a un estado terminal, incluso ante una falla de infraestructura.
- **`INTERNAL_ERROR` nunca consume un intento del alumno.** Es la regla que evita que una falla del
  propio sandbox se traduzca en una consecuencia negativa para quien entregó.

**Por qué "paso" y no "participante" es la distinción correcta.** Un participante de saga expone
una operación de compensación que otro orquestador o coreografía puede invocar para deshacer su
efecto. El sandbox no tiene ninguna operación así porque no tiene ningún efecto de negocio que
deshacer: una ejecución que ya corrió no se puede "des-ejecutar", y no hace falta, porque no
acreditó nada por sí misma. La compensación de una acreditación equivocada —si un profesor corrige
un test mal escrito después de que varios alumnos ya lo intentaron— ocurre en los servicios que sí
acreditan, no en el sandbox.

## Service Discovery

**El worker no se registra.** Nadie lo llama por nombre: consume de la cola de trabajo a su propio
ritmo. El registro sirve para que te encuentren, y al worker no lo tiene que encontrar nadie.

**El ejecutor tampoco se registra, por un motivo todavía más fuerte:** no es alcanzable por
resolución de nombre en absoluto. El worker lo encuentra por un socket Unix en un volumen
compartido, no por la red (`04-ejecutor.md` §2). Registrarlo lo expondría al resto de los servicios
de la plataforma, exactamente lo contrario de lo que sostiene su aislamiento.

**Los contenedores efímeros de ejecución quedan fuera del registro, y eso es un punto de
seguridad, no una omisión:** con service discovery, cualquier proceso con red puede resolver otro
servicio por nombre. Si el código del alumno tuviera red, alcanzar un servicio de la plataforma por
su nombre sería un bypass directo de sus reglas de negocio. Que el contenedor del alumno no tenga
red (`NetworkMode: none`) hace que la pregunta ni siquiera se plantee (`03-aislamiento.md` §1).

Resumen de quién se registra y por qué:

| Componente | ¿Se registra? | Cómo lo alcanza quien lo necesita |
|---|---|---|
| `api` | Sí | T05, a través del Gateway |
| `worker` | No | Nadie lo llama; consume de la cola de trabajo |
| `ejecutor` | No | El worker, por un socket Unix en un volumen compartido |
| Contenedor efímero | No | Nadie fuera del ejecutor que lo creó |

## API Gateway

Poco de este patrón es decisión propia del sandbox —el Gateway lo opera y lo configura la
plataforma— pero tres cosas sí lo son:

- **Solo la `api` es alcanzable.** El worker y el ejecutor no se registran ni se exponen; toda
  llamada de T05 llega únicamente a la `api`, y siempre a través del Gateway.
- **Los errores usan `application/problem+json` (RFC 9457)**, con la extensión `code` que T05 usa
  para decidir qué mostrarle al alumno (D25).
- **La idempotencia de entrada es obligatoria**, no opcional: todo `POST` exige el header
  `Idempotency-Key`, que es la contraparte de que el outbox garantice entrega *at least once* y no
  *exactly once* (`06-api.md` §5).

**Lo que el sandbox no hace, y que es una decisión tan propia como las anteriores.** No valida
tokens ni resuelve autenticación por su cuenta: esa responsabilidad es del Gateway y de la
plataforma, y el sandbox no la duplica. Tampoco decide el enrutamiento —qué instancia de la `api`
atiende cada request—, que es infraestructura compartida con los otros once servicios. Lo propio es
exclusivamente la superficie que la `api` decide exponer puertas adentro de esa configuración
compartida.

## BFF

No aplica. Un BFF adapta un backend a las necesidades de un cliente concreto de interfaz —agrega,
recorta y da forma a una vista para una pantalla—. El sandbox no tiene cliente de interfaz: su
único consumidor es otro microservicio (T05), que recibe salida cruda y decide él mismo cómo
mostrarla. Si existiera un BFF para la pantalla del alumno, sería de T05, no del sandbox.

---

## Con qué liderar en la defensa

1. **El sidecar ejecutor** — resuelve un problema de seguridad real y específico: reemplaza la API
   de Docker en vez de filtrarla, y por eso no hereda la clase de bug que rompe a un proxy con
   lista blanca.
2. **Health Check con la distinción saturado/enfermo** — demuestra entender la diferencia entre "el
   proceso vive" y "el servicio puede aceptar trabajo".
3. **La anti-corruption layer del worker** — DDD aplicado a algo concreto: la traducción del sobre
   de la capa 1 a los seis estados técnicos, no una cita de manual.
4. **La regla que atraviesa el diseño de estados:** ante cualquier duda de plataforma, el resultado
   es `INTERNAL_ERROR`, que nunca consume un intento del alumno.

## Afirmaciones deliberadas

- **"BFF no aplica: el consumidor del sandbox es otro microservicio, no una pantalla."**
- **"No somos participante de saga porque no tenemos nada que compensar. Somos un paso de la
  coreografía, y nuestro aporte es idempotencia y no dejar ninguna ejecución sin resolver."**
- **"El Circuit Breaker está diseñado pero fuera del MVP: ninguna historia de usuario lo incluye, y
  preferimos decirlo así antes que mostrar un breaker decorativo."**
