# `ms-sandbox/` — todo lo desplegable y su banco de pruebas

Un solo directorio para toda la implementación, separado de `../docs/` (el porqué) y de los
documentos raíz (propuestas, handoff, hallazgos).

| Directorio | Qué es | Rol |
|---|---|---|
| `ejecutor/` | El sidecar Java 21 que habla con Docker (`docker-java`) y expone `/ejecutar` | **Servicio desplegable** |
| `imagenes/` | Dockerfile(s) de las imágenes de ejecución y el entrypoint de la capa 1 | **Artefacto desplegable** (se construye con `docker build`) |
| `perfiles/` | El catálogo de perfiles: qué imagen, qué script de evaluación (capa 2) y qué límites | **Configuración desplegable**, la carga `ejecutor/` al arrancar |
| `pruebas/` | Bundles de alumno (camino feliz + hostiles) y el arnés `probar-capa1.sh` | **Banco de pruebas**, no se despliega |

## Cómo se relacionan

`ejecutor/` crea contenedores a partir de las imágenes de `imagenes/`; qué imagen y qué límites usar
en cada ejecución los elige un perfil de `perfiles/`, seleccionado por el header `X-Perfil`. El
contrato completo (framing de stdin, spec del `create`, catálogo de perfiles) está en
[`../docs/arquitectura/08-spec-ejecutor.md`](../docs/arquitectura/08-spec-ejecutor.md).

## Construir y probar

```bash
# la imagen de ejecución (contexto = imagenes/)
docker build -f imagenes/java21-junit/Dockerfile -t sandbox-runner:2.0.0-capa1 imagenes

# el ejecutor
cd ejecutor && mvn test && mvn package

# el arnés manual de dos capas, contra un bundle de pruebas/
cd pruebas && ./probar-capa1.sh bundles/ok-suma ../perfiles/java21-junit.sh
```

## Acoplamientos manuales — no hay validación automática que los una

- `perfiles/java21-junit@3.json`, campo `script`: tiene que ser el contenido de
  `perfiles/java21-junit.sh` **byte a byte**, escapado como string JSON. Si se edita el `.sh`, hay
  que regenerar el campo a mano.
- `Constantes.ENTRYPOINT` (en `ejecutor/`) tiene que coincidir con el `ENTRYPOINT` de
  `imagenes/java21-junit/Dockerfile` (`/opt/sandbox/capa1.sh`).
- El tope de `Constantes.MAX_SCRIPT_BYTES` (en `ejecutor/`) tiene que coincidir con
  `SANDBOX_MAX_SCRIPT_BYTES` que declara la imagen.
