# `imagenes/` — el contenedor de ejecución

Esta carpeta construye la imagen que corre el código de un alumno. En el modelo vigente —**dos
capas en un solo contenedor**, no dos imágenes: el tag `2.0.0-capa1` es el nombre, no una segunda
imagen— la imagen aporta el `ENTRYPOINT` (**capa 1**, `capa1/capa1.sh`) y las herramientas de
`/libs`; **qué** evaluar llega recién en tiempo de ejecución, por `stdin`, como el guion de la
**capa 2** (lo escribe el Grupo 5 y lo elige el perfil de `../perfiles/`). La capa 1 aísla y no
sabe evaluar; la capa 2 evalúa y no sabe aislar; el `ejecutor/` no conoce a ninguna de las dos —
manda bytes por un socket y lee un sobre JSON.

El contrato completo, incluida la spec del `create` de Docker, es normativo en
[`docs/arquitectura/08-spec-ejecutor.md`](../../docs/arquitectura/08-spec-ejecutor.md) — en
particular **§4.1** (spec del contenedor), **§4.4** (catálogo de perfiles) y **§7** (framing de
stdin, nonce, separación del reporte). Este documento describe el lado de la imagen y no debe
contradecir esa spec.

## Cómo se usa

```bash
# construir la imagen, parado en ms-sandbox/imagenes (contexto = imagenes/, NO imagenes/java21-junit/)
docker build -f java21-junit/Dockerfile -t sandbox-runner:2.0.0-capa1 .

# probarla a mano, como lo hará el ejecutor real
cd ../pruebas
./probar-capa1.sh bundles/ok-suma ../perfiles/java21-junit.sh
```

`probar-capa1.sh` arma el `stdin` de tres documentos, crea el contenedor con los mismos flags de
aislamiento que manda el ejecutor y muestra el sobre. Sirve para probar la imagen sin Java ni
Spring de por medio — ver «Construir y probar» más abajo para la tabla de los nueve bundles.

## El contrato de `stdin`: tres documentos pegados

No hay `docker cp` ni bind mount: el contenedor es `--read-only` y `/work` es el único punto
escribible. Todo entra por un solo `stdin`, en este orden, **sin separadores**, y termina con el
cierre de la conexión (spec §7, R7.2):

```
<nonce>\n              32 hex minúscula. Nunca se exporta (R7.3).
<n>\n                  cuántos BYTES mide el guion de la capa 2, en decimal ASCII
<guion de n bytes>     el script del perfil, opaco para la capa 1
<tar>                  el bundle del alumno: "todo lo que quede del stream"
```

El largo va **adelante** y no hay marca de fin porque el guion lo escribe el Grupo 5: es texto
arbitrario y puede contener cualquier línea, incluida la que se eligiera como separador — mismo
razonamiento que `Content-Length` en HTTP (R7.9). Se cuenta en bytes, no en caracteres: un acento en
un comentario del guion mueve el número (R7.11).

`capa1.sh` lee el nonce y el largo con `IFS= read -r` (en `dash` consume exactamente hasta el `\n`
sin tocar el resto del descriptor) y el guion con `dd iflag=fullblock` — el único lector verificado
que no se pasa de largo: `head -c` sobre BusyBox lee de a bloques de 1024 y descarta el sobrante de
un pipe, comiéndose la cabecera del tar (medido: guion de 314 B, tar de 10240 B llegando como
9530 B). Lo que queda del stream después del guion es el tar completo.

## Qué hace `capa1.sh`, en orden

1. **Nonce** — 32 hex; si no cumple el formato, `BUNDLE_INVALIDO` (22).
2. **Guion de la capa 2** — largo declarado, numérico, `> 0` y `<= SANDBOX_MAX_SCRIPT_BYTES`
   (262144 B por defecto); menos bytes de los declarados (`stdin` cortado a mitad, R8.5) también
   da `BUNDLE_INVALIDO` (22).
3. **El tar** — se valida *antes* de extraer: rechaza enlaces simbólicos y duros (`tar -tv`, líneas
   `l`/`h`), rutas absolutas y rutas con `..`. Extrae con `--no-same-owner --no-same-permissions
   --no-overwrite-dir`. A diferencia del entrypoint viejo, **no** exige que todo cuelgue de
   `src/`/`test/`: esa estructura es conocimiento de la capa 2, no de la capa 1.
4. **Contrato de directorios** — exporta `SANDBOX_IN`, `SANDBOX_REPORTS`, `SANDBOX_TMP`,
   `SANDBOX_STATUS`, `SANDBOX_LIBS` y `SANDBOX_MEM_MB` (memoria del cgroup en MB, o 512 si no se
   puede leer `/sys/fs/cgroup/memory.max`).
5. **Invoca la capa 2** con `sh -c "$SCRIPT" </dev/null`, parada en `$SANDBOX_IN`, bajo
   `timeout -k 5s "${SANDBOX_EVAL_TIMEOUT_S}s"` (backstop, 45 s por defecto). `</dev/null` explícito
   para que un `read` de la capa 2 no toque el descriptor por el que ya viajó el nonce.
6. **Barre `/proc`**, cuenta los procesos vivos (excepto PID 1 y `$$`) y los mata con `kill -9` —
   detección, no prevención (ver «Decisiones no obvias»); lee los avisos opcionales de
   `$SANDBOX_STATUS/fase` y `/detalle`; y clasifica y emite el sobre por el fd 3, el `stdout` real
   desviado antes de invocar nada.

## El sobre — schema `sandbox.capa1/v2`

Entre marcas con el nonce, para que el alumno no pueda falsificarlo (R7.4):

```
---SANDBOX-<nonce>-INICIO---
{ "schema":"sandbox.capa1/v2", "resultado":"OK", ... }
---SANDBOX-<nonce>-FIN---
```

| Campo | Qué trae |
|---|---|
| `resultado` | Ver la tabla de exit codes abajo. **No es el veredicto del alumno** |
| `detalle` | Texto libre, escapado y truncado a 512 B |
| `exitEval` | El exit code con el que salió la capa 2 (o `null` si nunca llegó a correr) |
| `procesosSobrevivientes` | Procesos vivos después de la capa 2. **Si es > 0, no hay veredicto confiable** |
| `faseDeclarada` / `detalleDeclarado` | Copia literal de `$SANDBOX_STATUS/fase` y `/detalle` — canal rico y frágil, no confiable si la capa 2 murió de golpe |
| `recursos.msEval` | Reloj de pared de todo el paso 5 (invocar + esperar la capa 2) |
| `recursos.cpuEvalMs` | CPU de usuario + sistema de los hijos, de `times` (builtin POSIX) |
| `reportesTarGzB64` | `$SANDBOX_REPORTS` entero, empaquetado sin mirar adentro, `tar.gz` + base64 |
| `stdoutB64` / `stderrB64` | Lo que escribió la capa 2 en sus streams reales (fd 1/2, ya redirigidos a archivo), truncado a `SALIDA_LIMITE_BYTES` (65536 B) **adentro** del contenedor |
| `truncado` | `true` si alguno de los dos streams internos superó ese tope |

## Bandas de exit code

`0` = la capa 2 llegó al final · `20–31` = de la capa 1 (`capa1.sh`) · `32–39` = reservada, sin uso
hoy (comentario «hueco a propósito») · `40–59` = la capa 2 se frenó a propósito, la define el perfil
· otro valor = error de infraestructura.

### De la capa 1

| `resultado` | Exit | Causa |
|---|---|---|
| `OK` | 0 | La capa 2 corrió y dejó algo en el buzón. **El veredicto lo decide el worker** |
| `BUNDLE_INVALIDO` | 22 | Nonce, largo de guion o tar mal formados; guion truncado por un cierre a mitad de `stdin` |
| `EVALUACION_ANOMALA` | 23 | La capa 2 salió con un código fuera de su banda 40-59 (o el `cd` a `$SANDBOX_IN` falló) |
| `TIMEOUT_PARED` | 27 | Saltó el backstop de la capa 1 (`SANDBOX_EVAL_TIMEOUT_S`) |
| `SIN_REPORTE` | 28 | La capa 2 salió con 0 pero dejó `$SANDBOX_REPORTS` vacío |
| `VEREDICTO_NO_CONFIABLE` | 30 | Sobrevivió al menos un proceso a la capa 2: pisa cualquier otra clasificación |
| `MUERTO_POR_SENAL` | 31 | `SIGKILL` sin que saltara el backstop. Ambiguo con el OOM del cgroup — el worker desambigua con `.State.OOMKilled` |
| `DETENIDO_POR_EVALUACION` | el mismo que devolvió la capa 2 | La capa 1 **propaga tal cual** el código 40-59; no lo traduce |

> Los códigos `20`/`21`/`24`–`26`/`29` del entrypoint de una sola capa (`ERROR_COMPILACION`,
> `SUITE_INVALIDA`, `TIMEOUT_COMPILACION`, `LIMITE_MEMORIA`, `TIMEOUT_CPU`, `SALIDA_ANTICIPADA`) ya
> no existen en la capa 1: compilar, correr JUnit y validar el reporte es conocimiento de
> evaluación, y ahora vive en la capa 2, banda 40-59.

### De la capa 2 (perfil `java21-junit`, banda 40-59)

Códigos literales de `../perfiles/java21-junit.sh` (idéntico al campo `script` del perfil
`java21-junit@4.json`):

| Código | Fase | Causa |
|---|---|---|
| 40 | `COMPILACION` | El bundle no trae fuentes en `src/`, o `javac` de la solución falla |
| 41 | `COMPILACION_SUITE` | El bundle no trae tests, `javac` de la suite falla, o compiló sin producir ninguna clase |
| 42 | `COMPILACION` / `COMPILACION_SUITE` | `javac` superó el reloj de plataforma (`TIMEOUT_COMPILE_S`) |
| 43 | `PRUEBAS` | La JVM se quedó sin memoria (`-XX:+ExitOnOutOfMemoryError`, exit 3) |
| 44 | `PRUEBAS` | Se agotó el reloj de CPU del alumno (`ulimit -t`, exit 137/152) |
| 45 | `PRUEBAS` | Backstop de pared agotado — durmió en vez de quemar CPU (exit 124) |
| 46 | `PRUEBAS` | La JVM terminó sin escribir ningún reporte |
| 47 | `PRUEBAS` | Hay reporte pero `tests="0"` — la guarda contra `System.exit(0)` |

## El contrato que debe respetar una capa 2 (para quien escriba un perfil nuevo)

Esto es lo que `capa1.sh` efectivamente exige u ofrece — no una wishlist:

- Recibe el control con `sh -c "$SCRIPT"`, parada en `$SANDBOX_IN`, con `stdin` en `/dev/null`, y
  puede leer `$SANDBOX_IN`, `$SANDBOX_REPORTS`, `$SANDBOX_TMP`, `$SANDBOX_STATUS`, `$SANDBOX_LIBS`,
  `$SANDBOX_MEM_MB` — las únicas variables que la capa 1 le exporta a propósito.
- **Todo lo que quede en `$SANDBOX_REPORTS` cuando termine vuelve en el sobre, y nada más vuelve** —
  se empaqueta entero y sin mirar el contenido.
- `$SANDBOX_STATUS/fase` y `/detalle` son opcionales y no confiables: si la capa 2 muere de golpe no
  llegan. El código de salida es lo único garantizado por el kernel.
- El código de salida **debe** caer en 40-59 para que la capa 1 lo marque como frenado a propósito
  (`DETENIDO_POR_EVALUACION`) y lo propague sin traducir. Cualquier otro valor no nulo cae en
  `EVALUACION_ANOMALA` (23).
- No debe dar por sentado que el bundle trae `src/`/`test/`: esa convención es propia de este
  perfil, no del contrato de la capa 1.
- No puede recuperar el nonce por ningún medio (R7.3, R7.10), aunque el texto del guion sí queda
  visible en `/proc/1/cmdline` — no importa, la capa 2 ya es código no confiable, igual que el del
  alumno.
- No hay forma de proteger el reporte de un proceso que el alumno deja vivo — eso lo detecta la
  capa 1 después, no la capa 2.

## Los relojes — seis, en dos grupos

| Reloj | Dueño | Valor | Mecanismo |
|---|---|---|---|
| Reloj de pared del ejecutor | plataforma | 60 s (`TIMEOUT_EJECUCION_MS`, spec §4.3) | `wait` con timeout + `kill` — la red de última instancia, fuera de esta imagen |
| Ulimit de CPU del contenedor | techo del perfil | `limites.cpuS` (20 s en `java21-junit@4`) | `--ulimit cpu=<n>:<n>` al crear el contenedor (spec §4.1); lo hace cumplir `Catalogo` al arrancar el ejecutor (R4.1), no en cada ejecución |
| Backstop de la capa 1 | plataforma | `SANDBOX_EVAL_TIMEOUT_S`, 45 s por defecto (`ENV` del Dockerfile) | `timeout -k 5s` alrededor de toda la capa 2 — **no** es el reloj de la evaluación, es la red de la imagen |
| Reloj de compilación | plataforma | `TIMEOUT_COMPILE_S` = 8 s (literal en el script del perfil) | `timeout --signal=KILL`, uno por fase de `javac` |
| Reloj de CPU del alumno | **alumno** | `CPU_TESTS_S` = 10 s (literal en el script del perfil) | `ulimit -t` dentro del subshell que corre JUnit — más estricto que el ulimit del contenedor, nunca puede superarlo |
| Backstop de pared de los tests | plataforma | `TIMEOUT_TESTS_S` = 25 s (literal en el script del perfil) | `timeout --signal=KILL`, para el que duerme en vez de quemar CPU |

Los tres primeros son de la capa 1 y del ejecutor; los tres últimos son de la capa 2 y viven en el
perfil (R4.2: «los relojes por fase no deben vivir en el ejecutor»). En `java21-junit.sh` van
literales porque el script *es* el perfil.

**Invariante que hoy se sostiene a mano.** Las fases de la capa 2 corren en serie adentro del
backstop de la capa 1, así que su peor caso tiene que caber con margen:

```
2 × TIMEOUT_COMPILE_S + TIMEOUT_TESTS_S + margen  ≤  SANDBOX_EVAL_TIMEOUT_S
        8 + 8 + 25 = 41 s   (margen 4 s)          ≤  45 s
SANDBOX_EVAL_TIMEOUT_S + 5 s de gracia (-k 5s)    <  60 s del ejecutor
```

Si no se cumple, una entrega lenta pero legítima muere por el backstop de la capa 1
(`TIMEOUT_PARED`, 27) antes de que la capa 2 pueda emitir su código preciso (42 o 45), y se pierde
el diagnóstico. `java21-junit@3` la violaba (20 + 20 + 30 = 70 s) y se retiró por `@4`. Ningún
componente la valida: el ejecutor no ve los relojes, que viajan adentro del guion opaco.

## Decisiones no obvias

- **`/libs` es de `root:root`, `chmod 0555`, y `junit.jar` es `0444`.** Si el alumno pudiera
  escribir ahí, podría reemplazar el jar del verificador *entre fases*: compilar bien y pisar el jar
  antes de que corra JUnit. No rompe el aislamiento, pero sí la integridad de la herramienta con la
  que se dicta el veredicto.
- **`/work` no se declara en el Dockerfile.** Lo monta el ejecutor como `tmpfs` al crear el
  contenedor (spec §4.1); `WORKDIR /work` solo hace que el proceso arranque parado ahí.
- **POSIX `sh`, no bash, en las dos capas.** El `sh` de la imagen (`eclipse-temurin:21-jdk`) es
  `dash`: nada de arrays ni `mapfile`. El entrypoint viejo declaraba `#!/bin/bash` y usaba ambos —
  sin la lógica de evaluación adentro, la capa 1 no los necesita.
- **La detección de sobrevivientes vive en la capa 1, no en la capa 2.** No se puede *prevenir* que
  un proceso en segundo plano reescriba el reporte — el buzón es un directorio que el alumno mismo
  escribe y JUnit corre con su uid — pero sí *detectar*: reescribir después de que la capa 2 terminó
  exige un proceso vivo, y `capa1.sh` barre `/proc` entero (no `pkill -x java`: la capa 1 ni sabe que
  la capa 2 corrió una JVM).
- **El nonce nunca se exporta.** Es variable de shell, no de entorno: exportarla la dejaría en
  `/proc/1/environ`, legible por el alumno y por la capa 2, y el marcador dejaría de ser
  infalsificable — el único motivo por el que existe (R7.3).
- **`sh -c "$SCRIPT"`, no `sh archivo`.** `/work` es escribible por el uid 1000, el mismo con el que
  corre la capa 1 y el alumno (`CapDrop: ALL` impide arrancar como root y bajar privilegios). Un
  archivo se puede reescribir mientras se lee de a pedazos; `sh -c` lo parsea de memoria antes de
  ejecutar una línea.
- **`exec 3>&1` antes de invocar nada.** El `stdout` real queda en el fd 3, y solo `emitir()`
  escribe ahí — para cuando la capa 2 corre, su `stdout` ya es un archivo, así que ni su propio JSON
  (que va al buzón, no a `stdout`) ni un `println` del alumno pueden contaminar el sobre.

## Los nueve bundles hostiles

`probar-capa1.sh` corre cada uno con la spec constante de la §4.1 (mismos flags que el ejecutor
real: `--network none`, `--read-only`, `--user 1000:1000`, `--cap-drop ALL`, `--tmpfs /work`, etc.);
con `jq` instalado muestra el sobre parseado y el contenido del buzón. `BundlesIT`
(`ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/BundlesIT.java`) corre los mismos nueve
bundles **a través del ejecutor Java real**, contra `sandbox-runner:2.0.0-capa1` y el catálogo de
producción (`java21-junit@4`); se saltea si no hay daemon de Docker escuchando.

| Bundle | Qué ataca | `resultado` esperado | `exitEval` | sobrevivientes |
|---|---|---|---|---|
| `ok-suma` | Línea de base, sin trampa | `OK` | 0 | 0 |
| `hostil-exit0` | `System.exit(0)` para dejar un reporte con `tests=0` | `DETENIDO_POR_EVALUACION` (47) | 47 | 0 |
| `hostil-cpu` | Quema CPU real hasta agotar el reloj del alumno | `DETENIDO_POR_EVALUACION` (44) | 44 | 0 |
| `hostil-memoria` | Agota el heap de la JVM | `DETENIDO_POR_EVALUACION` (43) | 43 | 0 |
| `hostil-sleep` | Duerme en vez de quemar CPU, para saltar el `ulimit -t` | `DETENIDO_POR_EVALUACION` (45) | 45 | 0 |
| `hostil-reporte` | Deja un proceso vivo reescribiendo el reporte | `VEREDICTO_NO_CONFIABLE` (30) | 0 | 6 |
| `hostil-reporte-loop` | Igual, pero reescribiendo en bucle | `VEREDICTO_NO_CONFIABLE` (30) | 0 | 6 |
| `hostil-paquete` | Sombrea `tp.Ayuda` (CVE-2024-23682-style) para pisar el soporte del profesor | `OK` — contenido por el orden del classpath | 0 | 0 |
| `hostil-red` | Intenta una conexión saliente para probar `--network none` | `OK` — la conexión falla y la assertion invertida del bundle pasa | 0 | 0 |

Los valores de esta tabla salen de los asserts de `BundlesIT`, no de una medición propia de este
documento.

## Cómo agregar una imagen nueva (otro lenguaje o runtime)

1. `imagenes/<perfil>/Dockerfile`, con contexto de build `imagenes/` (no el subdirectorio):
   `COPY capa1/capa1.sh /opt/sandbox/capa1.sh` (la misma capa 1, sin cambios); un toolchain propio
   en un directorio dedicado (equivalente a `/libs`), de solo lectura para `1000:1000`; `ENV
   SANDBOX_LIBS=<ruta>` y, si difieren de los defaults, `SANDBOX_EVAL_TIMEOUT_S` /
   `SANDBOX_MAX_SCRIPT_BYTES` / `SALIDA_LIMITE_BYTES`; `USER 1000:1000`; `WORKDIR /work`;
   `ENTRYPOINT ["/opt/sandbox/capa1.sh"]`.
2. Un script de capa 2 nuevo en `../perfiles/` que respete el contrato de arriba y salga siempre en
   la banda 40-59.
3. Un perfil `<perfilId>@<version>.json` en `../perfiles/` (spec §4.4) que apunte al tag nuevo y
   copie el script byte a byte en el campo `script`.
4. Tres acoplamientos **manuales**, sin validación automática que los una (ver
   `../README.md#acoplamientos-manuales--no-hay-validación-automática-que-los-una`): el
   `Constantes.ENTRYPOINT` del ejecutor debe seguir coincidiendo con el `ENTRYPOINT` del Dockerfile;
   `SANDBOX_MAX_SCRIPT_BYTES` de la imagen debe ser `>=` el `MAX_SCRIPT_BYTES` que el ejecutor le
   exige al catálogo (R4.5); y el campo `script` del perfil tiene que seguir siendo el `.sh` byte a
   byte cada vez que se lo edite.

## Pendiente

- **`java21-junit/Dockerfile`**: el `sha256` de `junit.jar` no está fijado ni verificado — se confía
  en Maven Central sobre TLS (`TODO(seguridad)` explícito en el propio Dockerfile).
