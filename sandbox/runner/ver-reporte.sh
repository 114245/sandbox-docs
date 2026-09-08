#!/usr/bin/env bash
# Corre un bundle y muestra el reporte de JUnit que salió en el sobre.
# Sin jq: extrae reportesTarGzB64 con sed.
#   ./ver-reporte.sh bundles/hostil-reporte
set -uo pipefail
cd "$(dirname "$0")"

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
./run.sh "$1" >"$TMP/salida" 2>&1
RC=$?

sed -n '/^=== worker ===$/,/^=== sobre del runner ===$/p' "$TMP/salida"

SOBRE=$(grep -o '"reportesTarGzB64":"[^"]*"' "$TMP/salida" | sed 's/.*:"//; s/"$//')
if [ -z "$SOBRE" ]; then
  echo "  (sin reporte en el sobre)"
  grep -o '"resultado":"[^"]*"\|"detalle":"[^"]*"' "$TMP/salida"
  exit "$RC"
fi

grep -o '"fase":"[^"]*"\|"resultado":"[^"]*"\|"detalle":"[^"]*"\|"exitCodeJava":[0-9]*\|"testsEnReporte":[0-9]*\|"cpuPruebasMs":[0-9]*\|"tiempoPruebasMs":[0-9]*' "$TMP/salida"

mkdir -p "$TMP/r"
echo "$SOBRE" | base64 -d | tar -xzf - -C "$TMP/r" 2>/dev/null

echo "--- archivos del reporte ---"
ls -1 "$TMP/r"
echo "--- atributos de <testsuite> ---"
grep -ho '<testsuite[^>]*>' "$TMP/r"/*.xml 2>/dev/null |
  grep -o 'name="[^"]*"\|tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"'
