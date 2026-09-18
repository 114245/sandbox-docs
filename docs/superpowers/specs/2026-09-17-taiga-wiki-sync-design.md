# Diseño de sincronización de la wiki de Taiga

## Objetivo

Extraer mediante la API REST la documentación del proyecto fuente de Taiga, conservarla en archivos revisables y generar una guía de convenciones para épicas, historias de usuario, tareas, sprints y documentación. La sincronización será estrictamente de lectura sobre los datos del proyecto.

## Fuente

- Wiki: `https://tree.taiga.io/project/tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion/wiki/`
- Slug esperado: `tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion`
- API: `https://api.taiga.io/api/v1`
- Credenciales: cuenta individual o técnica con acceso de lectura a la wiki.

La cuenta `jcarreggio1` configurada para la prueba devolvió HTTP 404 al resolver el slug. Antes de sincronizar se deberá confirmar el slug y usar una cuenta miembro del proyecto si éste es privado.

## Uso del template de historias de usuario

El template entregado por el usuario es una entrada válida y útil. Se guardará sin cambios como `docs/taiga-source/seeds/hu-template.raw.md` y cumplirá dos funciones:

1. Servir como referencia mientras se habilita el acceso a la wiki.
2. Actuar como caso de control para comparar la página obtenida por API y detectar secciones, saltos o enlaces perdidos.

Las URLs de Figma y Storybook del texto entregado parecen contener envoltorios repetidos de Markdown de Taiga. La semilla conservará esa forma exacta. Las correcciones propuestas se registrarán en un informe y sólo se aplicarán en la guía derivada después de revisión humana.

## Arquitectura

```text
.env.taiga.source.local
          │
          ▼
 scripts/sync-taiga-wiki.mjs
          │
          ├── POST /auth
          └── GET /projects, /wiki, /wiki-links y adjuntos
                         │
                         ▼
              docs/taiga-source/
                         │
                         ▼
              docs/convenciones-taiga.md
```

La autenticación requiere `POST /auth`; todas las solicitudes posteriores serán `GET`. El cliente rechazará cualquier otro método o ruta no contemplada. El token permanecerá en memoria y nunca se escribirá en disco.

## Archivos resultantes

```text
docs/taiga-source/
├── manifest.json
├── seeds/
│   └── hu-template.raw.md
├── pages/
│   └── <wiki-slug>.md
├── metadata/
│   ├── project.json
│   ├── wiki-links.json
│   └── attachments.json
└── attachments/
    └── <wiki-slug>/<filename>  # sólo con descarga explícita

docs/convenciones-taiga.md
```

Cada página conservará el Markdown recibido y tendrá front matter con `taiga_id`, `taiga_slug`, `taiga_version`, `taiga_modified_date`, `project_id`, `project_slug` y `source_url`. El manifiesto contendrá hashes SHA-256 para detectar cambios sin depender del orden de respuesta de la API.

## Reglas de seguridad

- No ejecutar `POST`, `PUT`, `PATCH` o `DELETE` contra recursos del proyecto.
- Permitir `POST` únicamente sobre `/auth`.
- Validar slug e ID antes de descargar páginas.
- Detenerse si el proyecto resuelto no coincide con la fuente configurada.
- Guardar credenciales sólo en `.env.taiga.source.local`, excluido por Git.
- No guardar tokens, contraseñas, cabeceras de autorización ni respuestas completas con datos personales.
- No seguir descargas hacia hosts inesperados.
- No interpretar ni ejecutar HTML, JavaScript o archivos adjuntos.
- Generar archivos en un directorio temporal y reemplazar el snapshot sólo después de completar todas las verificaciones.

## Extracción y normalización

El proceso consultará el proyecto por slug, listará todas las páginas sin paginación, volverá a obtener cada página por ID, recuperará la navegación de `wiki-links` e inventariará adjuntos. La descarga binaria de adjuntos será opcional, requerirá una bandera explícita y aceptará sólo hosts configurados. Los nombres de archivos derivarán del slug y se rechazarán rutas absolutas, `..`, barras y colisiones.

La extracción no corregirá el contenido funcional o editorial. Como única excepción, redactará valores sensibles encontrados en parámetros de URLs de medios protegidos y registrará cada redacción como diagnóstico. Un paso separado generará un informe de enlaces rotos, páginas sin enlace de navegación, enlaces sin página, slugs duplicados y diferencias respecto de la semilla de HU.

## Guía derivada

`docs/convenciones-taiga.md` resumirá únicamente reglas respaldadas por las páginas sincronizadas. Cada regla indicará la página y el encabezado de origen. Incluirá:

- Convenciones de épicas.
- Template y criterios de calidad de historias de usuario.
- Convenciones de tareas.
- Gestión de sprints y milestones.
- Estados y transiciones.
- Estimación y prioridades.
- Evidencia, prototipos y documentación.
- Definition of Ready y Definition of Done, si existen en la fuente.
- Conflictos, ambigüedades y puntos que requieren confirmación humana.

La guía no completará reglas ausentes mediante inferencias. Las reglas dudosas se marcarán como pendientes de validación.

## Criterios de aceptación

1. Una ejecución autorizada obtiene todas las páginas accesibles de la wiki sin modificar Taiga.
2. Una segunda ejecución sin cambios produce el mismo snapshot y los mismos hashes.
3. Ningún archivo generado contiene credenciales o tokens.
4. La cantidad de páginas del manifiesto coincide con la respuesta de la API.
5. Cada página tiene trazabilidad hacia su ID, slug, versión, fecha y URL original.
6. El template de HU entregado se conserva literalmente y se compara con la fuente remota.
7. Los enlaces y adjuntos se inventarían y los problemas se reportan sin correcciones silenciosas; cualquier token embebido se redacta y queda registrado.
8. La guía derivada cita su fuente local y distingue reglas confirmadas de observaciones pendientes.
