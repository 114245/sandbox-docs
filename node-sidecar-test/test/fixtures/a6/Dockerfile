# Imagen minima para A6: el test que valida el mecanismo de la seccion 7.
# El entrypoint lee la primera linea de stdin en una variable de shell NO exportada (R7.3, R7.4)
# y le pasa el resto del descriptor a tar sin tocarlo.
FROM busybox:1.36
COPY entrypoint.sh /opt/sandbox/entrypoint.sh
RUN chmod 0755 /opt/sandbox/entrypoint.sh
ENTRYPOINT ["/opt/sandbox/entrypoint.sh"]
