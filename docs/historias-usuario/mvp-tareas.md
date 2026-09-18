# Tareas del MVP — `ms-sandbox`

## Cómo usar este documento

Cada viñeta de este documento es una tarea de Taiga: cuelga de la historia de usuario indicada
por su prefijo (por ejemplo, `T-01-03` cuelga de HU-01) y se carga en Taiga dentro de esa
historia, nunca como ítem independiente ni como parte de una épica técnica aparte. Los tres
módulos del servicio (`api`, `worker`, `ejecutor`) se construyen desde cero; por eso la primera
tarea de las historias que dan origen a un módulo es crear ese módulo.

---

## Resumen

| Historia | Épica | Cantidad de tareas |
|---|---|---|
| HU-01 — Enviar una entrega y recibir confirmación inmediata | Épica 1 | 14 |
| HU-02 — Reintentar un envío sin que se ejecute dos veces | Épica 1 | 8 |
| HU-03 — Recibir errores claros y estables ante una entrega inválida | Épica 1 | 7 |
| HU-04 — Consultar el estado y el resultado de una ejecución | Épica 1 | 6 |
| HU-05 — Que la entrega se evalúe aunque haya un pico o se reinicie un servicio | Épica 2 | 19 |
| HU-06 — Que el código del alumno no pueda llegar a la red, al host ni a otros servicios | Épica 3 | 14 |
| HU-07 — Que cada ejecución respete los límites de CPU, memoria y tiempo del perfil | Épica 3 | 19 |
| HU-08 — Capturar y devolver la salida cruda del programa y el reporte de la herramienta | Épica 3 | 9 |
| HU-09 — Un chequeo de salud que distinga "saturado" de "roto" | Épica 3 | 8 |
| HU-10 — Recibir un aviso cuando termina una ejecución | Épica 1 | 8 |
| **Total** | | **112** |

---

## Tareas por historia

### HU-01 — Enviar una entrega y recibir confirmación inmediata (Épica 1)

- `T-01-01` Crear el módulo del servicio `api` con su empaquetado y su imagen de contenedor.
- `T-01-02` Crear la migración de la tabla de ejecuciones con la máquina de seis estados, todos técnicos, y las columnas del resultado crudo (salida estándar, salida de error y reportes, con sus topes).
- `T-01-03` Crear la migración de la tabla de outbox.
- `T-01-04` Definir el contrato del request de `POST /api/sandbox/executions` y publicarlo en la especificación de la API.
- `T-01-05` Implementar el caso de uso de solicitud de ejecución con las validaciones en el orden definido, todas resueltas antes de crear un contenedor.
- `T-01-06` Persistir la ejecución y el mensaje de outbox en una única transacción y responder `202 Accepted` con `executionId`, `status` y `queuePosition`. (CA1)
- `T-01-07` Implementar el cálculo de `queuePosition`.
- `T-01-08` Implementar el proceso relay que publica los mensajes pendientes del outbox cuando el broker está disponible. (CA4)
- `T-01-09` Implementar la validación del contenido mínimo del bundle según el perfil elegido, con código `INCOMPLETE_BUNDLE`. (CA2)
- `T-01-10` Implementar el rechazo de un identificador de perfil que no pertenece al catálogo, con código `UNSUPPORTED_LANGUAGE`, antes de tocar Docker. (CA3)
- `T-01-11` Armar el entorno de desarrollo con la API, el worker, el ejecutor, la base de datos y el broker.
- `T-01-12` Armar el pipeline de integración continua con el build de los módulos y la ejecución de las pruebas.
- `T-01-13` Escribir las pruebas unitarias del caso de uso y las de integración de los cinco criterios de aceptación, incluida la de broker caído con base de datos disponible.
- `T-01-14` Implementar el tope de ejecuciones en simultáneo por alumno, contando las ejecuciones en `QUEUED` o `RUNNING`, con respuesta `429` y código `STUDENT_LIMIT_EXCEEDED`. (CA5)

### HU-02 — Reintentar un envío sin que se ejecute dos veces (Épica 1)

- `T-02-01` Agregar la columna de clave de idempotencia con su restricción de unicidad. (CA4)
- `T-02-02` Validar la presencia del header `Idempotency-Key` y rechazar su ausencia con `422` y `MISSING_IDEMPOTENCY_KEY`. (CA1)
- `T-02-03` Calcular y persistir una huella del contenido del bundle para comparar reintentos.
- `T-02-04` Devolver `202` con la ejecución original ante la misma clave y el mismo contenido, sin encolar una segunda ejecución. (CA2)
- `T-02-05` Devolver `409` con `IDEMPOTENCY_CONFLICT` ante la misma clave con contenido distinto. (CA3)
- `T-02-06` Resolver la carrera de dos solicitudes simultáneas con la misma clave sin devolver un error interno.
- `T-02-07` Implementar en el ejecutor un error explícito y distinguible ante un identificador de ejecución repetido. (extra)
- `T-02-08` Escribir las pruebas de los tres caminos y la de concurrencia.

### HU-03 — Recibir errores claros y estables ante una entrega inválida (Épica 1)

- `T-03-01` Implementar el manejador global que serializa los errores de la API como `application/problem+json`. (CA1)
- `T-03-02` Definir el catálogo único de los códigos de error estables y una prueba que impida emitir un código fuera del conjunto. (CA1)
- `T-03-03` Implementar la validación explícita de rutas del bundle en la API: relativas, sin `..`, sin barra inicial y con la extensión permitida para su rol (`.java` para solución y test, `.xml` para configuración), con código `INVALID_PATH`. (CA2)
- `T-03-04` Implementar la validación del paquete reservado de los tests, solo para el perfil `java21-junit`, con código `RESERVED_PACKAGE`. (CA3) — bloqueada: T05 debe declarar cuál es el paquete reservado.
- `T-03-05` Implementar la respuesta `404` con `EXECUTION_NOT_FOUND` para una ejecución inexistente. (CA4)
- `T-03-06` Revisar los mensajes de error para que no expongan detalle interno de las validaciones.
- `T-03-07` Escribir una prueba por código de error y la prueba de contrato del tipo de contenido.

### HU-04 — Consultar el estado y el resultado de una ejecución (Épica 1)

- `T-04-01` Implementar `GET /api/sandbox/executions/{id}` como lectura directa sobre la tabla de ejecuciones. (CA1)
- `T-04-02` Definir la representación por fase, sin resultado final mientras la ejecución está en `QUEUED` o `RUNNING`. (CA2)
- `T-04-03` Incluir la salida cruda disponible cuando la ejecución alcanza un estado terminal. (CA3)
- `T-04-04` Devolver `404` con `EXECUTION_NOT_FOUND` ante un identificador inexistente. (CA4)
- `T-04-05` Documentar el endpoint en la especificación de la API.
- `T-04-06` Escribir las pruebas de los tres escenarios de la historia.

### HU-05 — Que la entrega se evalúe aunque haya un pico o se reinicie un servicio (Épica 2)

- `T-05-01` Crear el módulo del servicio `worker` con su empaquetado y su imagen de contenedor.
- `T-05-02` Modelar el sobre de trabajo y su validación de entrada.
- `T-05-03` Implementar el empaquetado del bundle para enviarlo al ejecutor.
- `T-05-04` Implementar el cliente del protocolo hacia el ejecutor, con manejo de saturación y una taxonomía de errores propia.
- `T-05-05` Implementar el banco de slots de ejecución del worker.
- `T-05-06` Declarar la cola de trabajo como durable, con mensajes persistentes y confirmaciones del publicador. (CA1)
- `T-05-07` Implementar el consumo con confirmación manual posterior al procesamiento del job. (CA2)
- `T-05-08` Limitar la cantidad de mensajes en vuelo por worker a la cantidad de slots disponibles. (CA4)
- `T-05-09` Configurar la cola de mensajes fallidos con una política de reintentos acotada. (CA5)
- `T-05-10` Marcar la ejecución como `INTERNAL_ERROR`, sin consumir vida ni intento, en el mismo movimiento en que el mensaje se envía a la cola de fallidos. (CA5)
- `T-05-11` Implementar el apagado ordenado que espera la finalización de los hilos en vuelo antes de cerrar el cliente del ejecutor.
- `T-05-12` Implementar el watchdog que marca `INTERNAL_ERROR` en las ejecuciones estancadas más allá del timeout máximo.
- `T-05-13` Escribir las pruebas de reencolado por caída del worker, de mensaje envenenado y de límite de mensajes en vuelo. (CA3)
- `T-05-14` Definir la alerta operativa por profundidad de la cola de mensajes fallidos mayor a cero. (extra)
- `T-05-15` Crear el usuario de base de datos del worker con permiso de actualización solo sobre las columnas de estado y resultado de la tabla de ejecuciones.
- `T-05-16` Marcar la ejecución como `RUNNING` al tomar el job, con una actualización condicional desde `QUEUED` para que una reentrega no haga retroceder el estado.
- `T-05-17` Persistir el estado terminal y la salida cruda en una única actualización condicional, idempotente ante la reentrega del mensaje, antes de confirmarlo.
- `T-05-18` Escribir las pruebas de reentrega después de persistir (no duplica ni pisa el resultado) y de caída del worker entre la escritura y la confirmación.
- `T-05-19` Implementar en la API el tope de profundidad de cola, contando las ejecuciones en `QUEUED`, y responder `429` con código `QUEUE_FULL` y header `Retry-After` sin persistir ni encolar la entrega, con su prueba. (CA6)

### HU-06 — Que el código del alumno no pueda llegar a la red, al host ni a otros servicios (Épica 3)

- `T-06-01` Crear el módulo del servicio `ejecutor` con su empaquetado y su imagen de contenedor.
- `T-06-02` Integrar el cliente del motor de contenedores y el ciclo de vida completo del contenedor efímero.
- `T-06-03` Implementar la especificación fija del contenedor: sin interfaz de red, filesystem de solo lectura, sin capabilities, sin escalamiento de privilegios, usuario no privilegiado y mounts vacíos. (CA1, CA2)
- `T-06-04` Blindar la especificación para que ningún campo provenga del request de la entrega, con una prueba de regresión que lo garantice. (CA4)
- `T-06-05` Implementar el envío del código y los tests por copia a través de la entrada estándar, sin ninguna descarga de red.
- `T-06-06` Implementar el servidor del protocolo entre el worker y el ejecutor.
- `T-06-07` Implementar el barrido de contenedores huérfanos.
- `T-06-08` Implementar el registro de eventos correlacionado por identificador de ejecución.
- `T-06-09` Implementar la validación estructural del bundle con lista blanca de tipos de entrada: aceptar solo archivos regulares y directorios, y rechazar FIFOs, dispositivos, sockets, symlinks y hard links, con falla cerrada. (CA3)
- `T-06-10` Implementar la validación de nombres de entrada con topes de cantidad, largo y profundidad.
- `T-06-11` Decidir en qué componente vive la validación estructural del bundle. — definición abierta del equipo.
- `T-06-12` Armar los bundles de prueba de intento de conexión de red, de entrada symlink y de entrada FIFO. (CA5)
- `T-06-13` Documentar el riesgo residual de kernel compartido y decidir si se incorpora un proxy del motor de contenedores con lista blanca de operaciones.
- `T-06-14` Cablear las pruebas de integración contra un motor de contenedores real en el pipeline. (extra)

### HU-07 — Que cada ejecución respete los límites de CPU, memoria y tiempo del perfil (Épica 3)

- `T-07-01` Implementar la carga del catálogo estático de los tres perfiles (`java21-junit`, `java21-pmd`, `java21-checkstyle`) desde su definición versionada.
- `T-07-02` Implementar la validación de esquema cerrado del catálogo, que rechaza al arrancar memoria, CPU o versión ausentes, en cero, negativas o por encima del techo de plataforma, para cualquiera de los tres perfiles. (CA5)
- `T-07-03` Definir los tres perfiles fijos del catálogo con sus límites y garantizar que no sean editables desde el request. (CA1)
- `T-07-04` Construir la imagen de ejecución `java21-junit` (JUnit, ejecuta código del alumno).
- `T-07-05` Implementar la primera capa de la imagen `java21-junit`: desempaquetado, compilación e invocación de la suite de tests.
- `T-07-06` Implementar los tres relojes de `java21-junit`: tiempo de CPU del alumno, reloj de pared de compilación y reloj de pared de respaldo.
- `T-07-07` Marcar el estado `TIMEOUT` por agotamiento del tiempo de CPU del alumno y no por reloj de pared, en `java21-junit`. (CA2)
- `T-07-08` Marcar el estado `MEMORY_LIMIT` tanto cuando el límite de memoria del contenedor mata el proceso como cuando la máquina virtual termina por su propio manejo de falta de memoria, en `java21-junit`. (CA3)
- `T-07-09` Marcar el estado `INTERNAL_ERROR` al vencer el reloj de compilación de `java21-junit`, sin consumir vida ni intento del alumno. (CA4)
- `T-07-10` Configurar el porcentaje de heap de la máquina virtual acorde al límite de memoria del perfil `java21-junit`.
- `T-07-11` Armar los bundles de prueba de bucle infinito, de fuga de memoria y de proceso que duerme sin consumir CPU, sobre `java21-junit`.
- `T-07-12` Medir el presupuesto de referencia por ejecución de `java21-junit` y registrar el dimensionamiento del pool. (extra)
- `T-07-13` Versionar las imágenes de ejecución y construirlas en el pipeline.
- `T-07-14` Construir la imagen `java21-pmd` con Apache PMD para análisis estático sobre las fuentes del alumno, usando el archivo de reglas provisto en el bundle; no ejecuta código del alumno.
- `T-07-15` Construir la imagen `java21-checkstyle` con Checkstyle sobre las fuentes del alumno, usando el archivo de configuración provisto en el bundle; no ejecuta código del alumno.
- `T-07-16` Agregar ArchUnit como librería en el classpath de tests de la imagen `java21-junit`, sin crear un perfil separado (corre como tests de JUnit).
- `T-07-17` Exponer el catálogo de los tres perfiles en `GET /languages`. (CA7)
- `T-07-18` Medir y fijar los límites de CPU, memoria y timeout de análisis de `java21-pmd` y `java21-checkstyle` (a definir por medición) y cargarlos en el catálogo.
- `T-07-19` Escribir una prueba de integración por perfil (`java21-junit`, `java21-pmd`, `java21-checkstyle`), incluida la de rechazo por `UNSUPPORTED_LANGUAGE` cuando el identificador de perfil no existe.

### HU-08 — Capturar y devolver la salida cruda del programa y el reporte de la herramienta (Épica 3)

- `T-08-01` Implementar la segunda capa de la imagen: lanzador de la herramienta del perfil (tests para `java21-junit`, PMD o Checkstyle para los otros dos) y buzón de reportes.
- `T-08-02` Cerrar los descriptores de archivo heredados antes de invocar la herramienta del perfil, para que el tope de salida capturada no pueda evadirse por un canal heredado. (CA2)
- `T-08-03` Implementar el acumulador de salida con su tope de bytes por flujo. (CA2)
- `T-08-04` Implementar el desempaquetado del paquete de reportes con falla cerrada y topes de cantidad de entradas, largo de nombre y profundidad, aceptando solo archivos regulares. (CA3)
- `T-08-05` Aplicar el tope de tamaño del paquete de reportes. (CA3)
- `T-08-06` Exponer en el resultado la salida cruda (salida estándar y de error) y el/los archivo(s) de reporte de la herramienta del perfil, tal como los produjo, sin interpretarlos. (CA1, CA4)
- `T-08-07` Armar los bundles de prueba de salida anticipada con código cero, de reporte falsificado y de bomba de desempaquetado, para verificar que el sandbox los devuelve crudos sin clasificarlos.
- `T-08-08` Comunicar por escrito al equipo y a la cátedra que la salida cruda puede ser falsificada por el código del alumno y que T05 no debe tratarla todavía como una calificación auténtica.
- `T-08-09` Agregar la columna del código de salida de la herramienta a la tabla de ejecuciones e incluirlo en el resultado crudo, sin interpretarlo, con su prueba. (CA1)

### HU-09 — Un chequeo de salud que distinga "saturado" de "roto" (Épica 3, Could)

- `T-09-01` Exponer `liveness` y `readiness` como indicadores separados en el endpoint de salud. (CA1)
- `T-09-02` Implementar el indicador que verifica que el catálogo de perfiles no esté vacío. (CA5)
- `T-09-03` Implementar el indicador de conectividad con el motor de contenedores.
- `T-09-04` Reportar `readiness` en `DOWN` con `503` cuando la base de datos está inaccesible, manteniendo `liveness` en `UP`. (CA3)
- `T-09-05` Mantener `readiness` en `UP` y seguir aceptando entregas con `202` cuando el broker está inaccesible pero la base de datos responde. (CA4)
- `T-09-06` Mantener `liveness` y `readiness` en `UP` con la cola llena, sin tratar la saturación como falla; el rechazo `429` de cola llena se implementa en HU-05. (CA2)
- `T-09-07` Escribir una prueba por cada situación de la tabla de comportamiento de la historia.
- `T-09-08` Implementar la ejecución canaria periódica que alimenta el chequeo de salud. (extra)

### HU-10 — Recibir un aviso cuando termina una ejecución (Épica 1)

- `T-10-01` Definir el contrato del evento `ExecutionCompleted` (campos, clave y versión) y acordarlo con el grupo de notificaciones y con T05. (CA1, CA2)
- `T-10-02` Ampliar el usuario de base de datos del worker creado en `T-05-15` con permiso de inserción sobre la tabla de outbox.
- `T-10-03` Insertar el evento en la tabla de outbox en la misma transacción en la que se persiste el estado terminal (`T-05-17`), solo cuando la actualización condicional modificó la ejecución. (CA3, CA5)
- `T-10-04` Emitir el evento también al marcar `INTERNAL_ERROR` por la cola de mensajes fallidos (`T-05-10`) y por el watchdog (`T-05-12`). (CA6)
- `T-10-05` Enrutar en el proceso relay por tipo de mensaje: el trabajo a la cola interna y `ExecutionCompleted` al topic del bus de eventos, con `executionId` como clave. (CA1, CA4)
- `T-10-06` Configurar el productor del bus de eventos (conexión, autenticación y topic) por configuración, sin valores fijos en el código.
- `T-10-07` Escribir las pruebas de los tres escenarios de la historia y del evento emitido por la cola de mensajes fallidos y por el watchdog.
- `T-10-08` Documentar el evento `ExecutionCompleted` en la especificación de la API.

---

## Definiciones que bloquean tareas

- **Autenticación servicio a servicio entre T05 y el sandbox a través del Gateway** — condiciona el cierre de HU-01 y HU-04.
- **Paquete reservado de los tests, que T05 debe declarar** — bloquea `T-03-04`.
- **Contrato del request del bundle (formato y identificador de perfil), a confirmar con T05** — condiciona HU-01.
- **Estabilidad de la clave natural de idempotencia entre reintentos, a garantizar por T05** — condiciona HU-02.
- **Componente donde vive la validación estructural del bundle** — decisión interna, `T-06-11`.
- **Definiciones pendientes con el grupo de notificaciones (nombre del topic, formato del sobre, autenticación, particiones y retención del bus de eventos de la plataforma)** — condiciona HU-10.

---

## Orden sugerido de ejecución

El camino crítico pasa primero por el ejecutor y la imagen de ejecución: son la base sobre la
que se valida el aislamiento y los límites de recursos, y sin ellos no hay forma de correr una
ejecución de punta a punta ni de verificar el resto del sistema. Recién con el ejecutor
disponible tiene sentido construir el worker, que depende de su protocolo y de su ciclo de vida
para poder encolar y coordinar trabajo real. La API, en cambio, puede avanzar en paralelo desde
el arranque: su propio alcance (recepción, idempotencia, errores estables y consulta de estado)
no depende de que el worker ni el ejecutor estén terminados, solo del contrato de mensajería y
del modelo de datos que define HU-01.

Conviene resolver temprano las tareas de entorno de desarrollo y de pipeline de integración
continua (dentro de HU-01), porque las pruebas de integración de las tres épicas — incluidas las
de HU-05 y HU-06 contra un motor de contenedores real — dependen de tener ese entorno armado.
Postergarlas obliga a probar cada módulo de forma aislada y a rehacer esa integración más tarde,
bajo presión de tiempo.

Dentro de la Épica 3, conviene cerrar primero la especificación de aislamiento y la validación
estructural del bundle (HU-06) antes que los relojes y los estados técnicos de recursos (HU-07), porque
HU-07 construye su imagen de ejecución sobre el mismo contenedor cuya especificación fija define
HU-06. HU-08 depende a su vez de que la imagen de HU-07 ya tenga su primera capa (compilación y
ejecución) para poder agregar la segunda capa de evaluación y reportes.

HU-10 depende del outbox y del proceso relay que construye HU-01, y de que HU-05 ya persista el
estado terminal de la ejecución, sobre el que se inserta el evento de finalización; conviene
encararla una vez que esas dos historias estén resueltas.
