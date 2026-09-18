# `ms-sandbox`: arquitectura

> **Tema 06 — Sandbox / Runtime.** Grupo 8, 11 integrantes.
> UTN FRC · Programación 4 + Metodología de Sistemas 2 · TPI 2026.
>
> Índice de la documentación de arquitectura y **tabla única de decisiones**: qué está decidido,
> qué sigue abierto y qué queda fuera del MVP. Cada documento es la fuente del detalle; este
> archivo es la fuente del **estado**.

El alcance del MVP lo fijan las [historias de usuario](../historias-usuario/mvp-historias-usuario.md)
y sus [tareas](../historias-usuario/mvp-tareas.md). Si esta documentación las contradice, mandan
las historias.

---

## 1. Documentos

| Documento | Qué cubre |
|---|---|
| [`01-contexto.md`](./01-contexto.md) | Qué es el sandbox dentro de la plataforma, la frontera con T05 y el glosario |
| [`02-arquitectura.md`](./02-arquitectura.md) | Vista general con diagramas: API, cola de trabajo, worker, ejecutor, imágenes y bus de eventos |
| [`03-aislamiento.md`](./03-aislamiento.md) | Spec del contenedor, catálogo de tres perfiles, límites y relojes, amenazas contenidas y deuda conocida |
| [`04-ejecutor.md`](./04-ejecutor.md) | Spec del ejecutor: protocolo con el worker, spec del contenedor, timeouts y criterios de aceptación |
| [`05-worker.md`](./05-worker.md) | Cola de trabajo, DLQ, concurrencia, escritura del resultado en la base, watchdog y emisión de `ExecutionCompleted` |
| [`06-api.md`](./06-api.md) | Endpoints, errores, idempotencia, outbox y relay, y modelo de datos |
| [`07-patrones.md`](./07-patrones.md) | Patrones de microservicios de la materia aplicados al servicio |

**Estado del código.** Los tres módulos del MVP (`api`, `worker` y `ejecutor`) se construyen desde
cero. El código de [`ms-sandbox/`](../../ms-sandbox/README.md) es un prototipo: valida el
aislamiento y el ejecutor contra Docker real, pero no es el entregable.

---

## 2. Decisiones cerradas

| # | Decisión | Por qué | Detalle |
|---|---|---|---|
| **D1** | **El ejecutor es un sidecar que arma él mismo la spec del contenedor.** El worker no habla con Docker | El worker nunca tiene acceso al socket de Docker. Se descartó un proxy del socket porque deja la spec en manos del cliente | `04` |
| **D2** | **El relay del outbox corre sólo en la `api` y enruta por tipo de mensaje:** el trabajo va a la cola interna y `ExecutionCompleted` al bus de eventos de la plataforma | La `api` es dueña del esquema; el worker inserta filas en el outbox pero no publica | `06`, `05` |
| **D3** | **Versión de la API de Docker fija en `v1.43`** | El mínimo de la API varía entre builds del Engine. Verificado contra Docker real | `04` |
| **D4** | **El worker llega al ejecutor por socket Unix, y el ejecutor a Docker sólo por `unix://` o `npipe://`.** TCP está prohibido | El cierre abortivo del canal adjunto por TCP puede truncar `stdin` | `04` |
| **D5** | **El bundle entra al contenedor por `stdin`, como un tar**, nunca por `docker cp` ni por red | Saca del camino el endpoint más peligroso de Docker | `03`, `04` |
| **D8** | **Un solo estado `TIMEOUT`, medido en tiempo de CPU del alumno** | Medir en CPU y no en reloj de pared elimina los timeouts intermitentes por contención del host | `03` |
| **D9** | **El ejecutor se implementa en Java 21** | Se descartó la implementación paralela en Node | `04` |
| **D14** | **Un único documento para la API** | Las dos versiones anteriores contaban lo mismo en dos registros | `06` |
| **D15** | **2 CPU por contenedor en `java21-junit`** | Medido de punta a punta: el mismo bundle tarda 3,3 s con 2 CPU y 8,4 s con 1 | `03` |
| **D17** | **Catálogo estático de tres perfiles** (`java21-junit`, `java21-pmd`, `java21-checkstyle`), cargado al arranque, sin CRUD y sin versión en el identificador | Alcanza para el MVP y no expone administración | `03` |
| **D18** | **Los límites viven en el perfil, nunca en el request.** Presupuestos separados de compilación y de tests | T05 no puede pedir más recursos de los que el sandbox decide dar | `03` |
| **D20** | **El resultado se consulta por polling y se avisa con `ExecutionCompleted`**, que lleva sólo `executionId` y `status` por el bus de eventos de la plataforma (Kafka). La salida cruda se trae con `GET` | La salida puede pesar 8 MiB; el aviso tiene que ser chico. El bus lo mantiene el grupo de notificaciones | `05`, `06` |
| **D22** | **El sandbox devuelve salida cruda y no emite veredicto.** T05 interpreta los reportes | El sandbox ejecuta; decidir si una entrega aprueba es dominio de T05 | `03`, `05` |
| **D23** | **Seis estados técnicos:** `QUEUED`, `RUNNING`, `COMPLETED`, `TIMEOUT`, `MEMORY_LIMIT` e `INTERNAL_ERROR` | Sin estados de negocio: `COMPLETED` significa que la herramienta terminó, no que aprobó | `05`, `06` |
| **D24** | **El worker escribe `RUNNING` y el estado terminal directamente en la base, antes del `ack`.** Tiene un usuario limitado a esas columnas y a insertar en el outbox | Evita un canal de resultados de vuelta hacia la `api` | `05` |
| **D25** | **Errores en `application/problem+json` (RFC 9457)** con la extensión `code` | Ni la cátedra ni T05 fijaron un formato común; se usa el estándar | `06` |
| **D26** | **`429` por cola llena (`QUEUE_FULL`, con `Retry-After`) y por tope de ejecuciones simultáneas del alumno (`STUDENT_LIMIT_EXCEEDED`)** | Saturado no es roto: la API rechaza sin declararse caída | `06` |

**A4** (cuántos perfiles arrancan) quedó cerrada por **D17**.

**A1** (¿el límite de CPU es por proceso o agregado?) quedó cerrada: el ulimit `cpu` es **por
proceso**, porque cada programa nuevo arranca su contador en cero. Es un freno contra un proceso
desbocado en cada fase, no un presupuesto. El techo agregado real es el reloj de pared del ejecutor
multiplicado por las CPU del perfil, y el presupuesto del alumno es el reloj de CPU de los tests. Ver
`03-aislamiento.md` (relojes).

---

## 3. Abierto

| # | Pregunta | Quién la cierra | Qué bloquea |
|---|---|---|---|
| **D12** | Paquete reservado de los tests del profesor | T05 | El rechazo `RESERVED_PACKAGE` (HU-03) |
| **D21** | Catálogo de imágenes base: quién las nombra, las versiona y aprueba una nueva, y si la base pasa a Alpine (el entrypoint es bash) | Nosotros | — |
| **A2** | Memoria, CPU y timeouts de `java21-pmd` y `java21-checkstyle` | Nosotros, por medición | El cierre de HU-07 |
| **A5** | ¿El botón "Ejecutar" del IDE pasa por el sandbox? | T05 | El dimensionamiento del pool |
| **A6** | Autenticación servicio a servicio entre T05 y el sandbox a través del Gateway | T05 y la plataforma | El cierre de HU-01 y HU-04 |
| **A7** | Formato final del bundle en el request y codificación de los archivos y de los reportes | T05 | HU-01 |
| **A8** | Estabilidad de la clave `Idempotency-Key` entre reintentos | T05 | HU-02 |
| **A9** | Nombre del topic de `ExecutionCompleted`, formato del sobre, autenticación, particiones y retención del bus | Grupo de notificaciones | El cierre de HU-10 |
| **A10** | Componente que hace la validación estructural del bundle (tipos de entrada del tar) | Nosotros | `T-06-11` |
| **A11** | Nombres físicos de la cola de trabajo, el exchange, la routing key y la DLQ | Nosotros | La declaración de la topología (HU-05) |

---

## 4. Fuera del MVP

| Tema | Por qué queda afuera |
|---|---|
| **Autenticidad de la salida cruda** (verificador de bytecode contra una lista blanca de APIs) | Es un desarrollo de varias semanas. **Deuda conocida:** el código del alumno corre en el mismo proceso que el runner de tests y puede fabricar su propio reporte o leer los tests ocultos. El MVP sirve para una demo, no para calificar a un alumno adversarial. Ver `03` |
| **Filtrado de tests por visibilidad** (antes **D10** y **D11**) | Falta que T05 defina si filtra el sandbox o filtra T05 sobre la respuesta completa |
| **Cacheo de la suite compilada** (antes **D13** y **P5**) | Depende de que T03 defina si una versión publicada de un desafío es inmutable |
| **CRUD de perfiles y catálogo dinámico** | El MVP usa el catálogo estático de **D17** |
| **Circuit Breaker del worker hacia el ejecutor** | Está diseñado, pero ninguna historia del MVP lo incluye. Ver `07-patrones.md` (Circuit Breaker) |

---

## 5. Pendientes técnicos

| # | Pendiente | Dónde se resuelve |
|---|---|---|
| **P2** | Casos hostiles a nivel tar (enlaces simbólicos y duros, FIFOs, dispositivos): la validación pasa a una lista blanca de **tipos** de entrada | HU-06 |
| **P3** | Suite hostil y pruebas contra Docker real en CI | Tareas de entorno y CI de HU-01 |
