#!/bin/sh
# A6: leer el nonce y despues el tar del MISMO descriptor.
# El mecanismo depende de que `read` no consuma mas alla de la primera linea; en dash y busybox sh
# eso vale porque leen de a un byte sobre descriptores no posicionables. Esto es lo que lo prueba.
read NONCE
tar -tf - && echo "FIN $NONCE"
