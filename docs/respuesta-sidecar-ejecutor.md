# Respuesta al briefing — sidecar ejecutor de `ms-sandbox`

> Respuesta a `briefing-investigacion-sidecar-ejecutor.md`. Todo lo afirmado acá tiene fuente o está marcado explícitamente como "hay que probarlo".
>
> Fecha: 1 de septiembre de 2026.

---

## 0. Lo más importante, primero

**El `attach` hijacked bidireccional probablemente no lo necesitan.** El Problema A —el que ustedes llaman "el tema principal del briefing"— se puede reducir a casi nada con un cambio de diseño de tres líneas en la spec del contenedor. Eso mueve el peso de las otras dos preguntas: si desaparece el demultiplexado en vivo, el argumento técnico a favor de Node se debilita mucho, y la decisión de lenguaje se vuelve casi puramente de equipo.

Además hay **tres cosas del briefing que están mal o imprecisas**, y las tres son del tipo que un tribunal puede picar:

1. El symlink de **CVE-2024-28185 (Judge0) no es un symlink dentro de un tar**. El vector es otro, y el vector real de Judge0 **sí les aplica a ustedes**, en un lugar donde no lo están mirando (§6.4).
2. El `Content-Type` de un `attach` no-TTY moderno **no es** `application/vnd.docker.raw-stream`, es `application/vnd.docker.multiplexed-stream`. Es un detalle chico pero les sirve como assert barato (§5.3).
3. "Los CVEs de runc requieren que el atacante controle la spec" es cierto pero por una razón más precisa que la que dan, y esa precisión les conviene (§8.2).

---

## 1. Problema A — sacarse el hijack de encima

### 1.1 El rediseño

El costo del `attach` no viene del hijack en sí. Viene de **escribir y leer al mismo tiempo sobre el mismo socket**: eso es lo que obliga a una máquina de estados con buffer acumulador, es donde viven los bugs, y es lo que hace que el cliente HTTP tenga que devolverte el socket crudo.

Se puede separar. La salida no hace falta leerla en vivo: el reporte de JUnit sirve igual si lo leen cuando el contenedor terminó.

**Flujo propuesto:**

| Paso | Llamada | Hijack |
|---|---|---|
| 1 | `POST /containers/create` — spec fija, con `OpenStdin: true`, `StdinOnce: true`, `Tty: false`, `AttachStdin: true`, `AttachStdout: false`, `AttachStderr: false` | no |
| 2 | `POST /containers/{id}/attach?stream=1&stdin=1` | **sí**, pero solo escritura |
| 3 | `POST /containers/{id}/start` | no |
| 4 | escribir el tar en el socket, **cerrar el socket entero** | — |
| 5 | `POST /containers/{id}/wait` (con timeout propio; si vence, `POST /kill`) | no |
| 6 | `GET /containers/{id}/logs?stdout=1&stderr=1` | no |
| 7 | `DELETE /containers/{id}?force=1&v=1` | no |

Lo que se gana:

- **No hay lectura y escritura simultáneas.** El socket hijacked es de una sola dirección; no hay nada que demultiplexar sobre él.
- **No hace falta half-close.** `StdinOnce: true` está documentado como "cerrar stdin después de que el primer cliente adjunto se desconecte". Cerrar el socket entero alcanza para que `tar -xf -` vea EOF. *(Esto verifíquenlo con un test: es exactamente el tipo de afirmación que su briefing dice, con razón, que no quieren aceptar sin evidencia. El test es un contenedor con `entrypoint: sh -c 'tar -tf - && echo FIN'` y ver si imprime FIN.)*
- **El demux sigue existiendo pero sobre un cuerpo finito y completo.** `logs` devuelve una respuesta HTTP normal, chunked, que se lee entera y después se demultiplexa en memoria. No hay máquina de estados sobre TCP en vivo.
- **No hay riesgo de deadlock por backpressure.** Como no están adjuntos a stdout, la salida del contenedor la drena el log driver del daemon, no ustedes.

El orden importa: **`attach` va antes de `start`**. Si arrancan primero, el contenedor puede llegar a leer stdin antes de que ustedes estén conectados. Su briefing ya tiene ese orden; consérvenlo.

### 1.2 Sacarse el hijack del todo (opcional)

Si quieren eliminar el hijack por completo, hay que meter el bundle por otra vía. Dos opciones reales, y una que no funciona:

**a) Variable de entorno con el tar en base64.** El entrypoint hace `echo "$BUNDLE_B64" | base64 -d | tar -x -C /work`. Cero hijack.

- Límite duro: en Linux, `MAX_ARG_STRLEN` es 128 KiB **por string individual** de argv/environ. Un bundle de fuentes Java entra cómodo, pero hay que medirlo y rechazar arriba de un umbral que ustedes fijen.
- Costo conceptual: mete un dato del llamador dentro del JSON de `create`. Debilita —un poco— la propiedad que es su mejor argumento de defensa ("nada de la spec viene del que llama"). Es un campo de tipo string, no un campo estructural como `Privileged` o `Binds`, que es la clase que rompió los tres CVEs de AuthZ. Pero se los van a preguntar.

**b) Volumen Docker.** `POST /volumes/create`, poblarlo con un contenedor auxiliar escribible y sin red, y después montar el volumen **read-only** en el contenedor del alumno, con un `tmpfs` aparte para scratch. No expone ningún path del host (a diferencia del bind mount que ya descartaron, y por la misma razón que lo descartaron). Costo: dos contenedores por job, ciclo de vida del volumen, y un barrido de volúmenes huérfanos.

**c) `docker cp` a un volumen — sigue sin andar.** Confirmado: el chequeo de read-only es sobre el contenedor, no sobre el destino, incluso cuando el destino es un volumen montado. Está reportado como bug abierto en moby, no como decisión de diseño: [moby/moby#43015](https://github.com/moby/moby/issues/43015). Y aunque lo arreglaran, el segundo problema que ustedes encontraron (el `tmpfs` se monta encima) sigue en pie. Su conclusión es correcta y ahora tiene una cita.

**Mi recomendación:** el flujo de §1.1 (hijack de una sola dirección). Es el que mantiene intacta la propiedad "nada viene del llamador" y el que menos código nuevo pide. Las alternativas sin hijack existen; no valen su costo salvo que el hijack de escritura les dé problemas concretos.

---

## 2. Pregunta 2 — el half-close

Si igual lo necesitan (por ejemplo si deciden no usar `StdinOnce`):

- **Node:** `socket.end()`. Manda FIN y deja el lado de lectura abierto. Ojo con la confusión habitual: `allowHalfOpen` controla qué pasa cuando **el remoto** manda FIN, no lo que hace `end()`.
- **Java NIO:** `SocketChannel.shutdownOutput()`.
- **Si no lo hacen:** el proceso de adentro se queda bloqueado en `read()` sobre stdin para siempre, y lo mata su timeout. El síntoma es indistinguible de un `while(true)` del alumno. Es exactamente el modo de falla que ustedes ya identificaron, y es grave para ustedes en particular: un veredicto de timeout con causa equivocada es un falso negativo con consecuencia académica.
- **Del lado de Docker no pasa nada malo.** El daemon simplemente mantiene el pipe abierto. No hay corrupción ni estado colgado; el contenedor queda hasta que alguien lo mate.

**Mitigación independiente del transporte:** que el entrypoint no dependa de EOF. `tar -x` sobre un tar bien formado termina en el bloque de fin (dos bloques de ceros) sin necesidad de EOF. Si el entrypoint es `tar -xf - -C /work && ./compilar-y-correr.sh`, GNU tar corta en el marcador de fin. Eso hace que un half-close olvidado sea un bug latente y no una falla en producción — que es peor para debuggear, así que igual háganlo bien, pero es una red.

---

## 3. Pregunta 3 — `attach` vs `logs`

**Sí, `logs` viene multiplexado con el mismo header de 8 bytes.** Es literalmente el mismo código: el paquete `stdcopy` de moby se usa tanto para producir como para leer los streams multiplexados de `attach` **y** de `logs` ([moby/moby#50462](https://github.com/moby/moby/pull/50462)). Mismo formato, misma trampa del TTY.

Diferencias que sí importan:

| | `/attach` | `/logs` |
|---|---|---|
| Transporte | hijack, socket crudo | HTTP normal, chunked |
| Cliente HTTP | tiene que entregar el socket | cualquiera sirve |
| Requiere log driver | no | **sí** |
| Después de que el contenedor terminó | no sirve | sirve, mientras no lo borren |

**La condición nueva:** `logs` solo funciona con un logging driver que soporte lectura — `json-file` o `local`. Con `none`, `syslog`, `gelf`, etc., devuelve error. Como su spec es hardcodeada, **fíjenlo explícitamente** en `LogConfig`. No lo dejen al default del daemon: el default es configurable en `/etc/docker/daemon.json`, y si alguien de la cátedra o de infra lo cambia, su sandbox deja de poder leer veredictos. Eso es un acoplamiento oculto a la configuración del host, y es de las cosas que conviene declarar en la defensa como decisión consciente.

Efecto colateral: `json-file` escribe los logs **en el disco del host**. Ahí aparece la superficie de DoS por disco, que se controla con `max-size`/`max-file` (ver siguiente sección).

---

## 4. Pregunta 4 — acotar la salida

**`--log-opt max-size` no alcanza, y falla de la peor manera posible para ustedes.**

`max-size` **rota**, no corta ni mata. Con `max-size=1m --max-file=1`, un programa que escribe sin parar sigue escribiendo para siempre y lo que queda en el archivo es el **último** megabyte. Para ustedes eso es catastrófico: el reporte de JUnit y los errores de compilación están al **principio**. Se quedarían con un megabyte de basura y perderían la información que define el veredicto.

Tres capas, y les recomiendo las tres:

1. **Cortar en el origen, adentro del contenedor.** En el entrypoint, pipear la salida del programa del alumno por `head -c 1000000`. Es la más barata y la más robusta: acota antes de que los bytes salgan, no necesita que nadie mate nada, y no depende de que el sidecar esté leyendo. Ojo con separar la salida del alumno de la de JUnit — si el reporte de JUnit va a un archivo XML en `/work` y se emite al final, la truncada del alumno no lo pisa.
2. **Contar bytes en streaming en el sidecar.** `GET /logs?follow=1&stdout=1&stderr=1`, contar el payload demultiplexado, y al pasar N hacer `POST /kill` y devolver un veredicto explícito `SALIDA_EXCEDIDA`. Determinístico y reportable, que es lo que le importa a un alumno que reclama una nota.
3. **`max-size` como backstop del disco del host**, no como control de veredicto. Con la capa 1 en su lugar, nunca se debería llegar.

Notas:

- `--memory` **no** frena un `while(true) System.out.println(...)`: los bytes salen del contenedor, no se acumulan adentro.
- El `tmpfs` sí tiene tope de tamaño, y sus páginas se cobran al cgroup de memoria del contenedor. Así que `--memory` + `size` del tmpfs acotan juntos la escritura a disco *interna*. Eso les cubre la bomba de descompresión del tar (§7.3).
- Fijen `--pids-limit` bajo. Un fork bomb en Java es raro pero `Runtime.exec` existe.

---

## 5. Pregunta 5 — trampas del demultiplexado

Las tres que ustedes ya tienen (frames partidos, half-close, TTY) son las correctas. Estas son las que faltan, ordenadas por gravedad:

### 5.1 El largo es un `uint32` sin tope

Un demuxer ingenuo hace `new byte[N]` con N sacado del header. N puede valer hasta 4 GiB. En su caso el header viene del daemon, no del atacante, así que no es explotable — **pero es la clase de bug que un evaluador va a buscar** y la defensa correcta es una línea: si `N > MAX_FRAME` (pongan 1 MiB), aborten la conexión con error, no intenten reservar. Escríbanlo y menciónenlo; es gratis y demuestra que entendieron dónde estaba el riesgo.

### 5.2 Frames de largo cero y tipo desconocido

- **N = 0 es posible.** Si su bucle no lo maneja, o entra en loop infinito o se cuelga esperando. Traten `N=0` como "frame válido, payload vacío, seguir".
- **Tipo 0 = `stdin`**, documentado en la API como "se escribe en stdout". En la práctica no lo van a ver en la salida de `logs`/`attach`, pero no lo silencien: cualquier tipo distinto de 1 o 2 debería ser **error fatal**, no "asumir stdout". Adivinar es cómo se corrompe un veredicto en silencio.

### 5.3 El `Content-Type` distingue TTY de no-TTY

Su briefing dice que la respuesta trae `application/vnd.docker.raw-stream`. En las versiones modernas de la API eso es lo que manda **con TTY**; **sin TTY** manda `application/vnd.docker.multiplexed-stream`. Úsenlo: verificar el `Content-Type` antes de demultiplexar es un assert de dos líneas que convierte "TTY mal configurado" de *corrupción silenciosa de la salida* en *error inmediato y claro*. Dado que su spec es hardcodeada nunca debería fallar, y por eso mismo es el lugar perfecto para un assert.

### 5.4 El `head` de Node

Si van por Node y usan el evento `'upgrade'`, la firma es `(res, socket, head)`. **`head` es un Buffer que puede ya contener los primeros bytes del stream**, leídos por el parser HTTP junto con los headers. Si lo ignoran y arrancan a leer del socket, se comen el primer frame. Es un bug clásico, intermitente (depende del tamaño de los paquetes) y muy difícil de encontrar después. Hay que meter `head` en el buffer del demuxer antes de leer nada del socket.

### 5.5 Una línea puede cruzar dos frames

Docker enmarca por escritura, no por línea. Una sola línea de salida puede llegar partida en dos frames del mismo tipo, y dos líneas pueden venir en un frame. No parseen línea por línea *dentro* de un frame: acumulen todo el stdout, y recién ahí partan por `\n`.

---

## 6. Problema B — el tar hostil

### 6.1 Pregunta 6 — ¿están cubiertos con GNU tar?

**Para su caso concreto —una sola extracción, en un directorio vacío y fresco, como usuario no-root, con el resto del filesystem de solo lectura— sí, y se puede fundamentar.** Pero la fundamentación buena no es "tar es seguro". Es otra, y es más fuerte.

**Lo que GNU tar sí garantiza, con fuente:**

- **`..` en el nombre del miembro:** GNU tar lo rechaza. Existe un mecanismo de protección documentado como *"Member name contains '..'"*, y el NVD lo nombra explícitamente al describir qué mecanismo se bypassea en [CVE-2025-45582](https://nvd.nist.gov/vuln/detail/CVE-2025-45582). Su fila de "path traversal" está cubierta por tar, además de por la estructura.
- **Symlink pisado por archivo regular (la que no probaron):** el comportamiento por defecto de GNU tar es **quitar la entrada existente antes de extraer**, y crear el archivo nuevo con `O_CREAT|O_EXCL`, con lo cual la escritura no sale por el symlink. El manual lo dice explícitamente para el caso de directorios (`--keep-directory-symlink` existe justamente para **desactivar** ese borrado: *"por defecto tar primero elimina el symlink y después extrae el directorio"*), y la semántica de unlink-antes-de-crear para archivos regulares está discutida en la lista de busybox como la propiedad que hace que un symlink a `/etc/passwd` en un tar no sea explotable ([hilo busybox, 2018](https://lists.busybox.net/pipermail/busybox/2018-February/086257.html)).
- **`--no-same-owner` es correcto pero no hace lo que ustedes creen.** Protege contra restaurar owner/setuid; **no** protege contra symlinks ni hard links. Además, como no-root ya es el comportamiento por defecto. Ponerlo igual es sano (explícito > implícito), pero no lo listen como la defensa contra el symlink, porque no lo es. Eso lo van a repreguntar.

**Flags que sí conviene agregar:**

```
tar -x --no-same-owner --no-same-permissions --no-overwrite-dir \
    -C /work -f -
```

Y sobre todo, **NO** pasar `-P` / `--absolute-names`: eso desactiva el stripping del `/` inicial y la protección de `..`. Es el único flag que puede romperles todo.

**La trampa grande: `tar` en Alpine no es GNU tar.** Es busybox, con chequeos distintos y más débiles. Si su imagen base es Alpine, todo el razonamiento de arriba no aplica. Instalen GNU tar explícitamente (`apk add tar`) o usen una base con GNU tar, y **verifiquen con `tar --version` en el build**.

### 6.2 El argumento que sí sirve ante un tribunal

No digan "GNU tar nos protege". Digan esto:

> Tenemos **dos** defensas independientes. La primera es que GNU tar rechaza `..` y hace unlink antes de crear, con lo que ni el traversal ni el symlink funcionan. La segunda, y la que realmente sostiene la afirmación, es que **aunque tar estuviera equivocado, no hay dónde escribir**: el rootfs es read-only, el proceso no es root, y el único punto escribible es un `tmpfs` que se destruye con el contenedor. Un traversal exitoso a `/opt/junit/junit.jar` falla en el `write`, no en el chequeo de tar.

Eso convierte un "creemos" en un argumento estructural. Y es verificable: el test es intentar el traversal y mostrar el `EROFS`.

### 6.3 El CVE que tienen que conocer: CVE-2025-45582

Es el que les van a tirar si el tribunal googlea. GNU tar hasta 1.35 permite sobrescribir archivos con un proceso de **dos pasos**: un primer archivo trae un symlink `x -> ../../../home/victima/.ssh`, y un segundo archivo trae `x/authorized_keys`. La extracción sigue el symlink. Bypassea justamente la protección de `"Member name contains '..'"`.

**Por qué no les aplica**, y es la respuesta que quieren tener lista: requiere **dos extracciones en el mismo directorio**. Ustedes extraen exactamente una vez, en un `tmpfs` vacío que muere con el contenedor. El NVD incluso señala que el manual de GNU tar recomienda un directorio vacío por cada `tar xf`, y que el problema aparece cuando terceros aconsejan lo contrario.

Esto les da una regla operativa que vale la pena escribir en el código: **un contenedor por entrega, sin reutilización de `/work`, nunca**. Si alguna vez optimizan reutilizando contenedores para bajar el arranque de la JVM, este CVE se les activa. Dejen el comentario en el código.

### 6.4 Corrección: el symlink de Judge0 no es lo que dice el briefing

Esto es importante y les cambia dónde tienen que mirar.

**CVE-2024-28185 no es un ataque de extracción de tar.** El vector real: Judge0 escribía un `run_script` **dentro del directorio del sandbox**, que es un directorio cuyo contenido el atacante controla. El atacante dejaba ahí un symlink con ese nombre, y la escritura del proceso privilegiado salía por el otro extremo. El patch fue cambiar el usuario Unix bajo el que corría el proceso, y [Tanto Security lo bypasseó](https://tantosec.com/blog/judge0/) con `chown` sobre un symlink (CVE-2024-28189), que es literalmente el mismo bug otra vez.

O sea: **el patrón no es "tar extrae un symlink". El patrón es "un proceso confiable escribe en un directorio cuyo contenido controla el atacante".**

**Y ese patrón sí les aplica.** Pregúntense: después de que el código del alumno corrió, ¿algo escribe en `/work`? Si el entrypoint hace algo como

```sh
java ... > /work/salida.txt
# o
junit-console --reports-dir=/work/reports
```

el alumno pudo haber creado `/work/salida.txt` como symlink desde su propio código Java (`Files.createSymbolicLink`) antes de terminar. En su caso el daño está muy acotado —no-root, rootfs read-only, tmpfs— así que probablemente no llegue a nada. Pero:

- Si el reporte de JUnit sale por un archivo y no por `stdout`, **revísenlo**.
- La mitigación es trivial y es la buena: **el reporte va por `stdout`, nunca por un archivo en el directorio escribible por el alumno.** Su diseño actual (reporte por stdout) ya está bien; lo que hay que hacer es *saber que está bien por esta razón* y no cambiarlo sin darse cuenta.

Corregir esto en el briefing les suma: pasan de citar mal un CVE a mostrar que entendieron la clase de bug y que la buscaron en su propio diseño.

### 6.5 Pregunta 7 — ¿tar del sistema, o extraer en código?

**Tar del sistema, adentro del contenedor desechable. No extraigan en el sidecar.**

El razonamiento es el mismo con el que eligieron el ejecutor sobre el proxy, y conviene decirlo así de explícito: extraer el tar en el sidecar mete **parseo de input influido por el atacante dentro del componente privilegiado**, que es exactamente lo que el diseño existe para evitar. Sería incoherente con su propia tesis.

Si quieren una segunda capa (y les recomiendo que sí), que sea un **chequeo estructural de rechazo**, no una extracción:

```
por cada entrada del tar:
  typeflag debe ser REGTYPE ('0' o '\0')     — nada de symlink, hardlink, device, fifo
  nombre coincide con ^[A-Za-z0-9_][A-Za-z0-9_.\-/]{0,127}$
  ningún componente del path es '.' ni '..'
  no empieza con '/'
  profundidad <= 4
  tamaño <= 256 KiB
  cantidad total de entradas <= 64
  tamaño total descomprimido <= 2 MiB
si algo no cumple: RECHAZAR el bundle entero. No sanear.
```

Dos reglas que valen para el tribunal:

- **Rechazar, nunca sanear.** Sanear (quitar el `..`, resolver el path) es donde viven los bugs de parseo — es el mismo error de clase que los tres CVEs de AuthZ que ustedes ya identificaron. Un allowlist de nombres es un regex; un saneador es un parser.
- **Ese chequeo va en el worker o en el sidecar, pero es defensa en profundidad, no la defensa.** La defensa es tar + read-only + no-root + tmpfs. Si el chequeo es la única defensa, volvieron al modelo del proxy.

### 6.6 Pregunta 8 — construir el tar hostil para probar

Con `tarfile` de Python se arma cualquier entrada, porque `TarInfo` se construye a mano. Esto es el fixture de test que les falta:

```python
import io, tarfile

def entrada(tf, nombre, typeflag, linkname="", contenido=b""):
    ti = tarfile.TarInfo(nombre)
    ti.type = typeflag
    ti.linkname = linkname
    ti.size = len(contenido)
    ti.mode = 0o644
    tf.addfile(ti, io.BytesIO(contenido) if contenido else None)

# 1. Path traversal clásico
with tarfile.open("t1_traversal.tar", "w") as tf:
    entrada(tf, "../../opt/junit/junit.jar", tarfile.REGTYPE, contenido=b"PWNED")

# 2. Entrada symlink: primero el enlace, después el archivo con el mismo nombre
with tarfile.open("t2_symlink.tar", "w") as tf:
    entrada(tf, "Solucion.java", tarfile.SYMTYPE, linkname="/etc/passwd")
    entrada(tf, "Solucion.java", tarfile.REGTYPE, contenido=b"root::0:0::/:/bin/sh\n")

# 3. Hard link a un archivo de afuera
with tarfile.open("t3_hardlink.tar", "w") as tf:
    entrada(tf, "Solucion.java", tarfile.LNKTYPE, linkname="/opt/junit/junit.jar")

# 4. Symlink a directorio, y después escribir "adentro" (la forma CVE-2025-45582)
with tarfile.open("t4_symdir.tar", "w") as tf:
    entrada(tf, "sub", tarfile.SYMTYPE, linkname="../../opt/junit")
    entrada(tf, "sub/junit.jar", tarfile.REGTYPE, contenido=b"PWNED")

# 5. Nombre absoluto
with tarfile.open("t5_absoluto.tar", "w") as tf:
    entrada(tf, "/etc/cron.d/pwn", tarfile.REGTYPE, contenido=b"* * * * * root id\n")

# 6. Device file (requiere root para extraerse; el test es que NO se cree)
with tarfile.open("t6_device.tar", "w") as tf:
    ti = tarfile.TarInfo("disco"); ti.type = tarfile.CHRTYPE
    ti.devmajor, ti.devminor = 8, 0; ti.size = 0
    tf.addfile(ti)

# 7. Header PAX que pisa el nombre (ver §6.7 — rompe validadores ingenuos)
with tarfile.open("t7_pax.tar", "w", format=tarfile.PAX_FORMAT) as tf:
    ti = tarfile.TarInfo("inocente.java")
    ti.pax_headers = {"path": "../../opt/junit/junit.jar"}
    ti.size = 5
    tf.addfile(ti, io.BytesIO(b"PWNED"))

# 8. Nombre con newline (inyección en sus propios logs/reportes)
with tarfile.open("t8_newline.tar", "w") as tf:
    entrada(tf, "ok.java\nTests run: 99, Failures: 0", tarfile.REGTYPE, contenido=b"x")
```

**Cómo se corre el test.** No lo prueben en su máquina. Levanten el contenedor real, con la spec real, mándenle el tar por el camino real, y verifiquen dos cosas: (a) que la extracción falla o ignora la entrada, y (b) que el archivo objetivo **no cambió**. El test que vale es el que corre contra el sistema completo, porque su argumento de defensa es estructural, no de tar.

Guarden la salida. Un tribunal que ve `t2_symlink.tar → tar: Cannot open: Read-only file system` y el hash del JAR sin cambiar, no repregunta.

### 6.7 Pregunta 9 — otras variantes de entrada hostil

Más allá de traversal, symlink y hard link:

| Variante | Qué hace |
|---|---|
| **Header PAX que pisa el path** | El nombre "real" viene en un registro `path=` del header extendido, no en el campo de 100 bytes del ustar. **Un validador que lee el campo ustar y no el PAX se bypassea entero.** Es el bug más probable si escriben el validador de §6.5 a mano. Igual con las extensiones GNU `L` (longname) y `K` (longlink). |
| **Nombres absolutos** | `/etc/...`. GNU tar les quita la `/` con warning; sin `-P` no es explotable, con `-P` sí. |
| **`..` en el medio** | `a/../../b`. Cubierto por el mismo chequeo, pero pruébenlo aparte. |
| **Device / FIFO / socket** | Solo extraíbles como root. Como no son root, fallan. Pruébenlo igual: el test documenta la defensa. |
| **Setuid/setgid en el modo** | Neutralizado por no-root + `noexec` en el tmpfs. Los tres juntos. |
| **Entradas duplicadas** | La última gana. Es lo que hace funcionar el ataque de symlink. Su validador debería rechazar nombres repetidos. |
| **Sparse files (GNU `S`)** | Un archivo que declara 10 GB y ocupa 1 KB en el tar. |
| **Tar bomb por cantidad** | 100.000 entradas de 0 bytes. Acotado por su límite de cantidad de entradas. |
| **Bomba de descompresión** | Solo si aceptan `.tar.gz`. 1 KB → 10 GB. Como `/work` es `tmpfs`, eso es RAM, **y las páginas del tmpfs se cobran al cgroup de memoria del contenedor** — o sea que `--memory` y el `size` del tmpfs lo acotan juntos. Es un buen ejemplo para la defensa: una restricción puesta por otro motivo tapando un vector distinto. |
| **Nombres con bytes raros** | `\n`, escapes ANSI, UTF-8 inválido. No rompen el filesystem: rompen **sus logs y su reporte**. Un nombre de archivo que contiene `Tests run: 99, Failures: 0` puede ensuciar la salida que ustedes parsean para armar el veredicto. Dado que un veredicto equivocado tiene consecuencia académica, esto no es cosmético. |

La última fila conecta con algo más grande: **su parser del reporte de JUnit consume una salida en la que el alumno puede escribir arbitrariamente** (`System.out.println("Tests run: 10, Failures: 0")`). Eso es un vector de falsificación de veredicto que no pasa por ninguna de las defensas de aislamiento. La mitigación es no parsear texto: usar el XML de JUnit (`--reports-dir`) o el exit code del Console Launcher, y emitirlo por un canal que el alumno no comparta. Si el reporte y la salida del alumno viajan por el mismo `stdout`, hace falta un delimitador que el alumno no pueda producir — un nonce aleatorio por ejecución, generado por el sidecar. **Esto probablemente sea el hallazgo más importante de todo este documento para su nota**, porque ataca directo la amenaza que ustedes mismos definieron como la que importa.

---

## 7. Problema C — el lenguaje

### 7.1 Pregunta 10 — ¿es cierto lo de `HttpClient`?

**Sí, es correcto, y sigue siéndolo en Java 21 y 25.**

- `java.net.http.HttpClient` no soporta Unix domain sockets. La funcionalidad está en desarrollo bajo [JDK-8377806, "HTTP over Unix Domain Sockets"](https://bugs.openjdk.org/browse/JDK-8377806), que agrega una opción `HttpOption.ALT_TRANSPORT_ADDRESS` que toma un `UnixDomainSocketAddress`. Los ejemplos del issue usan una imagen `jdk:27`, o sea que apunta a una release futura. No está en 21 ni en 25.
- `UnixDomainSocketAddress` existe desde Java 16 ([JEP 380](https://inside.java/2021/02/03/jep380-unix-domain-sockets-channels/)), pero da un `SocketChannel`, no un `java.net.Socket`. Los adaptadores de socket no están soportados para canales AF_UNIX, y por eso las librerías construidas sobre `Socket` o sobre el path TCP de Netty no lo heredan gratis. *(Verificable en dos líneas: `SocketChannel.open(StandardProtocolFamily.UNIX).socket()`.)*
- El ecosistema tampoco lo resolvió limpio. Apache HttpClient **no soporta oficialmente** el esquema `unix` ([HTTPCLIENT-2348](https://issues.apache.org/jira/browse/HTTPCLIENT-2348)); `docker-java` lo hacía con API deprecada y **se rompió** al salir httpclient5 5.4 ([docker-java#2363](https://github.com/docker-java/docker-java/issues/2363)). Ese es un dato duro y citable para su decisión, mucho mejor que "parece frágil".

### 7.2 Pregunta 11 — si fuera en Java, ¿cuál es la forma menos mala?

**Escribir el HTTP a mano sobre `SocketChannel`, y usar Jackson para el JSON.**

Con el rediseño de §1.1, lo que tienen que implementar es chico:

- armar request line + headers + body, escribir al channel;
- leer status line + headers hasta `\r\n\r\n`;
- decodificar `Content-Length` o `chunked`;
- para el attach de escritura: mandar `Upgrade: tcp` / `Connection: Upgrade`, leer el `101`, y a partir de ahí escribir bytes.

Son del orden de **200-250 líneas**, todas leíbles, todas testeables sin Docker (contra un `ServerSocketChannel` de mentira). Y son 200 líneas de un protocolo que ustedes **ya entienden** después de haber escrito el briefing.

Sobre las dependencias, un matiz que les conviene para la defensa: **"cero dependencias" no es el criterio correcto, y perseguirlo los puede llevar a peor código.** El criterio correcto es *qué parsea bytes controlados por el atacante*. En su diseño:

- El JSON de la spec lo **serializan** ustedes, y lo consume el daemon. Jackson serializando un record inmutable no es superficie de ataque.
- El JSON que **parsean** viene del daemon, que es confiable (es su TCB de todos modos).
- El único input hostil es el tar, y ese no lo toca el sidecar (§6.5).

Con eso, Jackson —que Spring Boot ya les trae— es aceptable y se puede defender. Escribir un parser de JSON a mano para evitar una dependencia sería *empeorar* la seguridad, y conviene decirlo así.

Sobre `docker-java`: **no.** No por gusto, sino por tres razones citables: (a) el árbol de dependencias es grande y no auditable por un equipo de 2º año; (b) modela la API entera de Docker, o sea que trae precisamente la superficie que el diseño quiere eliminar — tendrían un objeto con un setter `withPrivileged(true)` en el mismo proceso que tiene el socket; (c) su transporte UDS es la parte frágil, con historial de rotura documentado (§7.1). El punto (b) es el bueno: contradice la tesis del componente.

### 7.3 Pregunta 12 — legibilidad vs adecuación técnica, ante un tribunal

**Pesa más que el equipo pueda leerlo, y eso no es una concesión: es un requisito de seguridad con respaldo bibliográfico.**

La cita que quieren es **Saltzer & Schroeder (1975)**, *"The Protection of Information in Computer Systems"*, Proc. IEEE 63(9). Dos de sus ocho principios de diseño les aplican directo:

- **Economía de mecanismo:** el diseño tiene que ser lo más chico y simple posible, porque los errores que crean caminos de acceso no deseados **no se manifiestan durante el uso normal** — solo aparecen por inspección línea por línea, y esa inspección solo es viable si el diseño es chico. Es literalmente su criterio 2 ("líneas de código propio"), formulado hace 50 años por la referencia canónica del área.
- **Mínimo privilegio:** el sidecar es lo único con el socket.

Con eso, el argumento ante el tribunal se formula así, y no como preferencia:

> El componente es un control de seguridad. Su corrección no se establece por testing —los caminos de acceso no deseados no se ejercitan en el uso normal— sino por inspección. Por lo tanto, la propiedad "el equipo puede inspeccionarlo" no es una comodidad: es la condición de posibilidad de afirmar que el componente es correcto. Un componente escrito en el lenguaje técnicamente óptimo pero que nadie del equipo puede revisar es un componente cuya corrección no podemos afirmar. Elegimos el lenguaje que minimiza el costo de la inspección.

Y ahora la parte incómoda, porque un buen tribunal la va a ver: **ese argumento solo vale si efectivamente inspeccionan.** "Podemos leerlo" es una hipótesis; "lo leímos" es evidencia. Conviértanlo en un artefacto: una revisión línea por línea del sidecar hecha por dos personas que no lo escribieron, con las observaciones anotadas, versionada en el repo. Eso es barato (son 250 líneas), es exactamente lo que la cita pide, y es infinitamente más fuerte que la afirmación.

**Mi recomendación concreta: Java.** Con el rediseño de §1.1, la ventaja de Node era casi toda el hijack bidireccional, y ese desaparece. Lo que queda a favor de Java: el equipo lo sabe, el resto del TPI lo usa (mismo build, mismo CI, misma cultura de revisión), y el proceso de revisión que acabo de recomendar es viable en Java y no lo es en un lenguaje que el equipo lee con esfuerzo.

**Pero sean honestos sobre la condición:** si terminan necesitando el `attach` bidireccional completo, la ventaja de Node es real y no es chica, y en ese caso yo diría Node. Presenten la decisión así, condicionada al diseño. Un tribunal valora mucho más "elegimos X porque tomamos la decisión de diseño Y, y si hubiéramos tomado Z habríamos elegido W" que una preferencia sin bifurcación.

### 7.4 Pregunta 13 — ¿hay una referencia pública que mirar?

**No encontré un proyecto público que sea exactamente esto** (un sidecar HTTP que posee `docker.sock` y expone `POST /ejecutar` con la spec hardcodeada). Lo digo derecho porque ustedes pidieron contraargumentos, no confirmación: si esperaban encontrarlo y no lo encontraron, no es porque buscaron mal.

Lo que sí hay son implementaciones del **mismo patrón** en otros niveles, y sirven como referencia de diseño:

| Referencia | Qué mirar |
|---|---|
| **`isolate`** (ioi/isolate) — el sandbox de la IOI, y el que usa Judge0 por dentro | Un binario setuid chico con una CLI **fija y angosta**, invocado por un juez sin privilegios. Es su diseño exacto, un nivel más abajo. Y viene del mismo dominio: juzgar código de estudiantes. |
| **El proceso *broker* de Chromium** | El renderer no abre archivos ni puertos: se los pide al broker por un IPC acotado, y el broker evalúa la política. Documentación pública y abundante. |
| **El *gofer* de gVisor** | El sandbox no tiene acceso al filesystem; un proceso aparte hace las operaciones de archivo por un protocolo angosto. |
| **`buildkitd`** | En vez de darle el socket de Docker al CI para que buildee, se expone una API de build. Es la misma decisión "API propia acotada > socket del daemon", en producción y a escala. |
| **El *jailer* de Firecracker** | Helper de privilegio mínimo, separado del proceso de la VM. |

Y la observación que les conviene hacer en la defensa: **el proxy existe como imagen reutilizable y el ejecutor no, y eso no es casualidad.** El proxy es genérico —sirve para Traefik, Portainer, Watchtower— y por eso alguien lo puede publicar. El ejecutor es específico por definición: su valor viene de que la spec está hardcodeada, y una spec hardcodeada no es reutilizable. La popularidad relativa del proxy mide *empaquetabilidad*, no seguridad. Es un buen momento para mostrar que entienden por qué la industria tiene el artefacto menos seguro más a mano.

---

## 8. Diseño general

### 8.1 Pregunta 14 — ¿es un patrón reconocido?

Sí, y tiene **dos** nombres según el eje. Conviene usar los dos, porque cubren cosas distintas:

**Por la estructura: separación de privilegios con un monitor.** La referencia canónica es Provos, Friedl & Honeyman, *"Preventing Privilege Escalation"*, 12th USENIX Security Symposium, 2003 ([texto completo](https://www.usenix.org/legacy/event/sec03/tech/full_papers/provos_et_al/provos_et_al.pdf)). Es el paper de la privsep de OpenSSH. La estructura es idéntica a la suya: un proceso privilegiado (monitor / sidecar) que atiende un **conjunto fijo de pedidos**, y un proceso sin privilegios que procesa la entrada hostil (worker / contenedor del alumno). El punto que ustedes hacen sobre "no acepta una spec, así que no tiene nada que parsear" es exactamente la propiedad que hace funcionar a la privsep: el monitor no ejecuta pedidos arbitrarios, ejecuta un menú.

En vocabulario de sandboxing lo mismo se llama **broker** (Chromium). Y "sidecar" es correcto para la **forma de despliegue**, pero no nombra la propiedad de seguridad — vale la pena que lo distingan en la defensa: *sidecar* es dónde corre, *privilege separation* es por qué es seguro.

**Por la razón: evitar un parser differential.** Esta es la que les falta, y es la buena. Su frase —"el filtro y el daemon no interpretan el mismo byte stream"— es la definición de un **parser differential** (a veces *parsing inconsistency*). Es un concepto establecido, con literatura propia bajo el nombre **LangSec** (language-theoretic security; Sassaman, Patterson, Bratus, Shubina). Los tres CVEs que ustedes listaron son tres instancias del mismo fenómeno, y decirlo con el término técnico convierte una observación empírica ("falló tres veces en ocho años") en una predicción estructural ("va a volver a fallar, porque la clase de bug es intrínseca a filtrar un stream que otro reinterpreta").

Dato que refuerza el argumento: **el fix de CVE-2026-34040 es rechazar cuerpos de más de 4 MiB.** O sea, el arreglo del segundo intento de arreglar el problema de tamaño de cuerpo es... otro límite de tamaño. No un cambio estructural. Es la mejor evidencia posible de que la clase de bug no está cerrada. Úsenlo.

### 8.2 Corrección sobre los CVEs de runc

Su afirmación —"esa familia requiere que el atacante controle la spec"— es correcta pero la razón precisa importa, porque la razón es lo que les da la cobertura.

Las tres vulnerabilidades de noviembre de 2025 ([CVE-2025-31133, CVE-2025-52565, CVE-2025-52881](https://github.com/opencontainers/runc/security/advisories/GHSA-9493-h29p-rfm2)) requieren, según el análisis de Sysdig, **la capacidad de arrancar contenedores con configuraciones de montaje propias**, y el vector de entrega más probable son imágenes y Dockerfiles no confiables.

En su caso se cierra por **dos** razones, no una:

1. **La spec es de ustedes.** Cierra la parte de configuración de montajes.
2. **La imagen también es de ustedes.** CVE-2025-31133 necesita reemplazar el `/dev/null` del rootfs por un symlink *antes* de que runc lo bind-montee. Eso requiere controlar el contenido de la imagen, no solo la spec. El alumno no puede.

Y un tercer punto que les conviene: **CVE-2025-52565 afecta a contenedores que asignan consola**, o sea `Tty: true`. Ustedes usan `Tty: false` por el tema del multiplexado. Es decir, una decisión que tomaron por razones de protocolo también les cierra un CVE. Eso es exactamente el tipo de cosa que conviene señalar en una defensa, porque muestra que el diseño tiene coherencia y no es una suma de parches.

### 8.3 Pregunta 15 — qué les criticaría alguien con experiencia

Esto es lo que pidieron, así que va sin suavizar.

**a) ¿Quién más puede hablarle al sidecar?** Es la crítica más fuerte y la más fácil de arreglar. Un endpoint HTTP sin autenticación en una red de Docker es alcanzable por **todos** los contenedores de esa red. Si el sidecar termina en la bridge por defecto, cualquier contenedor de la máquina —incluidos los de los otros 11 grupos, si comparten host— puede pedirle ejecuciones. Todo el argumento del "radio de explosión" se cae si el sidecar es alcanzable desde más lugares que el worker. Mitigación: red interna dedicada solo worker+sidecar, o mejor, que el sidecar escuche en un **Unix socket en un volumen compartido** con el worker, no en TCP. Ese es el "cero red" que hace el argumento incontestable, y es prácticamente el mismo código.

**b) El sidecar no protege el veredicto, y el veredicto es lo que ustedes dijeron que importa.** Ustedes escribieron que un veredicto equivocado tiene consecuencia académica real. El sidecar mitiga *worker comprometido → root en el host*. No mitiga *worker comprometido → veredictos falsos*. Un worker comprometido puede mandar bundles con tests truchos y aprobar a cualquiera, y el sidecar los va a ejecutar felizmente porque no sabe qué desafío es. Su modelo de amenaza y su control principal apuntan a cosas distintas. **Díganlo ustedes antes de que se los digan**, y con la conclusión correcta: el sidecar protege el **host**, y la integridad del veredicto necesita un control distinto (autenticidad de los tests, por ejemplo que el bundle traiga los tests firmados o referenciados por hash desde el servicio del profesor, no provistos por el worker).

**c) DoS.** "Comprometer el worker = pedir ejecuciones" suena inofensivo hasta que son 10.000 ejecuciones por segundo. Sin límite de concurrencia y cola en el sidecar, un worker comprometido tumba el host, y con 120 sesiones concurrentes en el pico probablemente lo tumbe **sin** estar comprometido. El límite de concurrencia es parte del control de seguridad, no una optimización de performance. Pónganlo en el sidecar (que es quien sabe cuántos contenedores hay vivos), no en el worker.

**d) Contenedores huérfanos.** Si el sidecar muere entre `start` y `delete`, el contenedor queda. Con 120 sesiones eso se acumula rápido. Hace falta o `AutoRemove: true` en la spec, o un barrido por label al arrancar (`GET /containers/json?filters={"label":["sandbox=1"]}` y borrar lo viejo). Un evaluador con experiencia operativa pregunta esto siempre.

**e) La crítica más dura: "código de seguridad escrito por estudiantes de 2º año tiene más probabilidad de bug que la imagen de terceros que descartaron."** Es un argumento serio y hay que responderlo, no esquivarlo. La respuesta correcta no es "nuestro código es mejor", es: *las dos superficies difieren en clase, no en cantidad*. Un bug en `tecnativa` bypassea el control y da root. Un bug en el ejecutor, con la spec hardcodeada, ¿qué da? Háganse esa pregunta explícitamente y respóndanla en el informe — si la respuesta es "un contenedor mal formado que igual no tiene red ni privilegios", ganaron el argumento. Si hay algún camino en el que un bug del ejecutor produzca un contenedor privilegiado, encuéntrenlo ahora.

**f) El sidecar no reduce la TCB.** Siguen confiando en el daemon, en runc y en el kernel exactamente igual que antes. Y `--network none` sigue siendo la defensa central contra el alumno, no el sidecar. El sidecar solo mueve un privilegio de lugar. Es una mejora real, pero es más chica de lo que el énfasis del briefing sugiere, y conviene que la proporción la pongan ustedes.

**g) La spec hardcodeada tiene un costo que van a sentir.** Cambiar el límite de memoria por tipo de desafío requiere recompilar y redeployar el sidecar. Está bien, es el precio del diseño — pero si en algún momento agregan `POST /ejecutar?memoria=512m`, **acaban de reinventar el proxy**, con el mismo problema y sin ninguno de los ocho años de parches. Escríbanlo como una restricción explícita del componente, con esa justificación, para que nadie lo "mejore" después.

**h) Faltan límites en la spec.** Tienen memoria, CPU y pids. Faltan `--ulimit nofile`, `--ulimit fsize`, el `size` explícito del tmpfs, y `--ulimit nproc`. Ninguno es crítico dado el resto, pero un evaluador que revisa la spec línea por línea los va a notar.

---

## 9. Resumen de acciones

| # | Acción | Sección |
|---|---|---|
| 1 | Rediseñar a: attach solo-escritura + `StdinOnce` + `wait` + `logs`. Elimina el demux en vivo y el half-close | §1.1 |
| 2 | Verificar que el reporte de veredicto no viaje por un canal que el alumno pueda escribir o falsificar (nonce, o XML por canal separado) | §6.4, §6.7 |
| 3 | Poner el sidecar en un Unix socket compartido, o en una red interna dedicada | §8.3a |
| 4 | Límite de concurrencia + cola en el sidecar | §8.3c |
| 5 | Fijar `LogConfig` explícitamente en la spec; no depender del default del daemon | §3 |
| 6 | Cortar la salida en el origen con `head -c`, no con `max-size` | §4 |
| 7 | Verificar que el `tar` de la imagen sea GNU tar y no busybox | §6.1 |
| 8 | Escribir los 8 tests de tar hostil y guardar la salida como evidencia | §6.6 |
| 9 | Revisión línea por línea del sidecar por 2 personas que no lo escribieron, versionada | §7.3 |
| 10 | Corregir en el briefing: Judge0, el `Content-Type`, y la razón de los CVEs de runc | §6.4, §5.3, §8.2 |

## 10. Fuentes

- Provos, Friedl & Honeyman, *Preventing Privilege Escalation*, USENIX Security 2003 — https://www.usenix.org/legacy/event/sec03/tech/full_papers/provos_et_al/provos_et_al.pdf
- Saltzer & Schroeder, *The Protection of Information in Computer Systems*, Proc. IEEE 63(9), 1975 — economía de mecanismo, mínimo privilegio
- JDK-8377806, *HTTP over Unix Domain Sockets* — https://bugs.openjdk.org/browse/JDK-8377806
- JEP 380, *Unix domain socket channels* — https://inside.java/2021/02/03/jep380-unix-domain-sockets-channels/
- HTTPCLIENT-2348 / docker-java#2363 — https://github.com/docker-java/docker-java/issues/2363
- moby/moby#43015 — `docker cp` con rootfs read-only y volumen — https://github.com/moby/moby/issues/43015
- moby/moby#50462 — `stdcopy` produce y lee el stream multiplexado de `attach` **y** `logs` — https://github.com/moby/moby/pull/50462
- CVE-2026-34040 — bypass de AuthZ por cuerpo >1 MB; fix en Docker Engine 29.3.1 rechazando >4 MiB — https://scout.docker.com/vulnerabilities/id/CVE-2026-34040
- CVE-2025-45582 — GNU tar ≤1.35, traversal en dos pasos vía symlink; nombra la protección `"Member name contains '..'"` — https://nvd.nist.gov/vuln/detail/CVE-2025-45582
- GNU tar manual, *Security* y *Option Summary* — https://www.gnu.org/software/tar/manual/html_section/Security.html
- runc GHSA-9493-h29p-rfm2 / GHSA-qw9x-cqr3-wc7r (nov. 2025) — https://github.com/opencontainers/runc/security/advisories/GHSA-9493-h29p-rfm2
- Sysdig, análisis de los CVEs de runc — https://www.sysdig.com/blog/runc-container-escape-vulnerabilities
- Tanto Security, *Judge0 Sandbox Escape* (CVE-2024-28185 / 28189) — https://tantosec.com/blog/judge0/
