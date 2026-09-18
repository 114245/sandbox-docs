# Diseño de integración MCP con Taiga para el equipo

## Objetivo

Permitir que el equipo use Claude Code, Google Antigravity o Codex para consultar y modificar el proyecto de prueba de Taiga `jcarreggio1-test-mcp`. La primera validación cubrirá épicas, historias de usuario y tareas sin operar sobre el proyecto real de Deyappa.

## Proyecto de prueba verificado

- Interfaz web: `https://tree.taiga.io/project/jcarreggio1-test-mcp/backlog`
- API: `https://api.taiga.io/api/v1`
- Nombre: `Test-mcp`
- Slug: `jcarreggio1-test-mcp`
- ID: `1806761`
- Estado observado: backlog, tareas, incidencias y wiki habilitados; épicas deshabilitadas.

La activación de épicas será la primera modificación controlada si el MCP expone correctamente la actualización del proyecto. Si esa operación no está disponible o falla, se activará desde la interfaz de Taiga antes de continuar.

## Arquitectura

Se utilizará `@illodev/taiga-mcp` mediante transporte STDIO. Cada cliente inicia automáticamente un proceso local del servidor MCP y se comunica con él por entrada y salida estándar. El servidor traduce las herramientas MCP a llamadas HTTPS contra la API REST de Taiga.

No habrá un servicio MCP central ni un puerto local escuchando conexiones. Al cerrar Claude, Antigravity o Codex, termina el proceso que ese cliente inició. Si una persona abre dos clientes simultáneamente, cada cliente tendrá su propio proceso MCP.

La versión del paquete quedará fijada para evitar que distintos integrantes ejecuten versiones diferentes o que una publicación futura cambie el comportamiento sin revisión.

## Compatibilidad entre clientes y sistemas operativos

La configuración compartida incluirá:

- `.mcp.json` para Claude Code.
- `.agents/mcp_config.json` para Antigravity.
- `.codex/config.toml` para Codex.
- `scripts/start-taiga-mcp.mjs` como lanzador común.

El lanzador se implementará con Node.js y funcionará en Windows y Linux. Leerá el archivo local de credenciales, validará las variables requeridas y ejecutará la versión fijada del paquete mediante el comando apropiado para cada sistema operativo. Esto evita depender de Bash, PowerShell o rutas absolutas de una computadora concreta.

Cada integrante necesitará Node.js 20 o posterior. La primera ejecución necesitará conexión con el registro npm; las posteriores podrán reutilizar la caché local.

## Credenciales

Cada integrante creará en la raíz del repositorio un archivo llamado `.env.taiga.local` a partir de una plantilla versionada `.env.taiga.example`:

```dotenv
TAIGA_URL=https://api.taiga.io
TAIGA_USERNAME=jcarreggio1
TAIGA_PASSWORD=contraseña_local
```

Para otros integrantes, `TAIGA_USERNAME` y `TAIGA_PASSWORD` corresponderán a sus propias cuentas. El archivo `.env.taiga.local` se agregará a `.gitignore` y no será leído ni copiado por la configuración compartida.

La contraseña no debe enviarse por chat, escribirse en `.mcp.json`, `.agents/mcp_config.json`, `.codex/config.toml`, documentación, scripts o comandos que queden guardados en el historial del shell.

El procedimiento no cambia si el repositorio Git es privado. Un repositorio privado reduce quién puede leerlo, pero no convierte al control de versiones en un almacén de secretos. Las credenciales pueden permanecer en clones, historiales, copias de seguridad y registros de CI incluso después de borrar un archivo.

Si el proyecto de Taiga es privado, la arquitectura tampoco cambia. La cuenta configurada debe ser miembro del proyecto y Taiga aplicará los permisos de esa cuenta. Para conservar una auditoría útil, cada integrante debería usar su propia cuenta en lugar de compartir `jcarreggio1`.

## Alcance y permisos

El servidor MCP se autentica con las credenciales configuradas y puede operar sobre todos los proyectos a los que esa cuenta tenga acceso. El slug `jcarreggio1-test-mcp` orienta el flujo de trabajo, pero no constituye una barrera técnica dentro del servidor MCP.

Durante la prueba se usarán únicamente operaciones de lectura, creación y actualización sobre el proyecto de prueba. No se probarán eliminaciones, cambios de miembros o roles, importaciones, exportaciones ni webhooks. Los clientes deberán pedir aprobación antes de herramientas de escritura cuando esa opción esté disponible.

## Flujo de validación

1. Validar que los tres archivos de configuración sean sintácticamente correctos.
2. Iniciar el MCP sin credenciales y comprobar que el error sea claro y no exponga secretos.
3. Cargar las credenciales locales de `jcarreggio1`.
4. Resolver el proyecto por slug y confirmar su ID y nombre.
5. Consultar estados de épicas, historias y tareas.
6. Habilitar épicas o documentar el paso manual necesario.
7. Crear una épica marcada explícitamente como prueba MCP.
8. Crear una historia de usuario, vincularla a la épica y modificarla.
9. Crear una tarea dentro de la historia y modificarla.
10. Volver a consultar todos los elementos y comprobar sus relaciones y valores.
11. Repetir una consulta de lectura desde cada cliente disponible.

Los artefactos creados llevarán el prefijo `[MCP TEST]` para distinguirlos. No se borrarán automáticamente; quedarán visibles para que el equipo pueda revisar el resultado antes de decidir su limpieza.

## Documentación para el equipo

La guía de uso explicará:

- qué es MCP y qué papel cumplen el cliente, el servidor MCP y Taiga;
- requisitos para Windows y Linux;
- creación del archivo local de credenciales;
- arranque automático del proceso STDIO;
- instalación y comprobación en Claude, Antigravity y Codex;
- ejemplos de consultas y escrituras;
- diagnóstico de errores de Node.js, npm, autenticación, permisos y conectividad;
- rotación de contraseña y retiro de acceso de un integrante.

## Criterios de aceptación

- Ningún secreto queda en un archivo versionado ni aparece en la salida de las pruebas.
- La misma instalación funciona en Windows y Linux con Node.js 20 o posterior.
- Claude, Antigravity y Codex pueden descubrir las herramientas del MCP.
- Al menos un cliente completa el flujo de escritura sobre `jcarreggio1-test-mcp`.
- Los otros clientes completan como mínimo la lectura del proyecto y de los artefactos creados.
- La documentación permite que otro integrante configure su equipo sin asistencia oral.

