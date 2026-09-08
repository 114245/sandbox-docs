# syntax=docker/dockerfile:1
#
# java-runner — imagen de ejecución del ms-sandbox (TEMA 06)
#
# Contrato: el bundle entra por STDIN como tar, el resultado sale por STDOUT
# como un sobre JSON delimitado. Ver runner/README.md.
#
# Decisiones que están documentadas en docs/arquitectura/03-ms-sandbox-ejecucion.md:
#   §1.5   el bundle NO entra por `docker cp` (incompatible con --read-only)
#   §1.6a  no hay Maven: javac + junit-platform-console-standalone
#   §1.6b  la JVM lee los cgroups: MaxRAMPercentage + ExitOnOutOfMemoryError
#   §1.6d  el jar de JUnit vive en la imagen, fuera del alcance del alumno

FROM eclipse-temurin:21-jdk

# Versión del ConsoleLauncher. 1.11.3 es la que usó el spike.
# La línea 6.0.x existe y conviene medirla (§1.6a), pero se cambia acá, no en el script.
ARG JUNIT_VERSION=1.11.3
# TODO(seguridad): fijar y verificar el sha256 del jar. Hoy se confia en Maven
# Central sobre TLS. Se deja explicito en vez de simulado.

# tar, base64, timeout, find(GNU) ya vienen en la base Ubuntu de temurin.
# Solo hace falta curl para bajar el jar, y se borra después.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates procps; \
    mkdir -p /opt/junit; \
    curl -fsSL -o /opt/junit/junit.jar \
      "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_VERSION}/junit-platform-console-standalone-${JUNIT_VERSION}.jar"; \
    chmod 0444 /opt/junit/junit.jar; \
    apt-get purge -y --auto-remove curl; \
    rm -rf /var/lib/apt/lists/*

# El uid no privilegiado con el que corre TODO adentro del contenedor.
# No se crea con `useradd` a propósito: no queremos un home real ni una entrada
# de shell. Lo fija la spec del contenedor (08 §4.1), del lado del ejecutor.
ENV RUNNER_UID=1000 \
    RUNNER_GID=1000 \
    HOME=/work \
    JUNIT_JAR=/opt/junit/junit.jar

COPY entrypoint.sh /opt/sandbox/entrypoint.sh
RUN chmod 0555 /opt/sandbox/entrypoint.sh

# Los tres relojes (§1.3 paso 4). Con la spec de `08` §4.1 el contenedor se crea
# con `Env: []`, asi que estos defaults SON los relojes efectivos: viven en la
# imagen, no en el request. Cambiarlos es rebuild, y eso es la invariante P1.
ENV TIMEOUT_COMPILE_MS=20000 \
    CPU_TESTS_S=10 \
    TIMEOUT_TESTS_MS=30000 \
    SALIDA_LIMITE_BYTES=65536

USER 1000:1000
WORKDIR /work

ENTRYPOINT ["/opt/sandbox/entrypoint.sh"]
