# Convenciones de Taiga y documentación del proyecto

Esta guía reúne las reglas confirmadas en la wiki del proyecto **Plataforma de Aprendizaje Gamificado de Programación**. El snapshot fuente fue extraído por API el 17 de septiembre de 2026 y está registrado en [`taiga-source/manifest.json`](taiga-source/manifest.json).

Las páginas exportadas son la fuente de verdad. Esta guía resume su contenido para el trabajo cotidiano; no completa con supuestos las reglas que la wiki todavía no define.

## Uso obligatorio por personas y asistentes de IA

Antes de proponer una creación o modificación en Taiga:

1. Confirmar por lectura el nombre, slug e ID del proyecto.
2. Consultar esta guía y la página fuente aplicable.
3. Preparar la operación exacta, con todos los campos, relaciones y valores.
4. Mostrar ese payload y citar la convención utilizada.
5. Pedir autorización explícita para esa única escritura.
6. Ejecutar solamente lo aprobado y volver a leer el elemento para verificarlo.

En producción no se usan eliminaciones. Los usuarios del equipo no tienen permiso para borrar y el proyecto se evalúa de forma constante, por lo que una creación errónea puede quedar visible y tener consecuencias académicas. Las operaciones masivas requieren una autorización específica para el lote completo.

El procedimiento técnico y el texto que debe recibir cada IA están en [`taiga-mcp.md`](taiga-mcp.md#protocolo-obligatorio-para-producción).

## Índice de fuentes

| Categoría | Página fuente | Secciones |
| --- | --- | --- |
| Épicas | [`template-epicas.md`](taiga-source/pages/template-epicas.md) | `Objetivo`; `Suposiciones y Restricciones`; `Criterios de Aceptación a nivel Épico`; `Dependencias / Impactos` |
| Historias de usuario | [`template-hist-usuario.md`](taiga-source/pages/template-hist-usuario.md) | `Descripción`; `Notas / Observaciones`; `Criterios de Aceptación`; `BDD`; `Prototipo`; `Estimación / Prioridad`; `Dependencias / Impactos` |
| Documentación por grupo | [`guia-doc-proyecto-por-grupo.md`](taiga-source/pages/guia-doc-proyecto-por-grupo.md) | `Regla de nombrado`; `Estructura del contenido`; `Buenas prácticas` |
| Template de documentación | [`template-proyecto-por-grupo.md`](taiga-source/pages/template-proyecto-por-grupo.md) | `Referencia a Historia(s) de Usuario`; `Diagramas requeridos`; `Documentación de Endpoints`; `Recomendaciones finales` |
| Diagramas | [`guia-doc-diagramas.md`](taiga-source/pages/guia-doc-diagramas.md) | `DER`; `BPMN`; `Flujograma`; `Clases`; `Estados`; `Secuencias`; `Microservicios`; `Secuencia de entrega` |
| Producto y repositorios | [`guia-doc-producto.md`](taiga-source/pages/guia-doc-producto.md) | `Normas para Bases de Datos`; `Backend`; `Frontend`; `Flujo de Trabajo`; `Validaciones`; `Seguridad` |
| Criterio general de la wiki | [`home.md`](taiga-source/pages/home.md) | `Cómo trabajar con la Wiki`; `Buenas prácticas`; `Entorno académico`; `Inteligencia Artificial` |
| Template HU recibido antes de la extracción | [`hu-template.raw.md`](taiga-source/seeds/hu-template.raw.md) | Semilla literal para control de integridad |

La wiki extraída no define convenciones específicas para tareas, gestión de sprints, estados de Taiga, Definition of Ready ni Definition of Done. Esos puntos figuran como pendientes y no deben completarse por inferencia.

## Épicas

El título sigue este formato:

```text
[GXX] — [TÍTULO DEL ÉPICO]
```

Cada épica debe incluir:

- Un objetivo de una o dos líneas que explique el valor entregado al usuario o al negocio.
- Suposiciones y restricciones legales o técnicas.
- Criterios de aceptación de nivel épico: flujo mínimo extremo a extremo, KPIs iniciales, ausencia de regresiones críticas, observabilidad y alertas, y documentación de uso y operación.
- Dependencias e impactos: servicios o APIs, módulos, otros equipos, datos o migraciones y feature flags con su plan de retiro.

Fuente: [`template-epicas.md`](taiga-source/pages/template-epicas.md), secciones `Objetivo` a `Dependencias / Impactos`.

## Historias de usuario

El título sigue este formato:

```text
[GXX] — [TÍTULO DE LA HISTORIA DE USUARIO]
```

La historia debe expresar rol, funcionalidad y valor; registrar reglas y restricciones relevantes; tener al menos tres criterios medibles y tres escenarios BDD; incluir evidencia de interfaz o API cuando corresponda; y declarar estimación, prioridad y dependencias.

### Template normalizado

```markdown
[GXX] — [TÍTULO DE LA HISTORIA DE USUARIO]

## Descripción (Como / Quiero / Para)

- **Como:** [ROL]
- **Quiero:** [FUNCIONALIDAD DESEADA]
- **Para:** [PROPÓSITO / VALOR ENTREGADO]

## Notas / Observaciones

- **Reglas de negocio:** [DETALLAR]
- **Validaciones:** [DETALLAR]
- **Datos obligatorios:** [LISTAR CAMPOS]
- **Performance (tiempos, volumen, límites):** [DETALLAR]
- **Seguridad (roles, permisos, datos sensibles):** [DETALLAR]
- **Accesibilidad (WCAG/teclado/lectores):** [DETALLAR]
- **Otros:** [DETALLAR]

## Criterios de Aceptación (CA)

- **CA1:** [CONDICIÓN MEDIBLE Y OBJETIVA]
- **CA2:** [CONDICIÓN MEDIBLE Y OBJETIVA]
- **CA3:** [CONDICIÓN MEDIBLE Y OBJETIVA]
- **Extras (opcional):** [CONDICIÓN MEDIBLE Y OBJETIVA]

## BDD (mínimo 3 escenarios)

**Característica:** [NOMBRE / OBJETIVO DE LA FUNCIONALIDAD]

### Escenario 1

- **Dado:** [CONTEXTO INICIAL / PRECONDICIONES]
- **Cuando:** [ACCIÓN DEL USUARIO O DEL SISTEMA]
- **Entonces:** [RESULTADO OBSERVABLE Y VERIFICABLE]

### Escenario 2

- **Dado:** [CONTEXTO]
- **Cuando:** [ACCIÓN]
- **Entonces:** [RESULTADO]

### Escenario 3

- **Dado:** [CONTEXTO]
- **Cuando:** [ACCIÓN]
- **Entonces:** [RESULTADO]

## Prototipo

- **Capturas:** [PEGAR AQUÍ]
- **URL Figma:** [URL DIRECTA]
- **Storybook:** [URL DIRECTA]
- **Mock API / Swagger:** [GET /api/...], [POST /api/...]

## Estimación / Prioridad

- **Puntos (Fibonacci):** [1/2/3/5/8/13]
- **Prioridad:** [Must/Should/Could/Won't] o [1..5]

## Dependencias / Impactos

- **Servicios involucrados:** [LISTAR]
- **Módulos afectados:** [LISTAR]
- **Otros equipos / aprobaciones:** [LISTAR]
- **Impacto en datos / migraciones:** [DETALLAR]
- **Riesgos y mitigación (opcional):** [DETALLAR]
```

Fuente: [`template-hist-usuario.md`](taiga-source/pages/template-hist-usuario.md), todas sus secciones. La semilla literal recibida está en [`hu-template.raw.md`](taiga-source/seeds/hu-template.raw.md). La normalización sólo corrige presentación; no cambia campos, escalas ni cantidad mínima de escenarios.

## Estimación y prioridad

- Los puntos usan Fibonacci: `1`, `2`, `3`, `5`, `8` o `13`.
- La prioridad se expresa con MoSCoW (`Must`, `Should`, `Could`, `Won't`) o con una escala numérica de `1` a `5`.
- La wiki no define equivalencias entre ambas escalas ni cuál prevalece. Se debe usar la que haya acordado el equipo para el backlog y evitar convertir valores automáticamente.

Fuente: [`template-hist-usuario.md`](taiga-source/pages/template-hist-usuario.md), sección `Estimación / Prioridad`.

## Documentación por grupo

Cada grupo crea una página por tema o funcionalidad con el nombre `GXX - TEMA`. El título interno debe coincidir. La página debe explicar propósito y alcance, vincular las HU existentes del backlog, incluir los diagramas y sus enlaces editables de Draw.io, explicar cada diagrama y registrar decisiones técnicas, dependencias y particularidades.

Cuando se documenten endpoints, cada acción debe indicar controller, nombre de la acción, método HTTP, URL, descripción, variables de ruta, request y response de ejemplo.

Reglas de mantenimiento:

- Redacción técnica, clara, uniforme y sin ambigüedades.
- No duplicar información; conectar páginas relacionadas con enlaces internos.
- Mantener documentación, diagramas, enlaces y referencias alineados con los cambios del sistema.
- Referenciar siempre las HU relacionadas y verificar que existan en el backlog.
- Explicar decisiones relevantes y dependencias entre componentes.
- Mantener la trazabilidad `Requerimientos → HU → Diseño → Diagramas → Arquitectura → Implementación`.

Fuentes: [`guia-doc-proyecto-por-grupo.md`](taiga-source/pages/guia-doc-proyecto-por-grupo.md), secciones `Estructura y ubicación` a `Buenas prácticas`; [`template-proyecto-por-grupo.md`](taiga-source/pages/template-proyecto-por-grupo.md), secciones `Referencia a Historia(s) de Usuario`, `Documentación de Endpoints` y `Recomendaciones finales`; [`home.md`](taiga-source/pages/home.md), secciones `Cómo trabajar con la Wiki` y `Buenas prácticas`.

## Diagramas

La secuencia marcada como actualizada es:

```text
DER → BPMN → Clases → Estados → Secuencias → Microservicios
```

El flujograma es opcional. Se usa para lógica interna de una función o algoritmo; BPMN corresponde a procesos que atraviesan roles, áreas o sistemas, o que contienen eventos temporales, mensajes y decisiones de negocio.

Todos los diagramas requieren una breve descripción y un enlace editable a Draw.io. Deben mantenerse coherentes con el modelo de negocio y la implementación. Los checklists completos para DER, BPMN, clases, estados, secuencias y microservicios están en [`guia-doc-diagramas.md`](taiga-source/pages/guia-doc-diagramas.md).

## Estándares técnicos confirmados

La guía de producto también fija estas bases:

- MySQL 8.x; tablas y columnas en inglés y `snake_case`; PK `id BIGINT UNSIGNED AUTO_INCREMENT`; FK `<tabla>_id`; auditoría y bajas lógicas con `is_active`; `ON DELETE RESTRICT` salvo excepción explícita.
- Java 21 y Spring Boot por capas `api`, `application`, `domain` e `infrastructure`; Bean Validation, errores HTTP unificados, Spring Data JPA, Spring Security, JWT, roles por endpoint y logs para acciones críticas.
- Contratos entre microservicios en JSON/OpenAPI y Swagger habilitado en desarrollo.
- Angular 21 con componentes standalone, rutas lazy por grupo y sin dependencias cruzadas. No se modifican `package.json` ni `package-lock.json` desde PR comunes.
- `main` y `develop` reciben cambios mediante PR y no aceptan push directo. Los checks deben pasar según la política de cada rama.

Fuente: [`guia-doc-producto.md`](taiga-source/pages/guia-doc-producto.md), secciones `Normas para Bases de Datos`, `Normas para Backend`, `Normas para Frontend`, `Flujo de Trabajo` y `Validaciones del Repositorio`.

## IA en el contexto académico

La IA puede orientar, explicar, brindar pistas, analizar errores, formular preguntas y acompañar el razonamiento. Debe guiar al estudiante sin reemplazar su proceso de aprendizaje.

Fuente: [`home.md`](taiga-source/pages/home.md), sección `Inteligencia Artificial como herramienta de aprendizaje`.

## Pendientes de validación

- **Tareas:** no hay template ni reglas de título, contenido, estimación, asignación o transición.
- **Sprints o milestones:** no hay reglas de planificación, capacidad, entrada, cierre ni arrastre.
- **Estados:** no hay un flujo autorizado de estados para épicas, HU, tareas o incidencias.
- **Definition of Ready y Definition of Done:** no aparecen definidas con esos nombres ni con una lista equivalente completa.
- **Orden de diagramas:** `guia-doc-proyecto-por-grupo` omite BPMN en una secuencia anterior, mientras `guia-doc-diagramas` declara una secuencia “actualizada” que sí lo incluye. Esta guía muestra la secuencia actualizada, pero conviene confirmar formalmente que reemplaza a la anterior.
- **Enlaces del template HU:** los campos Figma y Storybook tienen Markdown mal formado en la fuente. El template normalizado pide una URL directa, pero la wiki debe corregirse mediante una escritura autorizada por separado.
- **Bloque de comandos en la guía de producto:** el bloque que contiene `npm run lint` y `npm run build` parece no estar cerrado correctamente en la fuente.
- **Navegación:** la página `home` no figura entre los enlaces devueltos por `/wiki-links`.
- **Medio protegido:** la guía de producto incluía un token firmado dentro de una URL de imagen. El snapshot reemplazó solamente ese valor por `[REDACTED]`; el contenido original de Taiga no fue modificado.

Los diagnósticos generados automáticamente están en [`metadata/diagnostics.json`](taiga-source/metadata/diagnostics.json).

## Actualización de esta guía

Para refrescar el snapshot sin modificar Taiga:

```bash
node scripts/sync-taiga-wiki.mjs --dry-run
node scripts/sync-taiga-wiki.mjs
```

La primera orden valida identidad y acceso sin escribir archivos. La segunda reemplaza el snapshot sólo después de obtener y validar todas las páginas. Luego se revisan los cambios del manifiesto y se actualiza manualmente esta guía; el extractor no convierte reglas nuevas en política de forma automática.
