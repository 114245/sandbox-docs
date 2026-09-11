#!/bin/sh
#
# java21-junit-tiempos.sh — EJEMPLO de capa 2 que ademas de evaluar MIDE.
#
# No es un perfil de produccion: vive en pruebas/, fuera del catalogo que carga
# el ejecutor. Es `perfiles/java21-junit.sh` (perfil java21-junit@4) con una
# sola cosa agregada: deja en el buzon un `tiempos.json` con el reloj de pared y
# la CPU de cada fase. Todo lo agregado esta marcado con [TIEMPOS]; el resto es
# identico al perfil de referencia, incluidos codigos de salida y relojes.
#
# Lo que devuelve, en $SANDBOX_REPORTS/tiempos.json. MEDIDO con bundles/ok-suma
# contra sandbox-runner:2.0.0-capa1 (probar-capa1.sh, Docker Desktop, 10-sep-2026):
#
#   {"schema":"g5.tiempos/v1",
#    "fuenteCpu":"cgroup-v1",
#    "presupuesto":{"compilacionS":8,"cpuPruebasS":10,"pruebasS":25},
#    "fases":[
#      {"fase":"COMPILACION","paredMs":1262,"cpuMs":1172,"cobraAlAlumno":false,"exit":0},
#      {"fase":"COMPILACION_SUITE","paredMs":1510,"cpuMs":1450,"cobraAlAlumno":false,"exit":0},
#      {"fase":"PRUEBAS","paredMs":1676,"cpuMs":1630,"cobraAlAlumno":true,"exit":0}],
#    "junitSuitesMs":114}
#
# Y los dos hostiles de tiempo, que se distinguen solo mirando pared contra CPU:
#   hostil-cpu   (44)  PRUEBAS paredMs=10083 cpuMs=10036 exit=137  -> quemo su CPU
#   hostil-sleep (45)  PRUEBAS paredMs=25050 cpuMs=1827  exit=124  -> durmio
#
# Una fase aparece solo si llego a correr. Si la evaluacion se frena (40-47), el
# archivo igual se escribe con las fases medidas hasta ahi: un 42 viene con el
# paredMs de la compilacion que se paso del reloj.
#
# CUATRO COSAS QUE HAY QUE SABER ANTES DE USAR ESTOS NUMEROS
#
#   1. CPU: se lee del CGROUP del contenedor, no de `times`. `times` solo cuenta
#      a los hijos que el shell espero: un proceso huerfano (doble fork desde el
#      codigo del alumno) lo adopta el PID 1, que es la capa 1, y su CPU
#      desaparece del conteo. MEDIDO con la imagen real: un huerfano quemo
#      843 ms de CPU; `times` informo 0 y el cgroup, 843. El cgroup cuenta todo
#      el contenedor, asi que el alumno puede INFLAR sus numeros pero no
#      achicarlos. Si no hay cgroup legible se cae a `times` y `fuenteCpu` lo
#      dice: con "times", el cpuMs de PRUEBAS no es confiable.
#
#   2. Pared: es ruido para calificar. Medido para el mismo bundle: de 1,8 a
#      5,2 s segun la carga del host (HANDOFF-opcion1.md §4). Sirve para
#      mostrar, no para poner nota.
#
#   3. cpuMs de PRUEBAS incluye arrancar la JVM y descubrir los tests, no solo
#      correrlos. `junitSuitesMs` es lo que JUnit dice que tardaron las suites,
#      medido adentro de la JVM: la diferencia es el costo fijo de la JVM. Ojo:
#      ese numero lo escribe un proceso donde tambien corre codigo del alumno.
#
#   4. Si la capa 1 informa procesosSobrevivientes > 0, este archivo no vale:
#      pudo reescribirlo un proceso vivo. Es la misma regla que para el XML.
#
# CONTRATO CON LA CAPA 1: el mismo que perfiles/java21-junit.sh.

set -u

JUNIT_JAR="${SANDBOX_LIBS}/junit.jar"

DIR_SOL="$SANDBOX_TMP/classes/sol"
DIR_TEST="$SANDBOX_TMP/classes/test"

# Los relojes del perfil, iguales a java21-junit@4. Misma invariante:
#     2 * TIMEOUT_COMPILE_S + TIMEOUT_TESTS_S + margen <= SANDBOX_EVAL_TIMEOUT_S (45)
#     8 + 8 + 25 = 41, margen 4 s. Medir no la mueve: cuesta un `date` y un `cat`.
TIMEOUT_COMPILE_S=8      # reloj de PLATAFORMA: compilar no se le cobra al alumno
CPU_TESTS_S=10           # reloj del ALUMNO, en tiempo de CPU
TIMEOUT_TESTS_S=25       # backstop de pared, para el que duerme en vez de quemar CPU

mkdir -p "$DIR_SOL" "$DIR_TEST"

# --------------------------------------------------------------------------
# [TIEMPOS] De donde sale la CPU. v2 y v1 conviven en la practica: el Docker
# Desktop donde se escribio esto monta cgroup v1.
# --------------------------------------------------------------------------
if [ -r /sys/fs/cgroup/cpu.stat ]; then
  FUENTE_CPU="cgroup-v2"
elif [ -r /sys/fs/cgroup/cpuacct/cpuacct.usage ]; then
  FUENTE_CPU="cgroup-v1"
else
  FUENTE_CPU="times"
fi

# CPU acumulada, en microsegundos.
cpu_us() {
  case "$FUENTE_CPU" in
    cgroup-v2) awk '$1 == "usage_usec" { printf "%.0f\n", $2 }' /sys/fs/cgroup/cpu.stat ;;
    cgroup-v1) awk '{ printf "%.0f\n", $1 / 1000 }' /sys/fs/cgroup/cpuacct/cpuacct.usage ;;
    # Segunda linea de `times`: usuario y sistema de los hijos, formato 0m1.220000s.
    *) times | awk 'NR == 2 { split($1, u, /[ms]/); split($2, s, /[ms]/);
                              printf "%.0f\n", (u[1] * 60 + u[2] + s[1] * 60 + s[2]) * 1000000 }' ;;
  esac
}

# Reloj de pared en milisegundos. `%N` es de GNU date (la imagen es Ubuntu);
# BusyBox lo devuelve literal, y ahi se cae a segundos enteros.
if [ "$(date +%N)" = "N" ]; then
  ahora_ms() { echo $(( $(date +%s) * 1000 )); }
else
  ahora_ms() { echo $(( $(date +%s%N) / 1000000 )); }
fi

TIEMPOS=""   # fragmentos JSON de las fases ya medidas, separados por coma

fase_inicio() {
  T0_PARED=$(ahora_ms)
  T0_CPU=$(cpu_us)
}

# $1 = fase, $2 = cobraAlAlumno (true|false), $3 = exit del comando medido
fase_fin() {
  pared=$(( $(ahora_ms) - T0_PARED ))
  cpu=$(( ($(cpu_us) - T0_CPU) / 1000 ))
  frag=$(printf '{"fase":"%s","paredMs":%s,"cpuMs":%s,"cobraAlAlumno":%s,"exit":%s}' \
    "$1" "$pared" "$cpu" "$2" "$3")
  TIEMPOS="${TIEMPOS:+$TIEMPOS,}$frag"
}

escribir_tiempos() {
  # Lo que JUnit dice que tardaron las suites, sumando el `time` de cada
  # <testsuite>. null si no hay XML (la evaluacion se freno antes).
  suites=$(grep -ho '<testsuite [^>]*' "$SANDBOX_REPORTS"/*.xml 2>/dev/null \
           | grep -o ' time="[0-9.]*"' | grep -o '[0-9.]*' \
           | awk '{ s += $1; n++ } END { if (n) printf "%.0f", s * 1000; else printf "null" }')
  [ -n "$suites" ] || suites=null

  destino="$SANDBOX_REPORTS/tiempos.json"
  # El codigo del alumno corrio con nuestro mismo uid y pudo dejar un enlace con
  # este nombre para que la escritura vaya a otro lado. Se borra y se crea nuevo.
  rm -f "$destino"
  printf '{"schema":"g5.tiempos/v1","fuenteCpu":"%s","presupuesto":{"compilacionS":%s,"cpuPruebasS":%s,"pruebasS":%s},"fases":[%s],"junitSuitesMs":%s}\n' \
    "$FUENTE_CPU" "$TIMEOUT_COMPILE_S" "$CPU_TESTS_S" "$TIMEOUT_TESTS_S" "$TIEMPOS" "$suites" \
    >"$destino"
}

# --------------------------------------------------------------------------
# Canal rico y fragil: fase + detalle en $SANDBOX_STATUS (igual que el perfil).
# --------------------------------------------------------------------------
avisar() {
  printf '%s' "$1" >"$SANDBOX_STATUS/fase"
  printf '%s' "$2" >"$SANDBOX_STATUS/detalle"
}

# $1 = fase, $2 = detalle, $3 = codigo de la banda 40-59
rendirse() {
  avisar "$1" "$2"
  nota "$1" "$2" "$3"
  escribir_tiempos                                   # [TIEMPOS]
  exit "$3"
}

nota() {
  printf '{"schema":"g5.nota/v1","fase":"%s","detalle":"%s","codigo":%s}\n' \
    "$1" "$2" "$3" >"$SANDBOX_REPORTS/nota.json"
}

# --------------------------------------------------------------------------
# Fase 1 — compilar la SOLUCION sola. No se le cobra al alumno.
# --------------------------------------------------------------------------
avisar "COMPILACION" "compilando la solucion"

find "$SANDBOX_IN/src" -name '*.java' >"$SANDBOX_TMP/fuentes-sol.txt" 2>/dev/null
if [ ! -s "$SANDBOX_TMP/fuentes-sol.txt" ]; then
  rendirse "COMPILACION" "el bundle no trae fuentes en src/" 40
fi

fase_inicio                                          # [TIEMPOS]
timeout --signal=KILL "${TIMEOUT_COMPILE_S}s" \
  javac -encoding UTF-8 -d "$DIR_SOL" "@$SANDBOX_TMP/fuentes-sol.txt"
rc=$?
fase_fin "COMPILACION" false "$rc"                   # [TIEMPOS]
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  rendirse "COMPILACION" "javac de la solucion supero el reloj de plataforma" 42
elif [ $rc -ne 0 ]; then
  rendirse "COMPILACION" "no compila la solucion del alumno" 40
fi

# --------------------------------------------------------------------------
# Fase 2 — compilar los TESTS, a un directorio SEPARADO. No se le cobra al alumno.
# --------------------------------------------------------------------------
avisar "COMPILACION_SUITE" "compilando las pruebas"

find "$SANDBOX_IN/test" -name '*.java' >"$SANDBOX_TMP/fuentes-test.txt" 2>/dev/null
if [ ! -s "$SANDBOX_TMP/fuentes-test.txt" ]; then
  rendirse "COMPILACION_SUITE" "el bundle no trae ningun archivo de test" 41
fi

fase_inicio                                          # [TIEMPOS]
timeout --signal=KILL "${TIMEOUT_COMPILE_S}s" \
  javac -encoding UTF-8 -cp "$JUNIT_JAR:$DIR_SOL" -d "$DIR_TEST" "@$SANDBOX_TMP/fuentes-test.txt"
rc=$?
fase_fin "COMPILACION_SUITE" false "$rc"             # [TIEMPOS]
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  rendirse "COMPILACION_SUITE" "javac de las pruebas supero el reloj de plataforma" 42
elif [ $rc -ne 0 ]; then
  rendirse "COMPILACION_SUITE" "no compila la suite de pruebas" 41
fi

# --------------------------------------------------------------------------
# Fase 3 — nombres de clase SIN escanear el classpath.
# --------------------------------------------------------------------------
( cd "$DIR_TEST" && find . -name '*.class' ! -name '*$*' ) \
  | sed -e 's#^\./##' -e 's#/#.#g' -e 's#\.class$##' | sort \
  >"$SANDBOX_TMP/clases.txt"

if [ ! -s "$SANDBOX_TMP/clases.txt" ]; then
  rendirse "COMPILACION_SUITE" "la suite compilo pero no produjo ninguna clase" 41
fi

set --
while IFS= read -r clase; do
  [ -n "$clase" ] || continue
  set -- "$@" "--select-class=$clase"
done <"$SANDBOX_TMP/clases.txt"

# --------------------------------------------------------------------------
# Fase 4 — correr las pruebas. Esta SI se le cobra al alumno.
# --------------------------------------------------------------------------
avisar "PRUEBAS" "ejecutando la suite"

fase_inicio                                          # [TIEMPOS]
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
fase_fin "PRUEBAS" true "$rc_java"                   # [TIEMPOS]

case "$rc_java" in
  3)   rendirse "PRUEBAS" "la JVM se quedo sin memoria (ExitOnOutOfMemoryError)" 43 ;;
  124) rendirse "PRUEBAS" "backstop de pared agotado: la suite no termina" 45 ;;
  152 | 137) rendirse "PRUEBAS" "se agoto el reloj de CPU del alumno (${CPU_TESTS_S}s)" 44 ;;
esac

# --------------------------------------------------------------------------
# Fase 5 — la guarda que frena el bypass del System.exit(0).
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
# Fin. `0` significa "la evaluacion corrio y dejo evidencia", NO "aprobo".
# --------------------------------------------------------------------------
avisar "FIN" "la suite corrio: $tests tests"
printf '{"schema":"g5.nota/v1","fase":"FIN","detalle":"la suite corrio","codigo":0,"tests":%s,"exitJava":%s}\n' \
  "$tests" "$rc_java" >"$SANDBOX_REPORTS/nota.json"
escribir_tiempos                                     # [TIEMPOS]
exit 0
