# Contexto de build: ms-sandbox/ (por eso los COPY de abajo arrancan con "ejecutor/" y
# "perfiles/", no con rutas relativas a este Dockerfile). Construir desde ms-sandbox/:
#   docker build -f ejecutor/Dockerfile -t sandbox-ejecutor:1.0.0 .

# ---- Etapa de compilacion ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Primero el pom solo: si src/ cambia pero las dependencias no, Docker reusa la capa de
# descargas de Maven en vez de volver a bajar todo el arbol.
COPY ejecutor/pom.xml ./pom.xml
COPY ejecutor/src ./src

RUN mvn -B -DskipTests package

# ---- Etapa final -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# Horneamos el catalogo de perfiles en la imagen: Main.java ya usa /opt/ejecutor/perfiles como
# valor por defecto (EJECUTOR_PERFILES), asi que con la ruta puesta aca no hace falta declarar
# ninguna variable de entorno para que el ejecutor la encuentre.
COPY perfiles/ /opt/ejecutor/perfiles/

# Jar shadeado (fat jar) de la etapa de compilacion. finalName=ejecutor en el pom.
COPY --from=build /build/target/ejecutor.jar /opt/ejecutor/ejecutor.jar

# Deliberado: no se declara EJECUTOR_SOCKET, DOCKER_HOST ni EJECUTOR_PERFILES. Main.java ya trae
# como default /run/ejecutor/ejecutor.sock, unix:///var/run/docker.sock (en Linux) y
# /opt/ejecutor/perfiles respectivamente; como esta imagen respeta esas rutas (el socket propio
# lo publica un volumen montado en /run/ejecutor, el socket del daemon se monta en
# /var/run/docker.sock, y el catalogo queda horneado arriba), los defaults del codigo YA SON la
# configuracion de produccion. Agregar ENV aca seria repetir en dos lugares algo que un solo
# archivo (Main.java) ya expresa.

# Corre como root (no se declara USER): el socket del daemon de Docker es root:docker con modo
# 0660, y un usuario sin ese grupo no puede abrirlo. Ademas este contenedor ya tiene montada la
# llave del daemon (equivalente a root en el host) — bajar a un usuario sin privilegios ahi no
# reduce superficie real, porque el privilegio grande (hablarle al daemon) ya esta concedido por
# el volumen, no por el UID del proceso. Esto NO aplica a la imagen del runner (imagenes/), que
# corre codigo de alumno sin acceso al daemon y por eso si declara USER 1000:1000.

ENTRYPOINT ["java", "-jar", "/opt/ejecutor/ejecutor.jar"]
