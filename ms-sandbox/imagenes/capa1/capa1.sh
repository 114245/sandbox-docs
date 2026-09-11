#!/bin/sh
#
# capa1.sh — la capa 1 del sandwich (G8). Es el ENTRYPOINT del contenedor.
#
# Reemplaza a entrypoint.sh bajo la Opcion 1 del V4: aca ya NO se compila ni se
# corre nada de JUnit. Todo eso es capa 2 y vive en el script del perfil, que
# entra por stdin. Ver otros/Respuesta_G8_a_Propuesta_V4.md seccion 2.4.
#
# ENTRA por STDIN, en este orden y sin separadores:
#     <nonce>\n              32 hex. NO se exporta jamas (08 R7.3)
#     <n>\n                  cuantos bytes mide el script de la capa 2
#     <script de n bytes>    el run.sh del perfil, opaco
#     <tar>                  el bundle, "lo que quede del stream"
#
#   El largo va ADELANTE y no hay marca de fin porque el script lo escribe G5 y
#   puede contener cualquier linea, incluida la que elijamos como separador. Y
#   usar el nonce como delimitador esta prohibido: se lo mostraria a la capa 2.
#   Es el mismo razonamiento que Content-Length en HTTP.
#
# SALE por STDOUT: un sobre JSON entre las marcas con el nonce (08 R7.4).
#
# POSIX sh a proposito: el entrypoint viejo declaraba #!/bin/bash pero el `sh`
# de la imagen es dash, y usaba arrays y mapfile. Sin la logica de evaluacion
# adentro, la capa 1 no necesita nada de bash.

set -u

# --------------------------------------------------------------------------
# Constantes y rutas
# --------------------------------------------------------------------------
NONCE=""
MARCA_INI="---SANDBOX-${NONCE}-INICIO---"
MARCA_FIN="---SANDBOX-${NONCE}-FIN---"

RAIZ=/work
DIR_IN=$RAIZ/in
DIR_REPORTES=$RAIZ/reports
DIR_TMP=$RAIZ/tmp
DIR_STATUS=$RAIZ/status

BUNDLE_TAR=$DIR_TMP/bundle.tar
SCRIPT_BIN=$DIR_TMP/eval.in
RUTAS=$DIR_TMP/rutas
FASE_OUT=$DIR_TMP/fase.out
FASE_ERR=$DIR_TMP/fase.err

# Vienen de la imagen (ENV del Dockerfile), no del request: el contenedor se
# crea con `Env: []` (08 seccion 4.1) y eso es la invariante P1.
SALIDA_LIMITE_BYTES="${SALIDA_LIMITE_BYTES:-65536}"
EVAL_TIMEOUT_S="${SANDBOX_EVAL_TIMEOUT_S:-45}"
MAX_SCRIPT_BYTES="${SANDBOX_MAX_SCRIPT_BYTES:-262144}"
LIBS="${SANDBOX_LIBS:-/libs}"

resultado="ERROR_INTERNO"
detalle=""
exit_eval="null"
procesos_sobrevivientes=0
ms_eval=0
cpu_eval_ms=0
fase_declarada=""
detalle_declarado=""

mkdir -p "$DIR_IN" "$DIR_REPORTES" "$DIR_TMP" "$DIR_STATUS" 2>/dev/null
: >"$FASE_OUT"
: >"$FASE_ERR"

# El stdout real se guarda en fd 3. El unico que escribe ahi es `emitir`.
#
# Esto es lo que hace que el JSON de la nota de G5 NO pueda salir por stdout
# (Respuesta_V4 seccion 2.2): para cuando corre la capa 2, su stdout ya es un
# archivo. Las marcas con el nonce impiden falsificar el reporte, pero si
# stdout quedara vivo no impedirian contaminarlo.
exec 3>&1
exec 1>>"$FASE_OUT"
exec 2>>"$FASE_ERR"

# --------------------------------------------------------------------------
# Utilidades
# --------------------------------------------------------------------------
ahora_ms() { echo $(( $(date +%s%N) / 1000000 )); }

# La salida se trunca ADENTRO del contenedor: un alumno que escupe gigabytes no
# debe poder inflar la RAM del worker.
b64_truncado() {
  head -c "$SALIDA_LIMITE_BYTES" "$1" 2>/dev/null | base64 -w0 2>/dev/null || printf ''
}

# Un campo JSON de texto libre, escapado. Los archivos de $DIR_STATUS los
# escribe la capa 2, o sea codigo no confiable: no pueden romper el sobre.
json_str() {
  printf '%s' "$1" | head -c 512 \
    | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
    | tr -d '\000-\010\013\014\016-\037' \
    | tr '\n\t\r' '   '
}

emitir() {
  reportes_b64=""
  truncado=false

  # El buzon se empaqueta ENTERO y sin mirarlo (Respuesta_V4 seccion 2.2): no
  # sabemos si adentro hay un XML de JUnit, uno de PMD o tres archivos.
  if [ -d "$DIR_REPORTES" ] && [ -n "$(ls -A "$DIR_REPORTES" 2>/dev/null)" ]; then
    reportes_b64=$(tar -czf - -C "$DIR_REPORTES" . 2>/dev/null | base64 -w0)
  fi

  n_out=$(wc -c <"$FASE_OUT" 2>/dev/null || echo 0)
  n_err=$(wc -c <"$FASE_ERR" 2>/dev/null || echo 0)
  if [ "$n_out" -gt "$SALIDA_LIMITE_BYTES" ] || [ "$n_err" -gt "$SALIDA_LIMITE_BYTES" ]; then
    truncado=true
  fi

  {
    printf '%s\n' "$MARCA_INI"
    printf '{'
    printf '"schema":"sandbox.capa1/v2",'
    printf '"resultado":"%s",' "$resultado"
    printf '"detalle":"%s",' "$(json_str "$detalle")"
    printf '"exitEval":%s,' "$exit_eval"
    printf '"procesosSobrevivientes":%s,' "$procesos_sobrevivientes"
    printf '"faseDeclarada":"%s",' "$(json_str "$fase_declarada")"
    printf '"detalleDeclarado":"%s",' "$(json_str "$detalle_declarado")"
    printf '"recursos":{"msEval":%s,"cpuEvalMs":%s},' "$ms_eval" "$cpu_eval_ms"
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
# Paso 1 — el nonce (08 R7.2, R7.3)
#
# `read` no puede consumir mas alla del \n: dash lee de a un byte sobre
# descriptores no posicionables. Es lo que deja el resto del stream intacto.
# NONCE es variable de shell y NO se exporta: exportarla la dejaria en
# /proc/1/environ, legible por el alumno, y el marcador dejaria de ser
# infalsificable -- que es el unico motivo por el que existe.
# --------------------------------------------------------------------------
if ! IFS= read -r NONCE; then
  morir "BUNDLE_INVALIDO" "no vino la linea del nonce en stdin" 22
fi
case "$NONCE" in
  *[!0-9a-f]*) morir "BUNDLE_INVALIDO" "nonce con formato invalido" 22 ;;
esac
[ "${#NONCE}" -eq 32 ] || morir "BUNDLE_INVALIDO" "nonce de largo distinto de 32" 22

MARCA_INI="---SANDBOX-${NONCE}-INICIO---"
MARCA_FIN="---SANDBOX-${NONCE}-FIN---"

# --------------------------------------------------------------------------
# Paso 2 — el script de la capa 2, por largo declarado
#
# `dd iflag=fullblock` es el UNICO lector verificado que no se pasa de largo en
# los tres shells. `head -c` NO sirve: busybox lee un bloque de 1024 y descarta
# el sobrante, que sobre un pipe no se puede devolver -- se come la cabecera
# del tar. Medido: script de 314 B, tar de 10240 B llegando como 9530 B.
# --------------------------------------------------------------------------
if ! IFS= read -r N_SCRIPT; then
  morir "BUNDLE_INVALIDO" "no vino la linea del largo del script" 22
fi
case "$N_SCRIPT" in
  '' | *[!0-9]*) morir "BUNDLE_INVALIDO" "largo de script no numerico" 22 ;;
esac
[ "$N_SCRIPT" -gt 0 ] || morir "BUNDLE_INVALIDO" "largo de script en cero" 22
[ "$N_SCRIPT" -le "$MAX_SCRIPT_BYTES" ] || \
  morir "BUNDLE_INVALIDO" "script de $N_SCRIPT bytes supera el tope" 22

dd iflag=fullblock bs="$N_SCRIPT" count=1 of="$SCRIPT_BIN" 2>/dev/null

leidos=$(wc -c <"$SCRIPT_BIN" 2>/dev/null || echo 0)
# Si stdin se corto a la mitad (el half-close de 08 R8.5) llegan menos bytes.
[ "$leidos" -eq "$N_SCRIPT" ] || \
  morir "BUNDLE_INVALIDO" "el script llego incompleto: $leidos de $N_SCRIPT bytes" 22

SCRIPT=$(cat "$SCRIPT_BIN")
rm -f "$SCRIPT_BIN"

# --------------------------------------------------------------------------
# Paso 3 — el tar: validar ANTES de extraer
#
# Es entrada hostil y nos llega desde la API de G5. Doble validacion a
# proposito (Respuesta_V4 seccion 2.4 paso 3).
#
# NUEVO respecto del entrypoint viejo: se rechazan enlaces simbolicos y duros.
# Es la familia de bug que tumbo a Judge0 tres veces y era el hueco declarado
# de la suite. Un symlink que apunte afuera de /work convierte la extraccion en
# escritura arbitraria.
#
# TAMBIEN NUEVO: ya NO se exige que todo cuelgue de src/ y test/. Bajo la
# Opcion 1 el `files[]` de G5 puede traer cualquier ruta; que exista `src/` es
# conocimiento de la capa 2, no nuestro. La capa 1 solo exige que las rutas
# sean relativas, sin `..` y sin enlaces.
# --------------------------------------------------------------------------
cat >"$BUNDLE_TAR"
[ -s "$BUNDLE_TAR" ] || morir "BUNDLE_INVALIDO" "bundle vacio en stdin" 22

if ! tar -tvf "$BUNDLE_TAR" >"$RUTAS" 2>/dev/null; then
  morir "BUNDLE_INVALIDO" "el tar no se puede leer" 22
fi

while IFS= read -r linea; do
  [ -n "$linea" ] || continue
  case "$linea" in
    l*) morir "BUNDLE_INVALIDO" "el tar trae un enlace simbolico" 22 ;;
    h*) morir "BUNDLE_INVALIDO" "el tar trae un enlace duro" 22 ;;
  esac
  # `tar -tv` imprime la ruta al final. Para un enlace seria "a -> b", pero
  # esos ya salieron por el case de arriba.
  ruta=${linea##* }
  case "$ruta" in
    /*)   morir "BUNDLE_INVALIDO" "ruta absoluta en el tar" 22 ;;
    *..*) morir "BUNDLE_INVALIDO" "ruta con .. en el tar" 22 ;;
  esac
done <"$RUTAS"

# --no-same-owner / --no-same-permissions: el tar no dicta uid ni modo.
if ! tar -xf "$BUNDLE_TAR" -C "$DIR_IN" \
        --no-same-owner --no-same-permissions --no-overwrite-dir 2>/dev/null; then
  morir "BUNDLE_INVALIDO" "fallo la extraccion del tar" 22
fi
rm -f "$BUNDLE_TAR"

# --------------------------------------------------------------------------
# Paso 4 — el contrato de directorios (Respuesta_V4 seccion 2.1)
#
# Estas variables SI se exportan, y esta bien: no son secretas. Saber que los
# reportes van a /work/reports no le sirve de nada a quien quiere hacer trampa.
# Esa asimetria es justamente por lo que el nonce viaja por otro lado.
# --------------------------------------------------------------------------
export SANDBOX_IN="$DIR_IN"
export SANDBOX_REPORTS="$DIR_REPORTES"
export SANDBOX_TMP="$DIR_TMP"
export SANDBOX_STATUS="$DIR_STATUS"
export SANDBOX_LIBS="$LIBS"

mem_bytes=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || echo max)
case "$mem_bytes" in
  '' | max | *[!0-9]*) SANDBOX_MEM_MB=512 ;;
  *)                   SANDBOX_MEM_MB=$(( mem_bytes / 1048576 )) ;;
esac
export SANDBOX_MEM_MB

# --------------------------------------------------------------------------
# Paso 5 — invocar la capa 2
#
# `sh -c "$SCRIPT"` y no `sh archivo`: /work es tmpfs con uid=1000, la capa 1
# corre como 1000 igual que el alumno, y CapDrop:ALL impide arrancar como root
# y bajar privilegios. O sea que cualquier archivo que escribamos, el alumno lo
# puede reescribir -- y `sh archivo` lee el script de a pedazos MIENTRAS corre,
# asi que reescribirlo a mitad de camino es un ataque real. Con -c el
# interprete lo parsea de memoria y no hay archivo que pisar.
#
# El texto queda visible en /proc/1/cmdline, y no importa: el script de G5 es
# capa 2, tratada como no confiable igual que el codigo del alumno
# (Respuesta_V4 seccion 4.7). Lo que NO cruza es el nonce: es variable no
# exportada, asi que el hijo no la hereda.
#
# </dev/null explicito: para aca stdin quedo en EOF, pero no queremos que un
# `read` del script de G5 toque el descriptor por el que viajo el nonce.
#
# El `timeout` es un backstop de la capa 1, no el reloj de la evaluacion: los
# relojes por fase son de la capa 2. Manda TERM y a los 5 s KILL, asi el 124
# nos queda como senal inequivoca de "salto NUESTRO backstop" y el 137 sigue
# significando SIGKILL de otro lado (el OOM del cgroup).
# --------------------------------------------------------------------------
t0=$(ahora_ms)
(
  cd "$DIR_IN" || exit 23
  timeout -k 5s "${EVAL_TIMEOUT_S}s" sh -c "$SCRIPT" </dev/null
  echo "$?" >"$DIR_TMP/rc"
  times >"$DIR_TMP/times" 2>/dev/null
)
ms_eval=$(( $(ahora_ms) - t0 ))

rc=$(cat "$DIR_TMP/rc" 2>/dev/null)
[ -n "$rc" ] || rc=137
exit_eval="$rc"

# `times` (builtin POSIX) linea 2 = user y sys de los HIJOS, formato 0m1.234s.
cpu_eval_ms=$(sed -n '2p' "$DIR_TMP/times" 2>/dev/null | awk '
  { t = 0
    for (i = 1; i <= NF; i++) { split($i, p, "m"); sub(/s$/, "", p[2]); t += p[1] * 60 + p[2] }
    printf "%d", t * 1000 }')
[ -n "$cpu_eval_ms" ] || cpu_eval_ms=0

# --------------------------------------------------------------------------
# Paso 6 — barrer y CONTAR los procesos que sobrevivieron (Respuesta_V4 2.4/7)
#
# ESTO NO ES PREVENCION Y NO PUEDE SERLO. El vector: el codigo del alumno deja
# un proceso en segundo plano que sobrevive a la capa 2 y reescribe el reporte
# con su propio veredicto. El buzon esta en una carpeta que el alumno escribe;
# no hay flag de Docker que lo saque de ahi. Lo que si se puede es DETECTAR:
# para reescribir el reporte despues, el atacante necesita un proceso vivo.
#
# La barrida es por /proc y no con `pkill -x java`: bajo la Opcion 1 la capa 1
# ni siquiera sabe que la capa 2 corrio una JVM.
# --------------------------------------------------------------------------
for entrada in /proc/[0-9]*; do
  pid=${entrada#/proc/}
  [ "$pid" = "1" ] && continue
  [ "$pid" = "$$" ] && continue
  procesos_sobrevivientes=$(( procesos_sobrevivientes + 1 ))
  kill -9 "$pid" 2>/dev/null
done

# --------------------------------------------------------------------------
# Paso 7 — los avisos opcionales de la capa 2 (Respuesta_V4 seccion 2.3)
#
# El archivo es el canal rico pero fragil; el numero, el pobre pero
# indestructible. Si la capa 2 se murio de golpe no alcanzo a escribir nada.
# --------------------------------------------------------------------------
fase_declarada=$(head -c 128 "$DIR_STATUS/fase" 2>/dev/null || printf '')
detalle_declarado=$(head -c 512 "$DIR_STATUS/detalle" 2>/dev/null || printf '')

# --------------------------------------------------------------------------
# Paso 8 — clasificar y emitir
#
# Bandas de Respuesta_V4 seccion 2.3:
#   0        la capa 2 llego al final
#   40-59    la capa 2 se freno a proposito; la tabla la define G5
#   20-31    nuestros
#   32-39    hueco a proposito
#   otro     error de infraestructura
#
# EL NUMERO NO ES EL VEREDICTO. El veredicto sale del reporte, siempre.
# --------------------------------------------------------------------------
codigo_de() {
  case "$1" in
    OK)                      echo 0  ;;
    BUNDLE_INVALIDO)         echo 22 ;;
    EVALUACION_ANOMALA)      echo 23 ;;
    TIMEOUT_PARED)           echo 27 ;;
    SIN_REPORTE)             echo 28 ;;
    VEREDICTO_NO_CONFIABLE)  echo 30 ;;
    MUERTO_POR_SENAL)        echo 31 ;;
    *)                       echo 1  ;;
  esac
}

if [ "$rc" -eq 124 ]; then
  resultado="TIMEOUT_PARED"
  detalle="salto el backstop de la capa 1 (${EVAL_TIMEOUT_S}s)"
elif [ "$rc" -eq 137 ]; then
  resultado="MUERTO_POR_SENAL"
  detalle="SIGKILL a la capa 2 sin que saltara el backstop: desambiguar con OOMKilled"
elif [ "$rc" -ge 40 ] && [ "$rc" -le 59 ]; then
  resultado="DETENIDO_POR_EVALUACION"
  detalle="la capa 2 se freno a proposito con $rc"
elif [ "$rc" -ne 0 ]; then
  resultado="EVALUACION_ANOMALA"
  detalle="la capa 2 salio con $rc, fuera de su banda 40-59"
else
  resultado="OK"
  detalle=""
fi

# Sobrevivio algo: el reporte pudo haber sido reescrito y no hay forma de saber
# si lo fue. No hay exito posible. Es preferible fallar que aprobar de mas, y
# por eso pisa cualquier otra clasificacion.
if [ "$procesos_sobrevivientes" -gt 0 ]; then
  morir "VEREDICTO_NO_CONFIABLE" \
        "sobrevivieron $procesos_sobrevivientes procesos a la capa 2: el reporte pudo ser reescrito" 30
fi

# El corolario de "todo lo que quede en el buzon vuelve": si quedo vacio, no
# hay aprobacion posible. Sin evidencia no hay veredicto (Respuesta_V4 2.2).
# Solo aplica cuando la capa 2 dijo haber terminado bien: un 40-59 es un freno
# deliberado y puede legitimamente no dejar nada.
if [ "$resultado" = "OK" ] && [ -z "$(ls -A "$DIR_REPORTES" 2>/dev/null)" ]; then
  morir "SIN_REPORTE" "la capa 2 salio con 0 pero dejo el buzon vacio" 28
fi

if [ "$resultado" = "DETENIDO_POR_EVALUACION" ]; then
  # El codigo de G5 se propaga TAL CUAL: es su tabla, no la traducimos.
  emitir
  exit "$rc"
fi

if [ "$resultado" != "OK" ]; then
  morir "$resultado" "$detalle" "$(codigo_de "$resultado")"
fi

# `OK` significa "la capa 2 corrio y dejo algo en el buzon", NO "el alumno
# aprobo". El veredicto lo decide el worker leyendo el reporte.
emitir
exit 0
