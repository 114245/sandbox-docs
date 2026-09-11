> ⚠️ **Desactualizado.** Este documento describe el modelo viejo de **una sola capa**
> (`entrypoint.sh`, eliminado del repo; ver historial de git). La imagen vigente es la de dos capas
> (`java21-junit/Dockerfile` + `capa1/capa1.sh` + el catálogo de `../perfiles/`). Pendiente
> reescribirlo entero para el modelo de dos capas; mientras tanto, el contrato vigente es
> [`docs/arquitectura/08-spec-ejecutor.md`](../../docs/arquitectura/08-spec-ejecutor.md).

# `java-runner` — contrato del contenedor de ejecución

Esta imagen es **el corazón del ms-sandbox**: recibe el código de un alumno y la
suite del profesor, los corre aislados, y devuelve hechos crudos. No conoce
desafíos, cursos ni alumnos, y **no decide el veredicto** — eso lo hace el worker
leyendo el reporte.

Implementa `docs/arquitectura/03-ms-sandbox-ejecucion.md` §1.3 (pasos 3 a 7).

---

## Cómo se usa

```sh
./build.sh                    # construye java-runner:21 (único paso con red)
./run.sh bundles/ok-suma      # simula lo que hará el worker
```

`run.sh` es la traducción ejecutable de §1.3 pasos 3–11: arma el tar, crea el
contenedor con todos los flags de aislamiento, manda el bundle por `stdin`, hace
`docker inspect` y destruye. Sirve para probar la imagen **sin nada de Spring**.

---

## Entrada — un tar por `stdin`

No hay `docker cp` ni bind mount. `docker cp` **no funciona** con `--read-only`
(el demonio rechaza la copia) y además el `tmpfs` de `/tmp` taparía lo copiado.
Ver §1.5, donde está la tabla de las tres opciones.

```
src/<paquete>/<Clase>.java     ← lo que escribió el ALUMNO
test/<paquete>/<Clase>Test.java ← lo que escribió el PROFESOR (T05)
```

El `entrypoint` **valida el tar antes de extraerlo**: sin rutas absolutas, sin
`..`, todo bajo `src/` o `test/`, y al menos un `.java` en `test/`. Es la segunda
barrera — la API ya validó lo mismo (§2.2), pero ésta es la que ve el tar real.

## Salida — un sobre JSON por `stdout`

Todo lo que imprimen `javac`, JUnit y el código del alumno va a archivos internos.
**Lo único que se escribe en el `stdout` del contenedor es el sobre**, entre marcas:

```
##SANDBOX-RUNNER-V1##
{ "schema":"sandbox.runner/v1", "fase":"TESTS", "resultado":"OK", ... }
##FIN-SANDBOX-RUNNER##
```

| Campo | Qué trae |
|---|---|
| `fase` | `BUNDLE` · `COMPILACION_SOLUCION` · `COMPILACION_TESTS` · `TESTS` |
| `resultado` | Ver la tabla de abajo. **No es el veredicto del alumno** |
| `exitCodeJava` | El exit code de la JVM de tests, para diagnóstico |
| `testsEnReporte` | Cuántos tests declara el XML. **Es la guarda contra `System.exit(0)`** |
| `procesosSobrevivientes` | Procesos que sobrevivieron a la JVM. **Si es > 0, el veredicto no es confiable** |
| `clasesTest` | Las clases que se le pasaron a JUnit por nombre |
| `recursos.cpuPruebasMs` | **CPU del alumno.** El número contra el que se evalúa el límite |
| `recursos.tiempoPruebasMs` | Reloj de pared de la misma fase. Informativo: varía con la carga |
| `recursos.tiempoCompilacionMs` | Costo de plataforma, no se le cobra al alumno |
| `reportesTarGzB64` | El directorio de reportes de JUnit, `tar.gz` + base64 |
| `stdoutB64` / `stderrB64` / `truncado` | Salida capturada, truncada **adentro** del contenedor |

### `resultado` y exit code del contenedor

| `resultado` | Exit | De quién es la culpa |
|---|---|---|
| `OK` | 0 | Corrió el pipeline y hay reporte. **El veredicto lo decide el worker** |
| `ERROR_COMPILACION` | 20 | Del alumno: no compila su código |
| `SUITE_INVALIDA` | 21 | De T05: no compila la suite del profesor |
| `BUNDLE_INVALIDO` | 22 | De quien armó el bundle (API o worker) |
| `TIMEOUT_COMPILACION` | 24 | De la plataforma: `javac` no entró en su presupuesto |
| `LIMITE_MEMORIA` | 25 | Del alumno |
| `TIMEOUT_CPU` | 26 | Del alumno: agotó su reloj de CPU |
| `TIMEOUT_PARED` | 27 | Del alumno: durmió en vez de quemar CPU |
| `SIN_REPORTE` | 28 | Del alumno: la JVM murió sin escribir reporte |
| `SALIDA_ANTICIPADA` | 29 | Del alumno: hay reporte pero con `tests=0` |
| `VEREDICTO_NO_CONFIABLE` | 30 | Del alumno: sobrevivió un proceso a la JVM |
| `MUERTO_POR_SENAL` | 31 | SIGKILL sin agotar la CPU. **El worker desambigua con `OOMKilled`** |

> **`OK` no significa que los tests pasaron.** Significa que hay reporte con al
> menos un test corrido. El veredicto sale del XML de JUnit, **nunca del exit
> code** (§1.6c).

---

## Los tres relojes

Se pasan por `-e` y los fija el worker. Son tres, y **solo uno es del alumno**:

| Variable | Tipo de reloj | De quién es | Mecanismo |
|---|---|---|---|
| `TIMEOUT_COMPILE_MS` | pared | **plataforma** | `timeout` sobre `javac` |
| `CPU_TESTS_S` | **CPU** | **alumno** | `ulimit -t` → `RLIMIT_CPU` → SIGKILL (137) |
| `TIMEOUT_TESTS_MS` | pared | backstop | `timeout`, generoso, para el que duerme |

El del alumno se mide en **tiempo de procesador**, no de pared, porque el tiempo
de CPU es inmune a la contención del host *por construcción*. Es lo que hace
`isolate` por debajo de Judge0 y Piston. Elimina la causa de los `TIMEOUT`
intermitentes sobre código correcto en vez de taparla con margen (§1.4d).

---

## Las cuatro decisiones que no son obvias

**1. No hay Maven.** `javac` + `junit-platform-console-standalone.jar` horneado en
la imagen. Sin red, Maven no descarga nada; aun offline levanta una JVM y tarda
segundos. El camino directo separa además **tres culpas distintas** en tres pasos
(§1.6a).

**2. No se escanea el classpath.** No se pasa `--scan-classpath`: los nombres de
las clases de test se derivan de los `.class` ya compilados y se le dan a JUnit
con `--select-class`. Ataca directo los 1.2–2.9 s de descubrimiento que midió el
spike.

> ⚠️ `--select-class` **por sí solo no encuentra nada** — hace falta además
> `--include-classname='.*'` ([junit-framework#2289](https://github.com/junit-team/junit-framework/issues/2289)).
> Si algún día parece que la técnica no funciona, es esto.

**3. Los tests van primero en el classpath.** Solución y tests compilan a
directorios **separados**, y la ejecución usa `--class-path $DIR_TEST:$DIR_SOL`.
Si el alumno declara una clase que sombrea una de soporte del profesor, gana el
`.class` del profesor. Es la medida 2 de §1.6d — la misma mitigación que aplicó
Ares después de un CVSS 8.2.

**4. La JVM se configura contra el cgroup.** `-XX:MaxRAMPercentage=60` (si no, con
`--memory 256m` el alumno tiene ~64 MB de heap y le explota código correcto) y
`-XX:+ExitOnOutOfMemoryError`, que hace que el OOM salga con `exitCode 3` en vez
de degenerar en un `TIMEOUT` (§1.6b).

---

## Estado — qué está y qué falta

Construido y corrido contra Docker real (server 29.2.1, imagen 226 MB).

| Pendiente (`00-propuesta` §10) | Estado acá |
|---|---|
| 1 — Los tres relojes, el del alumno en CPU | **Verificado**: los tres disparan (`hostil-cpu`, `hostil-sleep`) |
| 2 — Validar el paquete declarado | **Innecesario en el runner**: lo cubre el orden del classpath. Ver abajo |
| 3 — El reporte no escribible por el alumno | **No se puede prevenir.** Se detecta. Ver abajo |
| 4 — Casos hostiles: symlink y hard link | Falta — son ataques a nivel *tar*, no a nivel Java |
| 5 — No escanear el classpath | **Verificado**: `clasesTest: ["tp.SolucionTest"]` |
| 7 — Suite de entregas maliciosas en CI | **9 casos, todos contenidos.** Falta el CI |

```
$ ./suite-hostil.sh
ok-suma                OK    OK                       (sobrevivientes: 0)
hostil-exit0           OK    SALIDA_ANTICIPADA        (sobrevivientes: 0)
hostil-reporte         OK    VEREDICTO_NO_CONFIABLE   (sobrevivientes: 6)
hostil-reporte-loop    OK    VEREDICTO_NO_CONFIABLE   (sobrevivientes: 6)
hostil-paquete         OK    OK                       (sobrevivientes: 0)
hostil-red             OK    OK                       (sobrevivientes: 0)
hostil-cpu             OK    TIMEOUT_CPU              (sobrevivientes: 0)
hostil-memoria         OK    LIMITE_MEMORIA           (sobrevivientes: 0)
hostil-sleep           OK    TIMEOUT_PARED            (sobrevivientes: 0)
---
contenidos: 9   rojos: 0   salteados: 0
```

La suite no compara solo el `resultado`: en tres casos **afirma también sobre el
reporte**, porque el `resultado` solo no distingue contención de suerte.
`hostil-paquete` tiene que dar `failures="3"` (si diera 0, el sombreado funcionó
y el veredicto es falso) y `hostil-red` tiene que dar `failures="0"` (si fallara,
hubo red).

### Lo que se midió (3 tests triviales, `--cpus 2`)

| | ok-suma | hostil-exit0 |
|---|---|---|
| `tiempoCompilacionMs` (2 × `javac`) | 1825 | 1848 |
| `tiempoPruebasMs` (pared) | 1112 | 1214 |
| `cpuPruebasMs` | 2111 | 2233 |
| `testsEnReporte` | 3 | **0** |
| `resultado` | `OK` | `SALIDA_ANTICIPADA` |

**Se confirma que el reloj es casi todo toolchain**: el código del alumno corre en
microsegundos y el pipeline entero tarda ~3 s. Coincide con lo que midió el spike.

---

## Los cinco hallazgos de las primeras corridas

**1. `System.exit(0)` sí deja un reporte escrito, y el doc se quedaba corto.**
§1.6c dice que la guarda es exigir `tests > 0`, y tiene razón — pero este script
había implementado *"¿existe el reporte?"*, que **no alcanza**. La entrega hostil
sale con `exitCode 0`, `OOMKilled: false` y un `TEST-junit-platform-suite.xml`
perfectamente válido con `tests="0"`. Con la guarda de existencia, aprobaba.
Ahora se cuentan los tests del XML y el veredicto es `SALIDA_ANTICIPADA`.

**2. `date +%s%3N` no funciona en esta imagen.** Ubuntu 26.04 —la base de
`eclipse-temurin:21-jdk`— ya no trae GNU coreutils sino **uutils** (la
reimplementación en Rust), y su `date` **ignora el ancho** en `%3N`: devuelve los
9 dígitos de nanosegundos igual. Los tiempos salían como `2334953266 ms` para una
compilación de 2.3 s. Se pide `%N` y se divide. `stat -c%s`, `timeout`,
`base64 -w0` y `find -printf` sí se comportan como GNU.

**3. `RLIMIT_CPU` sale con 137, no con 152.** §1.4d dice que al agotarse el reloj
de CPU el kernel manda SIGXCPU (exit 152). En la práctica la JVM sale con **137**
(SIGKILL): `ulimit -t` deja el soft y el hard limit iguales, así que las dos
señales llegan juntas y gana la segunda. Importa porque **137 es ambiguo** — es
también el OOM-kill del cgroup. Lo que desambigua no es el exit code sino la CPU
medida: si se consumió el presupuesto entero, fue el reloj del alumno. Si no,
el runner devuelve `MUERTO_POR_SENAL` y deja que el worker decida con
`.State.OOMKilled`. Es el mismo razonamiento por el que el veredicto sale del
reporte y no del exit code.

**4. La memoria se comporta exactamente como predijo §1.6b.** `hostil-memoria`
sale con `exitCode 3` y **`OOMKilled: false`**. Queda confirmado que decidir con
ese flag solo clasificaría una fuga de memoria como error interno del sandbox —
y el alumno tendría intentos infinitos.

**5. `cpuPruebasMs` es ~2× el reloj de pared, y eso cambia el dimensionamiento.**
1112 ms de pared contra 2111 ms de CPU, porque **el tiempo de CPU se suma entre
threads** y la JVM arranca con varios. La consecuencia práctica no es cosmética:
con `CPU_TESTS_S=10`, el arranque de la JVM ya se come ~2 s del presupuesto **del
alumno** antes de que corra una línea suya. §1.4a decidió que compilar es costo de
plataforma; **el arranque de la JVM también lo es, y hoy se le está cobrando al
alumno.** Hay que decidirlo explícitamente y decírselo a T05 (definición 11).

### Pendiente 3 — el reporte no se puede proteger, se puede detectar

Éste es el hallazgo más importante de la sesión, y **contradice lo que este mismo
README decía antes**.

La idea original era: matar los procesos sobrevivientes antes de leer el reporte,
y con eso se cierra la ventana. `hostil-reporte-loop` demuestra que **no**. Un
atacante que reescribe el reporte *en bucle* gana siempre, porque **matarlo
después no deshace la escritura que ya hizo**. La corrida sin la defensa nueva
devolvía un `TEST-forjado.xml` con `failures="0"` como único archivo: el alumno
se escribió la nota.

Y el problema es estructural, no de implementación: el reporte vive en `/tmp`,
`/tmp` lo escribe el alumno, y **JUnit corre con el uid del alumno**. El archivo
está dentro de su dominio de confianza y no hay flag de Docker que lo saque de
ahí. Un segundo `tmpfs` con otro uid tampoco alcanza, porque el que tiene que
escribir el reporte es JUnit — o sea, el alumno.

> Es el mismo límite que §1.6d ya había encontrado para Ares: **el contenedor
> protege el host, no protege el veredicto.** La conclusión nueva es que el
> reporte-como-archivo es parte del veredicto, y hereda ese límite.

Lo que sí se puede es **detectar**: para reescribir el reporte *después* de que
JUnit lo escribió, el atacante necesita un proceso vivo. El runner cuenta los que
sobrevivieron a la JVM (`procesosSobrevivientes`) y si hay alguno el resultado es
`VEREDICTO_NO_CONFIABLE` — nunca éxito. Un ataque que insiste se detecta siempre;
uno que escribe una sola vez a ciegas tiene que acertarle a una ventana de
milisegundos sin saber cuándo termina JUnit.

Dos detalles de implementación que salieron de la misma prueba:

- El barrido original era `pkill -x java`, y **el proceso del ataque es un `sh`**:
  no matcheaba. Ahora se barre por `/proc`, sin filtrar por nombre.
- El `stdout` del contenedor (fd 3, por donde sale el sobre) **lo heredaban los
  hijos del alumno**, que podían escribir un sobre falso. Se cierra con `3>&-`
  antes de lanzar la JVM.

### Pendiente 2 — el orden del classpath ya lo cubre

`hostil-paquete` reproduce CVE-2024-23682: el alumno declara `tp.Ayuda` —una clase
de soporte del profesor— para que el test se compare contra su respuesta. **Queda
contenido por el orden del classpath** (`$DIR_TEST:$DIR_SOL`), sin necesidad de
validar paquetes: el reporte da `failures="3"`.

Validar el paquete declarado en la API sigue siendo deseable como defensa en
profundidad y para dar un `400` temprano con `PAQUETE_RESERVADO`, pero **ya no es
lo único que separa el veredicto de ser falso**.
