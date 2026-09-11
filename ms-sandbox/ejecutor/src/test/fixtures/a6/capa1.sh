#!/bin/sh
# Fixture de A6, NO es la imagen del runner de produccion.
#
# Valida las hipotesis bloqueantes del framing de tres documentos (seccion 7 + el
# handoff de la Opcion 1):
#
#   1. `read` no consume mas que la primera linea del descriptor.
#   2. `read` de la segunda linea tampoco se pasa.
#   3. `dd iflag=fullblock bs=$N count=1` lee EXACTAMENTE los N bytes del guion y no
#      se come ni un byte del tar. `head -c` en busybox se come 710; por eso no se usa.
#   4. Al tar le queda el resto del stream intacto.
#   5. El cierre del canal adjunto llega como EOF: sin EOF, `cat` se cuelga.
#
# Imprime el nonce, el guion recibido y el listado del tar. El nonce sale a stdout a
# proposito: el test necesita saber cual fue para comprobar que es el que entro.
read NONCE
read N

dd iflag=fullblock bs="$N" count=1 of=/work/eval.in 2>/dev/null
RECIBIDOS=$(wc -c < /work/eval.in)

# `cat` hasta EOF: si el ejecutor no cierra el canal adjunto, esto no vuelve nunca.
cat > /work/bundle.tar

echo "FIN $NONCE"
echo "GUION $RECIBIDOS de $N"
echo "---SANDBOX-$NONCE-INICIO---"
cat /work/eval.in
echo "TAR $(wc -c < /work/bundle.tar)"
tar -tf /work/bundle.tar
echo "---SANDBOX-$NONCE-FIN---"
