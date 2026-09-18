# Contexto de `ms-sandbox`

> Qué es el sandbox dentro de la plataforma, qué garantiza y qué no decide, la frontera con T05,
> el stack del servicio y el glosario de los términos técnicos que usan los demás documentos.

## 1. Qué es

T06 no es un microservicio de dominio: es infraestructura que ejecuta código de terceros en
aislamiento y devuelve salida cruda (D22, `README.md`). No tiene entidades de negocio propias: no
sabe qué es un alumno, un curso ni una nota. Su contrato es una función: recibe un bundle
autocontenido y un perfil, y devuelve el resultado técnico de haberlo ejecutado.

En términos de diseño de dominio, es un subdominio genérico: el problema que resuelve —ejecutar
código de terceros de forma aislada y devolver un resultado— no es exclusivo de esta plataforma ni
encapsula ninguna ventaja competitiva propia. Eso no lo hace menos importante para el sistema, lo
ubica: a cambio, es el servicio con el bounded context más limpio de los doce, porque no comparte
ninguna entidad con el resto. El detalle de esa lectura, aplicada patrón por patrón, está en
`07-patrones.md` (DDD).

**Lo que garantiza:**

- Aislamiento del código que corre: sin red, rootfs de solo lectura, límites de memoria, CPU y
  procesos — ver `03-aislamiento.md`.
- Un contrato fijo de recursos por perfil, elegido de un catálogo cerrado y nunca escrito por
  quien llama (D18) — ver `03-aislamiento.md` §2.
- Salida cruda: `stdout`, `stderr`, los reportes de la herramienta del perfil y su `exitCode`, sin
  interpretación — ver `05-worker.md` §5.
- Aviso de finalización por evento (`ExecutionCompleted`, D20) y consulta de estado por polling —
  ver `06-api.md` §2.

**Lo que no decide:**

- Si una entrega está bien o mal. El sandbox nunca emite un veredicto (D22): interpretar la salida
  cruda es responsabilidad de T05.
- Cuántas vidas o intentos consume una entrega, ni cuánta experiencia otorga: eso vive en T05 y en
  el resto de la plataforma.
- Qué significan `courseId` o `studentId`: viajan como correlación opaca (§3).

## 2. El sandbox en la plataforma

La plataforma tiene doce servicios, uno por tema del curso, agrupados en capas. La tabla completa
condensa el mapa del panorama de microservicios; alcanza para ubicar a T06 dentro del conjunto:

| Capa | Servicio | Grupo |
|---|---|---|
| Borde / plataforma | API Gateway | T01 |
| Borde / plataforma | Infraestructura común (broker, configuración, observabilidad) | T11 + común |
| Identidad y contexto | `ms-identidad` | T01 |
| Identidad y contexto | `ms-cursos` | T02 |
| Contenido y evaluación | `ms-desafios` | T03 |
| Contenido y evaluación | `ms-teoricos` | T04 |
| Contenido y evaluación | `ms-practicos` | T05 |
| Contenido y evaluación | **`ms-sandbox`** | **T06** |
| Contenido y evaluación | `ms-eval-ia` | T07 |
| Economía y gamificación | `ms-banco` | T08 |
| Economía y gamificación | `ms-mercado` | T09 |
| Economía y gamificación | `ms-progreso` | T10 |
| Interacción y gobierno | `ms-social` | T11 |
| Interacción y gobierno | `ms-backoffice` | T12 |

`ms-sandbox` está en la capa de contenido y evaluación, junto a los servicios que sí modelan
dominio educativo. La diferencia es estructural: los demás servicios de esa capa tienen entidades
propias (una entrega, un ítem teórico, una rúbrica); T06 no. Es un pool de cómputo con un
contrato, sin CRUD y sin reglas de negocio propias.

La partición del curso en doce temas es temática, no una descomposición por bounded context: cada
grupo se queda con un tema, y algunos temas no son un servicio de dominio. `ms-sandbox` es uno de
esos casos. Eso importa para el diseño porque las decisiones que en un servicio de dominio serían
reglas de negocio, acá son propiedades de infraestructura: aislamiento, límites de recursos y
contrato de ejecución, nada de eso modela una entidad propia de la plataforma.

**Reglas de plataforma que afectan a T06:**

| Regla | Qué implica para el sandbox |
|---|---|
| El API Gateway es la única puerta de entrada | T05 nunca llega al sandbox por otro camino, y el sandbox nunca llama a otro servicio directamente |
| No hay comunicación directa entre microservicios | El sandbox no conoce a T05 de forma sincrónica salvo a través del Gateway; con el worker y el ejecutor tampoco: son componentes internos, no otros microservicios |
| Cada servicio es dueño exclusivo de su base | La `api` es la única dueña del esquema de `ms-sandbox`; el worker solo tiene permisos acotados (`05-worker.md` §6) |
| Lo asincrónico viaja por el bus de eventos de la plataforma | El aviso de finalización (`ExecutionCompleted`) sale por ese bus, nunca por HTTP directo a T05 |

**Sincrónico vs. asincrónico.** La llamada de T05 al sandbox es asincrónica de punta a punta: el
`POST` responde `202` de inmediato, sin ejecutar nada en el camino del request, y T05 se entera del
resultado por dos vías complementarias — polling contra `GET /api/sandbox/executions/{id}` y el
evento `ExecutionCompleted` en el bus (D20). Ninguna ruta del sandbox puede quedar bloqueada
mientras un contenedor corre: una ejecución puede tardar varios segundos, y bloquear un hilo del
Gateway ese tiempo afecta a los demás servicios que comparten esa puerta de entrada.

Dentro del propio sandbox, esa misma regla se repite un nivel más abajo: la `api` no ejecuta nada
en el camino del request, solo valida y persiste; ejecutar es tarea del worker y del ejecutor, que
corren en un proceso separado y se comunican con la `api` únicamente a través de la base de datos y
del outbox. Ninguna capa del sandbox bloquea a la que tiene encima esperando a que termine un
contenedor.

## 3. La frontera con T05

T05 (Desafíos Prácticos) es el único consumidor del sandbox. La frontera entre los dos está
definida por quién decide qué:

| Responsabilidad | De quién |
|---|---|
| Escribir los tests y las reglas de análisis estático | T05 |
| Construir el bundle (código del alumno + tests o configuración del perfil) | T05 |
| Elegir el perfil (`java21-junit`, `java21-pmd`, `java21-checkstyle`) | T05, de un catálogo cerrado que el sandbox controla |
| Validar el bundle, aislar la ejecución, ejecutar y devolver salida cruda | Sandbox |
| Interpretar la salida cruda (reporte, `exitCode`, `stdout`/`stderr`) | T05 |
| Decidir si una entrega consume un intento o una vida, y qué ve el alumno | T05 |

`courseId` y `studentId` viajan en el request como correlación opaca: el sandbox los persiste y los
indexa, pero no valida su dominio contra ningún otro servicio y no sabe qué representan más allá de
esos dos campos. `studentId` cumple además un rol técnico propio: es la clave con la que se cuenta
el tope de ejecuciones simultáneas por alumno (`STUDENT_LIMIT_EXCEEDED`, `06-api.md` §4).

Una regla cruza toda la frontera: `INTERNAL_ERROR` nunca consume un intento del alumno. Es el
estado que agrupa toda falla de plataforma —del sandbox, del daemon, de la cola de mensajes— y
ninguna de esas causas es atribuible a la entrega (`05-worker.md` §5).

El filtrado de tests por visibilidad —qué parte de un reporte le llega al alumno y qué parte queda
reservada para el profesor— queda fuera del MVP (`README.md`, "Fuera del MVP"): hoy el sandbox
devuelve el reporte completo y es T05 quien decide qué mostrar.

Esta frontera es la que mantiene al sandbox como subdominio genérico: cualquier decisión que
requiera saber qué es un curso, un alumno o una nota queda del lado de T05. El sandbox solo sabe
ejecutar, aislar y reportar de forma cruda; interpretar esa salida en términos académicos es un
problema de dominio que no le corresponde resolver.

## 4. Stack

El stack lo fija la cátedra para los doce servicios del curso; `ms-sandbox` lo hereda salvo donde
una decisión propia lo especializa:

| Capa | Elección |
|---|---|
| Lenguaje | Java 21 (LTS) |
| Framework | Spring Boot 3.x (Web, Data JPA, Validation, Actuator) |
| Base de datos | PostgreSQL, una instancia por servicio |
| Resiliencia | Resilience4j |
| Observabilidad | Micrometer + OpenTelemetry |
| Empaquetado | Docker + `docker-compose` |
| Pruebas | JUnit 5 + Testcontainers |

**El ejecutor.** Se implementa en Java 21 con `docker-java` como cliente de la API de Docker (D9);
se descartó una implementación paralela en Node porque duplicaba el componente sin ganancia neta.
El detalle del protocolo del ejecutor y su contrato con el worker está en `04-ejecutor.md`.

**Brokers.** El sandbox usa dos, con roles distintos:

| Broker | Rol | Quién lo mantiene |
|---|---|---|
| RabbitMQ | Cola de trabajo interna: reparte ejecuciones entre workers, con ack por mensaje y DLQ | El propio sandbox |
| Kafka | Bus de eventos de la plataforma: transporta `ExecutionCompleted` hacia T05 | El grupo de notificaciones |

`ExecutionCompleted` lleva únicamente `executionId` y `status` (D20): la salida puede pesar hasta
8 MiB, muy por encima de lo que conviene poner en un mensaje del bus. El detalle de por qué
RabbitMQ para el trabajo y Kafka para el evento está en `05-worker.md` §2.

**Java dentro de Java.** El ejecutor y el contenedor de `java21-junit` corren los dos sobre la
JVM, lo que obliga a dimensionar la memoria del contenedor de ejecución contando heap, metaspace y
las pilas de los hilos, no solo el heap. Con la JVM bien configurada, quien se queda sin memoria es
la JVM y no el cgroup, y ese agotamiento llega como código de salida `3` con `OOMKilled: false`, no
como el `137` que uno esperaría de una muerte por el cgroup. El detalle completo —el porcentaje de
heap configurado sobre el límite del contenedor y los dos caminos de memoria agotada— está en
`03-aislamiento.md` §9 y §8.

**Pruebas.** Testcontainers levanta PostgreSQL y RabbitMQ reales en los tests de integración de la
`api` y del worker, igual que en el resto de los servicios del curso; el ejecutor, además, tiene su
propia suite contra un daemon de Docker real, descripta en `04-ejecutor.md` §13. Resilience4j está
disponible en el stack, pero el único uso diseñado para el sandbox —un circuit breaker del worker
hacia el ejecutor— queda fuera del alcance del MVP (`07-patrones.md`, Circuit Breaker).

## 5. Glosario

| Término | Significado |
|---|---|
| **Bundle** | El paquete autocontenido que T05 manda en el request: código del alumno más los archivos que exige el perfil (tests o configuración de análisis estático) |
| **Perfil** | Un identificador de un catálogo cerrado (`java21-junit`, `java21-pmd`, `java21-checkstyle`) que fija imagen, memoria, CPU y timeouts de una ejecución |
| **Capa 1** | El entrypoint de la imagen del contenedor: valida el tar, invoca la capa 2, detecta procesos sobrevivientes y arma el sobre de salida |
| **Capa 2** | El guion propio de cada perfil, que corre dentro de la capa 1: compila y prueba, o corre el análisis estático |
| **Sobre** | El objeto JSON (`sandbox.layer1/v3`) que la capa 1 emite entre los marcadores del nonce, con el resultado, los reportes y los tiempos medidos |
| **Nonce** | Un valor aleatorio de 16 bytes, distinto por ejecución, que delimita el sobre y que el código del alumno nunca puede ver ni producir |
| **Buzón de reportes** | El directorio (`$SANDBOX_REPORTS`) donde la capa 2 deja lo que produce; todo lo que queda ahí se empaqueta en el sobre |
| **Outbox** | La tabla donde la `api` y el worker insertan, en la misma transacción que su escritura de negocio, el mensaje pendiente de publicar |
| **Relay** | El proceso, dentro de la `api`, que lee el outbox y publica cada mensaje según su tipo: el trabajo a la cola interna, `ExecutionCompleted` al bus de la plataforma |
| **Slot** | Un cupo de ejecución concurrente, limitado tanto en el worker como en el ejecutor a la misma cantidad |
| **Watchdog** | El mecanismo del worker que detecta una ejecución estancada en `RUNNING` y la marca `INTERNAL_ERROR` |
| **DLQ** | La cola de mensajes fallidos: un mensaje que agotó sus reintentos termina ahí en vez de reencolarse indefinidamente |
| **Salida cruda** | `stdout`, `stderr`, los reportes de la herramienta del perfil y el `exitCode`, tal como los produjo la ejecución, sin ninguna interpretación de negocio |
| **`QUEUED`** | La ejecución fue aceptada y espera turno |
| **`RUNNING`** | El worker tomó el job y el contenedor está corriendo |
| **`COMPLETED`** | La herramienta del perfil terminó, cualquiera sea su resultado |
| **`TIMEOUT`** | Se agotó el presupuesto de CPU del alumno o su respaldo de pared |
| **`MEMORY_LIMIT`** | La ejecución se quedó sin memoria, por cualquiera de los dos caminos posibles |
| **`INTERNAL_ERROR`** | Falla de plataforma, nunca de la entrega: nunca consume un intento del alumno |

## Abierto

| # | Pregunta | Quién la cierra |
|---|---|---|
| — | El panorama de microservicios original nombraba a RabbitMQ como el broker único de toda la plataforma. Que el bus de eventos de la plataforma sea Kafka (D20) tiene que confirmarse con la cátedra y con el grupo de notificaciones | Nosotros, con la cátedra y el grupo de notificaciones |
| **A2** | Memoria, CPU y timeouts de `java21-pmd` y `java21-checkstyle` | Nosotros, por medición |
| **A5** | ¿El botón "Ejecutar" del IDE pasa por el sandbox? | T05 |
| **A6** | Autenticación servicio a servicio entre T05 y el sandbox a través del Gateway | T05 y la plataforma |
