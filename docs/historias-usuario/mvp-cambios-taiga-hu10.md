# Cambios a cargar en Taiga — aviso de finalización (HU-10) y código de salida

## Cómo usar este documento

Este documento lista **solo lo que cambia** en Taiga por dos decisiones:

1. **Aviso de finalización:** incorporar al MVP el evento `ExecutionCompleted` por el bus de
   eventos de la plataforma, en una historia nueva (HU-10).
2. **Código de salida:** el resultado crudo incluye el código de salida de la herramienta del
   perfil, sin interpretarlo (HU-04 y HU-08).

Los ítems que no aparecen acá no cambian.

- **Ítems existentes:** para cada uno se indica qué campo cambia, el texto actual y el texto nuevo.
  Se reemplaza solo ese campo; el resto del ítem queda igual.
- **Ítem nuevo:** HU-10 se crea completa, con sus tareas.

**Orden de carga:**

1. Actualizar la Épica 1 y la Épica 2.
2. Actualizar HU-01, HU-04, HU-05 y HU-08.
3. Crear HU-10 dentro de la Épica 1.
4. Cargar las tareas de HU-10 y la tarea nueva de HU-08.

**Resumen de puntos:** Épica 1 pasa de 16 a **21** (HU-10: 5). Épica 2 = 13 y Épica 3 = 34 no
cambian: el código de salida no cambia la estimación de HU-08. Total: de 63 a **68 puntos**.
Tareas: de 103 a **112** (8 de HU-10 y 1 de HU-08).

**Ninguna tarea existente cambia.** Las tareas de HU-10 amplían lo que construyen `T-05-10`,
`T-05-12`, `T-05-15` y `T-05-17`, y la tarea nueva de HU-08 amplía `T-01-02` y `T-08-06`, sin
modificar su texto.

---

## Épica 1: Recepción de ejecuciones (API)

### Objetivo

**Actual:**

> Proveer la interfaz HTTP de ingreso de entregas del alumno hacia el sandbox, aceptándolas de forma
> asíncrona y confiable mediante un outbox transaccional, garantizando idempotencia ante reintentos
> de red, devolviendo errores claros y estables cuando una entrega es inválida, y permitiendo
> consultar en cualquier momento el estado y el resultado de una ejecución.

**Nuevo:**

> Proveer la interfaz HTTP de ingreso de entregas del alumno hacia el sandbox, aceptándolas de forma
> asíncrona y confiable mediante un outbox transaccional, garantizando idempotencia ante reintentos
> de red, devolviendo errores claros y estables cuando una entrega es inválida, permitiendo
> consultar en cualquier momento el estado y el resultado de una ejecución, y avisando por el bus de
> eventos de la plataforma cuando una ejecución termina.

### Criterios de Aceptación a nivel Épico

**Se agrega** al final de la lista:

> * Cuando una ejecución llega a un estado terminal, el sandbox publica un evento
>   `ExecutionCompleted` con su estado en el bus de eventos de la plataforma, sin incluir la salida;
>   T05 la obtiene por polling (HU-10).

### Estimación agregada de la épica

**Actual:** 16 puntos (HU-01: 8, HU-02: 3, HU-03: 3, HU-04: 2).

**Nuevo:** 21 puntos (HU-01: 8, HU-02: 3, HU-03: 3, HU-04: 2, HU-10: 5).

### Dependencias / Impactos

| Campo | Actual | Nuevo |
|---|---|---|
| Servicios / APIs | API Gateway, T05, base de datos, broker de mensajería. | API Gateway, T05, base de datos, cola de trabajo interna, bus de eventos de la plataforma (Kafka). |
| Módulos afectados | capa REST de recepción de entregas, publicador de outbox y su tabla, proceso relay periódico, repositorio de ejecuciones. | capa REST de recepción de entregas, publicador de outbox y su tabla, proceso relay periódico (publica el trabajo en la cola interna y los eventos en el bus de la plataforma), repositorio de ejecuciones. |
| Otros equipos | T05 (confirmación del contrato de request, del paquete reservado de tests y de la autenticación servicio a servicio). | T05 (confirmación del contrato de request, del paquete reservado de tests, de la autenticación servicio a servicio y del consumo del evento `ExecutionCompleted`); grupo de notificaciones (topic, formato del sobre, autenticación y retención del bus de eventos). |

---

## Épica 2: Procesamiento asíncrono (cola y worker)

### Dependencias / Impactos → Impacto en datos / migraciones

**Actual:**

> ninguna tabla nueva: el worker actualiza las columnas de estado y resultado de la tabla de
> ejecuciones creada en la Épica 1, con un usuario de base de datos limitado a esas columnas.

**Nuevo:**

> ninguna tabla nueva: el worker actualiza las columnas de estado y resultado de la tabla de
> ejecuciones creada en la Épica 1, e inserta el evento de finalización en la tabla de outbox
> (HU-10), con un usuario de base de datos limitado a esas operaciones.

---

## HU-01 — Enviar una entrega y recibir confirmación inmediata

### Criterios de Aceptación → Extras (opcional)

**Actual:**

> el evento `ExecutionCompleted` que informa el resultado final viaja por el bus de eventos de la
> plataforma, no por el Gateway; su articulación exacta con el outbox interno de la cola de trabajo
> todavía no está resuelta con los demás grupos (ver cierre del documento).

**Nuevo:**

> el aviso de finalización (`ExecutionCompleted`) viaja por el bus de eventos de la plataforma, no
> por el Gateway, y lo publica el mismo proceso relay del outbox; se cubre en HU-10.

---

## HU-04 — Consultar el estado y el resultado de una ejecución

### Notas / Observaciones → Otros

**Actual:**

> el envío del evento `ExecutionCompleted` por el bus asincrónico (para no depender solo de
> polling) todavía no está resuelto con el panorama completo de eventos de la plataforma; por eso
> queda explícitamente fuera del alcance MVP de esta historia (ver sección de cierre).

**Nuevo:**

> el aviso de finalización por el bus de eventos de la plataforma (`ExecutionCompleted`) se cubre
> en HU-10. Ese evento solo informa el estado: la salida cruda se obtiene siempre con esta
> consulta, que sigue siendo la fuente de verdad.

### Criterios de Aceptación → Extras (opcional)

**Actual:**

> notificación por evento (`ExecutionCompleted`) queda fuera de esta historia (ver cierre del
> documento).

**Nuevo:**

> la notificación por evento (`ExecutionCompleted`) se cubre en HU-10.

### Criterios de Aceptación → CA3

**Actual:**

> una vez que la ejecución alcanza un estado terminal (por ejemplo `COMPLETED`, `TIMEOUT`,
> `MEMORY_LIMIT`), la respuesta incluye la salida cruda disponible (ver HU-08), sin que el sandbox
> agregue una interpretación de éxito o fallo.

**Nuevo:**

> una vez que la ejecución alcanza un estado terminal (por ejemplo `COMPLETED`, `TIMEOUT`,
> `MEMORY_LIMIT`), la respuesta incluye la salida cruda disponible y el código de salida de la
> herramienta (ver HU-08), sin que el sandbox agregue una interpretación de éxito o fallo.

### BDD → Escenario 3 → Entonces

**Actual:**

> el sandbox responde `200` con `status: COMPLETED` y la salida cruda disponible (salida estándar,
> de error y reporte de la herramienta) para que T05 la interprete antes de mostrarle algo al alumno

**Nuevo:**

> el sandbox responde `200` con `status: COMPLETED` y la salida cruda disponible (salida estándar,
> de error, reporte de la herramienta y su código de salida) para que T05 la interprete antes de
> mostrarle algo al alumno

---

## HU-05 — Que la entrega se evalúe aunque haya un pico de envíos o se reinicie un servicio

### Notas / Observaciones → Reglas de negocio

Cambia solo la última oración.

**Actual:**

> ... la API es la dueña del esquema (sus migraciones) y el worker solo actualiza las columnas de
> estado y resultado de la tabla de ejecuciones.

**Nuevo:**

> ... la API es la dueña del esquema (sus migraciones) y el worker solo actualiza las columnas de
> estado y resultado de la tabla de ejecuciones y, en la misma transacción del estado terminal,
> inserta el evento de finalización en la tabla de outbox (HU-10).

### Dependencias / Impactos → Impacto en datos / migraciones

**Actual:**

> ninguno adicional a lo definido en HU-01; se reutiliza la tabla de ejecuciones.

**Nuevo:**

> ninguno adicional a lo definido en HU-01; se reutilizan la tabla de ejecuciones y la de outbox.

---

## HU-08 — Capturar y devolver la salida cruda del programa y el reporte de la herramienta

### Notas / Observaciones → Reglas de negocio

Cambia solo la segunda oración.

**Actual:**

> ... Se limita a devolver la salida cruda: salida estándar, salida de error y el/los archivo(s) de
> reporte de la herramienta del perfil (por ejemplo, el reporte JUnit, el reporte PMD o el reporte
> Checkstyle), cada uno capado en tamaño, tal como los produjo la herramienta. ...

**Nuevo:**

> ... Se limita a devolver la salida cruda: salida estándar, salida de error y el/los archivo(s) de
> reporte de la herramienta del perfil (por ejemplo, el reporte JUnit, el reporte PMD o el reporte
> Checkstyle), cada uno capado en tamaño, tal como los produjo la herramienta, junto con el código
> de salida de la herramienta, sin interpretarlo. ...

### Notas / Observaciones → Datos obligatorios

**Actual:**

> el resultado de la ejecución incluye la salida estándar y de error capturadas, y el/los
> archivo(s) de reporte de la herramienta del perfil, cada uno con el tope de tamaño aplicado.

**Nuevo:**

> el resultado de la ejecución incluye la salida estándar y de error capturadas, el/los archivo(s)
> de reporte de la herramienta del perfil, cada uno con el tope de tamaño aplicado, y el código de
> salida de la herramienta (nulo si la herramienta no llegó a correr).

### Criterios de Aceptación → CA1

**Actual:**

> el resultado de una ejecución incluye la salida estándar y de error capturadas durante la
> ejecución, y el/los archivo(s) de reporte producidos por la herramienta del perfil, sin que el
> sandbox los interprete ni agregue un veredicto propio.

**Nuevo:**

> el resultado de una ejecución incluye la salida estándar y de error capturadas durante la
> ejecución, el/los archivo(s) de reporte producidos por la herramienta del perfil y el código de
> salida de la herramienta, sin que el sandbox los interprete ni agregue un veredicto propio.

### Tarea nueva

- `T-08-09` Agregar la columna del código de salida de la herramienta a la tabla de ejecuciones e incluirlo en el resultado crudo, sin interpretarlo, con su prueba. (CA1)

---

## HU-10 — Recibir un aviso cuando termina una ejecución (nueva)

> **Taiga:** crear dentro de la Épica 1.

### Descripción (Como / Quiero / Para)

- **Como:** T05 (Desafíos Prácticos)
- **Quiero:** recibir un aviso por el bus de eventos de la plataforma cuando una ejecución termina
- **Para:** mostrarle al alumno el feedback de su entrega sin depender de consultar el estado una y otra vez

### Notas / Observaciones

- **Reglas de negocio:** cuando una ejecución llega a un estado terminal (`COMPLETED`, `TIMEOUT`, `MEMORY_LIMIT` o `INTERNAL_ERROR`), el sandbox publica un evento `ExecutionCompleted` en el bus de eventos de la plataforma (Kafka), que mantiene el grupo de notificaciones. El evento **solo informa el estado**: T05 obtiene la salida cruda con `GET /api/sandbox/executions/{id}`, que sigue siendo la fuente de verdad. El worker inserta el evento en la tabla de outbox en la misma transacción en la que persiste el estado terminal, y el proceso relay lo publica en el bus. La cola de trabajo sigue siendo interna del sandbox: el bus de la plataforma solo transporta el aviso.
- **Validaciones:** se emite un único evento por ejecución. Una reentrega del mensaje de trabajo después de persistido el estado terminal no inserta un segundo evento. Los estados terminales que no produce la ejecución normal también emiten el evento: el `INTERNAL_ERROR` que se marca al enviar un mensaje a la cola de mensajes fallidos y el que marca el watchdog.
- **Datos obligatorios:** `executionId` (también es la clave del mensaje en el bus) y `status`. `[A DEFINIR]` — nombre del topic, formato del sobre (campos comunes de la plataforma, versión del esquema) y marca temporal, a acordar con el grupo de notificaciones.
- **Performance (tiempos, volumen, límites):** el evento es chico y no lleva la salida: los reportes pueden llegar a 8 MiB, por encima del tamaño de mensaje habitual del bus.
- **Seguridad:** `[A DEFINIR]` — la autenticación del sandbox como productor del bus la define el grupo de notificaciones.
- **Accesibilidad (WCAG/teclado/lectores):** No aplica: servicio backend sin interfaz.
- **Otros:** la entrega es *at least once*: T05 debe tolerar eventos duplicados, identificándolos por `executionId`. Usar `executionId` como clave garantiza que los eventos de una misma ejecución conserven su orden.

### Criterios de Aceptación (CA)

- **CA1:** cuando una ejecución llega a un estado terminal, se publica en el bus de eventos de la plataforma un evento `ExecutionCompleted` con `executionId` y `status`, con `executionId` como clave del mensaje.
- **CA2:** el evento no incluye la salida cruda ni los reportes; un `GET /api/sandbox/executions/{id}` posterior al evento devuelve el estado terminal y la salida cruda.
- **CA3:** el evento se inserta en la tabla de outbox en la misma transacción que el estado terminal: si la transacción falla, no queda ni el estado terminal ni el evento.
- **CA4:** si el bus de eventos está inaccesible, el evento queda retenido en la tabla de outbox y se publica cuando el bus vuelve, sin afectar al consumo de la cola de trabajo.
- **CA5:** una reentrega del mensaje de trabajo después de persistido el estado terminal no genera un segundo evento.
- **CA6:** una ejecución marcada `INTERNAL_ERROR` por la cola de mensajes fallidos o por el watchdog también emite su evento `ExecutionCompleted`.

### BDD

**Característica:** Aviso de finalización de una ejecución

**Escenario 1: Aviso al terminar una ejecución**
- **Dado:** que una ejecución está en `RUNNING`
- **Cuando:** el worker persiste su estado terminal `COMPLETED`
- **Entonces:** se publica un evento `ExecutionCompleted` con el `executionId` y `status: COMPLETED`, sin la salida cruda, y T05 obtiene la salida con `GET /api/sandbox/executions/{id}`

**Escenario 2: Aviso retenido con el bus caído**
- **Dado:** que el bus de eventos de la plataforma está inaccesible
- **Cuando:** una ejecución llega a un estado terminal
- **Entonces:** el evento queda retenido en la tabla de outbox, la cola de trabajo sigue procesando entregas, y el evento se publica cuando el bus vuelve

**Escenario 3: Reentrega sin aviso duplicado**
- **Dado:** que el worker ya persistió el estado terminal de una ejecución y murió antes de confirmar el mensaje
- **Cuando:** otro worker recibe la reentrega de ese mensaje
- **Entonces:** no se ejecuta de nuevo, no se modifica el estado terminal y no se inserta un segundo evento

### Prototipo

- **Capturas:** No aplica: servicio backend sin interfaz.
- **URL Figma:** No aplica: servicio backend sin interfaz.
- **Storybook:** No aplica: servicio backend sin interfaz.
- **Mock API / Swagger:** evento `ExecutionCompleted` en el bus de eventos de la plataforma; la salida se consulta con `GET /api/sandbox/executions/{id}`.

### Estimación / Prioridad

| Puntos (Fibonacci) | Prioridad (MoSCoW) |
| --- | --- |
| 5 | Should |

La prioridad es Should porque el cierre depende de definiciones del grupo de notificaciones; el
polling de HU-04 cubre la consulta del resultado mientras tanto.

### Dependencias / Impactos

- **Servicios involucrados:** `ms-sandbox` (worker y relay de la API), bus de eventos de la plataforma (Kafka), T05.
- **Módulos afectados:** persistencia del estado terminal en el worker, marcado de `INTERNAL_ERROR` por la cola de mensajes fallidos y por el watchdog, proceso relay del outbox (enruta por tipo de mensaje), productor del bus de eventos.
- **Otros equipos / aprobaciones:** grupo de notificaciones (nombre del topic, formato del sobre, autenticación, particiones y retención); T05 (consumo del evento y tolerancia a duplicados).
- **Impacto en datos / migraciones:** el usuario de base de datos del worker suma permiso de inserción en la tabla de outbox; la tabla de outbox distingue el mensaje de trabajo del evento de finalización por su tipo.
- **Riesgos y mitigación:** la historia no puede cerrarse sin las definiciones del grupo de notificaciones; se mitiga dejando el topic y la conexión como configuración y probando contra un bus local. Eventos duplicados por la entrega *at least once*; se mitiga con la clave `executionId` y la tolerancia a duplicados en T05.

---

## Tareas de HU-10

- `T-10-01` Definir el contrato del evento `ExecutionCompleted` (campos, clave y versión) y acordarlo con el grupo de notificaciones y con T05. (CA1, CA2)
- `T-10-02` Ampliar el usuario de base de datos del worker creado en `T-05-15` con permiso de inserción sobre la tabla de outbox.
- `T-10-03` Insertar el evento en la tabla de outbox en la misma transacción en la que se persiste el estado terminal (`T-05-17`), solo cuando la actualización condicional modificó la ejecución. (CA3, CA5)
- `T-10-04` Emitir el evento también al marcar `INTERNAL_ERROR` por la cola de mensajes fallidos (`T-05-10`) y por el watchdog (`T-05-12`). (CA6)
- `T-10-05` Enrutar en el proceso relay por tipo de mensaje: el trabajo a la cola interna y `ExecutionCompleted` al topic del bus de eventos, con `executionId` como clave. (CA1, CA4)
- `T-10-06` Configurar el productor del bus de eventos (conexión, autenticación y topic) por configuración, sin valores fijos en el código.
- `T-10-07` Escribir las pruebas de los tres escenarios de la historia y del evento emitido por la cola de mensajes fallidos y por el watchdog.
- `T-10-08` Documentar el evento `ExecutionCompleted` en la especificación de la API.
