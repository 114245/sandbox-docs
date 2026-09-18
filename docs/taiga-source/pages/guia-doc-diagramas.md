---
taiga_id: 226860
taiga_slug: guia-doc-diagramas
taiga_version: 2
taiga_modified_date: 2026-08-27T01:14:56.501Z
project_id: 1804026
project_slug: tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion
source_url: https://tree.taiga.io/project/tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion/wiki/guia-doc-diagramas
---

**Documentación Técnica Pendiente – Diagramas del Sistema**
-----------------------------------------------------------


* * *


**Descripción:**  
Este apartado reúne los diagramas técnicos requeridos para la documentación del proyecto.  
Cada equipo deberá elaborar y registrar aquí las representaciones correspondientes, asegurando que cumplan con los criterios establecidos en cada checklist.  
Los diagramas deberán reflejar la estructura, comportamiento y arquitectura del sistema de manera coherente con el modelo de negocio y las implementaciones realizadas.


* * *


**1\. Diagrama DER (Entidad–Relación)**
---------------------------------------


**Propósito:** Representar la estructura lógica de la base de datos.


**Checklist:**


* [ ] Definir todas las **entidades/tablas**.
* [ ] Especificar **atributos** con sus tipos de datos.
* [ ] Indicar **relaciones** (1:1, 1:N, N:M).
* [ ] Definir **claves primarias y foráneas**.
* [ ] Incluir **restricciones**, índices y normalización básica.
* [ ] Adjuntar **enlace a Draw.io (editable)**.


* * *


**2\. Diagrama BPMN (Proceso de Negocio)**
------------------------------------------


**Propósito:** Modelar el **flujo de negocio extremo a extremo**, incluyendo actores, eventos, decisiones y orquestación entre sistemas o áreas.


**Cuándo usar BPMN:**


* El flujo involucra más de un **rol**, **área** o **sistema**.
* Existen **eventos temporales** (deadlines, expiraciones) o **eventos de mensaje** (integraciones).
* Se requiere **estandarizar o automatizar** el proceso, o dejar trazabilidad del “happy path” y sus alternativas.


**Checklist:**


* [ ] Definir **pool** del proceso y **lanes** por rol o área.
* [ ] Incluir eventos de **inicio**, **intermedios** y **finalización**.
* [ ] Modelar **tareas** y **subprocesos** con nombres verbales y resultados claros.
* [ ] Usar **gateways** adecuados (XOR, OR, AND).
* [ ] Representar **message flows** entre sistemas (no mezclar con sequence flows).
* [ ] Incorporar **boundary events** (errores, timeouts, reintentos).
* [ ] Añadir **data objects** relevantes y anotaciones para reglas clave.
* [ ] Validar conformidad **BPMN 2.0** y numerar versiones.
* [ ] Adjuntar **enlace a Draw.io (editable)**.


* * *


**2.1 Flujograma (Flowchart) – Opcional**
-----------------------------------------


**Propósito:** Aclarar la **lógica interna** de un método, servicio o algoritmo cuando un texto o pseudocódigo no alcanza.  
No reemplaza al BPMN: se utiliza solo para representar **procesos locales o internos** de una función.


**Checklist:**


* [ ] Incluir **Start/End** y mantener el flujo **unidireccional**.
* [ ] Decisiones claramente **binarizadas (sí/no)** y bucles identificados.
* [ ] Señalar **manejo de errores** y condiciones límite.
* [ ] No exceder la **complejidad visual** (si crece, dividir o pasar a BPMN).
* [ ] Mantener sincronía con el **código o pseudocódigo** correspondiente.
* [ ] Adjuntar **enlace a Draw.io (editable)**.


* * *


### **Guía de decisión rápida: BPMN vs. Flujograma**


| Situación | BPMN | Flujograma |
| --- | --- | --- |
| Múltiples roles/áreas/sistemas | ✅ | ❌ |
| Eventos (tiempo, mensaje, error) | ✅ | ❌ |
| Vista negocio extremo a extremo | ✅ | ❌ |
| Explicar un algoritmo interno | ❌ | ✅ |
| Documentar una función/clase | ❌ | ✅ |
| Preparar automatización/workflow | ✅ | ❌ |


> **Regla práctica:** Si el proceso cruza límites organizacionales o tiene eventos → **BPMN**.  
> Si es lógica interna o algoritmo puntual → **Flujograma**.


* * *


**3\. Diagrama de Clases**
--------------------------


**Propósito:** Mostrar la **estructura estática** del sistema a nivel de objetos y sus relaciones.


**Checklist:**


* [ ] Definir **clases principales**.
* [ ] Especificar **atributos** y **métodos** clave.
* [ ] Representar relaciones (**herencia**, **asociación**, **composición**).
* [ ] Incluir **interfaces** y **clases abstractas**.
* [ ] Indicar **visibilidad** (public, private, protected).
* [ ] Adjuntar **enlace a Draw.io (editable)**.


* * *


**4\. Diagrama de Estados (o Máquina de Estados)**
--------------------------------------------------


**Propósito:** Describir los diferentes **estados** de un objeto o entidad del sistema y las **transiciones** que ocurren entre ellos.


**Checklist:**


* [ ] Identificar las entidades u objetos con comportamiento dependiente de estado.
* [ ] Enumerar todos los **estados posibles**.
* [ ] Definir los **eventos o condiciones** que generan las transiciones.
* [ ] Incluir **estado inicial y final**.
* [ ] Representar **acciones internas** o de entrada/salida en cada transición.
* [ ] Adjuntar **enlace a Draw.io (editable)**.


* * *


**5\. Diagrama de Secuencias**
------------------------------


**Propósito:** Detallar la **interacción temporal** entre objetos, servicios o componentes durante la ejecución de un proceso.


**Checklist:**


* [ ] Identificar **casos de uso principales**.
* [ ] Representar la **comunicación** entre microservicios u objetos.
* [ ] Modelar **flujos complejos de negocio**.
* [ ] Incluir **manejo de errores y excepciones**.
* [ ] Incorporar **integraciones externas** cuando corresponda.
* [ ] Adjuntar **enlace a Draw.io (editable)**.


* * *


**6\. Diagrama de Microservicios**
----------------------------------


**Propósito:** Describir la **arquitectura general** y la **comunicación entre los microservicios** del sistema.


**Checklist:**


* [ ] Identificar todos los **microservicios**.
* [ ] Especificar **APIs y endpoints** principales.
* [ ] Representar **comunicación síncrona y/o asíncrona**.
* [ ] Asociar **bases de datos** por microservicio.
* [ ] Incluir **gateways**, **service discovery** y mecanismos de balanceo.
* [ ] Adjuntar **enlace a Draw.io (editable)**.


* * *


**7\. Secuencia de entrega (actualizada)**
------------------------------------------


**DER → BPMN → Clases → Estados → Secuencias → Microservicios**


> **Notas:**  
>
> * El **BPMN** brinda el contexto de negocio que orienta los demás diagramas.
> * El **Flujograma** puede complementar, pero no reemplaza los modelos principales.


* * *


**Nota final:**  
Todos los diagramas deberán estar acompañados de una **breve descripción** y contar con un **enlace al archivo correspondiente en Draw.io (con permiso editable)**.  
Se recomienda mantener la coherencia entre los distintos niveles de modelado (**datos, lógica, procesos y arquitectura**) para asegurar la **trazabilidad del diseño del sistema**.
