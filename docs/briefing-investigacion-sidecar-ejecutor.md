# Briefing para investigación externa — el sidecar ejecutor de `ms-sandbox`

> **Para qué sirve este documento.** Es el contexto completo y autocontenido de **un problema puntual**: cómo construir el componente que lanza los contenedores de ejecución. Está escrito para que alguien que no conoce nada del proyecto pueda dar una respuesta útil sin pedir aclaraciones. Copiar y pegar entero, o la sección que corresponda, en buscadores, asistentes de IA, foros, o para llevarlo a un docente o a alguien de la industria.
>
> Complementa a [`briefing-investigacion-sandbox.md`](./briefing-investigacion-sandbox.md), que cubre el diseño general del servicio. Este baja a un solo componente.
>
> Creado: 1 de septiembre de 2026.

---

## 1. Contexto mínimo

Trabajo Práctico Integrador de 2° año en la **UTN Facultad Regional Córdoba** (Programación 4 + Metodología de Sistemas 2). El curso construye **una plataforma de e-learning gamificada** repartida en 12 grupos, uno por microservicio. Nuestro grupo (11 personas) tiene el **TEMA 06 — Sandbox / Runtime**.

**Qué hace nuestro servicio:** un alumno escribe código Java para resolver un desafío. Nosotros lo ejecutamos **aislado**, contra pruebas unitarias JUnit que escribió el profesor, y devolvemos un veredicto.

Tres condiciones que enmarcan todo:

- **No sale a producción.** Es académico. Pero se evalúa con criterio de ingeniería: hay que **defender** las decisiones ante un tribunal, no solo hacer que funcione.
- **La escala es chica y está fijada:** 120 usuarios, hasta 120 sesiones concurrentes en el pico. El problema no es escalar, es **aislar bien**.
- **Un veredicto equivocado tiene consecuencia académica real.** El resultado del sandbox modifica el XP del alumno, que determina promoción y regularidad. Un falso negativo le cuesta una vida.

**Stack:** Java 21 + Spring Boot 3.x, PostgreSQL, RabbitMQ, Docker. Frontend Angular. El equipo sabe **Java** y **TypeScript**. Nadie sabe Go ni Rust.

---

## 2. Cómo se ejecuta una entrega, hoy

Por cada entrega se levanta un **contenedor Docker efímero**, deliberadamente lisiado:

| Restricción | Para qué |
|---|---|
| `--network none` | Sin red. Es la defensa central: el código del alumno no puede hablar con nadie |
| `--read-only` | Filesystem raíz de solo lectura. **Innegociable** |
| `--tmpfs /work` | Único lugar escribible: un disco en RAM, acotado, con `noexec`. Muere con el contenedor |
| `--memory`, `--cpus`, `--pids-limit` | Límites de recursos por cgroups |
| `--cap-drop ALL`, `--security-opt no-new-privileges` | Sin capabilities de Linux, sin escalada |
| Usuario no-root | El proceso de adentro no es root |

Adentro corre una JVM con `javac` y el **JUnit Console Launcher** (standalone, horneado en la imagen). El contenedor vive segundos y se destruye.

**Cómo entran los archivos del alumno.** Ésta es la parte contraintuitiva, y es una decisión **forzada**, no elegida:

- **Bind mount** (`-v /host/jobs/123:/work`) — descartado: expone un path real del host adentro del contenedor. Si el alumno escapa de la JVM ya tiene un pie en la máquina.
- **`docker cp`** (API: `PUT /containers/{id}/archive`) — **no funciona**, y lo descubrimos probándolo, no razonándolo. Dos fallas concretas:
  1. Con el rootfs read-only el daemon rechaza la copia: `Error response from daemon: container rootfs is marked read-only`. El chequeo es sobre el contenedor, no sobre el destino.
  2. Aunque no la rechazara: `docker cp` corre sobre un contenedor creado pero **no arrancado**. Se copiaría a `/work` y después, al arrancar, el `tmpfs` se montaría **encima** y taparía todo lo copiado.
- **Tar por `stdin`** — la que queda. El worker le manda el tar al **proceso de adentro**, por su entrada estándar. El entrypoint hace `tar -xf - -C /work && ./compilar-y-correr.sh`. No toca el rootfs, no necesita montajes, y el reporte vuelve por `stdout`.

---

## 3. La decisión que ya tomamos: el sidecar es un **ejecutor**, no un proxy

El worker necesita lanzar contenedores, así que necesita `/var/run/docker.sock`. Y ese socket **es la API HTTP completa de Docker, sin ningún modelo de autorización**: no hay usuarios, ni roles, ni scopes. Quien puede hablarle puede todo, incluido `POST /containers/create` con `Privileged: true` y `Binds: ["/:/host"]`, que es root en el host en una sola llamada.

La solución es un **sidecar**: un contenedor acompañante que es **el único que tiene el socket montado**. El worker no lo tiene y no puede tenerlo.

Hay dos diseños posibles bajo ese mismo nombre, y **elegimos el segundo**:

| | Sidecar como **proxy** | Sidecar como **ejecutor** ← elegido |
|---|---|---|
| Qué expone | La API de Docker, filtrada por path y método | Un endpoint propio: `POST /ejecutar` |
| Quién arma la spec del contenedor | El worker | El sidecar, **hardcodeada** |
| Implementación típica | `tecnativa/docker-socket-proxy` (HAProxy), cero código | Código propio |
| Debilidad | No mira el body. `POST /containers/create` **hay que permitirlo**, y `Privileged` es un campo del body | Bugs propios, pero superficie mínima y fija |

**Por qué el ejecutor.** Filtrar el body de las llamadas a Docker falló tres veces en ocho años, siempre por la misma causa:

| CVE | Cómo se bypassea |
|---|---|
| **CVE-2018-16398** | Twistlock AuthZ Broker manejaba mal los regex: `containers/aa/pause?aaa=\/start` pasaba una política que permitía `start` y prohibía `pause`. Es un bypass del filtrado por path — el enfoque exacto de `tecnativa` |
| **CVE-2024-41110** (crítico) | Con `Content-Length: 0` el daemon reenvía el request al plugin **sin body**: el plugin no ve nada y aprueba, el daemon procesa el body completo. Detectado en 2018, parcheado, **el fix no se propagó a 19.03+** y la regresión se descubrió recién en abril de 2024. Cinco años de ventana |
| **CVE-2026-34040** (abril 2026) | Si el body supera 1 MB, el middleware de Docker lo descarta **antes** de pasárselo al plugin. Afecta a todos los plugins por igual, porque el bug está en Docker |

> **El patrón, en una frase:** filtrar el body es un problema de parseo sobre input influido por el atacante, y las tres veces falló porque **el filtro y el daemon no interpretan el mismo byte stream**. Un sidecar que **no acepta una spec de contenedor** no tiene ese problema, porque no tiene nada que parsear.

Beneficio adicional: la clase de bug dominante de `runc` (las races de procfs y mounts, tres CVEs en noviembre de 2025) **requiere en general que el atacante controle la spec del contenedor**. Con la spec hardcodeada, esa familia entera se cierra.

**Lo que el sidecar NO hace**, y conviene decirlo para no prometer de más: mitiga la amenaza de **worker comprometido**, que es la secundaria. El contenedor del alumno nunca puede alcanzar al sidecar — no tiene red ni el socket. Lo que se compra es **radio de explosión**: "comprometer el worker = root en el host" pasa a ser "comprometer el worker = pedir ejecuciones".

---

## 4. Lo que hay que construir

Un servicio HTTP chico, en su propio contenedor, con el socket de Docker montado. Recibe:

```
POST /ejecutar
Content-Type: application/octet-stream   (el tar del bundle)
```

y por cada llamada tiene que:

1. `POST /containers/create` — armar la spec **fija, hardcodeada**: imagen, límites, `--read-only`, `--tmpfs /work`, `--network none`, `--cap-drop ALL`, usuario no-root. Nada de esto viene del que llama.
2. Engancharse a la entrada y salida del contenedor.
3. `POST /containers/{id}/start`.
4. Escribir el tar del bundle en el **`stdin`** del contenedor, y después cerrar solo ese lado.
5. Leer `stdout` y `stderr` **por separado** (hace falta distinguir la salida del programa del ruido de la JVM y de JUnit).
6. Esperar a que termine y quedarse con el **exit code**.
7. Si se pasa del plazo: `POST /containers/{id}/kill`.
8. `DELETE /containers/{id}` y devolver el resultado.

El paso 2 y los pasos 4-5 son los que no entendemos del todo, y son el tema principal de este briefing.

---

## 5. Problema A — el `attach` "hijacked" con demultiplexado de frames

Para mandar el tar por `stdin` **mientras** se lee `stdout`, hace falta:

```
POST /containers/{id}/attach?stream=1&stdin=1&stdout=1&stderr=1
```

Y ahí pasan dos cosas raras, las dos juntas.

### 5.1 La conexión deja de ser HTTP

HTTP/1.1 es *request → response → se acabó*: uno habla, el otro contesta. Pero acá hace falta **escribir y leer al mismo tiempo** sobre la misma conexión. Entonces Docker usa el mecanismo de upgrade —el mismo que WebSocket— y responde:

```
HTTP/1.1 101 UPGRADED
Content-Type: application/vnd.docker.raw-stream
Connection: Upgrade
Upgrade: tcp
```

A partir de ese momento **abandona HTTP y se queda con el socket crudo**. No hay más headers, ni `Content-Length`, ni chunked encoding: bytes pelados en las dos direcciones hasta que alguien cierre. A eso se le dice que Docker *secuestra* (hijack) la conexión.

La consecuencia práctica es que el cliente HTTP tiene que **entregarte el socket** en vez de manejarlo él, y los clientes HTTP de alto nivel normalmente están diseñados para lo contrario.

### 5.2 Un solo socket, dos salidas

El contenedor tiene `stdout` y `stderr` separados, pero hay una sola conexión. Docker lo resuelve **enmarcando**: manda paquetes con un header de 8 bytes adelante.

```
 byte 0      bytes 1-3     bytes 4-7            bytes 8..8+N
┌─────────┬─────────────┬──────────────────┬──────────────────────┐
│  tipo   │   padding   │  largo (uint32,  │       payload        │
│ 1=out   │   (ceros)   │   big-endian)    │      (N bytes)       │
│ 2=err   │             │                  │                      │
└─────────┴─────────────┴──────────────────┴──────────────────────┘
```

Leer 8 bytes, sacar tipo y largo N, leer N bytes de payload, mandarlo al buffer que corresponda, repetir. Eso es **demultiplexar**.

### 5.3 Las tres cosas que sabemos que duelen

- **Los frames llegan partidos.** TCP no respeta los límites de los frames: un `read` puede traer medio header, o dos frames y pico. No se puede asumir "un read = un frame". Hace falta un buffer acumulador con máquina de estados, y ahí es donde se cometen los bugs.
- **El half-close del `stdin`.** Después de mandar el tar hay que cerrar **solo** el lado de escritura, para que el proceso de adentro vea EOF y deje de esperar. Si no, el contenedor se cuelga leyendo para siempre y lo mata el timeout — y el síntoma parece un problema del alumno.
- **El gotcha del TTY.** Todo esto vale con `Tty: false`. Con `Tty: true` Docker **no enmarca nada** —manda bytes crudos— porque un TTY tiene un solo flujo por definición, y se pierde la separación stdout/stderr.

---

## 6. Problema B — el tar es entrada hostil

Resolver *cómo* entra el bundle no resuelve *qué* trae adentro. El tar lo arma el sistema, pero **contiene archivos del alumno**, y el alumno controla sus nombres. Desempaquetar un tar con nombres controlados por un atacante es la familia de bugs que produjo los dos CVE 10.0 de Judge0 (otro sandbox de ejecución de código).

| Variante | Cómo funciona | ¿La probamos? |
|---|---|---|
| **Path traversal** | La entrada se llama `../../opt/junit/junit.jar`. Al extraer en `/work`, el `../../` sale a `/opt` y sobrescribe el JAR de JUnit. Quien reemplaza el motor de tests decide el veredicto | ✅ Sí, contenida |
| **Entrada symlink** | El tar trae **dos** entradas con el mismo nombre: primero un symlink `Solucion.java → /etc/passwd`, después un archivo regular. `tar` crea el enlace, y al escribir el segundo **la escritura sale por el otro extremo**. No hay ningún `../` a la vista | ❌ **No** |
| **Hard link** | Una entrada del tar es un hard link a un archivo de afuera del directorio de extracción, y escribir "adentro" escribe afuera | ❌ **No** |

El symlink es el vector exacto de **CVE-2024-28185** en Judge0. Lo parchearon cambiando el usuario Unix, y el investigador lo bypasseó con `chown` sobre un symlink (**CVE-2024-28189**).

Creemos que estamos cubiertos —GNU tar con `--no-same-owner`, dentro de un contenedor read-only, extrayendo en un `tmpfs`— pero *"creemos"* no es una respuesta de defensa.

---

## 7. Problema C — en qué lenguaje se escribe

**Ésta es una decisión de equipo que todavía no tomamos.** El contexto:

- El equipo sabe **Java** (backend del TPI) y **TypeScript** (frontend Angular). **Nadie sabe Go ni Rust.**
- **Descartamos usar un componente de tercero** (`tecnativa/docker-socket-proxy`) como control de seguridad. El ejecutor es código propio por definición.

Lo que ya evaluamos y **descartamos como criterio**: el **peso** (MB de imagen y RAM). El cálculo: el sidecar corre **uno por réplica de worker** (1-3 en este TP), no uno por ejecución; mientras tanto cada ejecución de alumno levanta un contenedor con JVM+JUnit, y con 3 concurrentes ya se pasa 1 GB. Los ~65 MB de diferencia entre un sidecar Node y uno Java son un costo **constante** que no escala con la carga. El disco se paga una vez, en el `pull`. El arranque importaría solo si el sidecar naciera por job — vive con el worker.

Los criterios que **sí** creemos que deciden:

1. ¿Alguien del equipo lo puede **auditar**? Un componente de seguridad que el equipo no puede leer no es auditable.
2. **Líneas de código propio** — ése es el "chico y auditable de una sentada" de verdad, no los MB.
3. **Dependencias externas** necesarias. Cero es lo ideal.
4. Qué hay **adentro de la imagen**: ¿shell, `curl`, gestor de paquetes? (Esto parece resolverse con imágenes *distroless*, no eligiendo lenguaje.)

Un dato técnico que encontramos y que nos parece que inclina la balanza, **pero que queremos verificar**:

> `java.net.http.HttpClient` (el cliente HTTP del JDK) **no soporta Unix domain sockets**. `UnixDomainSocketAddress` existe desde Java 16 y da el `SocketChannel`, pero habría que escribir HTTP/1.1 a mano encima —incluido el hijack y el demux— o traer una dependencia (`docker-java`, `httpclient5`). En Node, en cambio, `http.request({ socketPath: '/var/run/docker.sock', ... })` es nativo, y `req.on('upgrade', ...)` entrega el socket crudo para el hijack.

---

## 8. Las preguntas concretas

### Sobre el `attach` (Problema A)

1. ¿Es realmente necesario el `attach` hijacked, o hay una forma más simple de meter datos por `stdin` de un contenedor con rootfs read-only y `tmpfs` en el destino? ¿Existe alguna alternativa que no hayamos considerado?
2. ¿Cuál es la forma correcta de hacer el **half-close** del `stdin` en un `attach` hijacked, y qué pasa del lado de Docker si no se hace?
3. ¿Hay diferencias relevantes entre usar `/attach` y usar `/containers/{id}/logs` para recolectar la salida, si no hace falta ver la salida en vivo? ¿`logs` también viene multiplexado con el header de 8 bytes?
4. ¿Cómo se acota la salida de un programa que escribe sin parar (`while(true) System.out.println(...)`)? ¿Alcanza con `--log-opt max-size` o hace falta cortar en streaming?
5. ¿Hay trampas conocidas del demultiplexado que no estén en la documentación oficial? (Por ejemplo, ¿el header puede llegar partido? ¿hay frames de largo cero? ¿qué pasa con el tipo 0?)

### Sobre el tar hostil (Problema B)

6. ¿GNU tar, con `--no-same-owner`, extrayendo dentro de un contenedor read-only sobre un `tmpfs`, está protegido contra la **entrada symlink** y contra los **hard links**? ¿O hace falta alguna flag más (`--no-overwrite-dir`, `--absolute-names` desactivado, `--delay-directory-restore`…)?
7. ¿Conviene usar GNU tar del sistema, o extraer el tar **en código** desde el propio ejecutor con una librería que valide cada entrada antes de escribirla?
8. ¿Cómo se construye un tar malicioso a propósito, para poder **probar** estos casos? Las herramientas normales no dejan meter estas entradas fácilmente.
9. ¿Hay alguna otra variante de entrada hostil en un tar además de path traversal, symlink y hard link? (nombres absolutos, `..` en el medio del path, device files, nombres con bytes raros, tar bombs por tamaño…)

### Sobre el lenguaje (Problema C)

10. ¿Es correcto que `java.net.http.HttpClient` no soporta Unix domain sockets y que hay que escribir HTTP a mano sobre `SocketChannel`? ¿Cambió en Java 21 o 25?
11. Si se hiciera en Java: ¿cuál es la forma menos mala? ¿Escribir HTTP/1.1 a mano, o aceptar una dependencia como `docker-java`? ¿Cuánta superficie de auditoría agrega esa dependencia?
12. Para un componente de seguridad chico, mantenido por un equipo de 11 estudiantes: ¿pesa más **que el equipo pueda leerlo** o que esté escrito en el lenguaje técnicamente más adecuado? ¿Cómo se argumenta esa decisión ante un tribunal académico?
13. ¿Hay algún ejemplo público de un "ejecutor sidecar" como éste —que exponga una API propia acotada en vez de proxear la API de Docker— que podamos mirar como referencia?

### Sobre el diseño en general

14. ¿El diseño de "sidecar ejecutor con spec hardcodeada" es un patrón reconocido, o le estamos poniendo un nombre a algo que en la industria se llama de otra forma? Buscamos **fuentes citables**.
15. ¿Qué le criticaría alguien con experiencia a este diseño? Nos interesan especialmente los **contraargumentos**, no la confirmación.

---

## 9. Qué NO nos sirve como respuesta

Lo aclaramos porque es lo que solemos recibir:

- **"Usá Kubernetes / gVisor / Firecracker."** Ya evaluamos la escalera de aislamiento. La restricción es Docker sobre una VM, que es lo que da la cátedra.
- **"Usá una librería que lo resuelve."** Puede ser la respuesta correcta, pero necesitamos saber **qué hace por dentro** — el punto del componente es poder defenderlo.
- **"Docker ya es seguro."** El socket de Docker no tiene modelo de autorización; eso es un hecho documentado, no una opinión.
- **Respuestas genéricas sobre sandboxing.** Buscamos lo específico de estos tres problemas.
- **Confirmación sin fuente.** Si algo es cierto, queremos poder citarlo: documentación oficial, CVE, paper, código.

---

## 10. Glosario mínimo

| Término | Qué es |
|---|---|
| **Sidecar** | Contenedor acompañante que corre junto al servicio principal, en la misma unidad de despliegue, y le agrega una capacidad transversal sin tocar su código |
| **Socket de Docker** | `/var/run/docker.sock`. La API HTTP completa del daemon, expuesta como socket Unix. Sin autorización: quien le habla, puede todo |
| **Unix domain socket** | Socket de comunicación entre procesos de la misma máquina, que se direcciona por un path del filesystem en vez de por IP y puerto |
| **Hijack** | Cuando el servidor abandona el protocolo HTTP a mitad de camino y se queda con el socket crudo, para poder hablar en las dos direcciones |
| **Multiplexar / demultiplexar** | Meter dos flujos (stdout y stderr) en una sola conexión enmarcándolos, y volver a separarlos del otro lado |
| **Half-close** | Cerrar solo el lado de escritura de un socket, dejando abierto el de lectura. Es lo que le hace ver EOF al proceso de enfrente |
| **`tmpfs`** | Filesystem en RAM, efímero. Muere con el contenedor |
| **Bundle** | El tar que contiene el código del alumno más los tests del profesor |
| **Spec del contenedor** | El JSON que se le manda a `POST /containers/create` describiendo imagen, límites, montajes y privilegios |
| **Distroless** | Imagen base sin shell, sin gestor de paquetes y sin utilidades — solo el runtime y la aplicación |
