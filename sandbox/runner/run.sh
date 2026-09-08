#!/usr/bin/env bash
#
# run.sh — lo que después va a hacer el worker, a mano.
#
# Es la traducción ejecutable de docs/arquitectura/03-ms-sandbox-ejecucion.md
# §1.3, pasos 3 a 11. Sirve para probar la imagen sin tener nada de Spring.
#
#   ./run.sh bundles/ok-suma
#
# Variables de entorno (los tres relojes, §1.3 paso 4):
#   CPU_TESTS_S          reloj de CPU del ALUMNO           (default 10)
#   TIMEOUT_COMPILE_MS   reloj de pared, plataforma        (default 20000)
#   TIMEOUT_TESTS_MS     backstop de pared                 (default 30000)
#   MEMORIA_MB           --memory del contenedor           (default 512)
#   CPUS                 --cpus del contenedor             (default 2)
set -uo pipefail

cd "$(dirname "$0")"

BUNDLE_DIR="${1:-}"
if [ -z "$BUNDLE_DIR" ] || [ ! -d "$BUNDLE_DIR" ]; then
  echo "uso: $0 <directorio-del-bundle>   (debe contener src/ y test/)" >&2
  exit 64
fi

IMAGEN="${IMAGEN:-sandbox-runner:1.0.0}"
CPU_TESTS_S="${CPU_TESTS_S:-10}"
TIMEOUT_COMPILE_MS="${TIMEOUT_COMPILE_MS:-20000}"
TIMEOUT_TESTS_MS="${TIMEOUT_TESTS_MS:-30000}"
MEMORIA_MB="${MEMORIA_MB:-512}"
CPUS="${CPUS:-2}"

JOB_ID="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

export MSYS_NO_PATHCONV=1   # Git Bash en Windows: no traducir rutas de docker

# --- paso 3: armar el bundle como tar -------------------------------------
# Se arma con rutas relativas src/... y test/..., que es lo que valida el
# entrypoint antes de extraer.
tar -cf "$TMP/bundle.tar" -C "$BUNDLE_DIR" src test 2>/dev/null || {
  echo "el bundle debe tener src/ y test/" >&2; exit 64; }

# --- paso 4: crear el contenedor ------------------------------------------
# El worker NO monta nada del host. Todos estos flags son la frontera de
# seguridad: son la única que existe (§1.6d/§1.6e).
CID=$(docker create -i \
  --network none \
  --read-only \
  --user 1000:1000 \
  --memory "${MEMORIA_MB}m" --memory-swap "${MEMORIA_MB}m" \
  --cpus "$CPUS" \
  --pids-limit 64 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --tmpfs /work:rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000 \
  --label "sandbox.job=$JOB_ID" \
  -e "TIMEOUT_COMPILE_MS=$TIMEOUT_COMPILE_MS" \
  -e "CPU_TESTS_S=$CPU_TESTS_S" \
  -e "TIMEOUT_TESTS_MS=$TIMEOUT_TESTS_MS" \
  "$IMAGEN") || { echo "no se pudo crear el contenedor" >&2; exit 70; }

# --- pasos 5..7: arrancar, mandar el tar por stdin, leer el sobre ---------
# El reloj de pared del worker es la RED DE ÚLTIMA INSTANCIA: si salta, el que
# falló es el reloj de adentro, y el veredicto es ERROR_INTERNO, no TIMEOUT.
BACKSTOP_S=$(( (TIMEOUT_COMPILE_MS * 2 + TIMEOUT_TESTS_MS) / 1000 + 15 ))

# El nonce va como PRIMERA LINEA de stdin, pegado al tar (08 §7, R7.2).
# Es lo que hace que el marcador del reporte sea infalsificable: el codigo
# del alumno no lo puede leer ni adivinar.
NONCE=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d " \n")
{ printf "%s\n" "$NONCE"; cat "$TMP/bundle.tar"; } >"$TMP/entrada.bin"

t0=$(date +%s%3N)
timeout --signal=KILL "${BACKSTOP_S}s" \
  docker start -ai "$CID" <"$TMP/entrada.bin" >"$TMP/salida" 2>"$TMP/errores"
RC_WORKER=$?
ms_total=$(( $(date +%s%3N) - t0 ))

# --- paso 9: docker inspect ------------------------------------------------
EXIT_CONTENEDOR=$(docker inspect "$CID" --format '{{.State.ExitCode}}' 2>/dev/null || echo "?")
OOM_KILLED=$(docker inspect "$CID" --format '{{.State.OOMKilled}}' 2>/dev/null || echo "?")

# --- paso 10: destruir -----------------------------------------------------
docker rm -f "$CID" >/dev/null 2>&1

# --- paso 11: mostrar lo que el worker normalizaría ------------------------
echo "=== worker ==="
echo "  job              : $JOB_ID"
echo "  reloj de pared   : ${ms_total} ms   (backstop ${BACKSTOP_S}s)"
echo "  exit contenedor  : $EXIT_CONTENEDOR"
echo "  OOMKilled        : $OOM_KILLED"
if [ $RC_WORKER -eq 137 ] || [ $RC_WORKER -eq 124 ]; then
  echo "  !! saltó el backstop del worker → ERROR_INTERNO (falló el reloj de adentro)"
fi
[ -s "$TMP/errores" ] && { echo "--- stderr de docker ---"; cat "$TMP/errores"; }

echo "=== sobre del runner ==="
MARCA_INI="---SANDBOX-${NONCE}-INICIO---"
MARCA_FIN="---SANDBOX-${NONCE}-FIN---"
if ! grep -qF -- "$MARCA_INI" "$TMP/salida"; then
  echo "  !! no vino el sobre. Sin sobre no hay veredicto posible (§1.6c)."
  echo "--- salida cruda ---"
  head -c 4000 "$TMP/salida"
  exit 75
fi

SOBRE=$(sed -n "/^${MARCA_INI}$/,/^${MARCA_FIN}$/p" "$TMP/salida" |
        sed '1d;$d')

if command -v jq >/dev/null 2>&1; then
  echo "$SOBRE" | jq '{fase, resultado, detalle, exitCodeJava, clasesTest, recursos, truncado}'
  echo "=== reporte de JUnit ==="
  REPORTES=$(echo "$SOBRE" | jq -r '.reportesTarGzB64')
  if [ -n "$REPORTES" ] && [ "$REPORTES" != "null" ]; then
    mkdir -p "$TMP/reports"
    echo "$REPORTES" | base64 -d | tar -xzf - -C "$TMP/reports"
    ls -1 "$TMP/reports"
    grep -ho 'tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' \
      "$TMP/reports"/*.xml 2>/dev/null | head -20
  else
    echo "  (sin reporte)"
  fi
  echo "=== stdout del alumno ==="
  echo "$SOBRE" | jq -r '.stdoutB64' | base64 -d | head -c 2000
else
  echo "$SOBRE"
  echo "(instalá jq para ver esto formateado)"
fi
