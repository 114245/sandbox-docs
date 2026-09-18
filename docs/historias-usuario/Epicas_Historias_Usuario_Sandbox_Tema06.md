# TECNICATURA UNIVERSITARIA EN PROGRAMACIÓN
### UTN FRC — Sandbox / Runtime
**Tema 06:** Épicas e Historias de Usuario — Motor de Ejecución Aislado Sandbox
**Grupo 8:** Sandbox
**Trabajo Práctico Integrador:** Programación IV Back End

---

## Índice
1. [Guía de actualización en Taiga](#1-guía-de-actualización-en-taiga)
2. [Alcance del MVP](#2-alcance-del-mvp)
3. [Épicas e Historias de Usuario](#3-épicas-e-historias-de-usuario)
   - [3.1 Épica 1: Recepción de ejecuciones (API)](#31-épica-1-recepción-de-ejecuciones-api)
     - [HU-01: Enviar una entrega y recibir confirmación inmediata](#g08--hu-01--enviar-una-entrega-y-recibir-confirmación-inmediata)
     - [HU-02: Reintentar un envío sin que se ejecute dos veces](#g08--hu-02--reintentar-un-envío-sin-que-se-ejecute-dos-veces)
     - [HU-03: Recibir errores claros y estables ante una entrega inválida](#g08--hu-03--recibir-errores-claros-y-estables-ante-una-entrega-inválida)
     - [HU-04: Consultar el estado y el resultado de una ejecución](#g08--hu-04--consultar-el-estado-y-el-resultado-de-una-ejecución)
   - [3.2 Épica 2: Procesamiento asíncrono (cola y worker)](#32-épica-2-procesamiento-asíncrono-cola-y-worker)
     - [HU-05: Que la entrega se evalúe aunque haya un pico de envíos o se reinicie un servicio](#g08--hu-05--que-la-entrega-se-evalúe-aunque-haya-un-pico-de-envíos-o-se-reinicie-un-servicio)
   - [3.3 Épica 3: Ejecución aislada](#33-épica-3-ejecución-aislada)
     - [HU-06: Que el código del alumno no pueda llegar a la red, al host ni a otros servicios](#g08--hu-06--que-el-código-del-alumno-no-pueda-llegar-a-la-red-al-host-ni-a-otros-servicios)
     - [HU-07: Que cada ejecución respete los límites de CPU, memoria y tiempo del perfil](#g08--hu-07--que-cada-ejecución-respete-los-límites-de-cpu-memoria-y-tiempo-del-perfil)
     - [HU-08: Capturar y devolver la salida cruda del programa y el reporte de la herramienta](#g08--hu-08--capturar-y-devolver-la-salida-cruda-del-programa-y-el-reporte-de-la-herramienta)
     - [HU-09: Un chequeo de salud que distinga "saturado" de "roto" (opcional)](#g08--hu-09--un-chequeo-de-salud-que-distinga-saturado-de-roto)
4. [Fuera de alcance del MVP y deuda conocida](#4-fuera-de-alcance-del-mvp-y-deuda-conocida)

---

## 1. Guía de actualización en Taiga

> **Aviso:** la primera pasada de esta guía ya fue aplicada en Taiga. Los 12 ítems (las tres
> épicas y las nueve historias de este documento) ya existen como ítems propios, con sus IDs y
> títulos nuevos. Esta segunda pasada **no crea ningún ítem**: solo actualiza contenido en los 12
> ítems existentes y agrega las tareas de `mvp-tareas.md`. La guía de la primera pasada se eliminó:
> indexaba por títulos de Taiga que ya no existen, y seguirla ahora duplicaría ítems.

### Épicas de la segunda pasada

| Ítem en Taiga | Acción | Qué se corrige |
|---|---|---|
| Épica 1: Recepción de ejecuciones (API) | Actualizar | estimación agregada de la épica; suposiciones: el request trae archivos con rol (solución, test o configuración), identificador de perfil e identificadores de alumno y de curso, y no trae límites |
| Épica 2: Procesamiento asíncrono (cola y worker) | Actualizar | objetivo y CA épicos: declarar que el alcance incluye construir el worker como componente, que el worker escribe el estado y el resultado directamente en la base, y que la cola llena se rechaza con `429`; dependencias e impacto en datos |
| Épica 3: Ejecución aislada | Actualizar | objetivo, suposiciones y CA épicos: declarar que el alcance incluye construir el ejecutor, las imágenes de ejecución y el catálogo estático de tres perfiles, y que se devuelve salida cruda sin veredicto |

### Historias de la segunda pasada

| Historia | Épica | Acción | Qué se corrige | Puntos (antes → ahora) |
|---|---|---|---|---|
| HU-01 — Enviar una entrega y recibir confirmación inmediata | Épica 1 | Actualizar | notas y estimación: el puntaje incluye construir el módulo `api`; reglas de negocio, validaciones y datos obligatorios: validación del contenido mínimo según el perfil, roles solución/test/configuración, extensiones por rol, sin límites en el request, identificador de alumno; CA2 (`INCOMPLETE_BUNDLE`), CA3 (solo perfil desconocido), CA5 nuevo (`STUDENT_LIMIT_EXCEEDED`); BDD escenarios 1 y 2 | 5 → 8 |
| HU-02 — Reintentar un envío sin que se ejecute dos veces | Épica 1 | Actualizar | seguridad: requisito de diseño del ejecutor sin comparación con comportamiento actual | 3 → 3 |
| HU-03 — Recibir errores claros y estables ante una entrega inválida | Épica 1 | Actualizar | notas y riesgos: `INVALID_PATH` como alcance y validación de ruta explícita, sin referencia a estado de implementación; lista de códigos (se quitan `LIMITS_OUT_OF_RANGE`, `BUNDLE_WITHOUT_TESTS`, `UNKNOWN_SUITE_VERSION` y `EXECUTION_NOT_CANCELABLE`; entra `INCOMPLETE_BUNDLE`); CA2 (extensión por rol), CA3 (solo `java21-junit`), se elimina el CA de versión de suite y el anterior CA5 pasa a CA4; se elimina el escenario 3 del BDD | 3 → 3 |
| HU-04 — Consultar el estado y el resultado de una ejecución | Épica 1 | Actualizar | reglas de negocio y datos obligatorios: estado puramente técnico, máquina de seis estados (`QUEUED`, `RUNNING`, `COMPLETED`, `TIMEOUT`, `MEMORY_LIMIT`, `INTERNAL_ERROR`) y salida cruda sin veredicto | 2 → 2 |
| HU-05 — Que la entrega se evalúe aunque haya un pico o se reinicie un servicio | Épica 2 | Actualizar | notas y estimación: el puntaje incluye construir el módulo `worker`; reglas de negocio: el worker escribe `RUNNING` y el estado final con la salida cruda directamente en la base antes de confirmar el mensaje; CA6 nuevo y escenario 4 del BDD: cola llena con `429` `QUEUE_FULL` y `Retry-After` | 5 → 13 |
| HU-06 — Que el código del alumno no pueda llegar a la red, al host ni a otros servicios | Épica 3 | Actualizar | validaciones y riesgos: lista blanca de tipos de entrada y validación programática; notas y estimación: el puntaje incluye construir el módulo `ejecutor` | 8 → 13 |
| HU-07 — Que cada ejecución respete los límites de CPU, memoria y tiempo del perfil | Épica 3 | Actualizar | catálogo estático de tres perfiles sin CRUD (`java21-junit`, `java21-pmd`, `java21-checkstyle`) en vez de un único perfil hardcodeado; notas y estimación: el puntaje sube por las dos imágenes nuevas y la extensión del catálogo | 8 → 13 |
| HU-08 — Capturar y devolver la salida cruda del programa y el reporte de la herramienta | Épica 3 | Actualizar | ya no hay clasificador de veredictos ni estado `EARLY_EXIT`: el sandbox devuelve salida cruda capada, y T05 interpreta el resultado; notas y estimación: el puntaje baja al sacar el verificador de reporte y el clasificador | 8 → 5 |
| HU-09 — Un chequeo de salud que distinga "saturado" de "roto" | Épica 3 | Actualizar | seguridad: requisito del chequeo de salud, sin referencia a un chequeo actual; CA2: el `429` de cola llena pasa a HU-05 y esta historia solo verifica que la salud siga en `UP` | 3 → 3 |

Ninguna historia cambia de épica en esta segunda pasada (el movimiento de HU-05 a la Épica 2 ya
se aplicó en la primera pasada).

### Pasos a aplicar

> **Advertencia:** cargar las tareas de `mvp-tareas.md` recién al final. Si se cargan antes de
> terminar de editar las historias, quedan asociadas a contenido que todavía va a cambiar.

1. Actualizar el objetivo y los criterios de aceptación a nivel épico de las Épicas 2 y 3.
2. Actualizar la estimación agregada de las tres épicas.
3. Editar las nueve historias: descripción, notas, criterios de aceptación y estimación nueva.
4. Cargar las tareas de cada historia (ver `mvp-tareas.md`).

### Fuera del MVP (para no perder información)

Dos piezas de lo que el equipo ya había cargado en Taiga no forman parte del alcance MVP en su
forma original, y no hay que perderlas de vista para retomarlas más adelante:

- **El alta, baja y modificación de perfiles por API (CRUD) y el catálogo dinámico con ciclo de
  vida propio** (antes HU01: alta de perfiles con ciclo de vida propio y creación de una versión
  nueva por cada cambio) no están en el MVP. El MVP usa un catálogo **estático** de tres perfiles
  fijos (`java21-junit`, `java21-pmd`, `java21-checkstyle`), versionado junto con las imágenes y
  cargado al arranque, sin operaciones de administración expuestas (ver HU-07). El CRUD de
  perfiles y el catálogo dinámico quedan para una etapa posterior.
- **La verificación fina de evidencia de ejecución según un formato de reporte configurable**
  (antes parte de HU06) tampoco está en el MVP. El MVP ya no incluye ninguna clasificación de
  veredicto, ni siquiera básica: el sandbox se limita a capturar y devolver la salida cruda
  (salida estándar, de error y los reportes de la herramienta del perfil), con tope de tamaño y
  límites de desempaquetado (ver HU-08). La interpretación de esa salida, incluida la distinción
  entre éxito y fallo, es responsabilidad de T05; la verificación de que la salida cruda sea
  auténtica (que no haya sido fabricada por el propio código del alumno) queda fuera del MVP y se
  documenta como deuda conocida (ver sección 4).

---

## 2. Alcance del MVP

`ms-sandbox` no tiene usuarios humanos directos: su único consumidor es T05 (Desafíos Prácticos),
que a su vez le entrega feedback al alumno. El alcance MVP cubre tres frentes: recibir una entrega
por API de forma asíncrona y confiable, procesarla con una cola y un worker resilientes a picos y
reinicios, y ejecutarla en un contenedor aislado que respeta límites de recursos y devuelve la salida
cruda de la herramienta del perfil, sin emitir un veredicto. Los identificadores técnicos (rutas, códigos de error, nombres de eventos,
campos y estados) están en inglés; el texto descriptivo está en español. Todo dato marcado
`[A DEFINIR]` es un campo que todavía no tiene una respuesta fijada y que el equipo debe cerrar
antes de dar la historia por completa.

---

## 3. Épicas e Historias de Usuario

---

### 3.1 Épica 1: Recepción de ejecuciones (API)

> **Taiga:** actualiza la Épica 1 (ya creada en Taiga; ajusta la estimación agregada de la épica)

#### Objetivo
Proveer la interfaz HTTP de ingreso de entregas del alumno hacia el sandbox, aceptándolas de forma
asíncrona y confiable mediante un outbox transaccional, garantizando idempotencia ante reintentos
de red, devolviendo errores claros y estables cuando una entrega es inválida, y permitiendo
consultar en cualquier momento el estado y el resultado de una ejecución.

#### Suposiciones y Restricciones
* **Suposiciones:** T05 (Desafíos Prácticos) es el único consumidor de esta API; en cada solicitud
  envía los archivos del bundle con un rol declarado por archivo (solución, test o configuración),
  el identificador del perfil y los identificadores de alumno y de curso como correlación, sin
  límites de recursos (los define el perfil); toda llamada pasa por el API Gateway.
* **Restricciones (técnicas):** la API responde en el orden de milisegundos sin ejecutar código
  durante el request; toda escritura de negocio y su publicación al broker de mensajería viajan en
  la misma transacción de base de datos (outbox transaccional); el alcance MVP usa un catálogo
  estático de perfiles de ejecución (ver Épica 3), sin API de administración (CRUD).

#### Criterios de Aceptación a nivel Épico
* Toda entrega válida se acepta con `202` y queda persistida junto con su mensaje de outbox en la
  misma transacción (HU-01).
* Un reintento de red con la misma `Idempotency-Key` nunca dispara una segunda ejecución (HU-02).
* Toda entrega inválida se rechaza con un código de error estable en formato
  `application/problem+json` (HU-03).
* El estado y, cuando esté disponible, el resultado de cualquier ejecución pueden consultarse por
  polling en cualquier momento posterior a su aceptación (HU-04).

**Estimación agregada de la épica:** 16 puntos (HU-01: 8, HU-02: 3, HU-03: 3, HU-04: 2).

#### Dependencias / Impactos
* **Servicios / APIs:** API Gateway, T05, base de datos, broker de mensajería.
* **Módulos afectados:** capa REST de recepción de entregas, publicador de outbox y su tabla,
  proceso relay periódico, repositorio de ejecuciones.
* **Otros equipos:** T05 (confirmación del contrato de request, del paquete reservado de tests y
  de la autenticación servicio a servicio).
* **Impacto en datos / migraciones:** tablas de ejecuciones y de outbox, con una restricción de
  unicidad para la idempotencia.
* **Feature toggles / flags:** No.

---

#### [G08] — HU-01 — Enviar una entrega y recibir confirmación inmediata

> **Taiga:** actualiza HU-01 (ya creada en Taiga; ajusta descripción, notas y estimación)

##### Descripción (Como / Quiero / Para)
- **Como:** T05 (Desafíos Prácticos)
- **Quiero:** enviar una entrega (los archivos que exige el perfil elegido, cada uno con su rol, y el identificador del perfil) al sandbox y recibir una confirmación inmediata de que fue aceptada
- **Para:** que el alumno no quede con la pantalla bloqueada esperando una respuesta sincrónica de varios segundos

##### Notas / Observaciones
- **Reglas de negocio:** el request se basta a sí mismo: trae todos los archivos que necesita el perfil elegido, el sandbox nunca le vuelve a preguntar nada a T05 mientras corre. El sandbox no tiene dominio de negocio: no valida los identificadores de alumno ni de curso contra nadie, solo los guarda como campos de correlación (el de alumno, además, para aplicar su tope de ejecuciones en simultáneo). El request no trae límites de recursos: los límites viven en el perfil.
- **Validaciones:** antes de tocar Docker se valida, en este orden, que el perfil pertenezca al catálogo, que el bundle traiga el contenido mínimo que exige ese perfil (para `java21-junit`, al menos un archivo con rol solución y uno con rol test; para `java21-pmd` y `java21-checkstyle`, al menos un archivo con rol solución y un archivo con rol configuración), que las rutas de archivo sean relativas, sin `..`, sin barra inicial y con la extensión permitida para su rol (`.java` para solución y test, `.xml` para configuración), y que el alumno no supere su tope de ejecuciones en simultáneo. Todo esto se resuelve con `4xx`/`429` sin gastar un contenedor.
- **Datos obligatorios:** los archivos del bundle, con un rol declarado por archivo (solución, test o configuración), el identificador del perfil, `Idempotency-Key` (ver HU-02) y los identificadores de alumno y de curso como correlación.
- **Performance (tiempos, volumen, límites):** la API debe responder en el orden de milisegundos: valida, persiste en la misma transacción (la ejecución y el mensaje de outbox transaccional) y responde `202`, sin esperar a que la ejecución termine. Dimensionamiento de referencia: pool de 4-6 ejecuciones concurrentes para 120 sesiones de plataforma.
- **Seguridad:** `[A DEFINIR]` — la autenticación servicio a servicio entre T05 y `ms-sandbox` (vía API Gateway) no está definida.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** la llamada es siempre a través del API Gateway; no hay comunicación directa entre microservicios. El outbox transaccional forma parte del alcance MVP de esta historia: sin él, "guardé pero no publiqué" es un estado alcanzable y la solicitud encolada nunca llegaría a correr.

##### Criterios de Aceptación (CA)
- **CA1:** ante un `POST` a `/api/sandbox/executions` válido, la API responde `202 Accepted` con `{ executionId, status: QUEUED, queuePosition }` en la misma transacción en la que persiste la ejecución y el mensaje de outbox.
- **CA2:** si el bundle no trae el contenido mínimo que exige el perfil elegido, la API responde `400` con código `INCOMPLETE_BUNDLE`, sin crear ningún contenedor.
- **CA3:** si el identificador de perfil no pertenece al catálogo, la API responde `422` con código `UNSUPPORTED_LANGUAGE`.
- **CA4:** si el broker de mensajería está caído en el momento del `POST`, la API igualmente responde `202` y persiste el mensaje en la tabla de outbox; el proceso relay lo publica cuando el broker vuelve, sin pérdida de entregas.
- **CA5:** si el alumno ya tiene en curso tantas ejecuciones como su tope en simultáneo, la API responde `429` con código `STUDENT_LIMIT_EXCEEDED`, sin encolar la entrega.
- **Extras (opcional):** el evento `ExecutionCompleted` que informa el resultado final viaja por el bus de eventos de la plataforma, no por el Gateway; su articulación exacta con el outbox interno de la cola de trabajo todavía no está resuelta con los demás grupos (ver cierre del documento).

##### BDD
**Característica:** Recepción y confirmación inmediata de una entrega

**Escenario 1: Entrega válida aceptada**
- **Dado:** que T05 arma un bundle para el perfil `java21-junit` con al menos un archivo con rol solución y uno con rol test
- **Cuando:** T05 hace `POST /api/sandbox/executions` con ese bundle
- **Entonces:** el sandbox responde `202 Accepted` con `executionId`, `status: QUEUED` y `queuePosition`, y la ejecución queda persistida junto con su mensaje de outbox en la misma transacción

**Escenario 2: Entrega incompleta para su perfil, rechazada antes de ejecutar**
- **Dado:** que el bundle enviado para el perfil `java21-pmd` no contiene ningún archivo con rol configuración
- **Cuando:** T05 hace `POST /api/sandbox/executions`
- **Entonces:** el sandbox responde `400` con código `INCOMPLETE_BUNDLE` y no se crea ningún contenedor ni registro de ejecución en curso

**Escenario 3: Confirmación entregada aunque el broker esté caído**
- **Dado:** que el broker de mensajería está temporalmente inaccesible pero la base de datos está disponible
- **Cuando:** T05 hace `POST /api/sandbox/executions` con un bundle válido
- **Entonces:** el sandbox responde `202 Accepted` igual, persiste la ejecución y el mensaje pendiente en la tabla de outbox, y el proceso relay lo publica automáticamente cuando el broker se recupera, sin que la entrega se pierda

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** `POST /api/sandbox/executions`, `GET /api/sandbox/executions/{id}`, `GET /languages`, `GET /actuator/health`.

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 8 | Must |

El puntaje incluye la construcción del módulo `api` como componente nuevo del servicio (empaquetado, imagen de contenedor y capa de entrada REST), no solo su configuración.

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (API), T05 (consumidor), API Gateway.
- **Módulos afectados:** se crea el módulo `api`, con su capa de entrada REST del controlador de ejecuciones, caso de uso que solicita la ejecución, repositorio de la ejecución, publicador de outbox y su tabla, proceso relay periódico.
- **Otros equipos / aprobaciones:** T05 debe confirmar el contrato de request (formato del bundle, identificador de perfil); ver definiciones abiertas al cierre de este documento.
- **Impacto en datos / migraciones:** creación de las tablas de ejecuciones y de outbox (con una restricción de unicidad para la idempotencia, ver HU-02).
- **Riesgos y mitigación:** doble escritura entre la base de datos y el broker de mensajería, mitigada con outbox transaccional; ver también HU-05 para la tolerancia a fallas del lado del worker.

---

#### [G08] — HU-02 — Reintentar un envío sin que se ejecute dos veces

> **Taiga:** actualiza HU-02 (ya creada en Taiga; ajusta seguridad)

##### Descripción (Como / Quiero / Para)
- **Como:** T05 (Desafíos Prácticos)
- **Quiero:** poder reintentar el envío de una entrega ante un corte de red sin que la ejecución se dispare dos veces
- **Para:** que un problema de red no le consuma una vida al alumno por una entrega que en realidad sí llegó a procesarse

##### Notas / Observaciones
- **Reglas de negocio:** la `Idempotency-Key` es obligatoria y su clave natural combina el identificador de la entrega con el número de intento. Es la contraparte obligatoria del outbox: como el outbox garantiza entrega *at-least-once* (y no *exactly-once*), la idempotencia en la entrada es lo que evita ejecutar dos veces.
- **Validaciones:** si la `Idempotency-Key` llega repetida con el mismo contenido, se devuelve `202` con la ejecución original (reintentar es seguro). Si llega repetida con contenido distinto, se devuelve `409`. Si la clave está ausente, se rechaza con `422` y código `MISSING_IDEMPOTENCY_KEY`.
- **Datos obligatorios:** header `Idempotency-Key` con clave natural `submissionId:attemptNumber` en cada `POST /api/sandbox/executions`.
- **Performance (tiempos, volumen, límites):** una restricción de unicidad sobre la clave de idempotencia actúa como portero en la base: un segundo request con la misma clave choca y se descarta sin efectos adicionales.
- **Seguridad:** el proceso ejecutor (el componente con acceso a Docker) no deduplica por su propio identificador de ejecución interno; la responsabilidad de la idempotencia de negocio es explícitamente del worker/API con la clave `submissionId:attemptNumber`, y el ejecutor debe construirse con un requisito de diseño: devolver un error explícito y distinguible ante un identificador de ejecución repetido.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** —

##### Criterios de Aceptación (CA)
- **CA1:** un `POST /api/sandbox/executions` sin header `Idempotency-Key` es rechazado con `422` y código `MISSING_IDEMPOTENCY_KEY`.
- **CA2:** un `POST /api/sandbox/executions` con una `Idempotency-Key` ya usada y el mismo contenido de bundle responde `202` devolviendo el `executionId` de la ejecución original, sin encolar una nueva ejecución.
- **CA3:** un `POST /api/sandbox/executions` con una `Idempotency-Key` ya usada pero con contenido de bundle distinto responde `409` con código `IDEMPOTENCY_CONFLICT`.
- **CA4:** a nivel de base de datos, existe una restricción de unicidad sobre la clave de idempotencia que impide dos registros de ejecución con la misma clave.
- **Extras (opcional):** el ejecutor responde un error explícito y distinto de un error de daemon genérico ante un identificador de ejecución repetido.

##### BDD
**Característica:** Idempotencia de reintentos de entrega

**Escenario 1: Reintento con la misma entrega devuelve la ejecución original**
- **Dado:** que T05 ya envió una entrega con `Idempotency-Key: submission-123:1` y recibió `202` con `executionId = E1`
- **Cuando:** T05 reintenta el mismo `POST` con la misma `Idempotency-Key` y el mismo contenido, por un timeout de red
- **Entonces:** el sandbox responde `202` con el mismo `executionId = E1` y no se crea una segunda ejecución ni un segundo job en la cola

**Escenario 2: Falta la clave de idempotencia**
- **Dado:** que T05 arma un `POST /api/sandbox/executions` sin el header `Idempotency-Key`
- **Cuando:** envía la entrega
- **Entonces:** el sandbox responde `422` con código `MISSING_IDEMPOTENCY_KEY` y no encola ninguna ejecución

**Escenario 3: Misma clave, contenido distinto**
- **Dado:** que ya existe una ejecución con `Idempotency-Key: submission-123:1`
- **Cuando:** T05 envía un nuevo `POST` con la misma clave pero un bundle de contenido diferente
- **Entonces:** el sandbox responde `409` con código `IDEMPOTENCY_CONFLICT` y no modifica la ejecución original

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** `POST /api/sandbox/executions` (header `Idempotency-Key`).

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 3 | Must |

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (API), `ms-sandbox` (ejecutor), T05.
- **Módulos afectados:** capa de validación de la API, tabla de ejecuciones (restricción de unicidad de idempotencia).
- **Otros equipos / aprobaciones:** T05 debe garantizar que la combinación de identificador de entrega y número de intento sea efectivamente estable entre reintentos.
- **Impacto en datos / migraciones:** columna de clave de idempotencia con índice único en la tabla de ejecuciones.
- **Riesgos y mitigación:** riesgo de doble ejecución por reintento de red, mitigado por la clave de idempotencia; riesgo residual de que el ejecutor no deduplique por su cuenta, mitigado declarando la idempotencia como responsabilidad del worker/API.

---

#### [G08] — HU-03 — Recibir errores claros y estables ante una entrega inválida

> **Taiga:** actualiza HU-03 (ya creada en Taiga; ajusta notas y riesgos)

##### Descripción (Como / Quiero / Para)
- **Como:** T05 (Desafíos Prácticos)
- **Quiero:** recibir errores claros, estables y en un formato estándar cuando una entrega es inválida
- **Para:** poder mostrarle al alumno qué tiene que corregir, en vez de un error genérico

##### Notas / Observaciones
- **Reglas de negocio:** los errores se devuelven en `application/problem+json` (RFC 9457, Problem Details), con un conjunto pequeño y estable de códigos. Cada respuesta incluye los campos estándar (`type`, `title`, `status`, `detail`, `instance`) y el campo de extensión `code` con el código estable, que es el que T05 usa para decidir qué mostrarle al alumno.
- **Validaciones:** el alcance MVP cubre, como mínimo, los códigos que corresponden a validaciones de entrada de la API: `UNSUPPORTED_LANGUAGE`, `INCOMPLETE_BUNDLE`, `INVALID_PATH`, `RESERVED_PACKAGE`, `MISSING_IDEMPOTENCY_KEY`, `IDEMPOTENCY_CONFLICT`, `QUEUE_FULL`, `STUDENT_LIMIT_EXCEEDED`, `EXECUTION_NOT_FOUND`.
- **Datos obligatorios:** cada respuesta de error incluye, como mínimo, el código estable y un `status` HTTP coherente con `application/problem+json`.
- **Performance (tiempos, volumen, límites):** las validaciones que producen estos errores ocurren antes de crear un contenedor, en el orden de milisegundos.
- **Seguridad:** el mensaje de error no debe filtrar detalle interno de implementación que pueda usarse para evadir validaciones.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** dentro del alcance de esta historia, `INVALID_PATH` es una de las validaciones de menor costo de construcción y mayor impacto, por lo que se prioriza tempranamente dentro de la implementación.

##### Criterios de Aceptación (CA)
- **CA1:** toda respuesta de error de validación de la API usa `Content-Type: application/problem+json` y trae un código estable perteneciente al conjunto documentado en esta historia.
- **CA2:** una entrega con una ruta de archivo absoluta, que contiene `..` o cuya extensión no corresponde a su rol es rechazada con `400` y código `INVALID_PATH`, antes de tocar Docker.
- **CA3:** en el perfil `java21-junit`, una entrega con un archivo de rol solución que declara el paquete reservado de los tests es rechazada con `400` y código `RESERVED_PACKAGE`.
- **CA4:** una consulta sobre una ejecución inexistente responde `404` con código `EXECUTION_NOT_FOUND`.
- **Extras (opcional):** —

##### BDD
**Característica:** Errores estables y comprensibles ante entregas inválidas

**Escenario 1: Ruta de archivo hostil rechazada**
- **Dado:** que un archivo del bundle tiene una ruta como `../../../opt/junit/junit.jar`
- **Cuando:** T05 hace `POST /api/sandbox/executions` con ese bundle
- **Entonces:** el sandbox responde `400` con `application/problem+json` y código `INVALID_PATH`, sin crear ningún contenedor

**Escenario 2: Paquete reservado usurpado**
- **Dado:** que un archivo con rol solución declara el mismo paquete que usan los tests del profesor
- **Cuando:** T05 hace `POST /api/sandbox/executions` con ese bundle
- **Entonces:** el sandbox responde `400` con código `RESERVED_PACKAGE`

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** `POST /api/sandbox/executions`.

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 3 | Must |

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (API), T05.
- **Módulos afectados:** capa de validación de la API, mapeo de excepciones a `application/problem+json`.
- **Otros equipos / aprobaciones:** T05 debe declarar cuál es el paquete reservado de los tests (definición abierta, ver cierre del documento).
- **Impacto en datos / migraciones:** ninguno directo.
- **Riesgos y mitigación:** la validación de rutas debe ser explícita en la API y no puede delegarse al comportamiento del desempaquetador de tar; se implementa `INVALID_PATH` como validación propia de la API.

---

#### [G08] — HU-04 — Consultar el estado y el resultado de una ejecución

> **Taiga:** actualiza HU-04 (ya creada en Taiga; revisión de coherencia)

##### Descripción (Como / Quiero / Para)
- **Como:** T05 (Desafíos Prácticos)
- **Quiero:** consultar el estado y, cuando esté disponible, el resultado de una ejecución mediante `GET /api/sandbox/executions/{id}`
- **Para:** poder mostrarle al alumno el feedback de su entrega

##### Notas / Observaciones
- **Reglas de negocio:** el alcance MVP de esta historia es **solo polling** vía `GET /api/sandbox/executions/{id}`. El registro de ejecuciones sigue siendo la fuente de verdad del estado y resultado que T05 consulta. El estado de la ejecución es puramente técnico: el sandbox no interpreta el reporte de tests ni emite un veredicto de negocio (éxito, fallo, salida anticipada); esa interpretación es responsabilidad de T05 sobre la salida cruda que expone HU-08.
- **Validaciones:** el `id` consultado debe existir; si no, `404` con código `EXECUTION_NOT_FOUND`.
- **Datos obligatorios:** `executionId`. La respuesta incluye como mínimo el estado de la máquina de estados de la ejecución, con seis estados, todos técnicos (`QUEUED`, `RUNNING`, `COMPLETED`, `TIMEOUT`, `MEMORY_LIMIT`, `INTERNAL_ERROR`).
- **Performance (tiempos, volumen, límites):** es una lectura directa sobre la tabla de ejecuciones, sin dependencia del worker en el momento de la consulta.
- **Seguridad:** `[A DEFINIR]` — no hay definición de autorización específica sobre quién puede consultar el estado de una ejecución más allá de que la única vía de acceso es a través del Gateway.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** el envío del evento `ExecutionCompleted` por el bus asincrónico (para no depender solo de polling) todavía no está resuelto con el panorama completo de eventos de la plataforma; por eso queda explícitamente fuera del alcance MVP de esta historia (ver sección de cierre).

##### Criterios de Aceptación (CA)
- **CA1:** un `GET /api/sandbox/executions/{id}` sobre una ejecución existente responde `200` con el estado actual de la ejecución.
- **CA2:** mientras la ejecución está en `QUEUED` o `RUNNING`, la respuesta no incluye un resultado final, solo el estado.
- **CA3:** una vez que la ejecución alcanza un estado terminal (por ejemplo `COMPLETED`, `TIMEOUT`, `MEMORY_LIMIT`), la respuesta incluye la salida cruda disponible (ver HU-08), sin que el sandbox agregue una interpretación de éxito o fallo.
- **CA4:** un `GET /api/sandbox/executions/{id}` sobre un `id` inexistente responde `404` con código `EXECUTION_NOT_FOUND`.
- **Extras (opcional):** notificación por evento (`ExecutionCompleted`) queda fuera de esta historia (ver cierre del documento).

##### BDD
**Característica:** Consulta de estado y resultado de una ejecución

**Escenario 1: Consulta de una ejecución en curso**
- **Dado:** que una ejecución fue aceptada y está en estado `RUNNING`
- **Cuando:** T05 hace `GET /api/sandbox/executions/{id}`
- **Entonces:** el sandbox responde `200` con `status: RUNNING` y sin resultado final

**Escenario 2: Consulta de una ejecución inexistente**
- **Dado:** un `executionId` que nunca fue creado
- **Cuando:** T05 hace `GET /api/sandbox/executions/{id}` con ese id
- **Entonces:** el sandbox responde `404` con código `EXECUTION_NOT_FOUND`

**Escenario 3: Consulta de una ejecución ya finalizada**
- **Dado:** que una ejecución terminó en estado `COMPLETED`
- **Cuando:** T05 hace `GET /api/sandbox/executions/{id}`
- **Entonces:** el sandbox responde `200` con `status: COMPLETED` y la salida cruda disponible (salida estándar, de error y reporte de la herramienta) para que T05 la interprete antes de mostrarle algo al alumno

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** `GET /api/sandbox/executions/{id}`.

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 2 | Must |

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (API), T05.
- **Módulos afectados:** capa de lectura de la API sobre la tabla de ejecuciones.
- **Otros equipos / aprobaciones:** ninguna adicional para el alcance de polling.
- **Impacto en datos / migraciones:** ninguno adicional a la tabla creada en HU-01.
- **Riesgos y mitigación:** ninguno relevante identificado en el alcance MVP de polling.

---

### 3.2 Épica 2: Procesamiento asíncrono (cola y worker)

> **Taiga:** actualiza la Épica 2 (ya creada en Taiga; ajusta objetivo y criterios de aceptación a nivel épico)

#### Objetivo
Garantizar que toda entrega aceptada por la API llegue a evaluarse aunque haya un pico de envíos
simultáneos o se reinicie un componente del sandbox, usando una cola de trabajo con confirmación
manual de mensajes y una cola de mensajes fallidos simple, de forma que ninguna entrega se pierda
ni se procese sin control. El alcance de esta épica incluye construir el worker como componente
del servicio, además de su cola durable, su confirmación manual de mensajes y su cola de mensajes
fallidos.

#### Suposiciones y Restricciones
* **Suposiciones:** la API ya persistió la ejecución y su mensaje de outbox (Épica 1) antes de que
  llegue a esta cola; el worker es el único componente que consume esos mensajes y coordina la
  ejecución aislada (Épica 3).
* **Restricciones (técnicas):** la cola de trabajo debe ser durable y los mensajes persistentes;
  el worker confirma un mensaje solo después de terminar de procesarlo, nunca al recibirlo; la
  cantidad de mensajes en vuelo por worker está limitada a sus slots de ejecución disponibles.

#### Criterios de Aceptación a nivel Épico
* El worker se construye como componente del servicio, con su propio empaquetado y su imagen de
  contenedor.
* Un pico de entregas simultáneas se absorbe en la cola sin que el servicio caiga ni pierda
  entregas.
* Si un worker muere a mitad de una ejecución, el mensaje se reencola automáticamente y otro
  worker lo retoma, sin intervención manual.
* Un mensaje que falla de forma reproducible se aísla en una cola de mensajes fallidos en vez de
  bloquear indefinidamente a los demás jobs, y la ejecución correspondiente queda marcada de forma
  que el alumno no quede esperando para siempre.
* El worker registra el paso a `RUNNING` y el estado final con la salida cruda directamente en la
  tabla de ejecuciones, antes de confirmar el mensaje.
* Con la cola llena, la API rechaza nuevas entregas con `429` y un tiempo de espera sugerido, sin
  que el servicio se declare caído.

**Estimación agregada de la épica:** 13 puntos (HU-05: 13).

#### Dependencias / Impactos
* **Servicios / APIs:** broker de mensajería, worker de Sandbox, base de datos del sandbox (el
  worker escribe el estado y el resultado de la ejecución), API de Sandbox (rechazo por cola llena).
* **Módulos afectados:** consumidor de jobs, configuración de la cola de trabajo y de su cola de
  mensajes fallidos, watchdog del worker.
* **Otros equipos:** ninguno directo; es responsabilidad interna del sandbox.
* **Impacto en datos / migraciones:** ninguna tabla nueva: el worker actualiza las columnas de
  estado y resultado de la tabla de ejecuciones creada en la Épica 1, con un usuario de base de
  datos limitado a esas columnas.
* **Feature toggles / flags:** No.

---

#### [G08] — HU-05 — Que la entrega se evalúe aunque haya un pico de envíos o se reinicie un servicio

> **Taiga:** actualiza HU-05 (ya creada y ya movida a la Épica 2; ajusta notas y estimación)

##### Descripción (Como / Quiero / Para)
- **Como:** alumno
- **Quiero:** que mi entrega se evalúe aunque haya un pico de envíos simultáneos o se reinicie algún componente del sandbox
- **Para:** no perder mi entrega ni tener que volver a intentarlo desde cero

##### Notas / Observaciones
- **Reglas de negocio:** el alcance MVP de esta historia es **confirmación manual del mensaje (ack manual)** y una **cola de mensajes fallidos (dead letter queue) simple**. El worker confirma un mensaje recién cuando terminó de procesarlo, nunca al recibirlo: la confirmación no dice "lo recibí", dice "lo terminé". El worker escribe directamente en la base de datos el paso a `RUNNING` y el estado terminal con la salida cruda, antes de confirmar el mensaje; la API es la dueña del esquema (sus migraciones) y el worker solo actualiza las columnas de estado y resultado de la tabla de ejecuciones.
- **Validaciones:** la cola de trabajo debe ser durable y los mensajes persistentes, con confirmaciones del publicador, para sobrevivir al reinicio del broker. La cantidad de mensajes que cada worker puede tener en vuelo sin confirmar se limita a la cantidad de slots de contenedor disponibles, para no acaparar toda la cola en la memoria de un solo worker.
- **Datos obligatorios:** cada mensaje de la cola lleva el `executionId` como identificador de correlación.
- **Performance (tiempos, volumen, límites):** con el límite de mensajes en vuelo igual a la cantidad de slots, el techo de jobs en simultáneo por proceso worker queda definido por esa misma cantidad (por ejemplo, 6).
- **Seguridad:** un mensaje que falla de forma reproducible (payload malformado, perfil inexistente) se envía a la cola de mensajes fallidos sin reencolarse, en vez de reintentarse indefinidamente y saturar a todos los workers.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** al mandar un mensaje a la cola de mensajes fallidos, el registro de la ejecución correspondiente se marca `INTERNAL_ERROR` en el mismo movimiento; si no, el alumno vería `QUEUED` para siempre. `INTERNAL_ERROR` no consume vida ni intento. El alcance de esta historia incluye construir el worker como componente del servicio (su empaquetado, su imagen de contenedor y su lógica de consumo), no solo configurar su cola de trabajo y su confirmación de mensajes.

##### Criterios de Aceptación (CA)
- **CA1:** la cola de trabajo está declarada como durable y los mensajes se publican como persistentes; un reinicio del broker no pierde jobs pendientes.
- **CA2:** el consumo de la cola usa confirmación manual: el mensaje solo se confirma después de que el worker terminó de ejecutar el job, nunca al recibirlo.
- **CA3:** si un worker muere mientras procesa un job, el mensaje queda sin confirmar, el broker lo reencola automáticamente y otro worker lo toma, sin intervención manual.
- **CA4:** la cantidad de mensajes en vuelo por worker está limitada a la cantidad de slots de ejecución disponibles (por ejemplo, 6), de forma que un worker nunca acumula en su memoria más jobs de los que puede procesar en paralelo.
- **CA5:** un mensaje que falla de forma reproducible tras agotar sus reintentos se envía a la cola de mensajes fallidos (sin reencolarse) y, en el mismo movimiento, la ejecución correspondiente se marca `INTERNAL_ERROR` sin consumir vida ni intento del alumno.
- **CA6:** cuando la cantidad de ejecuciones en `QUEUED` alcanza el tope de profundidad de cola configurado, la API responde `429` con código `QUEUE_FULL` y un tiempo de espera sugerido en el header `Retry-After`, sin persistir ni encolar la entrega; el servicio no se declara caído por estar saturado.
- **Extras (opcional):** existe una alerta operativa cuando la profundidad de la cola de mensajes fallidos es mayor a cero, porque cada mensaje ahí representa una entrega que ningún alumno va a recibir.

##### BDD
**Característica:** Procesamiento asíncrono resiliente a picos y reinicios

**Escenario 1: Pico de entregas simultáneas**
- **Dado:** que llegan 30 entregas juntas en un pico de cierre de plazo
- **Cuando:** la API las publica como 30 mensajes en la cola de trabajo
- **Entonces:** la API responde `202` a las 30 de inmediato, y los workers disponibles las procesan de a un número acotado por vez (según sus slots), sin que el servicio caiga ni pierda entregas

**Escenario 2: Worker se reinicia a mitad de una ejecución**
- **Dado:** que un worker tomó un job y el proceso muere (deploy, crash) antes de confirmar el mensaje
- **Cuando:** el broker detecta que la conexión se cortó
- **Entonces:** el mensaje vuelve a la cola automáticamente y otro worker disponible lo toma y lo procesa, sin que el alumno tenga que reenviar la entrega

**Escenario 3: Mensaje envenenado no bloquea la cola**
- **Dado:** que un mensaje tiene un payload malformado que hace fallar el procesamiento de forma reproducible
- **Cuando:** el mensaje agota sus reintentos permitidos
- **Entonces:** el mensaje se envía a la cola de mensajes fallidos sin reencolarse indefinidamente, la ejecución correspondiente se marca `INTERNAL_ERROR` sin consumir vida ni intento, y los demás jobs de la cola siguen procesándose con normalidad

**Escenario 4: Cola llena, entrega rechazada con espera sugerida**
- **Dado:** que la cantidad de ejecuciones en `QUEUED` alcanzó el tope de profundidad de cola configurado
- **Cuando:** T05 hace `POST /api/sandbox/executions` con un bundle válido
- **Entonces:** el sandbox responde `429` con código `QUEUE_FULL` y un header `Retry-After`, no persiste ni encola la entrega, y T05 puede reintentarla con la misma `Idempotency-Key` pasado ese tiempo

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** No aplica directamente (componente interno de cola/worker, sin endpoint HTTP propio); el estado resultante se observa vía `GET /api/sandbox/executions/{id}`.

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 13 | Must |

El puntaje incluye la construcción del módulo `worker` como componente nuevo del servicio, además del consumo de la cola y de su confirmación de mensajes.

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (worker), broker de mensajería, `ms-sandbox` (API, para reflejar `INTERNAL_ERROR`).
- **Módulos afectados:** se crea el módulo `worker`, con su consumidor de jobs, la configuración de la cola de trabajo y de su cola de mensajes fallidos, y su watchdog.
- **Otros equipos / aprobaciones:** ninguna adicional; es responsabilidad interna del sandbox.
- **Impacto en datos / migraciones:** ninguno adicional a lo definido en HU-01; se reutiliza la tabla de ejecuciones.
- **Riesgos y mitigación:** un worker "vivo pero colgado" (no muerto, pero sin avanzar) no es cubierto por el reencolado automático del broker; se mitiga con un timeout de consumidor bajo y un watchdog propio que marca `INTERNAL_ERROR` a ejecuciones estancadas mucho más allá del timeout máximo. Riesgo de apagado desprolijo del worker: se mitiga esperando la finalización de los hilos en vuelo antes de cerrar el cliente de Docker/ejecutor.

---

### 3.3 Épica 3: Ejecución aislada

> **Taiga:** actualiza la Épica 3 (ya creada en Taiga; ajusta objetivo y criterios de aceptación a nivel épico)

#### Objetivo
Ejecutar el código del alumno de forma aislada del host y de los demás servicios de la
plataforma, respetando los límites de CPU, memoria y tiempo del perfil del catálogo que
corresponda, devolviendo la salida cruda del programa y de la herramienta del perfil de forma
confiable, y exponiendo un chequeo de salud que distinga un servicio saturado de uno roto. El
alcance de esta épica incluye construir el ejecutor, las imágenes de ejecución y el catálogo de
perfiles, además del aislamiento y de los límites de recursos.

#### Suposiciones y Restricciones
* **Suposiciones:** el worker nunca tiene acceso directo a Docker; solo el proceso ejecutor lo
  tiene, y su especificación de contenedor está fija de su lado, sin que el alumno la controle; el
  código y los tests entran por copia, nunca descargándose de la red.
* **Restricciones (técnicas):** todo contenedor corre sin red, con filesystem de solo lectura, sin
  capabilities de Linux, sin escalamiento de privilegios y con un usuario no root; el alcance MVP
  usa un catálogo estático de tres perfiles fijos (`java21-junit`, `java21-pmd`,
  `java21-checkstyle`), sin API de administración, con memoria, CPU y timeouts definidos de
  antemano por perfil; la captura de salida y el desempaquetado de reportes tienen topes de
  tamaño y de cantidad de entradas.

#### Criterios de Aceptación a nivel Épico
* El ejecutor, las imágenes de ejecución y el catálogo de perfiles se construyen como
  componentes/artefactos nuevos del servicio.
* Ningún código del alumno puede alcanzar la red, el host ni otro servicio de la plataforma
  (HU-06).
* Ninguna ejecución puede exceder los límites de CPU, memoria o tiempo del perfil seleccionado sin
  quedar marcada con un estado técnico y detenida correctamente (HU-07).
* Toda ejecución devuelve la salida cruda del programa y el reporte de la herramienta del perfil,
  con tope de tamaño aplicado; el sandbox nunca decide un veredicto de negocio, esa interpretación
  es responsabilidad de T05 (HU-08).
* El chequeo de salud distingue un servicio saturado (que sigue funcionando) de uno roto,
  evitando reinicios innecesarios en cascada (HU-09, opcional).

**Estimación agregada de la épica:** 34 puntos (31 Must + 3 Could): HU-06: 13, HU-07: 13, HU-08: 5, HU-09: 3.

#### Dependencias / Impactos
* **Servicios / APIs:** Docker, worker de Sandbox, proceso ejecutor.
* **Módulos afectados:** especificación del contenedor, validación de bundle, catálogo/validación
  de perfiles, script de arranque de la imagen, acumulador de salida, desempaquetador de
  reportes, endpoint de salud.
* **Otros equipos:** Cátedra (homologación de límites); T05 (confirmación de los valores de perfil
  y del paquete reservado de tests).
* **Impacto en datos / migraciones:** ninguno adicional relevante.
* **Feature toggles / flags:** No.

---

#### [G08] — HU-06 — Que el código del alumno no pueda llegar a la red, al host ni a otros servicios

> **Taiga:** actualiza HU-06 (ya creada en Taiga; ajusta validaciones, riesgos, notas y estimación)

##### Descripción (Como / Quiero / Para)
- **Como:** responsable de la plataforma
- **Quiero:** que el código del alumno, corriendo dentro del sandbox, no pueda alcanzar la red, el host ni ningún otro servicio de la plataforma
- **Para:** que nadie pueda acreditarse monedas, XP ni ningún otro beneficio escapando del contenedor

##### Notas / Observaciones
- **Reglas de negocio:** el aislamiento de red es la defensa central del diseño, no una buena práctica genérica: con service discovery, cualquier proceso con red podría resolver otro servicio por nombre y explotar sus endpoints. El contenedor corre sin red (solo loopback, sin DNS, sin ruta), con el filesystem de solo lectura, sin capabilities de Linux, sin posibilidad de escalar privilegios y con un usuario no privilegiado.
- **Validaciones:** el alcance MVP de esta historia incluye construir una **lista blanca de tipo y nombre de entrada al desempaquetar el bundle**: la validación debe usar una lista blanca de tipos de entrada (solo archivos regulares y directorios), rechazando FIFOs, dispositivos, sockets, symlinks y hard links, y debe ser una validación programática, nunca un parseo de la salida de texto de un listado de tar. Las rutas deben ser relativas y no pueden contener `..`. Todavía está pendiente decidir en qué componente vive esa validación.
- **Datos obligatorios:** especificación fija del contenedor (imagen, mounts vacíos, sin capabilities, sin red, sin privilegios), que no depende del contenido del request del alumno.
- **Performance (tiempos, volumen, límites):** ver HU-07 para los límites de CPU/memoria/tiempo del perfil.
- **Seguridad:** el worker nunca tiene acceso directo a Docker; solo el proceso ejecutor lo tiene, y su especificación de contenedor está fija de su lado, sin que el alumno la controle. Riesgo residual aceptado y declarado: el kernel es compartido y existe una familia de vulnerabilidades conocidas del motor de contenedores; se compensa desconectando la red del contenedor, que corta el vector que más importa (acceso a otros servicios). Todavía está pendiente decidir si conviene incorporar además un proxy de la API de Docker con lista blanca de operaciones, o aceptar el riesgo residual del ejecutor privilegiado.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** el código y los tests entran por copia (por entrada estándar, como un tar), nunca descargándose de la red.

##### Criterios de Aceptación (CA)
- **CA1:** todo contenedor de ejecución se crea sin interfaz de red: el proceso del alumno no tiene resolución DNS ni ruta hacia ningún otro servicio de la plataforma.
- **CA2:** todo contenedor de ejecución se crea con filesystem de solo lectura, sin capabilities de Linux, sin escalamiento de privilegios y con un usuario no root, sin excepciones dependientes del contenido del request.
- **CA3:** un bundle cuyo tar contiene una entrada de tipo distinto de archivo regular o directorio (por ejemplo, un FIFO, un symlink o un hard link) es rechazado antes de ejecutar, por la validación de tipo/nombre en lista blanca.
- **CA4:** la especificación del contenedor (imagen, mounts, capabilities, red) es fija y no admite parámetros provistos por el request de la entrega.
- **CA5:** una entrega hostil que intenta abrir una conexión de red desde dentro del contenedor no logra alcanzar ningún host ni servicio externo al contenedor.
- **Extras (opcional):** la suite de pruebas del sandbox incluye casos hostiles de intento de red, bucle infinito, bomba de procesos y agotamiento de memoria, verificados contra un motor de contenedores real.

##### BDD
**Característica:** Aislamiento de red y del host frente al código del alumno

**Escenario 1: Intento de conexión de red bloqueado**
- **Dado:** una entrega cuyo código intenta hacer una llamada de red hacia otro servicio de la plataforma
- **Cuando:** el código se ejecuta dentro del contenedor efímero
- **Entonces:** la conexión de red falla porque el contenedor no tiene interfaz de red, y el intento no alcanza a ningún servicio de la plataforma

**Escenario 2: Bundle con una entrada de tipo no permitido**
- **Dado:** un bundle en el que una entrada del tar es un symlink o un FIFO, no un archivo regular ni un directorio
- **Cuando:** el sandbox valida el bundle antes de crear el contenedor
- **Entonces:** la entrega se rechaza antes de tocar Docker, sin crear ningún contenedor

**Escenario 3: Escritura fuera del árbol del bundle**
- **Dado:** un bundle en el que una ruta de archivo intenta escapar del directorio de trabajo (por ejemplo, mediante `..` o una ruta absoluta)
- **Cuando:** el sandbox valida las rutas del bundle
- **Entonces:** la entrega se rechaza con `400` y código `INVALID_PATH` antes de ejecutar, y no se produce ninguna escritura fuera del árbol de trabajo del bundle

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** `POST /api/sandbox/executions`; la especificación de aislamiento no se expone como endpoint propio.

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 13 | Must |

El puntaje incluye la construcción del módulo `ejecutor` como componente nuevo del servicio, además de la especificación de aislamiento y la validación estructural del bundle.

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (ejecutor, worker), Docker.
- **Módulos afectados:** se crea el módulo `ejecutor`, con su especificación del contenedor, la validación de bundle en la API y/o worker, y el catálogo de perfiles (ver también HU-07).
- **Otros equipos / aprobaciones:** ninguna directa; el riesgo de kernel compartido y de vulnerabilidades del motor de contenedores es aceptado y documentado como riesgo residual, no bloqueante para el MVP.
- **Impacto en datos / migraciones:** ninguno.
- **Riesgos y mitigación:** el contenedor protege el host, pero no protege por sí solo la autenticidad de la salida cruda (ver HU-08 y el apartado de deuda conocida al cierre de este documento); la validación estructural del bundle es parte del alcance de esta historia: una validación basada en parsear la salida de texto para humanos del listado de tar sería frágil e incompleta, y queda descartada por diseño. Todavía está pendiente decidir en qué componente vive esa validación (API, worker o ejecutor); mientras no se decida, la historia no puede darse por terminada.

---

#### [G08] — HU-07 — Que cada ejecución respete los límites de CPU, memoria y tiempo del perfil

> **Taiga:** actualiza HU-07 (ya creada en Taiga; ajusta notas y estimación)

##### Descripción (Como / Quiero / Para)
- **Como:** profesor
- **Quiero:** que cada ejecución de una entrega respete los límites de CPU, memoria y tiempo definidos por el perfil de ejecución seleccionado del catálogo
- **Para:** que un bucle infinito o un consumo excesivo de recursos de un alumno no afecte a las demás

##### Notas / Observaciones
- **Reglas de negocio:** el alcance MVP de esta historia es un **catálogo estático de tres perfiles**, versionado junto con las imágenes de contenedor y cargado al arranque, sin API de alta/baja/modificación (CRUD): `java21-junit`, `java21-pmd` y `java21-checkstyle`. El request selecciona el perfil por su identificador; los límites viven en el perfil, no en el request: T05 no propone límites y el sandbox aplica siempre los del perfil elegido. `java21-junit` es el único perfil que ejecuta código del alumno (los tests del profesor, con ArchUnit incluido como librería en el classpath de tests, corriendo como tests de JUnit más); mantiene el perfil de referencia de **2 CPU** por ejecución, medido de punta a punta contra 1 CPU (una mejora considerable de tiempo total para el mismo bundle). `java21-pmd` y `java21-checkstyle` son análisis estático sobre las fuentes del alumno y **no ejecutan código del alumno**; aun así corren con el mismo aislamiento que los demás perfiles (sin red, límites, usuario no privilegiado), porque el archivo de reglas (PMD) o de configuración (Checkstyle) lo provee T05 en el bundle y ambas herramientas parsean entrada no confiable.
- **Validaciones:** el diseño usa tres relojes de naturaleza distinta para `java21-junit`: el reloj del alumno se mide en **tiempo de CPU** (no en reloj de pared), el reloj de compilación es de pared y es costo de plataforma (no se le cobra al alumno), y hay un reloj de pared de respaldo, generoso, para procesos que duermen en vez de consumir CPU. Medir el reloj del alumno en tiempo de CPU es lo que elimina los `TIMEOUT` intermitentes producidos por la contención del host, en vez de acolcharlos con margen. Un identificador de perfil que no está en el catálogo reutiliza el código de error estable `UNSUPPORTED_LANGUAGE` ya definido en HU-01/HU-03 para "lenguaje/perfil no soportado", sin tocar Docker. `GET /languages` expone el catálogo vigente de los tres perfiles.
- **Datos obligatorios:** por perfil: memoria del contenedor, CPU, y para `java21-junit` además presupuesto de CPU para la fase de tests, timeout de compilación y timeout de la fase de tests. Para `java21-junit`: memoria (ej. 512 MB), CPU (2), presupuesto de CPU para la fase de tests (10 s, techo), timeout de compilación (20 000 ms), timeout de la fase de tests (30 000 ms, de respaldo). Para `java21-pmd` y `java21-checkstyle`: memoria, CPU y timeout de análisis **a definir por medición**, porque no hay referencia previa de estas herramientas corriendo bajo el mismo aislamiento.
- **Performance (tiempos, volumen, límites):** presupuesto medido de referencia de `java21-junit`: ~4-8 s por ejecución con 512 MB / 2 CPU; pool de 6 ejecuciones simultáneas: ~3 GB RAM y hasta 12 cores en pico. Los presupuestos de `java21-pmd` y `java21-checkstyle` quedan a definir por medición antes del cierre de esta historia.
- **Seguridad:** la validación del catálogo de perfiles debe rechazar, para cualquiera de los tres perfiles, valores de memoria, CPU o versión iguales a cero o negativos, porque un valor de memoria en cero puede significar "sin límite" para el motor de contenedores, lo cual anularía el propósito de esta historia. El alcance MVP de esta historia incluye que los tres perfiles del catálogo tengan valores mínimos y máximos válidos y no nulos, validados al arrancar.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** desde la versión 10 de Java, la JVM respeta los límites de recursos del contenedor y toma por defecto el 25% del límite de memoria como heap; el perfil `java21-junit` sube ese porcentaje para evitar que el heap quede demasiado corto. La JVM que se queda sin memoria puede terminar sin que el motor de contenedores la marque como matada por falta de memoria; el estado técnico `MEMORY_LIMIT` debe contemplar los dos caminos de entrada posibles.

##### Criterios de Aceptación (CA)
- **CA1:** toda ejecución corre bajo uno de los tres perfiles fijos del catálogo (`java21-junit`, `java21-pmd`, `java21-checkstyle`), con memoria, CPU y timeouts fijos por perfil (no editables desde el request de T05).
- **CA2:** una entrega cuyo código de alumno agota el tiempo de CPU asignado bajo `java21-junit` termina en estado `TIMEOUT`, medido en tiempo de CPU y no en reloj de pared.
- **CA3:** una entrega cuyo código de alumno agota la memoria asignada al contenedor bajo `java21-junit` termina en estado `MEMORY_LIMIT`, detectado tanto si lo mata el límite de memoria del contenedor como si la JVM termina antes por su propio manejo de falta de memoria.
- **CA4:** un contenedor colgado en compilación (toolchain saturado) que excede el timeout de compilación de `java21-junit` termina en estado `INTERNAL_ERROR`, sin consumir vida ni intento del alumno.
- **CA5:** el catálogo de perfiles rechaza al arrancar cualquiera de los tres perfiles con memoria, CPU o versión igual a cero, negativo o ausente.
- **CA6:** un request que selecciona un identificador de perfil que no pertenece al catálogo reutiliza el código de error estable `UNSUPPORTED_LANGUAGE` (ver HU-01/HU-03), sin crear ningún contenedor.
- **CA7:** `GET /languages` devuelve los tres perfiles del catálogo vigente.
- **Extras (opcional):** dos entregas concurrentes en el pool de ejecución no compiten entre sí por el reloj del alumno más allá de la variación esperada, gracias a que el presupuesto de CPU no se ve afectado por la carga del host.

##### BDD
**Característica:** Respeto de límites de CPU, memoria y tiempo por perfil de ejecución

**Escenario 1: Bucle infinito no consume recursos indefinidamente**
- **Dado:** una entrega con un bucle infinito que consume CPU activamente, enviada con el perfil `java21-junit`
- **Cuando:** se ejecuta dentro del contenedor con los límites de ese perfil
- **Entonces:** al agotar el presupuesto de tiempo de CPU, el sandbox marca el estado como `TIMEOUT` y el contenedor se destruye, sin afectar a otras ejecuciones en curso

**Escenario 2: Fuga de memoria detectada correctamente**
- **Dado:** una entrega cuyo código reserva memoria sin liberarla hasta agotar el límite del contenedor, bajo el perfil `java21-junit`
- **Cuando:** se ejecuta con memoria limitada y el porcentaje de heap configurado
- **Entonces:** el sandbox marca el estado como `MEMORY_LIMIT`, sea que la JVM termine por su propia gestión de memoria o que el límite del contenedor la mate

**Escenario 3: Identificador de perfil desconocido**
- **Dado:** un request que indica un identificador de perfil que no existe en el catálogo
- **Cuando:** T05 hace `POST /api/sandbox/executions` con ese identificador
- **Entonces:** el sandbox responde con el código de error estable `UNSUPPORTED_LANGUAGE` (el mismo que ya usan HU-01/HU-03 para "lenguaje/perfil no soportado"), sin crear ningún contenedor

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** `POST /api/sandbox/executions`, `GET /languages` (catálogo de los tres perfiles y sus límites).

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 13 | Must |

El puntaje sube de 8 a 13 respecto de la versión anterior porque el alcance ya no es un único perfil hardcodeado: incluye construir dos imágenes nuevas (`java21-pmd`, `java21-checkstyle`), agregar ArchUnit a la imagen `java21-junit`, y extender el catálogo y su validación a tres perfiles con la nueva regla de rechazo por identificador desconocido.

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (ejecutor, worker), Docker.
- **Módulos afectados:** se construyen las tres imágenes de ejecución y sus perfiles, el catálogo/validación de perfiles con sus tres entradas, y el rechazo por identificador de perfil desconocido en la API.
- **Otros equipos / aprobaciones:** el valor exacto de CPU de `java21-junit` está confirmado a nivel de propuesta, pero depende de la confirmación formal de T05 (ver cierre del documento); T05 también debe proveer el archivo de reglas de PMD y el de configuración de Checkstyle en el bundle.
- **Impacto en datos / migraciones:** catálogo de perfiles versionado junto con las imágenes, sin impacto en el esquema de ejecuciones.
- **Riesgos y mitigación:** el catálogo de perfiles debe construirse con un esquema cerrado de validación al cargar: sin esa validación, un perfil mal cargado con memoria en cero anularía el límite sin que nadie lo note; el esquema cerrado es requisito de diseño de este componente, no una corrección posterior. Los límites de `java21-pmd` y `java21-checkstyle` quedan a definir por medición: hasta no medirlos, se corre el riesgo de subdimensionarlos o sobredimensionarlos frente al costo real de esas herramientas.

---

#### [G08] — HU-08 — Capturar y devolver la salida cruda del programa y el reporte de la herramienta

> **Taiga:** actualiza HU-08 (ya creada en Taiga; ajusta descripción, reglas de negocio, validaciones y estimación)

##### Descripción (Como / Quiero / Para)
- **Como:** T05 (Desafíos Prácticos)
- **Quiero:** recibir la salida cruda de la ejecución (salida estándar, salida de error y el/los archivo(s) de reporte que produjo la herramienta del perfil), cada uno con un tope de tamaño aplicado, exactamente como los produjo la herramienta
- **Para:** poder interpretar yo el resultado y mostrarle al alumno el feedback correcto, sin que el sandbox decida un veredicto de negocio por su cuenta

##### Notas / Observaciones
- **Reglas de negocio:** el sandbox **no interpreta** el reporte de tests ni el de la herramienta de análisis estático, y **no emite un veredicto de negocio** (éxito, fallo, salida anticipada). Se limita a devolver la salida cruda: salida estándar, salida de error y el/los archivo(s) de reporte de la herramienta del perfil (por ejemplo, el reporte JUnit, el reporte PMD o el reporte Checkstyle), cada uno capado en tamaño, tal como los produjo la herramienta. Es T05 quien interpreta esa salida cruda y decide qué mostrarle al alumno; en particular, T05 es responsable de no exponerle al alumno la salida correspondiente a tests ocultos.
- **Validaciones:** el alcance MVP de esta historia incluye construir la **captura de salida con un tope de tamaño** y los **límites de desempaquetado del paquete de reportes**. Sobre la captura de salida: cualquier descriptor de archivo heredado por el proceso del alumno debe cerrarse antes de invocar la herramienta del perfil, para que el alumno no pueda evadir el tope de bytes de salida capturada escribiendo directamente a un canal heredado. Sobre el desempaquetado: debe limitar cantidad de entradas, largo de nombre y profundidad, y aceptar solo archivos regulares, con falla cerrada.
- **Datos obligatorios:** el resultado de la ejecución incluye la salida estándar y de error capturadas, y el/los archivo(s) de reporte de la herramienta del perfil, cada uno con el tope de tamaño aplicado.
- **Performance (tiempos, volumen, límites):** tope de captura de salida: 65.536 bytes por flujo de salida; tope del paquete de reportes: 8 MiB.
- **Seguridad:** el reporte crudo puede ser falsificado por el código del alumno, porque corre en el mismo proceso y con el mismo usuario que el runner de la herramienta (para `java21-junit`): puede escribir directamente su propio reporte con un resultado falso y terminar el proceso antes de que corran los tests reales, o leer el código fuente de los tests ocultos. Esta debilidad **no** se cierra en el MVP: la mitigación de fondo es un verificador de bytecode que restringe qué puede hacer el código del alumno antes de ejecutarlo, y queda fuera de alcance por el esfuerzo que implica (ver la sección de historias fuera de MVP). Por eso, **T05 no debe tratar la salida cruda que devuelve el sandbox como una calificación auténtica todavía**: es evidencia sin verificar, no un veredicto certificado. En su lugar, el MVP sí incluye construir el cierre del descriptor heredado y los límites del paquete de reportes, requisitos de construcción que no dependen del verificador.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** el ocultamiento de tests ocultos (qué parte de la salida cruda no debe llegar al alumno) es responsabilidad de T05 sobre la salida que el sandbox le entrega completa; no es una decisión que tome el sandbox.

##### Criterios de Aceptación (CA)
- **CA1:** el resultado de una ejecución incluye la salida estándar y de error capturadas durante la ejecución, y el/los archivo(s) de reporte producidos por la herramienta del perfil, sin que el sandbox los interprete ni agregue un veredicto propio.
- **CA2:** la salida capturada tiene un tope de tamaño (65.536 bytes); una entrega que escribe directamente a un descriptor de archivo heredado no logra evadir ese tope.
- **CA3:** el desempaquetado del paquete de reportes rechaza (falla cerrada) un paquete que exceda el tope de cantidad de entradas, el largo de nombre o la profundidad permitidos, o que contenga entradas que no sean archivos regulares.
- **CA4:** el estado técnico terminal de la ejecución (`COMPLETED`, `TIMEOUT`, `MEMORY_LIMIT`, `INTERNAL_ERROR`) es independiente del contenido del reporte: el sandbox nunca decide ni informa un veredicto de éxito o fallo de negocio, esa interpretación queda a cargo de T05.
- **Extras (opcional):** —

##### BDD
**Característica:** Captura y entrega de la salida cruda de la ejecución

**Escenario 1: Entrega con reporte de tests que registra fallos**
- **Dado:** una entrega enviada con el perfil `java21-junit` cuyo código compila correctamente pero, según el reporte que produce la herramienta, falla 2 de 5 tests
- **Cuando:** la ejecución termina en estado `COMPLETED`
- **Entonces:** el sandbox devuelve el reporte crudo (con los 5 resultados) y la salida estándar capturada, sin agregar un veredicto propio; es T05 quien interpreta ese detalle para mostrárselo al alumno

**Escenario 2: Salida anticipada del proceso no oculta la ausencia de tests corridos**
- **Dado:** una entrega cuyo código termina el proceso anticipadamente antes de que corran los tests
- **Cuando:** el contenedor termina con código de salida `0` y la ejecución queda en estado `COMPLETED`
- **Entonces:** el sandbox devuelve igualmente la salida estándar capturada y el reporte tal como quedó (sin tests corridos), sin clasificar ese resultado como éxito ni como ningún otro veredicto; es T05 quien detecta, a partir del reporte crudo, que no corrió ningún test

**Escenario 3: Intento de evadir el tope de salida capturada**
- **Dado:** una entrega que escribe una cantidad de datos mayor al tope permitido directamente a un descriptor de archivo heredado, en vez de por la salida estándar
- **Cuando:** el sandbox procesa la salida de esa ejecución
- **Entonces:** el descriptor heredado ya fue cerrado antes de ejecutar el código del alumno, por lo que ese canal no está disponible y el tope de captura de salida no puede evadirse por esa vía

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** `GET /api/sandbox/executions/{id}` (salida cruda y reporte), `GET /api/sandbox/executions/{id}/artifacts`.

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 5 | Must |

El puntaje baja de 8 a 5 respecto de la versión anterior porque el alcance ya no incluye el verificador del reporte ni el clasificador de veredictos (éxito, fallo, `EARLY_EXIT`): queda solo la captura de salida con tope, el cierre del descriptor heredado y el desempaquetado del paquete de reportes con sus límites.

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (ejecutor, worker).
- **Módulos afectados:** script de arranque de la imagen (cierre del descriptor heredado), desempaquetador de reportes (límites del paquete de reportes), acumulador de salida (tope de captura).
- **Otros equipos / aprobaciones:** ninguna adicional para el alcance MVP; T05 es responsable de interpretar la salida cruda y de no exponerle al alumno la correspondiente a tests ocultos.
- **Impacto en datos / migraciones:** ninguno.
- **Riesgos y mitigación:** deuda conocida: sin el verificador de bytecode contra una lista blanca de APIs permitidas (fuera de MVP), un alumno puede fabricar su propio reporte o leer los tests ocultos corriendo el código en el mismo proceso que el runner; la salida cruda que devuelve el sandbox puede estar falsificada y T05 no debe tratarla todavía como una calificación auténtica. Ver la sección "Deuda conocida" al cierre de este documento.

---

#### [G08] — HU-09 — Un chequeo de salud que distinga "saturado" de "roto"

> **Taiga:** actualiza HU-09 (ya creada en Taiga, opcional, prioridad Could; ajusta seguridad)

##### Descripción (Como / Quiero / Para)
- **Como:** operador de la plataforma
- **Quiero:** un chequeo de salud básico que distinga un sandbox "saturado" (con la cola llena, pero funcionando) de un sandbox "roto"
- **Para:** no reiniciar innecesariamente un servicio sano, evitando que la carga se corra en cascada a los demás servicios

##### Notas / Observaciones
- **Reglas de negocio:** "saturado" no es "enfermo": un worker con la cola llena está funcionando perfectamente; si reportara estar caído, la carga se correría a los demás servicios y el sistema entero caería en cascada por estar trabajando a full. La saturación se responde con `429` y un tiempo de espera sugerido al cliente, no declarándose enfermo ante el orquestador.
- **Validaciones:** el alcance MVP de esta historia es un **`/health` básico**: verificar que el catálogo de perfiles no esté vacío y que la conectividad con Docker responda. El chequeo con una "ejecución canaria" (crear, correr y borrar un job trivial conocido de punta a punta) es una mejora identificada pero no obligatoria para el alcance mínimo de esta historia opcional.
- **Datos obligatorios:** resultado separado de `liveness` y `readiness`.
- **Performance (tiempos, volumen, límites):** distinción de comportamiento según situación: proceso vivo y todo bien → `liveness UP`, `readiness UP`, respuesta normal (`202`); cola llena → `liveness UP`, `readiness UP`, respuesta `429` con tiempo de espera sugerido; base de datos caída → `liveness UP`, `readiness DOWN`, `503`; broker caído → `liveness UP`, `readiness UP`, `202` (el outbox retiene).
- **Seguridad:** el chequeo de salud debe verificar que el catálogo de perfiles no esté vacío y que la conectividad con el motor de contenedores responda; no alcanza con reportar únicamente los semáforos internos.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** la ejecución canaria periódica y el chequeo de existencia de cada imagen del catálogo quedan como extensión posible más allá del alcance mínimo de esta historia opcional.

##### Criterios de Aceptación (CA)
- **CA1:** `GET /actuator/health` expone `liveness` y `readiness` como indicadores separados.
- **CA2:** con la cola de trabajo llena pero el resto de los componentes sanos, `liveness` y `readiness` reportan `UP`, y las nuevas solicitudes de ejecución reciben el `429` de cola llena definido en HU-05, no un estado de salud `DOWN`.
- **CA3:** con la base de datos inaccesible, `readiness` reporta `DOWN` (el servicio no puede aceptar trabajo nuevo de forma confiable), mientras `liveness` sigue en `UP` si el proceso está vivo.
- **CA4:** con el broker de mensajería inaccesible pero la base de datos disponible, `readiness` se mantiene `UP` (el outbox retiene los mensajes) y la API sigue aceptando entregas con `202`.
- **CA5:** el chequeo de salud verifica que el catálogo de perfiles no esté vacío antes de reportar `readiness: UP`.
- **Extras (opcional):** ejecución canaria periódica (crear, correr y borrar un job trivial conocido) que alimenta el resultado del chequeo de salud.

##### BDD
**Característica:** Distinción entre servicio saturado y servicio roto

**Escenario 1: Cola llena no se reporta como caída**
- **Dado:** que la cola de trabajo está en su capacidad máxima configurada
- **Cuando:** se consulta `GET /actuator/health`
- **Entonces:** `liveness` y `readiness` reportan `UP`, y una nueva solicitud de ejecución recibe `429` con un tiempo de espera sugerido, en vez de que el servicio se desregistre del orquestador

**Escenario 2: Base de datos caída sí se reporta como no lista**
- **Dado:** que la base de datos del sandbox no responde
- **Cuando:** se consulta `GET /actuator/health`
- **Entonces:** `readiness` reporta `DOWN` con `503`, mientras `liveness` puede seguir en `UP` si el proceso sigue corriendo

**Escenario 3: Broker caído no tumba la disponibilidad**
- **Dado:** que el broker de mensajería está temporalmente inaccesible pero la base de datos funciona con normalidad
- **Cuando:** se consulta `GET /actuator/health` y, en paralelo, T05 envía una entrega
- **Entonces:** `readiness` reporta `UP`, la entrega se acepta con `202` y queda retenida en el outbox hasta que el proceso relay pueda publicarla

##### Prototipo
- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** `GET /actuator/health`.

##### Estimación / Prioridad
| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 3 | Could |

##### Dependencias / Impactos
- **Servicios involucrados:** `ms-sandbox` (API, ejecutor), base de datos, broker de mensajería, orquestador de contenedores (para leer `liveness`/`readiness`).
- **Módulos afectados:** endpoint `/actuator/health` de la API, endpoint `/health` del ejecutor.
- **Otros equipos / aprobaciones:** ninguna.
- **Impacto en datos / migraciones:** ninguno.
- **Riesgos y mitigación:** riesgo operativo de reinicios en cascada si `readiness` se implementa mal (declarando `DOWN` ante saturación); se mitiga siguiendo estrictamente la tabla de comportamiento esperado descripta en esta historia.

---

## 4. Fuera de alcance del MVP y deuda conocida

### Historias fuera de MVP

La numeración anterior del backlog completo de `ms-sandbox` quedó discontinuada al renumerar las
nueve historias del MVP de 01 a 09; los números que estas piezas fuera de MVP tenían en esa
numeración ya no corresponden a nada y no hay que buscarles equivalencia. Por eso, las siguientes
piezas del backlog completo se identifican por su contenido, no por un número, y quedan
explícitamente fuera del alcance MVP sin desarrollarse en este documento:

- **La notificación del resultado por el bus de eventos de la plataforma:** está bloqueada por una
  definición pendiente sobre cómo se articula el evento `ExecutionCompleted` (bus de eventos de la
  plataforma) con el outbox interno de la cola de trabajo del sandbox; todavía no está resuelta, y
  depende del panorama completo de eventos de la plataforma.
- **El verificador de bytecode contra una lista blanca de APIs permitidas:** la corrección de fondo
  para que un alumno no pueda fabricar su propio reporte ni leer los tests ocultos es un
  desarrollo de varias semanas y queda fuera del MVP.
- **El filtrado de los tests por visibilidad:** bloqueado por dos definiciones pendientes con T05:
  si el sandbox filtra los tests por visibilidad o devuelve todo y T05 filtra, y cuál es el paquete
  reservado de los tests, que T05 todavía no declaró.
- **El CRUD de perfiles y el catálogo dinámico:** el MVP usa un catálogo estático de tres perfiles
  (`java21-junit`, `java21-pmd`, `java21-checkstyle`), versionado junto con las imágenes y cargado
  al arranque. Dar de alta, modificar o dar de baja un perfil por API, o versionar el catálogo en
  caliente sin redeploy, queda fuera del MVP.
- **La clasificación de veredictos sobre el resultado de los tests:** el sandbox ya no interpreta
  el reporte de tests ni emite un veredicto de negocio (éxito, fallo, salida anticipada); captura
  y devuelve la salida cruda, y es T05 quien la interpreta (ver HU-08). Ese modelo de clasificación
  ya no forma parte de lo planificado para el MVP.
- **El resto de las historias del backlog completo, que no tienen contenido definido.**

### Deuda conocida (a comunicar explícitamente al equipo y a cátedra)

**Sin el verificador de bytecode contra una lista blanca de APIs permitidas, un alumno puede fabricar su propia salida cruda.** El
código del alumno corre en el mismo proceso y con el mismo usuario que el runner de tests; puede
escribir directamente su propio reporte con un resultado falso y terminar el proceso antes de que
corran los tests reales. También puede leer el código fuente de los tests ocultos. El aislamiento
de host (red, filesystem, capabilities) funciona correctamente y resiste estas pruebas; lo que
falla es una propiedad distinta: **que esa salida cruda sea auténtica**. El sandbox ya no emite un
veredicto propio (ver HU-08): entrega la salida cruda tal como la produjo la herramienta, y es
T05 quien la interpreta; mientras el verificador de bytecode no exista, T05 no debe tratar esa
salida como una calificación auténtica de un alumno adversarial. **El MVP es aceptable para una
demo, pero no para calificar entregas reales de alumnos.** El dictamen de la revisión de
seguridad es explícito: el servicio "no está apto todavía para calificación adversaria real".

### Dependencias con otros grupos

- **Con T05 y el equipo de notificaciones/eventos de la plataforma:** cómo se articula la
  publicación del evento `ExecutionCompleted` por el bus de eventos de la plataforma con el
  outbox interno de la cola de trabajo del sandbox. Bloquea la notificación del resultado por el bus de eventos de la plataforma (fuera de MVP) y condiciona si
  en el futuro HU-04 puede complementarse con notificación por evento en vez de solo polling.
- **Con T05:** si el sandbox filtra los tests ocultos por visibilidad o T05 filtra sobre una
  respuesta completa, y cuál es el paquete reservado de los tests del profesor, necesario para que
  el sandbox pueda rechazar una entrega que lo usurpe. Afecta directamente a HU-03 (código
  `RESERVED_PACKAGE`) y al filtrado de los tests por visibilidad (fuera de MVP).
- **Con T05, sobre el alcance de los trabajos prácticos:** el verificador de bytecode contra una lista blanca de APIs permitidas
  (fuera de MVP) requiere que T05 confirme que los trabajos prácticos del MVP **no** usan archivos,
  reflexión ni procesos del sistema operativo. Esta confirmación no bloquea directamente ninguna
  historia del MVP definido en este documento, pero condiciona el alcance real de lo que un
  alumno puede intentar hacer dentro del contenedor durante la vigencia del MVP.
