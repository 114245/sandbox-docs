#!/usr/bin/env bash
# Construye la imagen de ejecución. Único lugar donde hace falta red.
set -euo pipefail

cd "$(dirname "$0")"

IMAGEN="${IMAGEN:-sandbox-runner:1.0.0}"
JUNIT_VERSION="${JUNIT_VERSION:-1.11.3}"

echo ">> construyendo $IMAGEN (junit $JUNIT_VERSION)"
docker build \
  --build-arg "JUNIT_VERSION=$JUNIT_VERSION" \
  -t "$IMAGEN" \
  .

echo ">> listo: $IMAGEN"
docker image inspect "$IMAGEN" --format '   tamaño: {{.Size}} bytes'
