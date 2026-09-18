---
taiga_id: 226861
taiga_slug: guia-doc-producto
taiga_version: 3
taiga_modified_date: 2026-08-27T01:18:29.935Z
project_id: 1804026
project_slug: tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion
source_url: https://tree.taiga.io/project/tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion/wiki/guia-doc-producto
---

Objetivo
--------

Establecer una normativa simple y clara para estudiantes que deben construir una **Plataforma**, definiendo estándares mínimos de **datos**, **backend**, **frontend**, **flujo de trabajo** y **seguridad**.

Alcance
-------

Aplica a todo el **código**, **scripts**, **migraciones de base de datos** y **documentación** generada por los equipos del curso. Incluye lineamientos para **MySQL**, **Java 21 + Spring Boot**, **Angular 21** y **Git**.

* * *

Normas para Bases de Datos (MySQL)
----------------------------------

1) Motor y configuración
------------------------

*   Motor: **MySQL 8.x**.

2) Nomenclatura y tipos
-----------------------

*   Tablas y columnas en **inglés**, **snake\_case** (ej.: `patient_address`).
*   Clave primaria (PK): columna `**id BIGINT UNSIGNED AUTO_INCREMENT**`.
*   Claves foráneas: `**<tabla>_id**` (ej.: `user_id`).
*   Fechas (**DATE**): sufijo `**_date**`.
*   Fecha y hora (**DATETIME**): sufijo `**_datetime**`.

3) Campos obligatorios en TODAS las tablas
------------------------------------------

*   `created_datetime (DATETIME)`
*   `created_user (BIGINT)`.
*   `last_updated_datetime (DATETIME)`
*   `last_updated_user (BIGINT)`.
*   `is_active (TINYINT 0/1)` para bajas lógicas (no se elimina físicamente).

4) Auditoría
------------

*   Tabla espejo `**<table>_audit**` con los mismos campos + `**version (BIGINT AUTO_INCREMENT)**`.
*   **Triggers** o capa de aplicación para insertar versiones.

5) Índices y claves
-------------------

*   Índices por **FK** y por campos de búsqueda (**dni**, **protocol\_number**, etc.).

6) Integridad y referencia
--------------------------

*   FK con **ON UPDATE RESTRICT** y **ON DELETE RESTRICT** salvo casos explícitos.
*   No almacenar derivados calculables (**normalización 3FN**).

* * *

Normas para Backend (Java + Spring Boot)
----------------------------------------

1) Estilo y estructura
----------------------

*   Código en **inglés**. Paquetes por dominio (ej.: `patients`, `orders`, `results`).
*   Capas:        
      - **api** (controllers)        
      - **application** (services/use cases)        
      - **domain** (entities)        
      - **infrastructure** (persistence/adapters)

2) Fechas y zonas
-----------------

*   No usar `String` para fechas.
*   Usar **LocalDate**, **LocalDateTime** y **ZonedDateTime** si aplica.

3) Validaciones y errores
-------------------------

*   **Bean Validation** (`@NotNull`, `@Email`, etc.).
*   Manejo unificado de errores (**ControllerAdvice**) y códigos **HTTP** consistentes.

4) Persistencia
---------------

*   **Spring Data JPA**.
*   Entidades con `**@Version**` para control optimista donde corresponda.

5) Seguridad
------------

*   **Spring Security**.
*   **JWT** para APIs.
*   **Roles** por endpoint.
*   **Logs de auditoría** en acciones críticas.

7) Documentación
----------------

*   **OpenAPI/Swagger** habilitado en entorno **dev**.

8) Compatibilidad entre microservicios
--------------------------------------

*   Contratos en **JSON/OpenAPI**.
*   Evitar acoplarse a nombres de clases de otro servicio.

* * *

Normas para Frontend (Angular 21)
---------------------------------

Este documento establece reglas, estándares y verificaciones obligatorias para el desarrollo en este repositorio.

1) Áreas de Trabajo por Grupos
------------------------------

Trabajá **EXCLUSIVAMENTE** dentro de tu grupo:

Reglas:

*   No modificar el código de otros grupos, el layout ni `**app.routes.ts**`, salvo cambios transversales acordados.
*   Agregar **rutas hijas** en el archivo `**.routes.ts**` de tu grupo.
*   Evitar **dependencias cruzadas** entre módulos.

* * *

2) Flujo de Trabajo (Git/PR)
----------------------------

### main

*   Rama de **producción**.
*   Solo recibe **PR** desde **develop** (releases) o desde **hotfix/**\* (urgencias en producción).
*   Está protegida: **no se permite push directo**.

### develop

*   Rama de **integración**.
*   Recibe **PR** desde **feature/** o **fix/**.
*   Acumula cambios que luego se liberan en **main**.

### feature/\*

*   Nuevas funcionalidades.
*   Se crean y fusionan desde/hacia **develop**.

### fix/\*

*   Correcciones menores detectadas durante el desarrollo.
*   Se crean y se fusionan desde/hacia **develop**.

### hotfix/\*

*   Arreglos urgentes en producción.
*   Se crean desde **main** y se fusionan a **main** mediante **PR**.
*   Luego se hace **merge** de **main** a **develop**.

### 🔒 Reglas de Protección de Ramas

**main**

*   ✅ Requiere **Pull Request** antes de hacer **merge**.
*   ✅ Requiere que los **checks** (GitHub Actions) pasen antes de mergear.
*   ✅ Requiere estar actualizado con la última versión.
*   ✅ No se permiten **push** directos.
*   ✅ Solo se aceptan **PR** desde **develop** o **hotfix/**\*.

**develop**

*   ✅ Requiere **Pull Request** antes de hacer **merge**.
*   ✅ Puede requerir **checks** (tests/lint/build).
*   ✅ No se permiten **push** directos.

![](https://media-protected.taiga.io/attachments/5/c/9/7/4e928a9ab4bbd1de24e43a92e831c9da9230f3e5bc1501d28c32cc28598d/image.png?token=[REDACTED]#_taiga-refresh=wikipage:4453350)

* * *

3) Políticas de Dependencias
----------------------------

*   **No** se permite modificar `**package.json**` ni `**package-lock.json**` en los PR (bloqueado por **CI**).
*   La instalación de nuevas librerías o actualizaciones la gestiona el **maintainer**.

* * *

4) Estándares de Código (Angular + TypeScript)
----------------------------------------------

*   Seguir la Guía de Estilo de Angular: 🔗 [https://angular.io/guide/styleguide](https://angular.io/guide/styleguide)
*   Usar **componentes standalone** y **rutas lazy** por grupo.
*   Nombres **descriptivos**; cada archivo con una **única responsabilidad**.
*   Evitar `console.log`.
*   Usar `console.warn` o `console.error` solo para casos relevantes.

* * *

5) Validaciones del Repositorio (CI)
------------------------------------

En cada **PR** se ejecuta:

*   **Lint (ESLint)**
*   **Build Angular (producción)**
*   El PR fallará si hay **errores de ESLint** o si la **duplicación de código > 3%**.
*   También fallará si se modifican `**package.json**` o `**package-lock.json**`.

**Comandos locales antes de subir cambios:**        
\`\`\`bash        
npm run lint        
npm run build

* * *

6) Seguridad: Conceptos y Referencias
-------------------------------------

**HTTPS/TLS**          
  🔗 [https://wiki.mozilla.org/Security/Server\_Side\_TLS](https://wiki.mozilla.org/Security/Server_Side_TLS)

**Reverse Proxy/API Proxy** (/api, CORS, rate limiting, logs)          
  🔗 [https://docs.nginx.com/nginx/admin-guide/web-server/reverse-proxy/](https://docs.nginx.com/nginx/admin-guide/web-server/reverse-proxy/)

**CORS**          
  🔗 [https://developer.mozilla.org/docs/Web/HTTP/CORS](https://developer.mozilla.org/docs/Web/HTTP/CORS)

**CSP (Content Security Policy)**          
  🔗 [https://developer.mozilla.org/docs/Web/HTTP/CSP](https://developer.mozilla.org/docs/Web/HTTP/CSP)

**Security Headers**          
  🔗 [https://owasp.org/www-project-secure-headers/](https://owasp.org/www-project-secure-headers/)

**Rate Limiting** (ej. `express-rate-limit`)          
  🔗 [https://www.npmjs.com/package/express-rate-limit](https://www.npmjs.com/package/express-rate-limit)

**Gestión de Secretos**          
  🔗 [https://cheatsheetseries.owasp.org/cheatsheets/Secrets\_Management\_Cheat\_Sheet.html](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)

**Cookies de Sesión** (HttpOnly, Secure, SameSite=Strict)          
  🔗 [https://cheatsheetseries.owasp.org/cheatsheets/Session\_Management\_Cheat\_Sheet.html](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)

**Source Maps** (deshabilitar en producción)          
  🔗 [https://angular.dev/tools/cli/build](https://angular.dev/tools/cli/build)

**Autorización/Validación Backend**          
  🔗 [https://owasp.org/www-project-application-security-verification-standard/](https://owasp.org/www-project-application-security-verification-standard/)
