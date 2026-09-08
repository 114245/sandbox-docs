#!/usr/bin/env bash
#
# suite-hostil.sh — la suite de entregas maliciosas.
#
# Pendiente 7 de `00-propuesta-ms-sandbox.md` §10. Es la evidencia para la
# defensa: no "diseñamos un sandbox seguro" sino "acá está el ataque, acá está
# el resultado esperado, y acá está la corrida que lo demuestra".
#
# Cada caso declara qué `resultado` DEBE devolver el runner. Si un ataque deja
# de estar contenido, esto se pone en rojo.
#
#   ./suite-hostil.sh            corre todo
#   ./suite-hostil.sh hostil-cpu corre uno
set -uo pipefail
cd "$(dirname "$0")"

# bundle | resultado esperado | overrides de entorno | qué demuestra
CASOS=(
  "ok-suma|OK||camino feliz: 3 tests, 0 failures"
  "hostil-exit0|SALIDA_ANTICIPADA||System.exit(0) deja un reporte con tests=0; la guarda es tests>0, no la existencia del archivo (§1.6c)"
  "hostil-reporte|VEREDICTO_NO_CONFIABLE||proceso en segundo plano que reescribe el reporte tras un delay (§1.6d medida 3)"
  "hostil-reporte-loop|VEREDICTO_NO_CONFIABLE||el mismo ataque en BUCLE: la prevencion por barrido no puede ganar, la deteccion si"
  "hostil-paquete|OK||el alumno sombrea una clase de soporte del profesor; gana el .class del profesor (§1.6d medida 2, CVE-2024-23682)"
  "hostil-red|OK||intenta abrir un socket; --network none lo impide (§1.2)"
  "hostil-cpu|TIMEOUT_CPU|CPU_TESTS_S=5|quema CPU sin parar; lo frena RLIMIT_CPU/SIGXCPU (§1.4d)"
  "hostil-memoria|LIMITE_MEMORIA||fuga de memoria; ExitOnOutOfMemoryError sale con exit 3 y OOMKilled=false (§1.6b)"
  "hostil-sleep|TIMEOUT_PARED|TIMEOUT_TESTS_MS=8000|duerme sin quemar CPU; solo lo frena el backstop de pared"
)

# Casos donde ademas importa el detalle del reporte, no solo el `resultado`.
# bundle | patron que DEBE aparecer en los <testsuite> | por que
ASERCIONES_REPORTE=(
  "ok-suma|failures=\"0\"|los 3 tests pasan"
  "hostil-paquete|failures=\"3\"|si pasaran, el sombreado funciono y el veredicto es falso"
  "hostil-red|failures=\"0\"|si fallaran, hubo red"
)

filtro="${1:-}"
ok=0; fallados=0; salteados=0
declare -a ROJOS=()

for caso in "${CASOS[@]}"; do
  IFS='|' read -r bundle esperado envs desc <<<"$caso"
  if [ -n "$filtro" ] && [ "$filtro" != "$bundle" ]; then
    salteados=$((salteados + 1)); continue
  fi

  printf '%-22s ' "$bundle"

  salida=$(env $envs ./run.sh "bundles/$bundle" 2>&1)
  obtenido=$(grep -o '"resultado":"[^"]*"' <<<"$salida" | head -1 | sed 's/.*:"//; s/"$//')
  sobrevivientes=$(grep -o '"procesosSobrevivientes":[0-9]*' <<<"$salida" | head -1 | grep -o '[0-9]*$')

  problema=""
  [ "$obtenido" = "$esperado" ] || problema="resultado=$obtenido, se esperaba $esperado"

  # Asercion sobre el reporte, si el caso la tiene.
  for a in "${ASERCIONES_REPORTE[@]}"; do
    IFS='|' read -r b patron porque <<<"$a"
    [ "$b" = "$bundle" ] || continue
    b64=$(grep -o '"reportesTarGzB64":"[^"]*"' <<<"$salida" | sed 's/.*:"//; s/"$//')
    if [ -z "$b64" ]; then
      problema="${problema:+$problema; }sin reporte, no se pudo verificar: $porque"
      continue
    fi
    d=$(mktemp -d)
    base64 -d <<<"$b64" | tar -xzf - -C "$d" 2>/dev/null
    if ! grep -ho '<testsuite[^>]*>' "$d"/*.xml 2>/dev/null | grep -q "$patron"; then
      problema="${problema:+$problema; }el reporte no dice $patron ($porque)"
    fi
    rm -rf "$d"
  done

  if [ -z "$problema" ]; then
    printf 'OK    %-24s %s\n' "$esperado" "(sobrevivientes: ${sobrevivientes:-?})"
    ok=$((ok + 1))
  else
    printf 'ROJO  %s\n' "$problema"
    ROJOS+=("$bundle: $problema")
    fallados=$((fallados + 1))
  fi
done

echo "---"
echo "contenidos: $ok   rojos: $fallados   salteados: $salteados"
if [ ${#ROJOS[@]} -gt 0 ]; then
  echo
  echo "CASOS NO CONTENIDOS:"
  printf '  - %s\n' "${ROJOS[@]}"
  exit 1
fi
