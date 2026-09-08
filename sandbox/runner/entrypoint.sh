#!/bin/bash
#
# entrypoint.sh — el script de arranque del contenedor de ejecución.
#
# ENTRA:  un tar por STDIN con  src/<...>.java  y  test/<...>.java
# SALE:   un sobre JSON por STDOUT, entre marcas, con el reporte de JUnit,
#         la salida del alumno y los tiempos medidos.
#
# Referencias a docs/arquitectura/03-ms-sandbox-ejecucion.md:
#   §1.3   la secuencia
#   §1.5   por qué el bundle entra por stdin, y por qué el tar es entrada hostil
#   §1.6a  javac + ConsoleLauncher, sin Maven, sin escaneo de classpath
#   §1.6b  MaxRAMPercentage + ExitOnOutOfMemoryError
#   §1.6c  el veredicto sale del REPORTE, nunca del exit code
#   §1.6d  classpath con los tests primero + el reporte fuera del alcance del alumno
#
# Regla que gobierna todo el script: es preferible fallar que aprobar de más.

set -uo pipefail

# Los marcadores dependen del nonce que el ejecutor manda como primera linea de
# stdin (08-spec-ejecutor.md R7.1-R7.4). Hasta leerlo quedan con el nonce vacio:
# si algo falla antes, el sobre sale igual pero el ejecutor no lo va a reconocer,
# que es exactamente lo que corresponde -- sin nonce no hay reporte confiable.
NONCE=""
MARCA_INI="---SANDBOX-${NONCE}-INICIO---"
MARCA_FIN="---SANDBOX-${NONCE}-FIN---"

# La raiz escribible es /work, que es donde la spec del contenedor monta el
# tmpfs (08 §4.1). El rootfs es read-only: fuera de aca no se escribe nada.
readonly RAIZ=/work

readonly DIR_IN=$RAIZ/in
readonly DIR_SOL=$RAIZ/classes/sol
readonly DIR_TEST=$RAIZ/classes/test
readonly DIR_REPORTES=$RAIZ/reports
readonly DIR_WORK=$RAIZ/tmp

readonly BUNDLE_TAR=$DIR_WORK/bundle.tar
readonly FASE_OUT=$DIR_WORK/fase.out
readonly FASE_ERR=$DIR_WORK/fase.err
readonly CRONO=$DIR_WORK/crono

mkdir -p "$DIR_IN" "$DIR_SOL" "$DIR_TEST" "$DIR_REPORTES" "$DIR_WORK" 2>/dev/null
: >"$FASE_OUT"
: >"$FASE_ERR"

# El stdout real se guarda en fd 3. Todo lo demás va a archivos: el único que
# escribe en el stdout del contenedor es `emitir`.
exec 3>&1
exec 1>>"$FASE_OUT"
exec 2>>"$FASE_ERR"

fase="BUNDLE"
resultado="ERROR_INTERNO"
exit_java="null"
ms_compilacion=0
ms_pruebas=0
cpu_pruebas_ms=0
clases_test=()
detalle=""
tests_en_reporte=0
procesos_sobrevivientes=0

# Reloj de pared en milisegundos.
#
# OJO: `date +%s%3N` NO sirve. Ubuntu 26.04 (la base de temurin:21-jdk) ya no
# trae GNU coreutils sino **uutils** (la reimplementación en Rust), y su `date`
# ignora el ancho en `%3N`: devuelve los 9 dígitos de nanosegundos igual. Los
# tiempos salían en nanos disfrazados de ms — 2334953266 "ms" para una
# compilación de 2.3 s. Se pide `%N` explícito y se divide acá.
ahora_ms() {
  echo $(( $(date +%s%N) / 1000000 ))
}

# La salida se trunca ADENTRO del contenedor, no afuera (04 §12): un alumno que
# escupe gigabytes no debe poder inflar la RAM del worker.
b64_truncado() {
  head -c "$SALIDA_LIMITE_BYTES" "$1" 2>/dev/null | base64 -w0 2>/dev/null || printf ''
}

json_lista_clases() {
  local sep="" c
  printf '['
  for c in ${clases_test+"${clases_test[@]}"}; do
    printf '%s"%s"' "$sep" "$c"
    sep=","
  done
  printf ']'
}

emitir() {
  local reportes_b64=""
  local truncado=false

  # El directorio de reportes se empaqueta entero: JUnit escribe más de un XML
  # y el worker los normaliza. Si no hay reportes viaja vacío, y el worker sabe
  # que NO puede haber veredicto de éxito (§1.6c).
  if [ -d "$DIR_REPORTES" ] && [ -n "$(ls -A "$DIR_REPORTES" 2>/dev/null)" ]; then
    reportes_b64=$(tar -czf - -C "$DIR_REPORTES" . 2>/dev/null | base64 -w0)
  fi

  local n_out n_err
  n_out=$(stat -c%s "$FASE_OUT" 2>/dev/null || echo 0)
  n_err=$(stat -c%s "$FASE_ERR" 2>/dev/null || echo 0)
  if [ "$n_out" -gt "$SALIDA_LIMITE_BYTES" ] || [ "$n_err" -gt "$SALIDA_LIMITE_BYTES" ]; then
    truncado=true
  fi

  {
    printf '%s\n' "$MARCA_INI"
    printf '{'
    printf '"schema":"sandbox.runner/v1",'
    printf '"fase":"%s",' "$fase"
    printf '"resultado":"%s",' "$resultado"
    printf '"detalle":"%s",' "$detalle"
    printf '"exitCodeJava":%s,' "$exit_java"
    printf '"testsEnReporte":%s,' "$tests_en_reporte"
    printf '"procesosSobrevivientes":%s,' "$procesos_sobrevivientes"
    printf '"clasesTest":%s,' "$(json_lista_clases)"
    printf '"recursos":{"tiempoCompilacionMs":%s,"tiempoPruebasMs":%s,"cpuPruebasMs":%s},' \
           "$ms_compilacion" "$ms_pruebas" "$cpu_pruebas_ms"
    printf '"limites":{"cpuPruebasS":%s,"timeoutCompileMs":%s,"timeoutTestsMs":%s,"salidaLimiteBytes":%s},' \
           "$CPU_TESTS_S" "$TIMEOUT_COMPILE_MS" "$TIMEOUT_TESTS_MS" "$SALIDA_LIMITE_BYTES"
    printf '"reportesTarGzB64":"%s",' "$reportes_b64"
    printf '"stdoutB64":"%s",' "$(b64_truncado "$FASE_OUT")"
    printf '"stderrB64":"%s",' "$(b64_truncado "$FASE_ERR")"
    printf '"truncado":%s' "$truncado"
    printf '}\n'
    printf '%s\n' "$MARCA_FIN"
  } >&3
}

# $1 = resultado, $2 = detalle, $3 = exit code del contenedor
morir() {
  resultado="$1"
  detalle="$2"
  emitir
  exit "$3"
}

# --------------------------------------------------------------------------
# Paso 1 — leer el bundle de stdin y validarlo ANTES de extraerlo
#
# §1.5: el tar es entrada hostil. La API ya valida las rutas, pero ésta es la
# segunda barrera y es la que ve el tar real. Path traversal está probado;
# symlink y hard link son los dos casos que faltan en la suite (§1.5, tabla).
# --------------------------------------------------------------------------
# R7.2: primero la linea del nonce, despues los bytes del tar, del MISMO
# descriptor. `read` no puede consumir mas alla del 
: bash lee de a un byte
# sobre descriptores no posicionables, que es lo que hace que esto funcione.
#
# R7.3: NONCE es una variable de shell y NO se exporta. Exportarla la dejaria
# en /proc/1/environ, legible por el codigo del alumno, y el marcador dejaria
# de ser infalsificable -- que es el unico motivo por el que existe.
if ! IFS= read -r NONCE; then
  morir "BUNDLE_INVALIDO" "no vino la linea del nonce en stdin" 22
fi

case "$NONCE" in
  *[!0-9a-f]* | "") morir "BUNDLE_INVALIDO" "nonce con formato invalido" 22 ;;
esac
[ "${#NONCE}" -eq 32 ] || morir "BUNDLE_INVALIDO" "nonce de largo distinto de 32" 22

MARCA_INI="---SANDBOX-${NONCE}-INICIO---"
MARCA_FIN="---SANDBOX-${NONCE}-FIN---"

cat >"$BUNDLE_TAR"
[ -s "$BUNDLE_TAR" ] || morir "BUNDLE_INVALIDO" "bundle vacio en stdin" 22

if ! tar -tf "$BUNDLE_TAR" >"$DIR_WORK/rutas" 2>/dev/null; then
  morir "BUNDLE_INVALIDO" "el tar no se puede leer" 22
fi

while IFS= read -r ruta; do
  [ -n "$ruta" ] || continue
  case "$ruta" in
    /*)               morir "BUNDLE_INVALIDO" "ruta absoluta en el tar" 22 ;;
    *..*)             morir "BUNDLE_INVALIDO" "ruta con .. en el tar" 22 ;;
    ./|./src/*|./test/*) : ;;
    src/*|test/*)     : ;;
    src/|test/|./src/|./test/) : ;;
    *)                morir "BUNDLE_INVALIDO" "ruta fuera de src/ y test/: $ruta" 22 ;;
  esac
done <"$DIR_WORK/rutas"

# --no-same-owner / --no-same-permissions: el tar no dicta uid ni modo.
if ! tar -xf "$BUNDLE_TAR" -C "$DIR_IN" \
        --no-same-owner --no-same-permissions --no-overwrite-dir; then
  morir "BUNDLE_INVALIDO" "fallo la extraccion del tar" 22
fi

if [ -z "$(find "$DIR_IN/test" -name '*.java' -print -quit 2>/dev/null)" ]; then
  morir "BUNDLE_INVALIDO" "el bundle no trae ningun archivo de test" 22
fi

# Los tests del profesor quedan solo-lectura. No alcanza como defensa (§1.6d:
# ninguno de los tres vectores pasa por modificar el test), pero es gratis.
find "$DIR_IN/test" -type f -exec chmod 0444 {} + 2>/dev/null

TO_COMPILE_S=$(( TIMEOUT_COMPILE_MS / 1000 ))
TO_TESTS_S=$(( TIMEOUT_TESTS_MS / 1000 ))

# --------------------------------------------------------------------------
# Paso 2 — compilar la SOLUCIÓN sola
#
# Si falla acá la culpa es del alumno (§1.6a). Reloj de PARED y presupuesto de
# PLATAFORMA: compilar no se le cobra al alumno (§1.4a).
# --------------------------------------------------------------------------
fase="COMPILACION_SOLUCION"
t0=$(ahora_ms)

mapfile -t FUENTES_SOL < <(find "$DIR_IN/src" -name '*.java' 2>/dev/null)
if [ "${#FUENTES_SOL[@]}" -eq 0 ]; then
  ms_compilacion=$(( $(ahora_ms) - t0 ))
  morir "ERROR_COMPILACION" "el bundle no trae fuentes en src/" 20
fi

timeout --signal=KILL "${TO_COMPILE_S}s" \
  javac -encoding UTF-8 -d "$DIR_SOL" "${FUENTES_SOL[@]}"
rc=$?
if [ $rc -eq 137 ] || [ $rc -eq 124 ]; then
  ms_compilacion=$(( $(ahora_ms) - t0 ))
  morir "TIMEOUT_COMPILACION" "javac de la solucion supero el reloj de plataforma" 24
elif [ $rc -ne 0 ]; then
  ms_compilacion=$(( $(ahora_ms) - t0 ))
  morir "ERROR_COMPILACION" "javac fallo sobre el codigo del alumno" 20
fi

# --------------------------------------------------------------------------
# Paso 3 — compilar los TESTS, a un directorio SEPARADO
#
# §1.6d medida 2: salidas separadas, para poder poner los tests PRIMERO en el
# classpath de ejecución. Si el alumno sombrea una clase de soporte del
# profesor, gana el .class del profesor.
# Si falla acá la culpa es de T05, no del alumno → SUITE_INVALIDA.
# --------------------------------------------------------------------------
fase="COMPILACION_TESTS"

mapfile -t FUENTES_TEST < <(find "$DIR_IN/test" -name '*.java' 2>/dev/null)

timeout --signal=KILL "${TO_COMPILE_S}s" \
  javac -encoding UTF-8 -cp "$JUNIT_JAR:$DIR_SOL" -d "$DIR_TEST" "${FUENTES_TEST[@]}"
rc=$?
ms_compilacion=$(( $(ahora_ms) - t0 ))
if [ $rc -eq 137 ] || [ $rc -eq 124 ]; then
  morir "TIMEOUT_COMPILACION" "javac de los tests supero el reloj de plataforma" 24
elif [ $rc -ne 0 ]; then
  morir "SUITE_INVALIDA" "no compila la suite de tests" 21
fi

# --------------------------------------------------------------------------
# Paso 4 — averiguar los nombres de clase SIN escanear
#
# §1.6a [IE]: no se pasa --scan-classpath. Las clases de test se derivan de los
# .class ya compilados (se descartan las internas, con `$`). Eso ataca directo
# los 1.2–2.9 s de descubrimiento que midió el spike.
# --------------------------------------------------------------------------
mapfile -t clases_test < <(
  cd "$DIR_TEST" && find . -name '*.class' ! -name '*$*' -printf '%P\n' 2>/dev/null |
    sed 's#/#.#g; s#\.class$##' | sort
)
if [ "${#clases_test[@]}" -eq 0 ]; then
  morir "SUITE_INVALIDA" "la suite compilo pero no produjo ninguna clase" 21
fi

SELECTORES=()
for c in "${clases_test[@]}"; do
  SELECTORES+=( "--select-class=$c" )
done

# --------------------------------------------------------------------------
# Paso 5 — correr los tests
#
# Cuatro cosas pasan acá, y las cuatro son deliberadas:
#   a) `ulimit -t` = RLIMIT_CPU: el reloj del ALUMNO, medido en tiempo de
#      procesador, inmune a la contención del host (§1.4d). El kernel manda
#      SIGXCPU al agotarse → exit 152.
#   b) `timeout` de pared como backstop generoso, para el que DUERME en vez de
#      quemar CPU.
#   c) classpath con $DIR_TEST PRIMERO (§1.6d medida 2).
#   d) --include-classname: sin esto --select-class no encuentra nada
#      (junit-framework#2289). No es opcional.
# --------------------------------------------------------------------------
fase="TESTS"
t1=$(ahora_ms)

(
  ulimit -t "$CPU_TESTS_S"
  TIMEFORMAT='%3U %3S'
  # java tiene su stdout/stderr redirigidos EXPLÍCITAMENTE a los archivos de
  # fase, así que lo único que cae en $CRONO.time es el reporte de `time`.
  { time timeout --signal=KILL "${TO_TESTS_S}s" \
      java -XX:MaxRAMPercentage=60 \
           -XX:+ExitOnOutOfMemoryError \
           -Djava.awt.headless=true \
           -jar "$JUNIT_JAR" execute \
           --class-path "$DIR_TEST:$DIR_SOL" \
           "${SELECTORES[@]}" \
           --include-classname='.*' \
           --details=summary \
           --disable-ansi-colors \
           --reports-dir="$DIR_REPORTES" \
           >>"$FASE_OUT" 2>>"$FASE_ERR" 3>&- ; } 2>"$CRONO.time"
  echo "$?" >"$CRONO.rc"
)
ms_pruebas=$(( $(ahora_ms) - t1 ))

exit_java=$(cat "$CRONO.rc" 2>/dev/null)
[ -n "$exit_java" ] || exit_java=1

# user + sys del proceso de tests, en ms. Es el número contra el que se evalúa
# el límite, y a diferencia del reloj de pared es estable entre corridas (§1.4d).
cpu_pruebas_ms=$(awk '/^[0-9]/ { printf "%d", ($1 + $2) * 1000; exit }' "$CRONO.time" 2>/dev/null)
[ -n "$cpu_pruebas_ms" ] || cpu_pruebas_ms=0

# --------------------------------------------------------------------------
# Paso 6 — detectar y barrer procesos sobrevivientes ANTES de leer el reporte
#
# §1.6d medida 3. El vector: el código del alumno deja un proceso en segundo
# plano que sobrevive a la JVM y reescribe el XML con su propio veredicto. Es
# el bug de Judge0 aplicado a nosotros.
#
# ESTO NO ES PREVENCIÓN, Y NO PUEDE SERLO. Verificado con
# `bundles/hostil-reporte-loop`: un atacante que reescribe el reporte EN BUCLE
# gana siempre, porque matarlo después no deshace la escritura que ya hizo. El
# reporte vive en /tmp, /tmp lo escribe el alumno, y JUnit corre con el uid del
# alumno: el archivo está DENTRO de su dominio de confianza, y no hay flag de
# Docker que lo saque de ahí.
#
# Lo que sí se puede, y es lo que se hace acá, es DETECTAR: para reescribir el
# reporte después de que JUnit lo escribió, el atacante necesita un proceso
# vivo. Se cuentan los que sobrevivieron a la JVM, y si hay alguno la entrega
# NO puede terminar en éxito. Un ataque que insiste se detecta siempre; uno que
# escribe una sola vez a ciegas tiene que ganarle a una ventana de milisegundos.
#
# La barrida se hace por /proc y no con `pkill -x java`, que era el bug: el
# proceso del ataque es un `sh`, no un `java`, y no matcheaba.
#
# El script es PID 1 (es el ENTRYPOINT), así que cualquier otro PID sobra.
# Solo se usan builtins acá adentro para no crear hijos mientras se cuenta.
# --------------------------------------------------------------------------
for entrada in /proc/[0-9]*; do
  pid=${entrada#/proc/}
  [ "$pid" = "1" ] && continue
  [ "$pid" = "$$" ] && continue
  procesos_sobrevivientes=$(( procesos_sobrevivientes + 1 ))
  kill -9 "$pid" 2>/dev/null
done

# Cuántos tests declara el reporte.
#
# VERIFICADO, y corrige lo que decía este script antes: con `System.exit(0)` en
# el código del alumno, JUnit **sí deja un reporte escrito** —un
# `TEST-junit-platform-suite.xml` con `tests="0"`—, el contenedor sale con
# exitCode 0 y `OOMKilled: false`. O sea que "¿existe el reporte?" NO alcanza
# como guarda: aprobaría una entrega vacía.
#
# La guarda que efectivamente frena el bypass es la que dice §1.6c: exigir
# `tests > 0`. El veredicto sigue siendo del worker, pero contar acá es defensa
# en profundidad y le da al worker el número ya calculado.
tests_en_reporte=$(
  grep -ho 'tests="[0-9]*"' "$DIR_REPORTES"/*.xml 2>/dev/null |
    grep -o '[0-9]*' | awk '{ s += $1 } END { print s + 0 }'
)
[ -n "$tests_en_reporte" ] || tests_en_reporte=0

clasificar_salida() {
  # VERIFICADO, y corrige al doc: §1.4d dice que al agotarse RLIMIT_CPU el
  # kernel manda SIGXCPU, o sea exit 152. En la práctica la JVM sale con **137**
  # (SIGKILL): con `ulimit -t` el soft y el hard limit quedan iguales, así que
  # el SIGXCPU y el SIGKILL llegan juntos y gana el segundo.
  #
  # 137 es ambiguo — es también el OOM-kill del cgroup. Lo que desambigua no es
  # el exit code sino **la CPU medida**: si se consumió el presupuesto entero,
  # fue el reloj del alumno. Es el mismo razonamiento por el que el veredicto
  # sale del reporte y no del exit code.
  local umbral_ms=$(( CPU_TESTS_S * 1000 * 95 / 100 ))

  case "$exit_java" in
    3)
      resultado="LIMITE_MEMORIA"; detalle="ExitOnOutOfMemoryError" ;;
    124)
      resultado="TIMEOUT_PARED"; detalle="backstop de pared agotado" ;;
    152|137)
      if [ "$cpu_pruebas_ms" -ge "$umbral_ms" ]; then
        resultado="TIMEOUT_CPU"
        detalle="se agoto el reloj de CPU del alumno (${cpu_pruebas_ms}ms de ${CPU_TESTS_S}s)"
      else
        # Lo mataron, pero no por CPU. El candidato es el OOM-killer del cgroup,
        # y ese dato lo tiene el worker en `.State.OOMKilled`, no nosotros.
        resultado="MUERTO_POR_SENAL"
        detalle="SIGKILL sin agotar la CPU (${cpu_pruebas_ms}ms): desambiguar con OOMKilled"
      fi ;;
    *)
      resultado="OK"; detalle="" ;;
  esac
}

# Código de salida del contenedor para cada `resultado`. Diagnóstico: el
# veredicto del alumno NO se decide con esto (§1.6c).
codigo_de() {
  case "$1" in
    OK)                     echo 0  ;;
    LIMITE_MEMORIA)         echo 25 ;;
    TIMEOUT_CPU)            echo 26 ;;
    TIMEOUT_PARED)          echo 27 ;;
    SIN_REPORTE)            echo 28 ;;
    SALIDA_ANTICIPADA)      echo 29 ;;
    VEREDICTO_NO_CONFIABLE) echo 30 ;;
    MUERTO_POR_SENAL)       echo 31 ;;
    *)                      echo 1  ;;
  esac
}

clasificar_salida

# Sin reporte NO hay éxito posible (§1.6c). La causa ya la clasificó
# `clasificar_salida`; si no encontró ninguna, es que la JVM se fue en silencio.
if [ -z "$(ls -A "$DIR_REPORTES" 2>/dev/null)" ]; then
  [ "$resultado" = "OK" ] && {
    resultado="SIN_REPORTE"
    detalle="la JVM termino sin escribir reporte"
  }
  morir "$resultado" "${detalle:-sin reporte}" "$(codigo_de "$resultado")"
fi

# Hay reporte, pero la ejecución no terminó bien: se agotó un reloj, la mató una
# señal, o se quedó sin memoria. Sin esto el script cae hasta el `exit 0` del
# final y **el contenedor informa exito con un TIMEOUT adentro**: encontrado
# corriendo el ejecutor real contra esta imagen, con `bundles/hostil-cpu`, que
# alcanzó a escribir un reporte de 0 tests antes de que el kernel lo frenara.
# El sobre siempre dijo la verdad; el exit code no.
if [ "$resultado" != "OK" ]; then
  morir "$resultado" "${detalle:-la ejecucion no termino bien}" "$(codigo_de "$resultado")"
fi

# Hay reporte, pero no corrió NINGÚN test: la JVM se murió antes. Es
# responsabilidad del alumno, así que es distinto de ERROR_INTERNO (§5.1).
if [ "$tests_en_reporte" -eq 0 ] && [ "$resultado" = "OK" ]; then
  morir "SALIDA_ANTICIPADA" "hay reporte pero con tests=0: la JVM murio antes de correr nada" 29
fi

# Sobrevivió algo a la JVM. El reporte pudo haber sido reescrito y NO hay forma
# de saber si lo fue: el veredicto no es confiable, así que no hay éxito posible.
# Es preferible fallar que aprobar de más.
if [ "$procesos_sobrevivientes" -gt 0 ]; then
  morir "VEREDICTO_NO_CONFIABLE" \
        "sobrevivieron $procesos_sobrevivientes procesos a la JVM: el reporte pudo ser reescrito" 30
fi

# `OK` significa "el pipeline corrió y hay reporte", NO "los tests pasaron".
# El veredicto lo decide el worker leyendo el XML (§1.6c).
emitir
exit 0
