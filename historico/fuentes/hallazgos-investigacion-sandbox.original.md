# Hallazgos de investigación externa — `ms-sandbox` (Tema 06)

> Respuesta al briefing del 27 de agosto de 2026. Cada sección responde una pregunta de §8 del briefing.
> Marco cada hallazgo con **[confirma]**, **[desafía]** o **[matiza]** respecto de las decisiones de §6.
> Las fuentes están linkeadas para que se puedan citar en la defensa.

---

## Resumen ejecutivo: el hallazgo transversal

Busqué incidentes reales en plataformas que hacen exactamente esto. Encontré cinco, en dos
proyectos, y **ninguno fue un exploit de kernel**:

| Proyecto | CVE | Cómo se escapó |
|---|---|---|
| Judge0 | CVE-2024-28185 (10.0) | Symlink dentro del directorio del sandbox intercepta una escritura de archivo |
| Judge0 | CVE-2024-28189 (10.0) | Bypass del parche anterior, vía `chown` sobre un symlink |
| Judge0 | CVE-2024-29021 | SSRF → Postgres interno → modificar argumentos de una ejecución encolada |
| Ares (Artemis, TUM) | CVE-2024-23683 | Subclase especial de `InvocationTargetException` |
| Ares (Artemis, TUM) | CVE-2024-23682 (8.2) | Class files del alumno en un paquete que el runner considera confiable |

**Los cinco son bugs de lógica en el código de orquestación de la propia plataforma**, no en el
aislamiento. El riesgo residual que ustedes declaran en §6 ("el kernel es compartido") es cierto pero
no es donde está la plata. El riesgo residual real es **el código de ustedes que arma el bundle, lo
inyecta y lee el reporte**.

Esto tiene una consecuencia inmediata y accionable, en §7 más abajo (el bundle por `stdin` como tar).

Y tiene una consecuencia argumentativa: en la defensa, "asumimos el riesgo de un exploit de kernel"
es una frase que suena madura pero es barata. **"Asumimos el riesgo de kernel Y auditamos
específicamente la clase de bug que tumbó a Judge0"** es otra cosa.

---

## 1. Filtrado del socket de Docker por contenido del body

**Respuesta corta: sí existe la herramienta, y sí, invertir el diseño es lo correcto — pero por una
razón mucho mejor que "no existe herramienta". [confirma, con argumento nuevo]**

### Sí existe, y es un mecanismo de primera clase de Docker

Docker tiene un framework de **authorization plugins (AuthZ)**, y la implementación de referencia es
[`opa-docker-authz`](https://github.com/open-policy-agent/opa-docker-authz) (Open Policy Agent). La
política se escribe en Rego y lee el body directamente:

```rego
package docker.authz
default allow = false
allow { not deny }
deny { input.Body.HostConfig.Privileged == true }
```

Se instala como plugin del daemon (`"authorization-plugins"` en `/etc/docker/daemon.json`) y el
daemon le manda cada request de la API para evaluación. La [documentación oficial de OPA](https://www.openpolicyagent.org/docs/docker-authorization)
lo muestra bloqueando también `SecurityOpt` (contenedores sin perfil seccomp).

O sea: el mecanismo estándar de la industria no es un proxy HAProxy, es un plugin del daemon. El
proxy tipo `tecnativa` es la versión pobre.

### Pero el mecanismo tiene un historial de fracasos que hace el argumento por ustedes

Tres bypasses en ocho años, todos contra exactamente esta idea:

**CVE-2018-16398** — Twistlock AuthZ Broker manejaba mal las expresiones regulares. Un request a
`containers/aa/pause?aaa=\/start` pasaba una política que permitía `start` pero prohibía `pause`.
*Esto es un bypass del filtrado por path — es decir, del enfoque exacto de `tecnativa/docker-socket-proxy`,
que también filtra con regex sobre el path.*
→ [NVD](https://nvd.nist.gov/vuln/detail/CVE-2018-16398)

**CVE-2024-41110** (CVSS crítico) — Un request con `Content-Length: 0` hace que el daemon reenvíe el
request al plugin **sin body**. El plugin no ve nada, aprueba. El daemon procesa el body completo.
Lo notable es la historia: se descubrió en 2018, se parcheó en Docker Engine 18.09.1 en enero de 2019,
**el fix no se propagó a 19.03 ni posteriores**, y la regresión recién se detectó en abril de 2024 y se
parcheó en julio de 2024 (docker-ce 27.1.1). Cinco años de ventana.
→ [NVD](https://nvd.nist.gov/vuln/detail/cve-2024-41110) · [The Register](https://www.theregister.com/2024/07/25/5yo_docker_vulnerability/)

**CVE-2026-34040** (abril de 2026, hace cuatro meses) — Misma clase, distinto truco: si el body supera
1 MB, el middleware de Docker lo descarta **antes** de pasárselo al plugin. El plugin ve un body vacío
y aprueba; el daemon crea el contenedor privilegiado. Cyera lo confirmó contra Docker Engine 27.5.1.
Afecta a **todos** los plugins por igual — OPA, Casbin, Prisma Cloud, custom — porque el bug está en el
middleware de Docker, no en el plugin. Se parcheó en 29.3.1 subiendo el límite a 4 MB y rechazando
(no truncando) los requests más grandes.
→ [Cyera](https://www.cyera.com/research/one-megabyte-to-root-how-a-size-check-broke-dockers-last-line-of-defense) · [SC Media](https://www.scworld.com/news/docker-authorization-bypass-flaw-enables-dangerous-container-creation)

Existe además una herramienta reciente que sí filtra por path del body con sintaxis de punto
(`body.HostConfig.Privileged`), [`docker-proxy` de Dragos](https://www.dragos.cc/blog/docker-proxy-guard-the-socket).
Es un binario único, sin dependencias, y hace exactamente lo que ustedes preguntaban. Pero es un
proyecto chico y reciente: no calificaría como "maduro" y no lo pondría en la defensa como opción
seria.

### El argumento para la defensa

No es "no existe herramienta". Es más fuerte:

> Filtrar el body es un **problema de parseo sobre input influido por el atacante**, y la industria lo
> falló tres veces en ocho años, siempre por la misma razón: el filtro y el daemon no interpretan el
> mismo byte stream. Un sidecar que **no acepta una spec de contenedor** no tiene ese problema, porque
> no tiene nada que parsear.

Es decir: la inversión que ustedes intuyeron no es un workaround por falta de herramientas, es la
mitigación estructural de una clase de vulnerabilidad documentada. El sidecar deja de exponer la API
de Docker y expone un verbo del dominio:

```
POST /ejecutar   { bundle }   →   { reporte }
```

La spec del contenedor (`--network none`, `--read-only`, límites, etc.) vive hardcodeada adentro del
sidecar. El worker no puede pedir `Privileged: true` porque no hay ningún campo donde escribirlo.

**Dato adicional para el argumento:** el README de `tecnativa/docker-socket-proxy` señala que el
propio proxy necesita `--privileged` en algunos entornos SELinux/AppArmor. Un componente de seguridad
que corre privilegiado para poder proteger es incómodo de defender.

---

## 2. Cómo lo resuelven los que hacen esto en serio

**Respuesta corta: nadie serio hace `docker run` por ejecución en el camino crítico. Usan Docker como
capa de empaquetado y algo más liviano adentro. [desafía parcialmente]**

### Judge0

Arquitectura real: Rails + PostgreSQL + Resque (cola). El worker levanta el job y usa el binario
**`isolate`** — el sandbox del sistema CMS de la Olimpiada Internacional de Informática, que usa
namespaces y cgroups de Linux del mismo modo que Docker. Judge0 corre *dentro de* un contenedor
Docker; `isolate` hace el aislamiento *por ejecución*.
→ [judge0/judge0](https://github.com/judge0/judge0) · [judge0/compilers](https://github.com/judge0/compilers)

**Y se lo llevaron puesto.** El [análisis de Tanto Security](https://tantosec.com/blog/judge0/) es
lectura obligatoria para el grupo. El resumen:

1. El código de Judge0 escribe un `run_script` en el directorio del sandbox. El alumno crea ahí un
   **symlink** apuntando afuera. La escritura sale del sandbox. (CVE-2024-28185)
2. El parche cambió el usuario Unix bajo el que corre Rails. El investigador lo bypasseó usando
   `chown` sobre un symlink. (CVE-2024-28189)
3. Y el remate: **el `docker-compose.yml` de Judge0 corre el contenedor con `privileged: true`**, así
   que una vez que salís de `isolate` podés montar el filesystem del host y escribir, por ejemplo, un
   cron job. Escape total.

Fijate el encadenamiento: **el bug fue de lógica, pero lo que lo convirtió en takeover del host fue el
contenedor privilegiado.** Esto valida dos decisiones de ustedes —el worker no tiene el socket, y
nada corre privilegiado— con un caso concreto y citable.

### Piston

Misma forma: `isolate` **adentro de** Docker. Una API en Node dentro del contenedor recibe los
requests y ejecuta. `isolate` aporta namespaces, chroot, múltiples usuarios sin privilegios y cgroups.
→ [engineer-man/piston](https://github.com/engineer-man/piston)

### El patrón que hay detrás

Los dos usan Docker como **entorno** (imagen con todos los compiladores, distribución, reproducibilidad)
y `isolate` como **aislamiento por ejecución**. Nadie levanta un contenedor por submission en el
camino crítico, porque a escala de contest eso no cierra.

**Esto desafía su diseño.** Pero la respuesta es buena y está en su propio briefing: 120 usuarios,
4–6 ejecuciones en paralelo. A esa escala el costo de `create` es irrelevante (sus propios números lo
confirman: el tiempo se lo lleva `javac`, no el arranque). Y `isolate` no capitaliza la unidad de
Docker. La forma de decirlo en la defensa:

> "Judge0 y Piston usan `isolate` dentro de Docker porque a escala de miles de submissions por hora el
> `docker create` por ejecución no es viable. A 120 usuarios y 4–6 ejecuciones concurrentes medimos que
> el `create` no está en el camino crítico, así que elegimos el contenedor efímero, que además nos da
> el `--network none` y el `--read-only` sin código nuestro. La misma decisión a escala de LeetCode
> sería equivocada."

Eso es exactamente lo que la cátedra quiere escuchar: una decisión con su condición de validez explícita.

### AWS Lambda y la comparación cuantitativa

Lambda usa **microVMs de Firecracker**. Ese es el escalón "escala masiva", y confirma la clasificación
en tres clases de motor: **microVM / kernel en espacio de usuario (gVisor) / contenedor OCI (runc)**.

Hay un paper comparativo de mayo de 2026 que mide esto empíricamente y les sirve muchísimo para
cuantificar el riesgo residual: *AI Code Sandboxes: A Comparative Security Study, Part 1*
([arXiv:2606.08433](https://arxiv.org/abs/2606.08433)). Datos relevantes:

| Métrica (ventana 24 meses) | runc (contenedor) | gVisor | Firecracker |
|---|---|---|---|
| CVEs de escape publicados | 4 de 4 | 0 de 3 | 2 de 2 (ambos de 2026) |
| Fugas de identidad del host (28 sondas) | 10 | 2 | 0 |
| Primitivas kernel alcanzables (de 14) | 11 | 5 | 7 |
| String del kernel del guest | idéntico al del host | sintético | propio |

El estudio también mide algo que les conviene saber: el perfil **seccomp por defecto de Docker
bloquea unas 44 syscalls de 300+** — verificado contra Docker v29.5.2, donde 361 syscalls quedan
permitidas incondicionalmente. O sea, "seccomp default" suena mucho más fuerte de lo que es.

**Cómo usarlo:** no como "deberíamos usar gVisor", sino como *cuantificación del riesgo declarado*.
"Elegimos la clase de motor con la peor postura de escape medida, y lo hacemos con conocimiento: 4 de
4 CVEs de runc en 24 meses fueron de escape. Lo compensamos con `--network none`, que corta el vector
que nos importa (alcanzar a los otros once servicios), y lo declaramos como riesgo residual."

---

## 3. WebAssembly como alternativa

**Respuesta corta: no. Y ahora tienen una razón técnica, no académica, para descartarlo. [confirma, mejor]**

Estado del ecosistema Java→WASM a mediados de 2026:

- **TeaVM** compila bytecode JVM (no fuente) a WasmGC, delegando el garbage collector al motor host.
  Se considera **listo para producción en targets de navegador** desde fines de 2025. Para targets
  WASI del lado servidor existe un fork mantenido por Fermyon, **experimental y no mergeado upstream**.
- **CheerpJ** es orientado a navegador y apunta a modernizar aplicaciones legacy tipo applet.
- **GraalVM** está construyendo soporte para emitir WASM desde `native-image`; si prospera será el
  camino recomendado, pero todavía no está.
→ [Java Code Geeks, abril 2026](https://www.javacodegeeks.com/2026/04/webassembly-in-2026-where-it-has-landed-what-wasi-0-2-changes-and-why-java-and-kotlin-developers-should-pay-attention-now.html) · [Fermyon](https://developer.fermyon.com/wasm-languages/java)

### El problema que lo mata para su caso específico

La limitación recurrente que reportan todas las fuentes: **la reflection y la carga dinámica de clases
no se traducen bien**. TeaVM y GraalVM `native-image` trabajan bajo la *closed-world assumption*: hay
que saber en tiempo de compilación qué clases existen.

Y JUnit 5 es, de punta a punta, un motor de descubrimiento y ejecución **basado en reflection**. El
`Launcher` descubre clases por escaneo, instancia por reflection, invoca métodos por reflection.
Además, en su caso el código del alumno **no se conoce en tiempo de build** — llega en runtime.

O sea: para ustedes, WASM tiene que resolver simultáneamente el peor caso de WASM (reflection y class
loading dinámico) sobre un input que por definición no está disponible en tiempo de compilación.

**Cómo decirlo en la defensa:** no es "no capitaliza la unidad de Docker". Es:

> "Evaluamos WASM. TeaVM es el compilador Java→WASM más maduro y sólo está listo para navegador; el
> soporte WASI del lado servidor es un fork experimental. Pero el bloqueante no es la madurez del
> tooling: es que WASM impone closed-world assumption y JUnit 5 es enteramente reflection, sobre
> código que recibimos en runtime. El modelo de aislamiento es más limpio, pero es incompatible con
> nuestro contrato."

Eso vale mucho más que descartarlo por el temario.

---

## 4. Superficie residual real del contenedor

**Respuesta corta: la superficie residual son dos cosas concretas y nombrables — el kernel del host
verbatim, y runc. [matiza]**

### runc tiene una clase de bug recurrente, y tiene nombre

**Races de procfs y mounts.** Históricos: CVE-2019-19921, CVE-2023-27561, CVE-2023-28642. Y el 5 de
noviembre de 2025 salieron tres a la vez, todos de la misma familia:

- **CVE-2025-31133** — runc usa un bind-mount de `/dev/null` para "enmascarar" archivos sensibles. Si
  el atacante reemplaza `/dev/null` por un symlink, runc monta un destino controlado por el atacante
  en modo lectura-escritura. → escritura en `/proc` → escape.
- **CVE-2025-52565** — Lo mismo, contra el bind-mount de `/dev/pts/$n` a `/dev/console`. El montaje
  ocurre **antes** de que se apliquen `maskedPaths` y `readonlyPaths`, así que se puede conseguir una
  copia escribible de `/proc/sysrq-trigger` o `/proc/sys/kernel/core_pattern`.
- **CVE-2025-52881** — Redirige escrituras destinadas a `/proc` hacia ubicaciones arbitrarias. **La
  advisory dice explícitamente que puede evadir AppArmor y SELinux bajo ciertas condiciones.**

→ [Advisory de runc](https://github.com/opencontainers/runc/security/advisories/GHSA-qw9x-cqr3-wc7r) · [Sysdig](https://www.sysdig.com/blog/runc-container-escape-vulnerabilities) · [Orca](https://orca.security/resources/blog/new-runc-vulnerabilities-allow-container-escape/)

### El matiz que les conviene: casi todos requieren una config maliciosa

Varios de estos exigen que el atacante controle la **spec del contenedor** (mounts, symlinks en la
config). En su diseño, el alumno nunca controla la spec — está hardcodeada en el sidecar (ver §1).

**Esto conecta las dos preguntas y es un argumento fuerte:** el sidecar-ejecutor con spec hardcoded no
sólo previene `Privileged: true`, sino que **cierra el vector de configuración maliciosa que es la
clase de bug dominante de runc**. Eso convierte una decisión que parecía defensiva en una decisión que
mitiga una familia de CVEs documentada.

### Mitigaciones que las advisories nombran y que ustedes deberían revisar

1. **User namespaces.** Las advisories de runc son explícitas: los user namespaces son de los mejores
   mecanismos de hardening contra breakouts, y el kernel aplica restricciones extra a contenedores con
   user namespace. Con `userns-remap` en el daemon, el root del contenedor deja de ser root del host.
   Nota: la mayoría de los runtimes bloquean por seccomp `unshare(CLONE_NEWUSER)` dentro del contenedor
   igual, así que habilitar user-ns para el contenedor no habilita user-ns *para el alumno*.
   **Recomendación concreta: probar `userns-remap`. Es una línea en `daemon.json` y sube un escalón real.**
2. **Perfil seccomp propio y más chico que el default.** El default deja 361 syscalls. Ustedes corren
   `javac` y `java` y nada más: podrían recortar mucho. Con `--security-opt seccomp=perfil.json`.
3. **AppArmor.** Confirmar que el perfil `docker-default` esté efectivamente aplicado (en Ubuntu se
   carga pero sólo se aplica donde se asigna explícitamente).
4. **Versión del engine.** Docker Engine 29.x bundlea runc 1.3.5, que incluye el trío de noviembre de
   2025. Si están instalando `runc` desde el archivo de Ubuntu, atención: Ubuntu Noble marcó los tres
   como *"Ignored — backport too intrusive"* en el paquete apt. Vale la pena chequear qué runc corre en
   la máquina de la demo.

### La fuga que sí existe y conviene declarar

Medido en el paper de arXiv: un contenedor runc filtra **10 de 28 identificadores del host** —
versión del kernel, modelo y microcódigo de CPU, RAM total, producto del BIOS, mapa de IRQs, y modelo y
número de serie de los discos. El string del kernel del guest es **idéntico byte a byte** al del host.

Para su modelo de amenaza (académico, sin multi-tenancy con secretos) el impacto es bajo, pero
declararlo explícitamente es exactamente el tipo de rigor que se evalúa: *"el alumno puede leer el
número de serie del disco del host; lo aceptamos porque no hay secreto ahí y cerrarlo requeriría
microVM"*.

---

## 5. Costo de `javac` y del descubrimiento de JUnit

**Respuesta corta: hay una palanca inmediata (`--select-class`) y una estructural (cachear la suite
compilada por versión). [confirma la hipótesis, con soluciones concretas]**

### Sí, se puede saltear el escaneo de classpath. Es una opción del ConsoleLauncher

El `junit-platform-console-standalone` acepta `-c` / `--select-class` (repetible) y también
`--select-method`, `--select-package`, `--select-file`. Si no pasás `--scan-classpath`, **no escanea**.
→ [Console Launcher, JUnit User Guide](https://docs.junit.org/6.0.3/running-tests/console-launcher.html)

Ustedes conocen los nombres de las clases de test: los escribió el profesor y vienen en el bundle. No
hay razón para escanear. Esto ataca directo los 1.2–2.9 s que midieron.

**Trampa documentada que se van a comer:** hay un issue conocido donde `--select-class` por sí solo no
alcanza y hay que agregar además `--include-classname` con un regex que matchee el nombre de la clase.
→ [junit-framework#2289](https://github.com/junit-team/junit-framework/issues/2289). Si prueban
`--select-class` y "no encuentra nada", es esto. Vale la pena medirlo antes de descartarlo.

**Dato de contexto:** la documentación actual de JUnit ya es de la línea **6.0.x**. Si están sobre
JUnit 5.x, vale medir si el descubrimiento mejoró.

### Precompilar y cachear la suite de tests por versión: sí, y es la palanca grande

Ustedes miden **dos invocaciones de `javac`** de 1.0–2.3 s cada una. Una es la del alumno, la otra es
la de los tests del profesor. La del profesor **es idéntica en todas las entregas de un mismo desafío**.

Y hay dos piezas de su propio sistema que hacen que esto encaje perfecto:

- T03 (`ms-desafios`) ya hace **versionado**. Hay un identificador estable por versión de desafío.
- El **extra de ustedes es "almacenamiento de artefactos de ejecución"**. Los `.class` de la suite son
  exactamente un artefacto.

Diseño: la primera ejecución de una versión de desafío compila los tests y guarda los `.class`
como artefacto indexado por `(desafioId, version)`. Las siguientes ejecuciones reciben los `.class` ya
compilados en el bundle y sólo compilan el código del alumno. **Eliminan una de las dos invocaciones
de `javac` completas.**

Bonus argumentativo: esto convierte su "extra" de un adorno en una pieza que participa del camino
crítico de rendimiento. Eso es mucho más defendible que "también guardamos los logs".

Riesgo a manejar: la invalidación de la caché. Si la clave es `(desafioId, version)` y T03 garantiza
que una versión publicada es inmutable, el problema desaparece. Verifiquen ese supuesto con T03 — si
una versión puede editarse en el lugar, la caché envenena veredictos, y ahí el bug tiene consecuencia
académica.

### El *compiler API* en un proceso caliente: no

`javax.tools.JavaCompiler` en el worker significa **compilar fuente no confiable dentro de la JVM del
worker**. `javac` no es un sandbox: procesa anotaciones, puede cargar processors del classpath, y un
bug del compilador se ejecuta en su proceso privilegiado. Rompe el modelo entero.

Si quieren un compilador caliente, tiene que estar **adentro de un contenedor**, no en el worker. Y en
ese caso ya no es "un proceso caliente", es un pool de contenedores (§6).

### La palanca más barata sigue siendo la que ya midieron

De 1 a 2 CPU: 8.5 s → 5.1 s. `javac` paraleliza. **Antes de escribir una línea de caché, subir
`--cpus`.** Y decirlo así en la defensa: "medimos, encontramos que la CPU era la palanca, y aplicamos
la solución de una línea antes que la de doscientas".

---

## 6. Pool de contenedores calientes

**Respuesta corta: el patrón existe, tiene nombre y es literalmente el de AWS Lambda. Pero sus propios
números dicen que no les conviene. [desafía la premisa]**

### El patrón: *warming pool* / *active pool*

Es la arquitectura de Lambda, descrita en las patentes de AWS: hay un **warming pool** de instancias
pre-inicializadas; cuando llega un request, la instancia se saca del warming pool y pasa al **active
pool**, se le asigna un usuario, y **una vez asignada a un usuario no puede usarse para tareas de otro
usuario** — explícitamente, para evitar el co-mingling de recursos entre usuarios.
→ [US 9,928,108](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9928108) · [US 10,303,492](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10303492)

O sea: la regla que ustedes se pusieron ("nunca reusar entre dos alumnos") es la misma que se puso AWS.
Eso es citable.

### La trampa, nombrada en la literatura de seguridad de serverless

El survey [*Serverless Computing: A Security Perspective* (arXiv:2107.03832)](https://arxiv.org/pdf/2107.03832)
lo dice directo: los contenedores calientes reducen el arranque **a costa de garantías de seguridad**.
Aunque se restauren los valores por defecto del filesystem, queda un `/tmp` escribible que persiste
entre invocaciones, y un atacante que comprometa una función puede dejar estado ahí para ataques de
larga duración. La mitigación que nombra el paper es exactamente deshabilitar el reuso.

Otras trampas prácticas: un contenedor que quedó idle puede estar en estado degradado, así que el pool
necesita health probing periódico y reemplazo. (Encaja con su patrón Health Check, con un giro
distinto: "saturado no es enfermo", pero "rancio sí".)

### La versión que sí es compatible con su regla

Pre-crear N contenedores con la spec endurecida y dejarlos **creados pero no arrancados** (o arrancados
y bloqueados esperando el `stdin`). Al llegar una ejecución, tomás uno, lo usás, lo destruís. Refill
asincrónico, fuera del camino crítico. Ahorrás el `create`, nunca reusás.

### Pero: sus propios números dicen que no vale la pena

Ustedes midieron 4–8 s de reloj total, con `javac` (1.0–2.3 s × 2) y descubrimiento (1.2–2.9 s) como
consumidores. El `create` de un contenedor no aparece en su tabla, y `java -version` adentro del
contenedor da 38–82 ms. El `create` está en el orden de los cientos de milisegundos.

**La respuesta más fuerte no es implementar el pool. Es esto:**

> "Evaluamos el pool caliente. Es el patrón de AWS Lambda —warming pool y active pool, con la regla de
> no reusar entre usuarios— y la literatura de serverless documenta su trampa: el scratch persistente
> permite ataques de larga duración. Medimos que el `create` no está en nuestro camino crítico:
> el tiempo se lo llevan `javac` y el descubrimiento de JUnit. Lo descartamos por falta de beneficio
> medido, no por complejidad."

"Medimos y no valía la pena" es una respuesta de ingeniería. "Lo implementamos porque estaba en la
lista" no lo es.

---

## 7. Cómo se fija un timeout con varianza alta

**Respuesta corta: no se fija *un* timeout. Se fijan tres relojes distintos, por fase. Esto resuelve
directamente su hallazgo de que "el camino feliz daba TIMEOUT". [desafía el diseño actual]**

### Lo que hace `isolate` (y por lo tanto Judge0 y Piston)

Tres límites separados, con semánticas distintas:

- **`--time`** — límite de **tiempo de CPU**. El manual es explícito: *el tiempo en que el SO le asigna
  el procesador a otras tareas no se cuenta*. Si se excede, el programa se mata (después de
  `--extra-time`, si está seteado).
- **`--extra-time`** — gracia después de exceder el límite de CPU, antes de matar. La ventaja
  documentada: **el tiempo de ejecución real se reporta igual**, aunque exceda levemente el límite.
- **`--wall-time`** — reloj de pared. Mide desde el inicio hasta la salida; **no se detiene cuando el
  programa pierde la CPU o espera un evento externo**. Es el backstop contra un proceso que duerme en
  vez de quemar CPU.

→ [Manual de isolate](https://www.ucw.cz/isolate/isolate.1.html)

### Y los aplican por fase, no una vez

Judge0 en su versión 1.4 tenía, **sólo para la compilación**: 5 s de CPU, 2 s de extra, 10 s de wall.
La ejecución tenía su propio presupuesto aparte, configurable por submission
(`CPU_TIME_LIMIT`, `CPU_EXTRA_TIME`, `WALL_TIME_LIMIT` en `judge0.conf`).
→ [judge0#114](https://github.com/judge0/judge0/issues/114) · [judge0.conf](https://github.com/judge0/judge0/blob/master/judge0.conf)

**Esto responde su hallazgo directamente.** Ustedes descubrieron que "compilar se comía el reloj del
alumno". La solución de la industria no es agrandar el presupuesto: es que compilación y ejecución
tengan presupuestos separados, y que el del alumno se mida en **CPU**, no en reloj de pared.

### Y responde su varianza de 1.8 s a 5.2 s

Esa varianza es contención del host: otras ejecuciones del pool compitiendo por CPU. **El tiempo de CPU
es inmune a eso por construcción.** Si el presupuesto del alumno se mide en CPU, dos corridas idénticas
dan el mismo número aunque el host esté saturado. Los falsos TIMEOUT intermitentes desaparecen porque
desaparece su causa, no porque se les agregó margen.

### Cómo lo hacen con Docker, que no les da `--time`

Docker no expone un límite de tiempo de CPU como `isolate`. Dos caminos, y les recomiendo el segundo:

1. **Leer el consumo de CPU de los cgroups** (`cpu.stat` / la API de stats del daemon) y decidir el
   veredicto en base a eso. Funciona, pero es polling y el kill sigue siendo por reloj.
2. **`RLIMIT_CPU` dentro del contenedor.** Un `ulimit -t <segundos>` en el entrypoint antes de invocar
   `java` hace que el kernel mande `SIGXCPU` al proceso al agotar el tiempo de CPU. Es el mismo
   mecanismo que usa `isolate` por debajo. Se combina con el timeout de reloj del sidecar como backstop.

Diseño resultante, y es fácilmente defendible:

| Reloj | Ámbito | Quién lo aplica | Para qué |
|---|---|---|---|
| CPU (`RLIMIT_CPU`) | sólo la fase de tests | kernel, dentro del contenedor | veredicto TIMEOUT del alumno |
| Reloj de pared, fase compilación | `javac` | sidecar | detectar compilador colgado |
| Reloj de pared, global | ejecución entera | sidecar | backstop contra proceso dormido |

**Advertencia de dimensionamiento:** el tiempo de CPU se **suma entre threads**. Con `--cpus 2`, un
alumno que lanza dos threads girando quema el presupuesto al doble de velocidad. Para anti-trampa eso
es lo que querés; pero significa que el presupuesto de CPU no se puede dimensionar mirando una corrida
single-thread.

---

## 8. Normalización de reportes de test

**Respuesta corta: no hay estándar formal; el XML de JUnit ni siquiera tiene especificación oficial. El
intento actual de estándar es CTRF. [matiza]**

### El dato incómodo sobre el XML de JUnit

No tiene especificación oficial. Es un formato de facto que lleva más de treinta años y cada framework
soporta su propia variante. Por eso normalizar entre frameworks es un problema real y no un problema
de ustedes.

### CTRF (Common Test Report Format)

Es el estándar abierto emergente: un **esquema JSON único**, agnóstico de lenguaje y de framework, para
que los resultados sean portables entre reporters, dashboards e integraciones de CI sin que cada
consumidor tenga que aprender TRX, JUnit XML, xUnit XML, NUnit XML y los formatos ad-hoc.

Lo mínimo requerido por test es **name, duration, status**; el resto del esquema es opcional
(herramienta, entorno, build). Tiene además campos de `retries` y `flaky` — relevante para su problema
de varianza.
→ [ctrf.io](https://ctrf.io/docs/intro)

Para Java hay implementación nativa: `io.github.alexshamrai:junit-ctrf-reporter`, que ofrece tanto una
**Jupiter Extension** como un **Platform TestExecutionListener** (requiere Java 17+).
→ [junit-ctrf-extension](https://github.com/alexshamrai/junit-ctrf-extension)

Señal de adopción que vale citar: Microsoft.Testing.Platform incorporó un provider de reportes CTRF
a partir de la versión 2.3.0.

Alternativas: **TAP** (más simple, menos metadata) y **Allure** (modelo más rico, pero requiere
tooling de Allure para parsear).

### Recomendación

**No adopten CTRF como formato de cable con T05.** Su contrato con T05 es suyo y debería ser del
dominio ("veredicto de una entrega"), no un formato genérico de test.

**Sí adopten la *forma* de CTRF para su modelo interno normalizado.** Ustedes ya nombraron un
*anti-corruption layer* sobre JUnit en §5.3 — CTRF es exactamente el modelo que ese ACL debería
producir, y poder decir "nuestro modelo de veredicto normalizado sigue la forma de CTRF, el estándar
abierto emergente para reportes de test" convierte una decisión propia en una decisión con respaldo.

Y si mañana entra otro lenguaje: existe un conversor oficial `junit-to-ctrf`, así que el camino de
migración está trazado.

---

## 9. Anti-trampa cuando código y tests corren en la misma JVM

**Este es el hallazgo más importante del documento. [confirma fuerte, y agrega un vector que no tenían]**

### Existe la implementación de referencia, es académica, y la rompieron dos veces

**Ares** (Artemis Java Test Sandbox, `de.tum.in.ase:artemis-java-test-sandbox`) es una extensión de
JUnit 5 de la Technische Universität München para testear código de alumnos de forma segura en la
plataforma Artemis. Es literalmente su problema, resuelto por una universidad, en producción.
→ [ls1intum/Ares](https://ls1intum.github.io/Ares/)

Y tiene dos CVEs de escape:

- **CVE-2024-23683** (< 1.7.6) — escape del sandbox creando una **subclase especial de
  `InvocationTargetException`**. Permite ejecutar Java arbitrario cuando la víctima ejecuta el código
  supuestamente sandboxeado.
- **CVE-2024-23682** (< 1.8.0, CVSS 8.2, **CWE-501 Trust Boundary Violation**) — escape **incluyendo
  class files en un paquete en el que Ares confía**. La mitigación fue validar paquetes con el Maven
  Enforcer.
  → [GHSA](https://github.com/advisories/GHSA-hj55-9jmv-9jrj) · [NVD](https://nvd.nist.gov/vuln/detail/CVE-2024-23682)

### Y el propio proyecto declaró que su diseño no tiene futuro

En la discusión [ls1intum/Ares#113, "On the Future of Ares and JEP 411"](https://github.com/ls1intum/Ares/discussions/113),
el mantenedor lo dice sin vueltas: casi todas las funciones de seguridad de Ares están implementadas
por su subclase `ArtemisSecurityManager`, no hay reemplazo directo planificado más allá de un setting
especial para prevenir `System.exit`, y **Ares tal como está implementado no tiene futuro a largo
plazo**.

Contexto oficial: **JEP 411** deprecó terminalmente el SecurityManager en Java 17, y **JEP 486** lo
deshabilitó de forma permanente — la especificación se revisó para que los desarrolladores **no puedan
habilitarlo**, y las bibliotecas de la plataforma ya no le delegan decisiones de acceso a recursos. La
documentación de Oracle para Java 21 lo dice explícitamente: *no hay reemplazo*.
→ [JEP 411](https://openjdk.org/jeps/411) · [JEP 486](https://openjdk.org/jeps/486)

El camino de reemplazo que evalúa Ares (basado en el artículo de Ron Pressler
[*Security and Sandboxing Post SecurityManager*](https://inside.java/2021/04/23/security-and-sandboxing-post-securitymanager/),
sección "Shallow Java Sandboxes") es usar el sistema de módulos con un **class loader propio para el
módulo del alumno, que inspeccione el bytecode al cargar cada clase por primera vez** y lo manipule
para lanzar excepciones donde corresponda.

### Conclusión para la pregunta que hicieron

**"¿Vale la pena partir en dos procesos?" → No, o casi no.**

Aislar código del alumno del runner *dentro* de la JVM no es alcanzable en Java 21 con ningún mecanismo
soportado. Ares lo intentó con el mecanismo que existía, lo rompieron dos veces con dos trucos
distintos, y el mecanismo ya no existe. **El contenedor es la frontera. Es la única frontera real que
tienen, y por eso su decisión de §6 es correcta.**

Partir en dos procesos dentro del contenedor no compra frontera de seguridad (los dos procesos están
en el mismo contenedor, mismo namespace, mismo usuario). Compraría algo distinto: aislar el *fallo*
—un `System.exit` o un OOM del alumno no tumba al runner— pero eso ya lo resuelven leyendo el veredicto
del reporte y no del exit code.

### Pero hay un vector que su modelo actual NO cubre

**El contenedor protege el host. No protege el veredicto.**

CVE-2024-23682 es *inyectar class files en un paquete confiable*. Aplicado a ustedes: nada impide que
un alumno declare su clase en el **mismo paquete que los tests del profesor**, o con el nombre de una
clase auxiliar de la que dependen los tests. Al compilarse todo junto en el mismo classpath, la clase
del alumno puede **sombrear o reemplazar** una clase de soporte del profesor. Los tests pasan. El
contenedor está perfectamente aislado. El veredicto es falso, y el veredicto es la nota.

Esto no lo cierra ningún flag de Docker. Lo cierran tres cosas baratas, todas en el sidecar o en el
armado del bundle:

1. **Validar el paquete y el nombre de clase del código del alumno** contra lo que declara la consigna.
   Rechazar la entrega si declara un paquete reservado. (Es literalmente la mitigación que aplicó Ares.)
2. **Compilar en directorios de salida separados** y componer el classpath con los tests **primero**,
   de modo que el `.class` del profesor gane la resolución.
3. **Escribir el reporte a una ruta que el código del alumno no pueda tocar.** Este es el bug de
   Judge0. Si el reporte va a un directorio escribible por el alumno, el alumno puede escribirlo él.
   Con `--read-only` + `tmpfs` esto se controla, pero hay que verificar que el `tmpfs` donde va el
   reporte no sea el mismo working dir donde corre el código.

### Y el punto que se conecta con el resumen ejecutivo: el bundle por tar en `stdin`

Ustedes resolvieron la incompatibilidad de `docker cp` con `--read-only` mandando el bundle como tar
por `stdin`. **La extracción de un tar con paths controlados por el atacante es exactamente la clase de
bug que tumbó a Judge0.** Ustedes probaron path traversal (`../`), bien. Pero hay dos casos distintos
que conviene testear aparte:

- **Entradas symlink dentro del tar.** Un tar puede contener un symlink `Solucion.java → /etc/passwd`
  y después una entrada regular que escribe sobre ese path. `../` no aparece por ningún lado.
- **Hard links** dentro del tar apuntando fuera del directorio de extracción.

Si la extracción la hace `tar` de GNU con `--no-same-owner` y dentro de un contenedor read-only ya
están bastante cubiertos, pero **vale la pena agregar estos dos casos a las diez entregas de prueba**.
Y vale la pena decirlo en la defensa: *"agregamos dos casos hostiles después de estudiar los CVEs de
Judge0, porque su escape fue exactamente esta clase de bug"*.

---

## 10. Comunicar un veredicto negativo sin filtrar la solución

**Respuesta corta: la decisión correcta es que el sandbox devuelva todo y T05 filtre. Pero hay tres
canales de fuga que hay que cerrar explícitamente. [confirma la arquitectura, agrega detalle]**

No encontré un estándar de industria acá; es una decisión de producto. Pero la separación limpia es
clara y ustedes ya la tienen por arquitectura:

- **El sandbox reporta hechos.** Qué test corrió, qué resultado dio, cuánto tardó, qué mensaje produjo.
- **T05 decide qué se muestra.** Es el dueño de las consignas y de los casos de prueba; es quien sabe
  cuáles son ocultos. La política de visibilidad es del dominio de T05, no del sandbox.

Esto es coherente con su §4.3: el sandbox es una función pura y no tiene dominio de negocio. Meterle
política de visibilidad sería darle dominio. Vale decirlo así en la defensa.

### El mecanismo

Los tests de JUnit tienen identificadores únicos y display names. Si el profesor marca los tests
ocultos con `@Tag("oculto")` (o los declara en un paquete/clase separada), el tag viaja en el reporte y
T05 puede colapsar los ocultos en *"3 de 5 pruebas ocultas fallaron"*.

### Los tres canales de fuga que hay que cerrar

1. **El mensaje de aserción.** `assertEquals(expected, actual)` produce un mensaje con el valor
   esperado. Para un test oculto, eso *es* la solución. T05 tiene que descartar `message` y
   `stackTrace`, no sólo el nombre del test.
2. **El stack trace.** Contiene nombres de métodos y a veces literales. Mismo tratamiento.
3. **`stdout` — el canal que se olvida.** Si la captura de salida es global al proceso, la salida
   producida durante los tests ocultos se mezcla con la de los visibles. Un alumno que imprima desde su
   propio código durante un test oculto ve la entrada. **Necesitan captura de salida por test**, no
   global. JUnit 5 lo permite (`@ExtendWith` con captura de streams, o el mecanismo de capture de
   output de la Platform), y Ares justamente ofrece utilidades para testear con `System.out` y
   `System.in` de forma cómoda.

Ese tercer punto es el que se les puede escapar y es fácil de nombrar en la defensa como algo que
detectaron.

---

## 11. "Database per service" aplicado al broker

**Respuesta corta: su lectura es la estándar. Pero les propongo desafiar un detalle: un vhost por
servicio probablemente les rompe el EDA. [desafía un detalle]**

### Por qué su conclusión es correcta

"Database per service" protege una propiedad: **ningún servicio lee las tablas de otro**. Es una
propiedad sobre el *acceso a los datos*, no sobre la *cantidad de instancias de infraestructura*. Un
broker por servicio no tiene análogo, porque el broker **es el bus compartido**: si cada servicio tiene
el suyo, no hay bus, hay doce colas privadas y federación entre ellas.

La propiedad análoga es: ningún servicio consume la cola de otro, ni publica en el exchange de otro.
Eso se obtiene con **usuarios por servicio y ACLs**, no con más brokers.

### Qué compran realmente los vhosts

Un vhost es una **separación lógica dentro de una única instancia de broker**: cada vhost tiene su
propio conjunto de exchanges, colas, bindings y permisos, aislado de los demás. Los ACLs se definen por
usuario y por vhost, con granularidad fina (leer de esta cola, escribir en este exchange, nada más).

El caso de uso canónico que aparece en la documentación y en las guías es **multi-tenancy y separación
de entornos**: staging y producción pueden compartir un broker sin riesgo de contaminación cruzada,
mientras usen vhosts distintos.

### El desafío al detalle

**Los exchanges no cruzan vhosts.** Un mensaje publicado en un exchange del vhost A no llega a una cola
del vhost B sin un *shovel* o *federation* explícito.

Su sandbox publica `EjecucionFinalizada`, que consumen T05 y T03. Si cada servicio tuviera su propio
vhost, ese evento necesitaría un shovel configurado para llegar a dos consumidores en dos vhosts
distintos — es decir, **un vhost por servicio rompe activamente la coreografía EDA de la materia.**

La forma que yo defendería:

| Nivel | Qué usar | Por qué |
|---|---|---|
| Entornos (dev / demo / defensa) | **vhosts** | Es el caso de uso canónico; aislamiento total |
| Servicios dentro de un entorno | **un vhost compartido + usuario por servicio + ACLs** | Preserva el bus; el aislamiento lo dan los permisos |
| Colas | Nombradas por servicio consumidor | Ownership explícito |

Esto es más fuerte que "un solo broker con separación lógica", porque nombra **qué separación lógica** y
**por qué esa y no otra**. Y muestra que evaluaron el vhost-por-servicio y lo descartaron con un
argumento técnico (los exchanges no cruzan vhosts), que según §9 de su briefing vale tanto como la
decisión tomada.

---

## 12. Sidecar en un lenguaje distinto al de la aplicación

**Respuesta corta: sí, se sostiene, y es de hecho el beneficio definitorio del patrón. [confirma —
pero con la advertencia de que esta es la pregunta que menos verifiqué]**

La agnosticidad de lenguaje no es un efecto colateral tolerado del patrón sidecar: es uno de los
beneficios que la literatura le atribuye. El ejemplo canónico lo demuestra solo — **Envoy, el sidecar
de Istio, está escrito en C++ y se adjunta a servicios escritos en cualquier lenguaje**. Lo mismo para
los shippers de logs y métricas.

El razonamiento: el sidecar corre en su propio proceso (y típicamente en su propio contenedor),
comunicándose con la aplicación por localhost o por un socket. La frontera es el protocolo, no el
runtime. Si la frontera fuera el runtime, sería una biblioteca, no un sidecar.

**Advertencia honesta:** esta es la pregunta a la que le dediqué menos búsqueda y la única donde no
tengo una cita fuerte. Para la defensa buscaría respaldo bibliográfico directo en:

- Burns & Oppenheimer, *"Design Patterns for Container-based Distributed Systems"*, HotCloud 2016 — es
  el paper que introduce sidecar, ambassador y adapter como patrones nombrados. Es la cita de origen.
- Richardson, *Microservices Patterns* — el capítulo de observabilidad / cross-cutting concerns.

Si consiguen el paper de HotCloud, la cita ahí es de altísimo valor porque es primaria y corta la
discusión.

---

## Lo que yo cambiaría del diseño, en orden de impacto

1. **Presupuestos de tiempo separados por fase, con el reloj del alumno medido en CPU** (§7). Resuelve
   el "camino feliz da TIMEOUT" y elimina la varianza de 1.8–5.2 s por construcción, no por margen.
2. **Validar paquete/nombre de clase de la entrega del alumno** (§9). El contenedor no protege el
   veredicto; esto sí. Es barato y es la mitigación que aplicó Ares tras un CVSS 8.2.
3. **Agregar dos casos hostiles al set de prueba: symlinks y hard links dentro del tar** (§9). Es la
   clase de bug que tumbó a Judge0 tres veces, y ustedes inyectan el bundle como tar.
4. **`--select-class` en vez de `--scan-classpath`** (§5). Ataca directo los 1.2–2.9 s medidos. Ojo con
   el issue de `--include-classname`.
5. **Cachear la suite de tests compilada por `(desafioId, version)`** (§5). Elimina una de las dos
   invocaciones de `javac`, y hace que su "extra" de artefactos participe del camino crítico.
6. **Probar `userns-remap` en el daemon** (§4). Una línea, y sube un escalón real contra la clase de
   bug dominante de runc.
7. **Captura de `stdout` por test, no global** (§10). Canal de fuga fácil de olvidar.
8. **Revisar la postura de vhosts: uno por entorno, no uno por servicio** (§11).

## Lo que confirmé y no tocaría

- El sidecar-ejecutor con spec hardcodeada (§1) — y ahora con un argumento mucho mejor que "no hay
  herramienta".
- Nada corre privilegiado, el worker no tiene el socket (§2, validado por el escape de Judge0).
- Veredicto desde el reporte, nunca desde el exit code (§9).
- El contenedor como única frontera real; nada de aislamiento intra-JVM (§9, validado por los dos CVEs
  de Ares y por JEP 486).
- Descartar WASM (§3) — pero por reflection y closed-world, no por el temario.
- Un solo broker (§11).
- Sidecar en otro lenguaje (§12).
