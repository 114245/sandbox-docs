# Contexto de build: ms-sandbox/. Construir desde ms-sandbox/:
#   docker build -f worker/Dockerfile -t sandbox-worker-tests:1.0.0 .
#
# No es un servicio (worker/ no tiene clase principal, solo tests): una sola etapa que compila
# y corre la suite con Maven.

FROM maven:3.9-eclipse-temurin-21

# D16IT resuelve los bundles con Path.of("..", "pruebas", "bundles") relativo al directorio de
# trabajo del proceso de test (el working dir de "mvn test" es la raiz del modulo). Por eso hay
# que conservar la MISMA relacion de directorios que en el repo: worker/ y pruebas/ como
# hermanos, con WORKDIR parado en worker/, para que "../pruebas/bundles" siga apuntando adonde
# D16IT espera.
WORKDIR /app/worker

COPY worker/pom.xml ./pom.xml
COPY worker/src ./src
COPY pruebas/ /app/pruebas/

# El comando por defecto corre la suite completa (incluye los *IT; D16IT se autosaltea con
# assumeTrue si no encuentra el socket del ejecutor en SANDBOX_EJECUTOR_SOCKET).
CMD ["mvn", "-B", "test"]
