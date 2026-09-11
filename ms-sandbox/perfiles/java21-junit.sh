#!/bin/sh
#
# java21-junit.sh — la CAPA 2 (lo que en el modelo real escribe G5).
#
# Es el campo `script` del perfil `java21-junit`. La capa 1 lo recibe por stdin
# y lo ejecuta con `sh -c`, parado en $SANDBOX_IN. Todo lo que hay aca adentro
# es conocimiento de evaluacion: que es compilar, que es un test, que significa
# aprobar. La capa 1 no sabe nada de esto.
#
# Sale de extraer los pasos 2 a 5 del entrypoint.sh viejo. Dos cambios de
# fondo respecto de aquel:
#
#   1. POSIX sh, no bash. Se invoca con `sh -c`, y el `sh` de la imagen es
#      dash: nada de arrays ni de mapfile. Las listas de archivos van por
#      @argfile de javac, que para esto es mas limpio que un array igual.
#   2. Los codigos de salida son de la BANDA 40-59, que es la que
#      Respuesta_G8_a_Propuesta_V4.md seccion 2.3 le reserva a G5. Los 20-31
#      son de la capa 1 y aca no se usan.
#
# CONTRATO CON LA CAPA 1 (Respuesta_V4 seccion 2.1):
#   $SANDBOX_IN       donde esta el codigo; es el directorio de trabajo
#   $SANDBOX_REPORTS  el buzon: TODO lo que quede aca vuelve, y nada mas vuelve
#   $SANDBOX_TMP      borradores, no vuelven
#   $SANDBOX_STATUS   avisos opcionales: archivos `fase` y `detalle`
#   $SANDBOX_LIBS     las herramientas, solo lectura
#   $SANDBOX_MEM_MB   cuanta memoria hay, para dimensionar la JVM
#
# El JSON con la nota va como un archivo mas en $SANDBOX_REPORTS, NO por
# stdout: para cuando corremos, la capa 1 ya desvio stdout a un archivo.

set -u

JUNIT_JAR="${SANDBOX_LIBS}/junit.jar"

DIR_SOL="$SANDBOX_TMP/classes/sol"
DIR_TEST="$SANDBOX_TMP/classes/test"

# Los relojes del perfil. En el modelo real estos salen de `limits` del perfil;
# aca van literales porque el script ES el perfil.
#
# INVARIANTE (nadie la valida, se sostiene a mano): las fases corren en serie
# adentro del backstop de la capa 1, asi que
#     2 * TIMEOUT_COMPILE_S + TIMEOUT_TESTS_S + margen <= SANDBOX_EVAL_TIMEOUT_S (45)
#     8 + 8 + 25 = 41, margen 4 s.
# Si no se cumple, una entrega lenta pero legitima muere por el backstop de la
# capa 1 (TIMEOUT_PARED, 27) antes de que esta capa pueda emitir 42 o 45.
TIMEOUT_COMPILE_S=8      # reloj de PLATAFORMA: compilar no se le cobra al alumno
CPU_TESTS_S=10           # reloj del ALUMNO, en tiempo de CPU
TIMEOUT_TESTS_S=25       # backstop de pared, para el que duerme en vez de quemar CPU

mkdir -p "$DIR_SOL" "$DIR_TEST"

# --------------------------------------------------------------------------
# El canal rico y fragil del contrato: fase + detalle en $SANDBOX_STATUS.
# La capa 1 los copia al sobre tal cual. Si nos morimos de golpe no llegan, y
# para eso esta el codigo de salida, que lo produce el kernel y siempre llega.
# --------------------------------------------------------------------------
avisar() {
  printf '%s' "$1" >"$SANDBOX_STATUS/fase"
  printf '%s' "$2" >"$SANDBOX_STATUS/detalle"
}

# $1 = fase, $2 = detalle, $3 = codigo de la banda 40-59
rendirse() {
  avisar "$1" "$2"
  nota "$1" "$2" "$3"
  exit "$3"
}

# El JSON de la nota. Va al BUZON, no a stdout (Respuesta_V4 seccion 2.2).
nota() {
  printf '{"schema":"g5.nota/v1","fase":"%s","detalle":"%s","codigo":%s}\n' \
    "$1" "$2" "$3" >"$SANDBOX_REPORTS/nota.json"
}

# --------------------------------------------------------------------------
# Fase 1 — compilar la SOLUCION sola
#
# Si falla aca la culpa es del alumno. Reloj de PARED y presupuesto de
# plataforma: compilar no se le cobra.
# --------------------------------------------------------------------------
avisar "COMPILACION" "compilando la solucion"

find "$SANDBOX_IN/src" -name '*.java' >"$SANDBOX_TMP/fuentes-sol.txt" 2>/dev/null
if [ ! -s "$SANDBOX_TMP/fuentes-sol.txt" ]; then
  rendirse "COMPILACION" "el bundle no trae fuentes en src/" 40
fi

timeout --signal=KILL "${TIMEOUT_COMPILE_S}s" \
  javac -encoding UTF-8 -d "$DIR_SOL" "@$SANDBOX_TMP/fuentes-sol.txt"
rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  rendirse "COMPILACION" "javac de la solucion supero el reloj de plataforma" 42
elif [ $rc -ne 0 ]; then
  rendirse "COMPILACION" "no compila la solucion del alumno" 40
fi

# --------------------------------------------------------------------------
# Fase 2 — compilar los TESTS, a un directorio SEPARADO
#
# Salidas separadas para poder poner los tests PRIMERO en el classpath de
# ejecucion: si el alumno sombrea una clase de soporte del profesor, gana el
# .class del profesor.
#
# Si falla aca la culpa NO es del alumno: la suite esta rota. Es el caso que
# Respuesta_V4 seccion 6 dice que no consume intento.
# --------------------------------------------------------------------------
avisar "COMPILACION_SUITE" "compilando las pruebas"

find "$SANDBOX_IN/test" -name '*.java' >"$SANDBOX_TMP/fuentes-test.txt" 2>/dev/null
if [ ! -s "$SANDBOX_TMP/fuentes-test.txt" ]; then
  rendirse "COMPILACION_SUITE" "el bundle no trae ningun archivo de test" 41
fi

timeout --signal=KILL "${TIMEOUT_COMPILE_S}s" \
  javac -encoding UTF-8 -cp "$JUNIT_JAR:$DIR_SOL" -d "$DIR_TEST" "@$SANDBOX_TMP/fuentes-test.txt"
rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  rendirse "COMPILACION_SUITE" "javac de las pruebas supero el reloj de plataforma" 42
elif [ $rc -ne 0 ]; then
  rendirse "COMPILACION_SUITE" "no compila la suite de pruebas" 41
fi

# --------------------------------------------------------------------------
# Fase 3 — nombres de clase SIN escanear el classpath
#
# No se pasa --scan-classpath: las clases de test se derivan de los .class ya
# compilados. Ataca directo el costo de descubrimiento que midio el spike.
# --------------------------------------------------------------------------
( cd "$DIR_TEST" && find . -name '*.class' ! -name '*$*' ) \
  | sed -e 's#^\./##' -e 's#/#.#g' -e 's#\.class$##' | sort \
  >"$SANDBOX_TMP/clases.txt"

if [ ! -s "$SANDBOX_TMP/clases.txt" ]; then
  rendirse "COMPILACION_SUITE" "la suite compilo pero no produjo ninguna clase" 41
fi

# POSIX no tiene arrays: los selectores se acumulan en los parametros
# posicionales con `set --`.
set --
while IFS= read -r clase; do
  [ -n "$clase" ] || continue
  set -- "$@" "--select-class=$clase"
done <"$SANDBOX_TMP/clases.txt"

# --------------------------------------------------------------------------
# Fase 4 — correr las pruebas
#
#   a) `ulimit -t` = RLIMIT_CPU: el reloj del ALUMNO, en tiempo de procesador,
#      inmune a la contencion del host. Al agotarse el kernel manda SIGXCPU.
#   b) `timeout` de pared como backstop, para el que DUERME en vez de quemar.
#   c) $DIR_TEST PRIMERO en el classpath.
#   d) --include-classname: sin esto --select-class no encuentra nada
#      (junit-framework#2289). No es opcional.
#   e) MaxRAMPercentage sobre $SANDBOX_MEM_MB, que nos dijo la capa 1.
# --------------------------------------------------------------------------
avisar "PRUEBAS" "ejecutando la suite"

(
  ulimit -t "$CPU_TESTS_S"
  timeout --signal=KILL "${TIMEOUT_TESTS_S}s" \
    java -XX:MaxRAMPercentage=60 \
         -XX:+ExitOnOutOfMemoryError \
         -Djava.awt.headless=true \
         -jar "$JUNIT_JAR" execute \
         --class-path "$DIR_TEST:$DIR_SOL" \
         "$@" \
         --include-classname='.*' \
         --details=summary \
         --disable-ansi-colors \
         --reports-dir="$SANDBOX_REPORTS"
  echo "$?" >"$SANDBOX_TMP/rc-java"
)
rc_java=$(cat "$SANDBOX_TMP/rc-java" 2>/dev/null)
[ -n "$rc_java" ] || rc_java=137

case "$rc_java" in
  3)
    # La JVM bien configurada se queda sin memoria ANTES que el cgroup, asi que
    # el OOM llega como exit 3 con OOMKilled:false, no como el 137 que uno
    # esperaria. Los dos caminos existen.
    rendirse "PRUEBAS" "la JVM se quedo sin memoria (ExitOnOutOfMemoryError)" 43 ;;
  124)
    rendirse "PRUEBAS" "backstop de pared agotado: la suite no termina" 45 ;;
  152 | 137)
    # Con `ulimit -t` el soft y el hard limit quedan iguales, asi que SIGXCPU y
    # SIGKILL llegan juntos y gana el segundo: sale 137, no 152. El 137 es
    # ambiguo (tambien es el OOM del cgroup) y lo que desambigua no es el
    # codigo sino la CPU medida, que la reporta la capa 1.
    rendirse "PRUEBAS" "se agoto el reloj de CPU del alumno (${CPU_TESTS_S}s)" 44 ;;
esac

# --------------------------------------------------------------------------
# Fase 5 — la guarda que frena el bypass del System.exit(0)
#
# MEDIDO: con `System.exit(0)` en el codigo del alumno, JUnit SI deja un
# reporte escrito -- un TEST-junit-platform-suite.xml con tests="0" -- y la JVM
# sale con 0. O sea que "existe el reporte?" NO alcanza como guarda: aprobaria
# una entrega vacia. La que frena el bypass es exigir tests > 0.
#
# Esta guarda vive aca, en la capa 2, y no en la capa 1: leer un XML de JUnit
# es saber que formato tiene el reporte, y eso es conocimiento de evaluacion.
# Es exactamente el `reportFormat` que le pedimos a G5 en la seccion 4.6.
# --------------------------------------------------------------------------
if [ -z "$(ls -A "$SANDBOX_REPORTS" 2>/dev/null)" ]; then
  rendirse "PRUEBAS" "la JVM termino sin escribir reporte" 46
fi

tests=$(grep -ho 'tests="[0-9]*"' "$SANDBOX_REPORTS"/*.xml 2>/dev/null \
        | grep -o '[0-9]*' | awk '{ s += $1 } END { print s + 0 }')
[ -n "$tests" ] || tests=0

if [ "$tests" -eq 0 ]; then
  rendirse "PRUEBAS" "hay reporte pero con tests=0: la JVM murio antes de correr nada" 47
fi

# --------------------------------------------------------------------------
# Fin. `0` significa "la evaluacion corrio y dejo evidencia", NO "el alumno
# aprobo": eso lo decide el worker leyendo el XML. El numero no es el veredicto.
# --------------------------------------------------------------------------
avisar "FIN" "la suite corrio: $tests tests"
printf '{"schema":"g5.nota/v1","fase":"FIN","detalle":"la suite corrio","codigo":0,"tests":%s,"exitJava":%s}\n' \
  "$tests" "$rc_java" >"$SANDBOX_REPORTS/nota.json"
exit 0
