# A6: el test bloqueante de la seccion 7, contra un daemon de Docker real.
#
# Java sobre Windows no puede hablarle al socket del daemon (Docker Desktop lo expone como npipe),
# asi que la suite corre adentro de un contenedor con /var/run/docker.sock montado. No hace falta
# instalar nada en WSL.
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

# Fixture de A6: busybox con el entrypoint 'read N; tar -tf -'. NO es el runner de produccion.
docker build -t sandbox-runner:1.0.0 src/test/fixtures/a6

docker run --rm `
  -v /var/run/docker.sock:/var/run/docker.sock `
  -v "${PWD}:/app" -v ejecutor-m2:/root/.m2 -w /app `
  maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=ProtocoloIT
