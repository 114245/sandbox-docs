# A6: el test bloqueante de la seccion 7, contra un daemon de Docker real.
#
# Ya NO hace falta meter la suite adentro de un contenedor. Con docker-java, el transporte
# httpclient5 habla el named pipe de Docker Desktop, asi que el ejecutor corre nativo en Windows.
# Docker.conectar elige npipe o unix segun la plataforma; DOCKER_HOST lo pisa si hace falta.
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

# Fixture de A6: busybox con la capa 1 de juguete que verifica el framing de tres documentos.
# NO es el runner de produccion. Se etiqueta con sandbox-runner:1.0.0 a proposito: es la imagen
# que declara el perfil de prueba test-busybox@1, asi el test corre la spec de produccion entera
# sin tocar un solo campo.
docker build -t sandbox-runner:1.0.0 src/test/fixtures/a6

mvn -B test -Dtest=ProtocoloIT
