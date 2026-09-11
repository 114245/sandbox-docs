# syntax=docker/dockerfile:1
#
# Imagen de la CAPA 1 — modelo de dos capas de la Opcion 1 del V4.
#
# Diferencia de fondo con el Dockerfile viejo: aca NO vive la logica de
# evaluacion. La imagen aporta el entrypoint (capa 1) y las herramientas en
# /libs; que hacer con ellas lo dice el script del perfil, que entra por stdin.
#
# Dos correcciones al Dockerfile del seccion 6 del V4, las que le senalamos a
# G5 en la seccion 5 de nuestra respuesta:
#   1. /libs NO se chownea al usuario del sandbox. Con permiso de escritura, el
#      alumno podria reescribir junit.jar ENTRE FASES: compilar bien y
#      reemplazar el jar del analizador antes de que corra. No rompe el
#      aislamiento del contenedor, pero si la integridad de las herramientas
#      con las que se dicta el veredicto.
#   2. /work no se declara aca: lo monta el ejecutor como tmpfs (08 §4.1).
#      WORKDIR /work existe solo para que el proceso arranque parado ahi.

FROM eclipse-temurin:21-jdk

ARG JUNIT_VERSION=1.11.3

# TODO(seguridad): fijar y verificar el sha256 del jar. Hoy se confia en Maven
# Central sobre TLS. Se deja explicito en vez de simulado.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    mkdir -p /libs; \
    curl -fsSL -o /libs/junit.jar \
      "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_VERSION}/junit-platform-console-standalone-${JUNIT_VERSION}.jar"; \
    apt-get purge -y --auto-remove curl; \
    rm -rf /var/lib/apt/lists/*

# root:root, y solo lectura para todos los demas. Es el punto 1 de arriba.
RUN chown -R root:root /libs && chmod 0555 /libs && chmod 0444 /libs/junit.jar

COPY capa1/capa1.sh /opt/sandbox/capa1.sh
RUN chmod 0555 /opt/sandbox/capa1.sh && chown root:root /opt/sandbox/capa1.sh

# El contenedor se crea con `Env: []` (08 §4.1), asi que estos defaults SON los
# valores efectivos: viven en la imagen, no en el request. Eso es P1.
#
# SANDBOX_EVAL_TIMEOUT_S es el BACKSTOP de la capa 1, no el reloj de la
# evaluacion: los relojes por fase son de la capa 2 y viven en el perfil.
ENV SANDBOX_LIBS=/libs \
    SANDBOX_EVAL_TIMEOUT_S=45 \
    SANDBOX_MAX_SCRIPT_BYTES=262144 \
    SALIDA_LIMITE_BYTES=65536 \
    HOME=/work

USER 1000:1000
WORKDIR /work

ENTRYPOINT ["/opt/sandbox/capa1.sh"]
