# Ejecutor de `ms-sandbox` — implementación Java 21

Implementación del contrato de [`08-spec-ejecutor.md`](08-spec-ejecutor.md). El documento es la
fuente de verdad; este README solo dice dónde vive cada requerimiento y qué quedó abierto.

Alineado con la versión de la spec que incorpora §0 (cambios C1 a C6).

## Correr

```bash
mvn test                    # suite completa; los *IT se saltean si no hay socket del daemon
mvn package                 # target/ejecutor.jar (fat jar)
powershell scripts/verificar-a6.ps1   # A6 contra un daemon de Docker real
```

La suite tarda unos 3 minutos: tres tests consumen el reloj de ejecución completo de 60 s
(`r82` de timeout, `r510` de inspect en timeout, y `a33` de escritura trabada). Son controles de
tiempo real, así que no se pueden acelerar sin falsearlos.

Variables de entorno de despliegue (no tocan ningún campo de la spec del contenedor, así que no
violan P1): `EJECUTOR_SOCKET` (por defecto `/run/ejecutor/ejecutor.sock`) y `DOCKER_SOCKET`
(por defecto `/var/run/docker.sock`).

## Dónde vive cada cosa

| Módulo | Líneas | Spec |
|---|---|---|
| `Spec` | 74 | §4.1, §4.2 — el JSON de `create`, constante salvo label y nombre |
| `Constantes` | 18 | §4.3 |
| `Http` | 108 | HTTP/1.1 a mano: cabeceras, `Content-Length`, chunked (R5.7) |
| `ClienteDocker` | 174 | §5 — las ocho llamadas, upgrade a `101` (R5.8), watchdogs (R8.3) |
| `Demultiplexor` | 53 | §6 completa |
| `Reporte` | 27 | §7.5–§7.8 |
| `Ejecucion` | 152 | §5, §7.1–§7.2, §8 — nonce, timeouts, limpieza garantizada |
| `Servidor` | 217 | §3, §9 — `/ejecutar`, `/salud`, semáforo y cola |
| `Barrido` | 26 | §10 |
| `Motor`, `Main`, `Log`, `ErrorDaemon` | 48 | frontera, cableado, R11.6, R11.7 |
| **Total** | **897** | código efectivo, sin comentarios ni blancos |

Agrupado como la tabla de §14: cliente HTTP 282, spec del contenedor 92, demultiplexador 53,
extracción del reporte 27, orquestación 152, servidor 217, barrido 26, cableado 48.

Ni `docker-java` ni equivalente (R11.4). Dependencias: `jackson-databind` en runtime, JUnit 5 en
tests. Jackson solo serializa la spec que escribimos nosotros y parsea lo que devuelve el daemon,
que ya es parte de la base de confianza (R11.3).

## Cobertura de §13

| Test | Dónde | Estado |
|---|---|---|
| A1, A2 | `SpecTest`, `EjecucionTest#elCuerpoDeCreateEsElGolden` | ✅ `src/test/resources/spec-create-referencia.json` es el archivo compartido con la implementación Node |
| A6 | `ProtocoloIT` | ✅ contra Docker real |
| A7 | `EjecucionTest#r61_rawStreamEsErrorDaemon` | ✅ |
| A8–A12 | `DemultiplexorTest` | ✅ |
| A21 | `ReporteTest`, `EjecucionTest` | ✅ |
| A26, A27 | `ServidorTest` | ✅ |
| **A31** *(C2)* | `EjecucionTest#a31_oomKilledVieneDelInspect`, `#a31_elInspectQueFallaNoAbortaLaEjecucion` | ✅ más `#r510_elInspectTambienCorreEnTimeout` |
| **A32** *(C1)* | `SpecTest#a32_ulimitCpuYSuRelacionConElRelojDePared` | ✅ incluye el assert sobre las constantes de R4.1 |
| **A33** *(C3)* | `EjecucionTest#a33_laEscrituraQueNoAvanzaNoRetieneElCupo` | ✅ |
| A3–A5, A13–A20, A22–A25, A28–A30 | — | fuera del alcance acordado: necesitan la imagen `sandbox-runner` de producción |

Además de la suite de §13, hay tests que fijan invariantes que la spec afirma pero no numera:
orden `attach` → `start` (R5.1), `wait` → `inspect` → `logs` → `delete` (R5.4, R5.10), versión de la
API en todas las rutas (R5.0), el tar viaja opaco (I7), y el orden exacto de los diez campos de la
respuesta de §3.1.

## Decisiones que la spec dejaba abiertas

- **`TAG_FIJO`.** Se fijó en `1.0.0`, o sea `sandbox-runner:1.0.0`.
- **`X-Ejecucion-Id`.** Se valida contra el UUID canónico estricto. No es cosmética: el id se
  concatena en la URL de `create` como nombre del contenedor.
- **El tope de la escritura de R8.5.** La spec pide que el paso 4 tenga «su propio tope de tiempo»
  pero no define una constante en §4.3. Se acota por **lo que queda del reloj de ejecución**, en vez
  de inventar un valor nuevo: al vencer, el paso 5 encuentra el presupuesto agotado y resuelve en
  `TIMEOUT` sin margen extra. Si se prefiere un tope propio y más corto, hay que agregar la
  constante a §4.3 para que Node use la misma.

Las dos ambigüedades que esta implementación había resuelto por su cuenta —el orden entre truncado
y extracción del reporte, y qué se remueve exactamente de `stdout`— quedaron fijadas en la spec como
R7.7 y R7.8, con el mismo criterio que ya estaba implementado.

## Pendientes

1. **R11.5 (< 400 líneas) no se cumple**: 897 de código efectivo. §14 ya recoge el motivo y R14.0
   deja la decisión para cuando exista la implementación Node: si Node tampoco lo alcanza, el que
   hay que revisar es el objetivo, no las implementaciones.
2. **R10.2 quedó desactualizada por C1.** Dice que `EDAD_HUERFANO_MS` es «20 veces mayor» que
   `TIMEOUT_EJECUCION_MS`, pero con el reloj de pared en 60 s la relación pasó a ser de 10 veces.
   El barrido sigue siendo correcto —diez minutos contra uno es holgura de sobra— pero el número
   del texto hay que corregirlo.
3. **La versión mínima de API que acepta el daemon varía entre builds del Engine.** Con 29.2.1 el
   `create` con `v1.43` fue rechazado; con 29.7.2, que reporta `MinAPIVersion 1.40`, A6 pasa. No hay
   nada que cambiar en el código, pero conviene que el despliegue fije la versión del Engine y que
   el arranque verifique `MinAPIVersion` contra `VERSION_API_DOCKER`, en vez de descubrirlo en la
   primera ejecución de un alumno.
4. **R14.1**: falta la revisión línea por línea por dos personas que no escribieron el código.
