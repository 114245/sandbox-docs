---
taiga_id: 226862
taiga_slug: template-proyecto-por-grupo
taiga_version: 3
taiga_modified_date: 2026-08-27T01:30:34.239Z
project_id: 1804026
project_slug: tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion
source_url: https://tree.taiga.io/project/tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion/wiki/template-proyecto-por-grupo
---

GXX - \[Título del Tema\]
-------------------------

_(Ejemplo: G03 - Gestión de Clientes)_

* * *

Descripción del tema u objeto
-----------------------------

Explicar brevemente el propósito o alcance de este módulo, funcionalidad o componente.       
Debe quedar claro **qué hace, para qué sirve y qué partes del sistema involucra.**

**Ejemplo:**  

> Este módulo gestiona las operaciones de alta, baja y modificación de clientes, incluyendo validaciones de datos y vinculación con el sistema de pedidos.

* * *

Referencia a Historia(s) de Usuario (HU)
----------------------------------------

Listar las **Historias de Usuario** asociadas a este tema.       
Cada una debe estar documentada en el **backlog de Taiga** y correctamente identificada.

> 💡 **Sugerencia:** Podés **linkear directamente** cada HU desde el backlog de Taiga, utilizando el enlace permanente de la historia.       
> Esto facilita la trazabilidad entre la documentación técnica y los requerimientos funcionales del sistema.

**Ejemplo:**  

> \[**HU-03 – Registrar nuevo cliente**\]:       
> Como empleado, quiero registrar un nuevo cliente para poder asociarle pedidos.  
> 
> \[**HU-07 – Modificar datos del cliente**\]:       
> Como usuario, quiero modificar los datos de un cliente existente para mantener la información actualizada.

* * *

Diagramas requeridos
--------------------

Los diagramas deben elaborarse siguiendo el orden establecido en el documento:       
[👉 **Documentación Técnica Pendiente – Diagramas del Sistema**](https://tree.taiga.io/project/tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion/wiki/guia-doc-diagramas)

**Secuencia sugerida:**

1.  DER (Diagrama Entidad–Relación)
2.  BPMN (Proceso de Negocio)
3.  Flujograma (opcional, si aplica)
4.  Clases
5.  Estados
6.  Secuencias
7.  Microservicios

> **Nota:** Todos los diagramas deben mantenerse actualizados ante cualquier modificación del sistema o del código fuente.

* * *

1\. Diagrama DER (Entidad–Relación)
-----------------------------------

**Propósito:** Mostrar la estructura lógica de la base de datos y las relaciones entre entidades o tablas.

**Checklist:**

*   [ ] Definir todas las entidades/tablas.
*   [ ] Especificar atributos y tipos de datos.
*   [ ] Indicar relaciones (1:1, 1:N, N:M).
*   [ ] Definir claves primarias y foráneas.
*   [ ] Incluir restricciones e índices relevantes.

* * *

2\. Diagrama BPMN (Proceso de Negocio)
--------------------------------------

**Propósito:** Representar el **flujo de negocio completo**, desde que inicia un proceso hasta que termina, mostrando roles, tareas y decisiones.

**Cuándo usar BPMN:**

*   Si el flujo involucra **más de un rol, área o sistema**.
*   Si hay **interacciones o integraciones** entre módulos o servicios.
*   Si existen **eventos temporales, condiciones o decisiones**.
*   Si se busca **mostrar el proceso de negocio a nivel general** (no técnico).

**Checklist:**

*   [ ] Definir **pool** del proceso y **lanes** por rol o área.
*   [ ] Incluir eventos de **inicio**, **intermedios** y **fin**.
*   [ ] Modelar **tareas** y **subprocesos** con nombres verbales claros.
*   [ ] Usar **gateways** (XOR, OR, AND) para decisiones o bifurcaciones.
*   [ ] Mostrar los **message flows** entre sistemas.
*   [ ] Incorporar **boundary events** (errores, tiempo, cancelaciones).
*   [ ] Añadir **data objects** o documentos clave del proceso.

**Ejemplo:**  

> El proceso “Registrar Pedido” comienza con la solicitud del cliente, continúa con la validación del producto y finaliza con la confirmación del pago.       
> Los roles involucrados son Cliente, Sistema de Pedidos y Sistema de Pagos.

* * *

2.1 Flujograma (Flowchart) – Opcional
-------------------------------------

**Propósito:** Mostrar la **lógica interna** de una función, algoritmo o proceso puntual (por ejemplo, cómo se calcula un descuento o cómo se valida un campo).

**Cuándo usarlo:**

*   Si el flujo no involucra varios roles o sistemas.
*   Si se quiere aclarar la **lógica interna** de una función o proceso técnico.
*   Si se necesita explicar **condiciones, bucles o decisiones** dentro de un módulo.

**Checklist:**

*   [ ] Incluir **inicio y fin** del flujo.
*   [ ] Mostrar **decisiones** (sí/no) y **bucles**.
*   [ ] Indicar **entradas y salidas** del proceso.
*   [ ] Representar **manejo de errores** si aplica.
*   [ ] Mantener el diagrama simple y legible.

**Ejemplo:**  

> El flujograma describe el proceso de validación de un usuario:  
> 
> 1.  Se ingresa el email y la contraseña.
> 2.  Si los datos son válidos, se permite el acceso.
> 3.  En caso contrario, se muestra un mensaje de error.

* * *

Enlace(s) al diagrama en Draw.io
--------------------------------

Agregar los enlaces directos a los diagramas creados en Draw.io.       
Asegurarse de que tengan **permiso editable** y estén organizados en la carpeta compartida del grupo.

**Ejemplo:**  

> [Enlace al diagrama – Gestión de Clientes](https://app.diagrams.net/)

* * *

Explicación de los diagramas
----------------------------

Incluir una **breve interpretación de cada diagrama**, resaltando sus partes más importantes.       
Debe responder a:  

*   ¿Qué muestra?
*   ¿Qué decisiones o relaciones se destacan?
*   ¿Cómo se vincula con el resto del sistema?

**Ejemplo:**  

> En el diagrama DER se representan las entidades _Cliente_ y _Pedido_, vinculadas mediante una relación uno a muchos.       
> Se destacan las claves primarias (`cliente_id`, `pedido_id`) y las foráneas que aseguran la integridad referencial.

* * *

Observaciones y notas técnicas
------------------------------

_(Opcional, pero recomendable)_       
Usar este espacio para documentar decisiones técnicas, dependencias o particularidades del módulo.

**Ejemplo:**  

> Este módulo se comunica con el servicio de autenticación mediante API REST.       
> El modelo Cliente implementa validaciones de formato de correo y duplicados.

* * *

Documentación de Endpoints:
---------------------------

### Nombre del Controller (Ej: PacienteController)

#### **Acción: Accion(nombre del enpoint) (Ej: Registrar paciente(createPacient))**

**Método**: POST/GET/PUT/PATCH

**URL**: `/api/v1/ejemplo/wiki/{orderId}/endpoints`

**Descripción**:

**Request:**

Path variable: (opcional, solo si contiene) Ej:

*   **orderId**: identificador de la orden a cancelar (ejemplo: `“ORD-982374”`)

**Request:**

```json
{
"example": "request"
}
```

**Response:**

```json
{
"example": "response"
}
```

#### **Acción: Accion2(nombre del enpoint) (Ej: Registrar paciente(createPacient))**

**Método**: POST/GET/PUT/PATCH

**URL**: `/api/v1/ejemplo2/wiki/{orderId}/endpoints`

**Descripción**:

**Request:**

Path variable: (opcional, solo si contiene) Ej:

*   **orderId**: identificador de la orden a cancelar (ejemplo: `“ORD-982374”`)

**Request:**

```json
{
"example2": "request"
}
```

**Response:**

```json
{
"example2": "response"
}
```

* * *

Recomendaciones finales
-----------------------

*   Redactar siempre con lenguaje **técnico, claro y uniforme**.
*   Usar **diagramas legibles** y consistentes con la implementación real.
*   Actualizar esta página cuando el módulo cambie.
*   No duplicar información entre grupos: vincular mediante enlaces.
*   Respetar el formato y estructura definidos en esta plantilla.

* * *

**Objetivo final:**       
Este documento debe permitir que **cualquier persona externa** (docente, compañero o revisor) pueda entender **qué hace el módulo, cómo funciona y cómo se integra al sistema** sin necesidad de leer el código.
