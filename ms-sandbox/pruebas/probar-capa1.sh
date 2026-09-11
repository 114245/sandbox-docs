#!/usr/bin/env bash
#
# probar-capa1.sh — hace a mano lo que va a hacer el ejecutor Java.
#
# Es el equivalente de run.sh para el modelo de dos capas: arma el stdin de
# TRES documentos, levanta el contenedor y muestra el sobre.
#
#   ./probar-capa1.sh bundles/ok-suma
#   ./probar-capa1.sh bundles/hostil-cpu ../perfiles/java21-junit.sh
#
# El punto de este script es que el framing lo arma alguien de afuera del
# contenedor, igual que lo hara Ejecucion.java. Si esto anda, portarlo a Java
# es mecanico.
set -uo pipefail

cd "$(dirname "$0")"

BUNDLE_DIR="${1:-}"
PERFIL="${2:-../perfiles/java21-junit.sh}"

if [ -z "$BUNDLE_DIR" ] || [ ! -d "$BUNDLE_DIR" ]; then
  echo "uso: $0 <directorio-del-bundle> [script-del-perfil]" >&2
  exit 64
fi
[ -f "$PERFIL" ] || { echo "no existe el perfil: $PERFIL" >&2; exit 64; }

IMAGEN="${IMAGEN:-sandbox-runner:2.0.0-capa1}"
MEMORIA_MB="${MEMORIA_MB:-512}"
CPUS="${CPUS:-1}"
CPU_ULIMIT_S="${CPU_ULIMIT_S:-20}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export MSYS_NO_PATHCONV=1   # Git Bash en Windows: no traducir rutas de docker

# --- el tar del bundle -----------------------------------------------------
tar -cf "$TMP/bundle.tar" -C "$BUNDLE_DIR" src test 2>/dev/null || {
  echo "el bundle debe tener src/ y test/" >&2; exit 64; }

# --- el stdin de tres documentos -------------------------------------------
#
#   <nonce>\n <n>\n <script de n bytes> <tar>
#
# `n` se cuenta en BYTES, no en caracteres: un acento en un comentario del
# script mueve el numero. En Java es script.getBytes(UTF_8).length.
NONCE=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
N=$(wc -c < "$PERFIL")

{
  printf '%s\n' "$NONCE"
  printf '%s\n' "$N"
  cat "$PERFIL"
  cat "$TMP/bundle.tar"
} > "$TMP/entrada.bin"

# --- el contenedor, con la spec constante de 08 §4.1 -----------------------
CID=$(docker create -i \
  --network none \
  --read-only \
  --user 1000:1000 \
  --memory "${MEMORIA_MB}m" --memory-swap "${MEMORIA_MB}m" \
  --cpus "$CPUS" \
  --pids-limit 128 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --ulimit "cpu=${CPU_ULIMIT_S}:${CPU_ULIMIT_S}" \
  --ulimit nofile=256:256 \
  --ulimit nproc=128:128 \
  --ulimit fsize=33554432:33554432 \
  --tmpfs /work:rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000 \
  --label sandbox=1 \
  "$IMAGEN") || { echo "no se pudo crear el contenedor" >&2; exit 70; }

BACKSTOP_S=60   # el reloj de pared de 08 §4.3, la red de ultima instancia

t0=$(date +%s%N)
timeout --signal=KILL "${BACKSTOP_S}s" \
  docker start -ai "$CID" <"$TMP/entrada.bin" >"$TMP/salida" 2>"$TMP/errores"
RC_WORKER=$?
ms_total=$(( ($(date +%s%N) - t0) / 1000000 ))

EXIT_CONTENEDOR=$(docker inspect "$CID" --format '{{.State.ExitCode}}' 2>/dev/null || echo "?")
OOM_KILLED=$(docker inspect "$CID" --format '{{.State.OOMKilled}}' 2>/dev/null || echo "?")
docker rm -f "$CID" >/dev/null 2>&1

# --- lo que el ejecutor Java normalizaria ----------------------------------
echo "=== ejecutor ==="
echo "  bundle           : $BUNDLE_DIR"
echo "  perfil           : $PERFIL  ($N bytes)"
echo "  reloj de pared   : ${ms_total} ms   (backstop ${BACKSTOP_S}s)"
echo "  exit contenedor  : $EXIT_CONTENEDOR"
echo "  OOMKilled        : $OOM_KILLED"
if [ $RC_WORKER -eq 137 ] || [ $RC_WORKER -eq 124 ]; then
  echo "  !! salto el backstop del ejecutor -> resultado TIMEOUT"
fi
[ -s "$TMP/errores" ] && { echo "--- stderr de docker ---"; cat "$TMP/errores"; }

echo "=== sobre de la capa 1 ==="
MARCA_INI="---SANDBOX-${NONCE}-INICIO---"
MARCA_FIN="---SANDBOX-${NONCE}-FIN---"
if ! grep -qF -- "$MARCA_INI" "$TMP/salida"; then
  echo "  !! no vino el sobre. Sin sobre no hay veredicto posible."
  echo "--- salida cruda ---"
  head -c 4000 "$TMP/salida"
  exit 75
fi

SOBRE=$(sed -n "/^${MARCA_INI}\$/,/^${MARCA_FIN}\$/p" "$TMP/salida" | sed '1d;$d')

if command -v jq >/dev/null 2>&1; then
  echo "$SOBRE" | jq '{resultado, detalle, exitEval, procesosSobrevivientes,
                       faseDeclarada, detalleDeclarado, recursos, truncado}'
  echo "=== buzon (/work/reports) ==="
  REPORTES=$(echo "$SOBRE" | jq -r '.reportesTarGzB64')
  if [ -n "$REPORTES" ] && [ "$REPORTES" != "null" ] && [ "$REPORTES" != "" ]; then
    mkdir -p "$TMP/reports"
    echo "$REPORTES" | base64 -d | tar -xzf - -C "$TMP/reports"
    ls -1 "$TMP/reports"
    [ -f "$TMP/reports/nota.json" ] && { echo "--- nota.json ---"; cat "$TMP/reports/nota.json"; }
    grep -ho 'tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"' \
      "$TMP/reports"/*.xml 2>/dev/null | head -10
  else
    echo "  (buzon vacio)"
  fi
  echo "=== stdout de adentro ==="
  echo "$SOBRE" | jq -r '.stdoutB64' | base64 -d | head -c 1500
else
  echo "$SOBRE"
fi
