#!/bin/sh
# Fixture de A6, NO es la imagen del runner de produccion.
# Valida la unica hipotesis bloqueante de la seccion 7: que `read` no consuma mas que la
# primera linea del descriptor, dejando el resto del stream intacto para tar.
read N
echo "FIN $N"
echo "---SANDBOX-$N-INICIO---"
tar -tf -
echo "---SANDBOX-$N-FIN---"
