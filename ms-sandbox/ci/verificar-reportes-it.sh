#!/bin/sh
#
# verificar-reportes-it.sh -- F-16 (spec 2026-09-14-f16-perfil-y-ci.md, 4.4).
#
# BundlesIT y ProtocoloIT se saltean solos (assumptions) si no hay daemon de
# Docker, y Surefire cuenta eso como skipped, no como fallo. En CI eso dejaria
# la suite adversaria sin correr y el job en verde. Este chequeo lo impide:
#   1. tienen que existir los reportes de BundlesIT y ProtocoloIT, con tests > 0;
#   2. ningun reporte del modulo puede tener skipped distinto de 0.
#
# Vive en la CI y no en los tests para que en local se pueda seguir corriendo
# `mvn verify` sin Docker.
#
# Uso: sh verificar-reportes-it.sh <directorio de surefire-reports>

set -eu

dir=${1:?falta el directorio de reportes}
fallo=0

atributo() {
  # $1 = atributo, $2 = archivo. Lee el atributo del elemento <testsuite>.
  sed -n "s/.*<testsuite[^>]* $1=\"\([0-9]*\)\".*/\1/p" "$2" | head -n 1
}

for it in BundlesIT ProtocoloIT; do
  reporte="$dir/TEST-sandbox.ejecutor.$it.xml"
  if [ ! -f "$reporte" ]; then
    echo "FALLA: no hay reporte de $it ($reporte)"
    fallo=1
    continue
  fi
  tests=$(atributo tests "$reporte")
  if [ -z "$tests" ] || [ "$tests" -eq 0 ]; then
    echo "FALLA: $it no corrio ningun test"
    fallo=1
  fi
done

hay_reportes=0
for reporte in "$dir"/TEST-*.xml; do
  [ -f "$reporte" ] || continue
  hay_reportes=1
  skipped=$(atributo skipped "$reporte")
  if [ "${skipped:-desconocido}" != "0" ]; then
    echo "FALLA: skipped=${skipped:-desconocido} en $reporte"
    grep -o '<skipped[^>]*>' "$reporte" | head -n 5 || true
    fallo=1
  fi
done

if [ "$hay_reportes" -eq 0 ]; then
  echo "FALLA: no hay reportes TEST-*.xml en $dir"
  fallo=1
fi

if [ "$fallo" -eq 0 ]; then
  echo "OK: BundlesIT y ProtocoloIT corrieron contra Docker y no hubo tests salteados"
fi
exit "$fallo"
