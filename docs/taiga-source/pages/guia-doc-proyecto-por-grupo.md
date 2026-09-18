---
taiga_id: 226859
taiga_slug: guia-doc-proyecto-por-grupo
taiga_version: 3
taiga_modified_date: 2026-08-27T01:13:24.763Z
project_id: 1804026
project_slug: tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion
source_url: https://tree.taiga.io/project/tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion/wiki/guia-doc-proyecto-por-grupo
---

# Guía para la Documentación del Proyecto en la Wiki de Taiga


## Objetivo


Establecer una metodología uniforme para que todos los grupos documenten el desarrollo de su proyecto dentro de la Wiki de **Taiga**, asegurando coherencia, trazabilidad y claridad en la presentación del trabajo.


---


## 1. Estructura y ubicación


Cada grupo deberá registrar la documentación de su proyecto dentro de la **Wiki de Taiga**, creando una página por tema o funcionalidad desarrollada.


La documentación debe organizarse siguiendo las pautas que se detallan a continuación.


---


## 2. Regla de nombrado


Cada página de la Wiki deberá nombrarse con el siguiente formato:


> **GXX - TEMA**


**Ejemplos:**


`G03 - Gestión de Clientes`  
`G07 - Módulo de Pedidos`


Donde:


- **GXX** → número del grupo (por ejemplo, G01, G02, G03, etc.).
- **TEMA** → título o funcionalidad principal que se documenta.


---


## 3. Estructura del contenido


Cada página deberá incluir los siguientes apartados:


### a) Título del tema


Debe coincidir con el nombre asignado en la regla de nombrado.


**Ejemplo:**


> G03 - Gestión de Clientes


### b) Descripción del tema u objeto


Breve explicación que indique el propósito o alcance del tema documentado.


**Ejemplo:**


> Este módulo gestiona las operaciones de alta, baja y modificación de clientes, incluyendo validaciones de datos y vinculación con el sistema de pedidos.


### c) Referencia a Historia(s) de Usuario (HU)


Debe indicarse la o las **Historias de Usuario del backlog** que se vinculan con este tema, para mantener la trazabilidad entre los requerimientos funcionales y su implementación técnica.


**Ejemplo:**


> **HU-03:** Como empleado, quiero registrar un nuevo cliente para poder asociarle pedidos.  
> **HU-07:** Como usuario, quiero modificar los datos de un cliente existente para mantener la información actualizada.


### d) Diagramas requeridos


Agregar los diagramas necesarios respetando la siguiente secuencia:


**Secuencia definida:**


`DER → Clases → Estados → Secuencias → Microservicios`


### e) Enlace al diagrama en Draw.io


Incluir el enlace directo al archivo correspondiente en **Draw.io**.


**Ejemplo:**


> [Enlace al diagrama – Gestión de Clientes](https://app.diagrams.net/)


### f) Explicación del diagrama


Redactar una breve explicación interpretativa de cada diagrama, **destacando los elementos clave o las decisiones de diseño**.


**Ejemplo:**


> En el diagrama DER se representan las entidades Cliente y Pedido, vinculadas mediante una relación uno a muchos.  
> Se destacan las claves primarias (`cliente_id`, `pedido_id`) y las claves foráneas que aseguran la integridad referencial.


---


## 4. Buenas prácticas


- Mantener una **redacción clara, técnica y uniforme**.
- Evitar duplicar información en distintas páginas.
- Usar **enlaces internos** en la Wiki para conectar temas relacionados.
- Actualizar la documentación cada vez que se realicen **cambios significativos** en el sistema.
- Asegurar que los **diagramas sean legibles, actualizados y consistentes** entre sí.
- En caso de referenciar una **HU**, verificar que exista y esté correctamente documentada en el backlog.


---


## 5. Nota final


El objetivo de este proceso es construir una **documentación colaborativa, trazable y progresiva** del proyecto, donde cada grupo aporte de manera ordenada su parte del sistema, facilitando la **integración final y la revisión global**.

