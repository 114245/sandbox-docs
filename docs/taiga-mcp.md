# Taiga mediante MCP en Claude, Antigravity y Codex

Esta guía explica cómo conectar los asistentes del equipo con el proyecto de prueba de Taiga sin guardar contraseñas en Git. La integración usa el servidor comunitario open source [`@illodev/taiga-mcp`](https://github.com/illodev/taiga-mcp), fijado en la versión `1.0.0`.

## El modelo mental

Hay tres piezas distintas:

1. **El cliente o host** es Claude Code, Google Antigravity o Codex. El usuario conversa con él y decide qué operaciones autoriza.
2. **El servidor MCP** es un proceso Node.js local. Publica herramientas con parámetros definidos, por ejemplo crear una épica o actualizar una historia de usuario.
3. **Taiga** conserva los proyectos y datos. El MCP transforma cada llamada de herramienta en una petición HTTPS a la API REST de Taiga.

```text
Claude / Antigravity / Codex
            │
            │ MCP por STDIO
            ▼
  taiga-mcp en la computadora
            │
            │ HTTPS
            ▼
       API de Taiga
```

`STDIO` significa que el cliente y el MCP se comunican por la entrada y salida estándar del proceso. No hay un puerto local, panel web ni servicio que debas levantar manualmente. Al abrir un cliente, éste ejecuta el lanzador; al cerrarlo, termina ese proceso. Si abrís dos clientes a la vez, cada uno tendrá su propio proceso MCP.

En la primera ejecución, `npx` descarga el paquete desde npm y lo guarda en su caché. Por eso esa primera conexión requiere Internet y puede tardar más. Las ejecuciones posteriores normalmente reutilizan la copia descargada.

## Proyecto de prueba

- Nombre: `Test-mcp`
- Slug: `jcarreggio1-test-mcp`
- ID de Taiga: `1806761`
- Web: <https://tree.taiga.io/project/jcarreggio1-test-mcp/backlog>
- API configurada: `https://api.taiga.io`

El módulo de épicas quedó habilitado durante la validación. La versión `1.0.0` del MCP permite crear y modificar épicas, pero su herramienta de actualización de proyecto no expone el campo `is_epics_activated`. Si otro proyecto lo tiene deshabilitado, un administrador debe activarlo una vez desde **Settings → Modules → Epics** en Taiga.

## Requisitos

- Node.js 20 o posterior.
- npm y npx disponibles en `PATH`.
- Una cuenta de Taiga con acceso al proyecto de prueba.
- El repositorio `sandbox-docs/` abierto como raíz del workspace. Las configuraciones MCP, scripts y documentación están en esta raíz.

Comprobá Node.js y npm:

```powershell
node --version
npm --version
npx --version
```

Los mismos comandos funcionan en Linux. Si `node` no se reconoce en Windows, instalá una versión LTS desde <https://nodejs.org/> y volvé a abrir la terminal y el cliente.

## Configuración personal de credenciales

Cada integrante debe crear su propia copia local. En PowerShell:

```powershell
Copy-Item .env.taiga.example .env.taiga.local
```

En Linux:

```bash
cp .env.taiga.example .env.taiga.local
chmod 600 .env.taiga.local
```

Después, abrí `.env.taiga.local` con un editor y completalo:

```dotenv
TAIGA_URL=https://api.taiga.io
TAIGA_USERNAME=tu_usuario_de_taiga
TAIGA_PASSWORD=tu_contraseña_de_taiga
```

No pegues la contraseña en un chat ni la pases como argumento de un comando. `.env.taiga.local` está excluido en `.gitignore`; verificá siempre que siga sin aparecer entre los archivos para versionar.

Aunque el repositorio Git sea privado, la contraseña no debe guardarse allí. Un secreto versionado puede permanecer en el historial, clones, copias de seguridad o registros de CI después de borrar el archivo visible.

Para la prueba inicial se utilizará `jcarreggio1`. Para el uso del grupo, cada persona debe configurar su propia cuenta. Así Taiga conserva permisos y auditoría por usuario, y retirar a un integrante no obliga a cambiar una contraseña compartida.

## Archivos compartidos

Los tres clientes usan el mismo lanzador `scripts/start-taiga-mcp.mjs`:

| Cliente | Configuración del proyecto |
| --- | --- |
| Claude Code | `.mcp.json` |
| Google Antigravity | `.agents/mcp_config.json` |
| Codex | `.codex/config.toml` |

El lanzador busca `.env.taiga.local` desde la raíz del repositorio, valida las tres variables requeridas y ejecuta `@illodev/taiga-mcp@1.0.0`. En Windows invoca `npx.cmd` mediante `cmd.exe`, que es la forma correcta de ejecutar ese comando de npm; en Linux ejecuta `npx` directamente. No usa `shell: true` ni construye comandos con la contraseña.

Esta versión del servidor publica 236 herramientas porque refleja una parte amplia de la API de Taiga: proyectos, épicas, historias, tareas, incidencias, estados y otras entidades. No necesitás memorizar esa lista. Pedile al cliente una intención concreta, como “creá una tarea dentro de esta historia”, y el cliente seleccionará la herramienta y completará sus argumentos. En clientes que implementan búsqueda de herramientas, como Claude Code, el catálogo puede cargarse bajo demanda para no ocupar todo el contexto de la conversación.

Las variables definidas en el sistema tienen prioridad sobre el archivo local. Esto permite usar un gestor de secretos o variables de CI en el futuro sin modificar el repositorio.

## Activación en cada cliente

### Claude Code

1. Abrí Claude Code desde la raíz del repositorio.
2. Claude detectará `.mcp.json` y pedirá confiar en la configuración compartida la primera vez. Revisá que ejecute `node scripts/start-taiga-mcp.mjs` antes de aprobarla.
3. Abrí `/mcp` y confirmá que `taiga-deyappa-test` esté conectado.

### Google Antigravity

1. Abrí la carpeta como workspace.
2. Antigravity detectará `.agents/mcp_config.json`.
3. Usá `/mcp` en la CLI o **MCP Servers → Manage MCP Servers** en el IDE.
4. Confirmá que `taiga-deyappa-test` figure activo.

### Codex

1. Abrí Codex desde la raíz del repositorio y marcá el proyecto como confiable si lo solicita.
2. Iniciá una sesión nueva después de agregar o modificar `.codex/config.toml`; una sesión que ya estaba abierta no incorpora herramientas nuevas dinámicamente.
3. Usá `/mcp` o `codex mcp list` y confirmá que `taiga-deyappa-test` esté habilitado.

Codex está configurado con aprobación `writes`: las lecturas pueden ejecutarse normalmente y las herramientas que escriben deben solicitar revisión.

## Protocolo obligatorio para producción

El proyecto de producción forma parte de una instancia evaluativa constante. Una historia mal creada, un estado incorrecto, una relación equivocada o una estimación modificada puede afectar esa evaluación, aunque el impacto parezca pequeño.

Además, los usuarios del equipo no tienen permisos de eliminación en producción. Una creación accidental no podrá deshacerse con la misma cuenta. Habrá que pedir la intervención de alguien con mayores permisos y el dato incorrecto puede permanecer visible mientras tanto.

Por estas razones, todas las IA deben trabajar en **modo lectura por defecto** y cumplir este procedimiento antes de cada escritura:

1. Confirmar mediante una lectura el nombre, slug e ID del proyecto sobre el que actuarán.
2. Leer [`docs/convenciones-taiga.md`](convenciones-taiga.md) y abrir la página fuente citada para el tipo de elemento. Si la convención no existe, declararlo y no inventarla.
3. Mostrar la operación propuesta antes de ejecutarla: herramienta, elemento afectado, valores actuales, valores nuevos, estado, relaciones y cantidad de elementos. Citar la convención y sección aplicadas.
4. Preguntar de forma explícita: **“¿Autorizás esta creación/modificación en producción?”**
5. Esperar una respuesta afirmativa. El silencio, una autorización anterior o una instrucción general de continuar no autorizan escrituras posteriores.
6. Ejecutar únicamente la operación mostrada. Cualquier campo, elemento u operación adicional requiere una nueva confirmación.
7. Volver a leer el elemento después de escribir y comunicar su ID, referencia, estado y relaciones resultantes.

Esto se aplica a todas las modificaciones, incluidas las que pueden parecer menores: crear elementos, editar descripciones, cambiar estados, asignar personas, mover a un sprint, modificar puntos, etiquetas, fechas, prioridades o relaciones entre épicas, historias y tareas.

Las operaciones masivas quedan deshabilitadas por defecto en producción. Sólo pueden utilizarse después de mostrar la lista completa de elementos y cambios, y de recibir una autorización explícita para ese lote concreto. No deben invocarse herramientas de eliminación, aunque aparezcan disponibles en el catálogo MCP.

Si el resultado no coincide con lo autorizado, la IA debe detenerse, no intentar compensarlo con más escrituras y comunicar exactamente qué cambió. Debido a la falta de permisos de eliminación, la corrección debe coordinarse con el responsable del proyecto o con un administrador.

La aprobación automática de escrituras configurada en algunos clientes es una defensa adicional, pero no reemplaza este procedimiento. Claude, Antigravity y Codex deben respetar la misma regla, aunque sus interfaces de aprobación sean diferentes.

Al comenzar una sesión sobre producción, usá esta instrucción:

```text
Trabajá en modo lectura por defecto. Antes de crear o modificar cualquier dato en
Taiga, confirmá el proyecto por nombre, slug e ID; mostrame la herramienta y todos
los valores que vas a enviar; consultá docs/convenciones-taiga.md y citá la regla
aplicable; y preguntame si autorizo exactamente esa operación. Esperá mi aprobación
explícita antes de ejecutarla. La aprobación vale sólo para la operación mostrada.
Después de escribir, volvé a leer el elemento y reportá el resultado. No uses
herramientas de eliminación ni operaciones masivas sin una autorización específica
para el lote completo.
```

## Sincronización de la wiki por API

Las convenciones se extraen con `scripts/sync-taiga-wiki.mjs`. Este comando no es un segundo servidor MCP ni queda ejecutándose: se inicia, autentica, realiza la lectura y termina. Usa `POST` únicamente para `/api/v1/auth`; después restringe las operaciones del proyecto a rutas `GET` permitidas. El token vive en memoria y no se escribe en disco.

Las credenciales de esta lectura se guardan aparte para evitar que Claude, Antigravity o Codex las hereden automáticamente como credenciales del MCP:

En PowerShell:

```powershell
Copy-Item .env.taiga.source.example .env.taiga.source.local
```

En Linux:

```bash
cp .env.taiga.source.example .env.taiga.source.local
chmod 600 .env.taiga.source.local
```

Cada integrante que necesite actualizar la copia local debe completar su propio usuario y contraseña. No hace falta que todos mantengan un proceso levantado; sólo ejecuta el comando quien refresca la documentación:

```bash
node scripts/sync-taiga-wiki.mjs --dry-run
node scripts/sync-taiga-wiki.mjs
```

El `dry-run` confirma nombre, slug, ID y cantidades sin alterar archivos. La ejecución normal guarda las páginas, metadatos, diagnósticos y hashes bajo `docs/taiga-source/`. Los adjuntos binarios no se descargan salvo que se agregue expresamente `--download-attachments`, y aun así sólo se aceptan hosts configurados.

El extractor conserva el contenido de la wiki, excepto valores sensibles dentro de URLs de medios protegidos: esos tokens se reemplazan por `[REDACTED]` y se registra un diagnóstico. Nunca copies automáticamente las credenciales productivas de `.env.taiga.source.local` a `.env.taiga.local`; el segundo archivo alimenta el MCP y puede habilitar escrituras según los permisos de la cuenta.

## Prueba segura recomendada

Empezá por lectura:

```text
Usá Taiga MCP para resolver el proyecto jcarreggio1-test-mcp. Mostrame su nombre, ID y módulos habilitados. No hagas modificaciones.
```

Después consultá catálogos necesarios:

```text
Listá los estados de épicas, historias de usuario y tareas del proyecto jcarreggio1-test-mcp. No modifiques nada.
```

Cuando las lecturas funcionen, pedí una escritura acotada:

```text
En el proyecto jcarreggio1-test-mcp, creá una épica llamada "[MCP TEST] Integración compartida". Antes de ejecutar la escritura, mostrame los datos exactos que vas a enviar.
```

Continuá con una historia y una tarea usando el mismo prefijo `[MCP TEST]`. Pedí luego que vuelva a leer los elementos para verificar que los valores y relaciones persistieron.

Durante esta prueba no autorices herramientas para eliminar datos, cambiar miembros o roles, importar o exportar proyectos, ni administrar webhooks. Los elementos no se borrarán automáticamente: quedarán visibles para revisión y la limpieza será una decisión separada.

## Resultado de la validación

La prueba autenticada del 17 de septiembre de 2026 confirmó el handshake MCP, la resolución del proyecto, las escrituras y la lectura posterior de estos elementos:

| Ref. | Tipo | Resultado |
| --- | --- | --- |
| `#1` | Épica | `[MCP TEST] Integración compartida`, estado `New` |
| `#2` | Historia | `[MCP TEST] Historia de validación`, actualizada a `Ready` |
| `#3` | Historia | `[MCP TEST] Historia vinculada a épica`, relacionada con la épica `#1` |
| `#4` | Tarea | `[MCP TEST] Tarea de validación`, dentro de la HU `#2` y actualizada a `In progress` |

La herramienta `taiga_epics_related_userstories_add` de esta versión tiene un defecto: envía `user_story`, pero omite el campo `epic` que Taiga exige y recibe un HTTP 400. La operación `taiga_epics_related_userstories_bulk_create` sí funciona y fue la utilizada para crear la HU `#3` ya vinculada. Si el equipo necesita asociar historias existentes con frecuencia, conviene corregir el wrapper en un fork o contribuir el arreglo al proyecto antes de adoptarlo para producción.

Codex inició el servidor y completó las operaciones anteriores. Claude Code reconoció la configuración de proyecto y la dejó como `Pending approval`; cada integrante debe abrir Claude y aprobarla una vez después de revisar el comando. Antigravity no estaba instalado en la máquina de validación, por lo que un integrante que lo use debe confirmar que `taiga-deyappa-test` figure activo en su workspace.

## Permisos y alcance real

El slug de prueba orienta las instrucciones, pero el servidor MCP no queda técnicamente encerrado en ese proyecto. Puede operar sobre cualquier proyecto permitido para la cuenta configurada. La barrera efectiva son los permisos de Taiga y la aprobación de herramientas en el cliente.

Un proyecto privado de Taiga usa el mismo procedimiento. Cada persona debe ser miembro y tener permisos de lectura o escritura suficientes. Un repositorio Git privado tampoco cambia la configuración ni habilita guardar la contraseña en Git.

## Diagnóstico

### El servidor figura desconectado

- Confirmá `node --version`, `npm --version` y `npx --version`.
- Cerrá y abrí nuevamente el cliente después de cambiar la configuración.
- Confirmá que abriste el cliente desde la raíz del repositorio.
- Revisá los logs de `/mcp`; el error debe mencionar nombres de variables, nunca sus valores.

### Faltan variables de Taiga

Confirmá que el archivo se llame exactamente `.env.taiga.local`, esté en la raíz y contenga valores no vacíos para `TAIGA_URL`, `TAIGA_USERNAME` y `TAIGA_PASSWORD`.

### npm no puede descargar el paquete

La primera ejecución requiere acceso a `registry.npmjs.org`. Revisá Internet, proxy corporativo, firewall y configuración de npm. No elimines la versión fijada para solucionar el problema.

### Taiga rechaza la autenticación

Entrá a <https://tree.taiga.io/> con las mismas credenciales. Si cambiaste la contraseña, actualizá únicamente `.env.taiga.local` y reiniciá el cliente.

### Una lectura funciona, pero una escritura falla

La visibilidad pública puede permitir lecturas sin membresía. Las escrituras siempre dependen de una cuenta autenticada y de sus permisos dentro del proyecto. Revisá el rol del usuario en Taiga y el estado o versión actual del elemento que intentás modificar.

### Las herramientas de épicas fallan

Confirmá que el módulo de épicas esté habilitado en el proyecto. Activarlo no crea épicas por sí solo; sólo habilita la función del proyecto.

## Mantenimiento

La versión del MCP está fijada deliberadamente. Para actualizarla, revisá el código y las notas del proyecto open source, cambiá la versión en el lanzador y repetí las pruebas de lectura y escritura en `jcarreggio1-test-mcp` antes de usarla en Deyappa.

Si una credencial pudo haberse expuesto, cambiá inmediatamente la contraseña en Taiga, reemplazala en `.env.taiga.local` y revisá que el archivo nunca haya entrado al historial de Git.

## Referencias

- [Servidor Taiga MCP seleccionado](https://github.com/illodev/taiga-mcp)
- [MCP en Claude Code](https://code.claude.com/docs/en/mcp)
- [MCP en Google Antigravity](https://antigravity.google/docs/mcp)
- [MCP en Codex](https://developers.openai.com/codex/mcp)
- [API REST de Taiga](https://docs.taiga.io/api.html)
