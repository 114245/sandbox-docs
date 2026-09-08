# Hallazgos de investigación externa — `ms-sandbox` (Tema 06)

> Respuesta al briefing del 27 de agosto de 2026. Cada sección responde una pregunta de §8 del briefing.
> Marco cada hallazgo con **[confirma]**, **[desafía]** o **[matiza]** respecto de las decisiones de §6.
> Las fuentes están linkeadas para que se puedan citar en la defensa.
>
> **Versión podada.** Se removieron los caminos ya descartados que no aportan argumento defendible
> (inventario de tooling que no vamos a usar, diseños alternativos que no vamos a construir, listados
> de ecosistema). Lo que se descartó **con argumento** se conservó, comprimido — según §9 del briefing,
> "evaluamos X y lo descartamos porque Y" vale tanto como la decisión tomada. El detalle de lo removido
> está en la anteúltima sección; el documento íntegro queda en
> `docs/_fuentes/hallazgos-investigacion-sandbox.original.md`.
>
> **Hay un [Glosario](#glosario) al final** con todo el vocabulario técnico explicado sin dar nada por
> sabido. Si aparece un término que no se entiende, está ahí.

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
aislamiento. El riesgo residual que declaramos en §6 ("el kernel es compartido") es cierto pero
no es donde está la plata. El riesgo residual real es **nuestro código que arma el bundle, lo
inyecta y lee el reporte**.

Consecuencia accionable: §9 (los casos hostiles de symlink y hard link en el tar).

Consecuencia argumentativa: en la defensa, "asumimos el riesgo de un exploit de kernel" es una frase
que suena madura pero es barata. **"Asumimos el riesgo de kernel Y auditamos específicamente la clase
de bug que tumbó a Judge0"** es otra cosa.

---

## 1. Filtrado del socket de Docker por contenido del body

**Respuesta corta: el mecanismo existe (authorization plugins de Docker), pero tiene tres bypasses
documentados en ocho años y todos son de la misma clase. Eso arma el argumento a favor de invertir el
diseño. [confirma la inversión, con mejor fundamento]**

Docker tiene un framework de **authorization plugins (AuthZ)**; la implementación de referencia es
[`opa-docker-authz`](https://github.com/open-policy-agent/opa-docker-authz), que evalúa políticas Rego
sobre el body del request (`input.Body.HostConfig.Privileged`). O sea: el mecanismo estándar de la
industria no es un proxy HAProxy, es un plugin del daemon. El proxy tipo `tecnativa` es la versión pobre.

### El historial de fracasos hace el argumento por nosotros

**CVE-2018-16398** — Twistlock AuthZ Broker manejaba mal las expresiones regulares. Un request a
`containers/aa/pause?aaa=\/start` pasaba una política que permitía `start` pero prohibía `pause`.
*Es un bypass del filtrado por path — el enfoque exacto de `tecnativa/docker-socket-proxy`.*
→ [NVD](https://nvd.nist.gov/vuln/detail/CVE-2018-16398)

**CVE-2024-41110** (crítico) — Con `Content-Length: 0` el daemon reenvía el request al plugin **sin
body**. El plugin no ve nada y aprueba; el daemon procesa el body completo. Lo notable es la historia:
descubierto en 2018, parcheado en 18.09.1 en enero de 2019, **el fix no se propagó a 19.03 ni
posteriores**, y la regresión se detectó recién en abril de 2024. Cinco años de ventana.
→ [NVD](https://nvd.nist.gov/vuln/detail/cve-2024-41110) · [The Register](https://www.theregister.com/2024/07/25/5yo_docker_vulnerability/)

**CVE-2026-34040** (abril de 2026) — Misma clase, distinto truco: si el body supera 1 MB, el middleware
de Docker lo descarta **antes** de pasárselo al plugin. Afecta a **todos** los plugins por igual, porque
el bug está en el middleware de Docker, no en el plugin. Parcheado en 29.3.1.
→ [Cyera](https://www.cyera.com/research/one-megabyte-to-root-how-a-size-check-broke-dockers-last-line-of-defense) · [SC Media](https://www.scworld.com/news/docker-authorization-bypass-flaw-enables-dangerous-container-creation)

### El argumento para la defensa

No es "no existe herramienta". Es más fuerte:

> Filtrar el body es un **problema de parseo sobre input influido por el atacante**, y la industria lo
> falló tres veces en ocho años, siempre por la misma razón: el filtro y el daemon no interpretan el
> mismo byte stream. Un sidecar que **no acepta una spec de contenedor** no tiene ese problema, porque
> no tiene nada que parsear.

La inversión no es un workaround por falta de tooling: es la mitigación estructural de una clase de
vulnerabilidad documentada. El sidecar deja de exponer la API de Docker y expone un verbo del dominio:

```
POST /ejecutar   { bundle }   →   { reporte }
```

La spec del contenedor (`--network none`, `--read-only`, límites, etc.) vive hardcodeada adentro del
sidecar. El worker no puede pedir `Privileged: true` porque no hay ningún campo donde escribirlo.

**Dato adicional:** el README de `tecnativa/docker-socket-proxy` señala que el propio proxy necesita
`--privileged` en algunos entornos SELinux/AppArmor. Un componente de seguridad que corre privilegiado
para poder proteger es incómodo de defender.

---

## 2. Cómo lo resuelven los que hacen esto en serio

**Respuesta corta: nadie serio hace `docker run` por ejecución en el camino crítico. Usan Docker como
capa de empaquetado y algo más liviano adentro. [desafía parcialmente]**

### Judge0 y Piston: `isolate` dentro de Docker

Ambos usan el binario **`isolate`** (el sandbox del sistema CMS de la Olimpiada Internacional de
Informática: namespaces, chroot, cgroups, usuarios sin privilegios) para el aislamiento *por ejecución*,
corriendo ellos mismos *dentro de* un contenedor Docker.
→ [judge0/judge0](https://github.com/judge0/judge0) · [engineer-man/piston](https://github.com/engineer-man/piston)

**Y a Judge0 se lo llevaron puesto.** El [análisis de Tanto Security](https://tantosec.com/blog/judge0/)
es lectura obligatoria para el grupo:

1. Judge0 escribe un `run_script` en el directorio del sandbox. El alumno crea ahí un **symlink**
   apuntando afuera. La escritura sale del sandbox. (CVE-2024-28185)
2. El parche cambió el usuario Unix bajo el que corre Rails. El investigador lo bypasseó usando `chown`
   sobre un symlink. (CVE-2024-28189)
3. El remate: **el `docker-compose.yml` de Judge0 corre el contenedor con `privileged: true`**, así que
   una vez fuera de `isolate` se puede montar el filesystem del host. Escape total.

**El bug fue de lógica, pero lo que lo convirtió en takeover del host fue el contenedor privilegiado.**
Esto valida dos decisiones nuestras —el worker no tiene el socket, nada corre privilegiado— con un caso
concreto y citable.

### El patrón, y por qué no lo seguimos

Los dos usan Docker como **entorno** y `isolate` como **aislamiento por ejecución**. Nadie levanta un
contenedor por submission en el camino crítico, porque a escala de contest eso no cierra.

**Esto desafía nuestro diseño, y la respuesta está en nuestros propios números.** La forma de decirlo:

> "Judge0 y Piston usan `isolate` dentro de Docker porque a escala de miles de submissions por hora el
> `docker create` por ejecución no es viable. A 120 usuarios y 4–6 ejecuciones concurrentes medimos que
> el `create` no está en el camino crítico, así que elegimos el contenedor efímero, que además nos da
> el `--network none` y el `--read-only` sin código nuestro. La misma decisión a escala de LeetCode
> sería equivocada."

Una decisión con su condición de validez explícita es exactamente lo que se evalúa.

### Cuantificación del riesgo residual

AWS Lambda usa **microVMs de Firecracker** — el escalón "escala masiva". Eso confirma la clasificación
en tres clases de motor: **microVM / kernel en espacio de usuario (gVisor) / contenedor OCI (runc)**.

Un paper comparativo de mayo de 2026 mide esto empíricamente: *AI Code Sandboxes: A Comparative Security
Study, Part 1* ([arXiv:2606.08433](https://arxiv.org/abs/2606.08433)).

| Métrica (ventana 24 meses) | runc (contenedor) | gVisor | Firecracker |
|---|---|---|---|
| CVEs de escape publicados | 4 de 4 | 0 de 3 | 2 de 2 (ambos de 2026) |
| Fugas de identidad del host (28 sondas) | 10 | 2 | 0 |
| Primitivas kernel alcanzables (de 14) | 11 | 5 | 7 |
| String del kernel del guest | idéntico al del host | sintético | propio |

El estudio también mide que el **perfil seccomp por defecto de Docker bloquea unas 44 syscalls de
300+** — verificado contra Docker v29.5.2, donde 361 syscalls quedan permitidas incondicionalmente.
"Seccomp default" suena mucho más fuerte de lo que es.

**Cómo usarlo:** no como "deberíamos usar gVisor", sino como *cuantificación del riesgo declarado*.
"Elegimos la clase de motor con la peor postura de escape medida, y lo hacemos con conocimiento: 4 de
4 CVEs de runc en 24 meses fueron de escape. Lo compensamos con `--network none`, que corta el vector
que nos importa (alcanzar a los otros once servicios), y lo declaramos como riesgo residual."

---

## 3. WebAssembly como alternativa

**Respuesta corta: no. Y ahora tenemos una razón técnica, no académica, para descartarlo. [confirma, mejor]**

El compilador Java→WASM más maduro es **TeaVM** (bytecode JVM → WasmGC), listo para producción sólo en
targets de navegador; el soporte WASI del lado servidor es un fork de Fermyon, experimental y no
mergeado upstream. GraalVM está construyendo emisión de WASM desde `native-image`, todavía no listo.
→ [Java Code Geeks, abril 2026](https://www.javacodegeeks.com/2026/04/webassembly-in-2026-where-it-has-landed-what-wasi-0-2-changes-and-why-java-and-kotlin-developers-should-pay-attention-now.html) · [Fermyon](https://developer.fermyon.com/wasm-languages/java)

**Pero el bloqueante no es la madurez del tooling.** TeaVM y GraalVM `native-image` trabajan bajo la
*closed-world assumption*: hay que saber en tiempo de compilación qué clases existen. Y JUnit 5 es, de
punta a punta, un motor de descubrimiento y ejecución **basado en reflection**. Además, el código del
alumno **no se conoce en tiempo de build** — llega en runtime.

O sea: WASM tendría que resolver simultáneamente su peor caso (reflection y class loading dinámico)
sobre un input que por definición no está disponible en tiempo de compilación.

**Cómo decirlo en la defensa** — no es "no capitaliza la unidad de Docker", es:

> "Evaluamos WASM. TeaVM es el compilador Java→WASM más maduro y sólo está listo para navegador; el
> soporte WASI del lado servidor es un fork experimental. Pero el bloqueante no es la madurez del
> tooling: es que WASM impone closed-world assumption y JUnit 5 es enteramente reflection, sobre
> código que recibimos en runtime. El modelo de aislamiento es más limpio, pero es incompatible con
> nuestro contrato."

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

### El matiz que nos conviene: casi todos requieren una config maliciosa

Varios de estos exigen que el atacante controle la **spec del contenedor** (mounts, symlinks en la
config). En nuestro diseño, el alumno nunca controla la spec — está hardcodeada en el sidecar (§1).

**Esto conecta las dos preguntas:** el sidecar-ejecutor con spec hardcoded no sólo previene
`Privileged: true`, sino que **cierra el vector de configuración maliciosa que es la clase de bug
dominante de runc**. Convierte una decisión que parecía defensiva en una que mitiga una familia de CVEs
documentada.

### Mitigaciones a revisar

1. **User namespaces.** Las advisories de runc son explícitas: son de los mejores mecanismos de
   hardening contra breakouts, y el kernel aplica restricciones extra a contenedores con user namespace.
   Con `userns-remap` en el daemon, el root del contenedor deja de ser root del host. Nota: la mayoría
   de los runtimes bloquean por seccomp `unshare(CLONE_NEWUSER)` dentro del contenedor igual, así que
   habilitar user-ns para el contenedor no habilita user-ns *para el alumno*.
   **Recomendación concreta: probar `userns-remap`. Es una línea en `daemon.json` y sube un escalón real.**
2. **Perfil seccomp propio y más chico que el default.** El default deja 361 syscalls. Corremos `javac`
   y `java` y nada más: se puede recortar mucho, con `--security-opt seccomp=perfil.json`.
3. **AppArmor.** Confirmar que el perfil `docker-default` esté efectivamente aplicado (en Ubuntu se
   carga pero sólo se aplica donde se asigna explícitamente).
4. **Versión del engine.** Docker Engine 29.x bundlea runc 1.3.5, que incluye el trío de noviembre de
   2025. Si se instala `runc` desde el archivo de Ubuntu, atención: Ubuntu Noble marcó los tres como
   *"Ignored — backport too intrusive"*. Vale chequear qué runc corre en la máquina de la demo.

### La fuga que sí existe y conviene declarar

Medido en el paper de arXiv: un contenedor runc filtra **10 de 28 identificadores del host** — versión
del kernel, modelo y microcódigo de CPU, RAM total, producto del BIOS, mapa de IRQs, y modelo y número
de serie de los discos. El string del kernel del guest es **idéntico byte a byte** al del host.

Para nuestro modelo de amenaza (académico, sin multi-tenancy con secretos) el impacto es bajo, pero
declararlo explícitamente es el tipo de rigor que se evalúa: *"el alumno puede leer el número de serie
del disco del host; lo aceptamos porque no hay secreto ahí y cerrarlo requeriría microVM"*.

---

## 5. Costo de `javac` y del descubrimiento de JUnit

**Respuesta corta: hay una palanca inmediata (`--select-class`) y una estructural (cachear la suite
compilada por versión). [confirma la hipótesis, con soluciones concretas]**

### Saltear el escaneo de classpath: es una opción del ConsoleLauncher

El `junit-platform-console-standalone` acepta `-c` / `--select-class` (repetible) y también
`--select-method`, `--select-package`, `--select-file`. Si no se pasa `--scan-classpath`, **no escanea**.
→ [Console Launcher, JUnit User Guide](https://docs.junit.org/6.0.3/running-tests/console-launcher.html)

Conocemos los nombres de las clases de test: los escribió el profesor y vienen en el bundle. No hay
razón para escanear. Esto ataca directo los 1.2–2.9 s medidos.

**Trampa documentada:** hay un issue conocido donde `--select-class` por sí solo no alcanza y hay que
agregar además `--include-classname` con un regex que matchee el nombre de la clase.
→ [junit-framework#2289](https://github.com/junit-team/junit-framework/issues/2289). Si `--select-class`
"no encuentra nada", es esto.

**Dato de contexto:** la documentación actual de JUnit ya es de la línea **6.0.x**. Si estamos sobre
JUnit 5.x, vale medir si el descubrimiento mejoró.

### Cachear la suite de tests compilada por versión: la palanca grande

Medimos **dos invocaciones de `javac`** de 1.0–2.3 s cada una. Una es la del alumno, la otra la de los
tests del profesor. La del profesor **es idéntica en todas las entregas de un mismo desafío**.

Dos piezas del propio sistema hacen que encaje:

- T03 (`ms-desafios`) ya hace **versionado**: hay un identificador estable por versión de desafío.
- Nuestro **extra es "almacenamiento de artefactos de ejecución"**. Los `.class` de la suite son
  exactamente un artefacto.

Diseño: la primera ejecución de una versión de desafío compila los tests y guarda los `.class` como
artefacto indexado por `(desafioId, version)`. Las siguientes reciben los `.class` ya compilados y sólo
compilan el código del alumno. **Elimina una de las dos invocaciones de `javac` completas.**

Bonus argumentativo: convierte el "extra" de un adorno en una pieza que participa del camino crítico de
rendimiento — mucho más defendible que "también guardamos los logs".

Riesgo a manejar: la invalidación de la caché. Si la clave es `(desafioId, version)` y T03 garantiza que
una versión publicada es inmutable, el problema desaparece. **Verificar ese supuesto con T03** — si una
versión puede editarse en el lugar, la caché envenena veredictos, y ahí el bug tiene consecuencia académica.

### El *compiler API* en un proceso caliente: descartado

`javax.tools.JavaCompiler` en el worker significa **compilar fuente no confiable dentro de la JVM del
worker**. `javac` no es un sandbox: procesa anotaciones, puede cargar processors del classpath, y un bug
del compilador se ejecuta en nuestro proceso privilegiado. Rompe el modelo entero.

### La palanca más barata sigue siendo la que ya medimos

De 1 a 2 CPU: 8.5 s → 5.1 s. `javac` paraleliza. **Antes de escribir una línea de caché, subir
`--cpus`.** Y decirlo así: "medimos, encontramos que la CPU era la palanca, y aplicamos la solución de
una línea antes que la de doscientas".

---

## 6. Pool de contenedores calientes — descartado por medición

**Respuesta corta: el patrón existe, tiene nombre y es literalmente el de AWS Lambda. Pero nuestros
propios números dicen que no conviene. [desafía la premisa]**

El patrón es *warming pool* / *active pool*, descrito en las patentes de AWS: instancias
pre-inicializadas que pasan al pool activo al llegar un request, y **una vez asignadas a un usuario no
pueden usarse para tareas de otro** — explícitamente, para evitar el co-mingling entre usuarios. O sea:
la regla que nos pusimos ("nunca reusar entre dos alumnos") es la misma que se puso AWS.
→ [US 9,928,108](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9928108) · [US 10,303,492](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10303492)

La trampa está nombrada en la literatura: el survey [*Serverless Computing: A Security Perspective*
(arXiv:2107.03832)](https://arxiv.org/pdf/2107.03832) dice que los contenedores calientes reducen el
arranque **a costa de garantías de seguridad** — queda un `/tmp` escribible que persiste entre
invocaciones, y un atacante que comprometa una función puede dejar estado ahí para ataques de larga
duración. La mitigación que nombra el paper es exactamente deshabilitar el reuso.

**La respuesta para la defensa:**

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
directamente nuestro hallazgo de que "el camino feliz daba TIMEOUT". [desafía el diseño actual]**

### Lo que hace `isolate` (y por lo tanto Judge0 y Piston)

- **`--time`** — límite de **tiempo de CPU**. El manual es explícito: *el tiempo en que el SO le asigna
  el procesador a otras tareas no se cuenta*.
- **`--extra-time`** — gracia después de exceder el límite de CPU, antes de matar. La ventaja
  documentada: **el tiempo de ejecución real se reporta igual**, aunque exceda levemente el límite.
- **`--wall-time`** — reloj de pared. **No se detiene cuando el programa pierde la CPU o espera un
  evento externo.** Es el backstop contra un proceso que duerme en vez de quemar CPU.

→ [Manual de isolate](https://www.ucw.cz/isolate/isolate.1.html)

### Y los aplican por fase, no una vez

Judge0 en su versión 1.4 tenía, **sólo para la compilación**: 5 s de CPU, 2 s de extra, 10 s de wall. La
ejecución tenía su propio presupuesto aparte, configurable por submission (`CPU_TIME_LIMIT`,
`CPU_EXTRA_TIME`, `WALL_TIME_LIMIT`).
→ [judge0#114](https://github.com/judge0/judge0/issues/114) · [judge0.conf](https://github.com/judge0/judge0/blob/master/judge0.conf)

**Esto responde nuestro hallazgo directamente.** Descubrimos que "compilar se comía el reloj del
alumno". La solución de la industria no es agrandar el presupuesto: es que compilación y ejecución
tengan presupuestos separados, y que el del alumno se mida en **CPU**, no en reloj de pared.

### Y responde la varianza de 1.8 s a 5.2 s

Esa varianza es contención del host: otras ejecuciones compitiendo por CPU. **El tiempo de CPU es inmune
a eso por construcción.** Si el presupuesto del alumno se mide en CPU, dos corridas idénticas dan el
mismo número aunque el host esté saturado. Los falsos TIMEOUT intermitentes desaparecen porque
desaparece su causa, no porque se les agregó margen.

### Cómo hacerlo con Docker, que no da `--time`

**`RLIMIT_CPU` dentro del contenedor.** Un `ulimit -t <segundos>` en el entrypoint antes de invocar
`java` hace que el kernel mande `SIGXCPU` al proceso al agotar el tiempo de CPU. Es el mismo mecanismo
que usa `isolate` por debajo. Se combina con el timeout de reloj del sidecar como backstop.

(La alternativa —leer el consumo de CPU de los cgroups y decidir el veredicto en base a eso— funciona,
pero es polling y el kill sigue siendo por reloj. No la recomiendo.)

Diseño resultante:

| Reloj | Ámbito | Quién lo aplica | Para qué |
|---|---|---|---|
| CPU (`RLIMIT_CPU`) | sólo la fase de tests | kernel, dentro del contenedor | veredicto TIMEOUT del alumno |
| Reloj de pared, fase compilación | `javac` | sidecar | detectar compilador colgado |
| Reloj de pared, global | ejecución entera | sidecar | backstop contra proceso dormido |

**Advertencia de dimensionamiento:** el tiempo de CPU se **suma entre threads**. Con `--cpus 2`, un
alumno que lanza dos threads girando quema el presupuesto al doble de velocidad. Para anti-trampa eso es
lo que se quiere; pero significa que el presupuesto no se puede dimensionar mirando una corrida
single-thread.

---

## 8. Normalización de reportes de test

**Respuesta corta: no hay estándar formal; el XML de JUnit ni siquiera tiene especificación oficial. El
intento actual de estándar es CTRF. [matiza]**

El XML de JUnit **no tiene especificación oficial**. Es un formato de facto de más de treinta años y
cada framework soporta su propia variante. Normalizar entre frameworks es un problema real, no un
problema nuestro.

**CTRF (Common Test Report Format)** es el estándar abierto emergente: un **esquema JSON único**,
agnóstico de lenguaje y de framework. Lo mínimo requerido por test es **name, duration, status**; el
resto es opcional. Tiene además campos de `retries` y `flaky` — relevante para nuestro problema de
varianza. → [ctrf.io](https://ctrf.io/docs/intro)

Para Java hay implementación nativa: `io.github.alexshamrai:junit-ctrf-reporter`, con **Jupiter
Extension** y **Platform TestExecutionListener** (Java 17+).
→ [junit-ctrf-extension](https://github.com/alexshamrai/junit-ctrf-extension)

Señal de adopción citable: Microsoft.Testing.Platform incorporó un provider de reportes CTRF desde 2.3.0.

### Recomendación

**No adoptar CTRF como formato de cable con T05.** Nuestro contrato con T05 es nuestro y debería ser del
dominio ("veredicto de una entrega"), no un formato genérico de test.

**Sí adoptar la *forma* de CTRF para el modelo interno normalizado.** Ya nombramos un *anti-corruption
layer* sobre JUnit en §5.3 del briefing — CTRF es exactamente el modelo que ese ACL debería producir, y
poder decir "nuestro modelo de veredicto normalizado sigue la forma de CTRF, el estándar abierto
emergente" convierte una decisión propia en una decisión con respaldo. Si mañana entra otro lenguaje,
existe un conversor oficial `junit-to-ctrf`.

---

## 9. Anti-trampa cuando código y tests corren en la misma JVM

**Este es el hallazgo más importante del documento. [confirma fuerte, y agrega un vector que no teníamos]**

### La implementación de referencia es académica, y la rompieron dos veces

**Ares** (Artemis Java Test Sandbox, `de.tum.in.ase:artemis-java-test-sandbox`) es una extensión de
JUnit 5 de la Technische Universität München para testear código de alumnos de forma segura en la
plataforma Artemis. Es literalmente nuestro problema, resuelto por una universidad, en producción.
→ [ls1intum/Ares](https://ls1intum.github.io/Ares/)

Y tiene dos CVEs de escape:

- **CVE-2024-23683** (< 1.7.6) — escape creando una **subclase especial de
  `InvocationTargetException`**. Permite ejecutar Java arbitrario cuando la víctima ejecuta el código
  supuestamente sandboxeado.
- **CVE-2024-23682** (< 1.8.0, CVSS 8.2, **CWE-501 Trust Boundary Violation**) — escape **incluyendo
  class files en un paquete en el que Ares confía**. La mitigación fue validar paquetes con el Maven
  Enforcer.
  → [GHSA](https://github.com/advisories/GHSA-hj55-9jmv-9jrj) · [NVD](https://nvd.nist.gov/vuln/detail/CVE-2024-23682)

### El propio proyecto declaró que su diseño no tiene futuro

En [ls1intum/Ares#113, "On the Future of Ares and JEP 411"](https://github.com/ls1intum/Ares/discussions/113),
el mantenedor dice que casi todas las funciones de seguridad de Ares están implementadas por su subclase
`ArtemisSecurityManager`, que no hay reemplazo directo planificado, y que **Ares tal como está
implementado no tiene futuro a largo plazo**.

Contexto oficial: **JEP 411** deprecó terminalmente el SecurityManager en Java 17, y **JEP 486** lo
deshabilitó de forma permanente — la especificación se revisó para que los desarrolladores **no puedan
habilitarlo**. La documentación de Oracle para Java 21 lo dice explícitamente: *no hay reemplazo*.
→ [JEP 411](https://openjdk.org/jeps/411) · [JEP 486](https://openjdk.org/jeps/486)

### Conclusión: **"¿vale la pena partir en dos procesos?" → No.**

Aislar el código del alumno del runner *dentro* de la JVM no es alcanzable en Java 21 con ningún
mecanismo soportado. Ares lo intentó con el mecanismo que existía, lo rompieron dos veces con dos trucos
distintos, y el mecanismo ya no existe. **El contenedor es la frontera. Es la única frontera real que
tenemos, y por eso la decisión de §6 es correcta.**

Partir en dos procesos dentro del contenedor no compra frontera de seguridad (mismo contenedor, mismo
namespace, mismo usuario). Compraría aislar el *fallo* —un `System.exit` o un OOM del alumno no tumba al
runner— pero eso ya lo resolvemos leyendo el veredicto del reporte y no del exit code.

### El vector que nuestro modelo actual NO cubre

**El contenedor protege el host. No protege el veredicto.**

CVE-2024-23682 es *inyectar class files en un paquete confiable*. Aplicado a nosotros: nada impide que
un alumno declare su clase en el **mismo paquete que los tests del profesor**, o con el nombre de una
clase auxiliar de la que dependen los tests. Al compilarse todo junto en el mismo classpath, la clase
del alumno puede **sombrear o reemplazar** una clase de soporte del profesor. Los tests pasan. El
contenedor está perfectamente aislado. El veredicto es falso, y el veredicto es la nota.

Esto no lo cierra ningún flag de Docker. Lo cierran tres cosas baratas, en el sidecar o en el armado del
bundle:

1. **Validar el paquete y el nombre de clase del código del alumno** contra lo que declara la consigna.
   Rechazar la entrega si declara un paquete reservado. (Es literalmente la mitigación que aplicó Ares.)
2. **Compilar en directorios de salida separados** y componer el classpath con los tests **primero**, de
   modo que el `.class` del profesor gane la resolución.
3. **Escribir el reporte a una ruta que el código del alumno no pueda tocar.** Este es el bug de Judge0.
   Con `--read-only` + `tmpfs` se controla, pero hay que verificar que el `tmpfs` donde va el reporte no
   sea el mismo working dir donde corre el código.

### El bundle por tar en `stdin`

Resolvimos la incompatibilidad de `docker cp` con `--read-only` mandando el bundle como tar por `stdin`.
**La extracción de un tar con paths controlados por el atacante es exactamente la clase de bug que tumbó
a Judge0.** Probamos path traversal (`../`), bien. Pero hay dos casos distintos que conviene testear aparte:

- **Entradas symlink dentro del tar.** Un tar puede contener un symlink `Solucion.java → /etc/passwd` y
  después una entrada regular que escribe sobre ese path. `../` no aparece por ningún lado.
- **Hard links** dentro del tar apuntando fuera del directorio de extracción.

Si la extracción la hace `tar` de GNU con `--no-same-owner` y dentro de un contenedor read-only ya
estamos bastante cubiertos, pero **vale agregar estos dos casos a las diez entregas de prueba**. Y vale
decirlo en la defensa: *"agregamos dos casos hostiles después de estudiar los CVEs de Judge0, porque su
escape fue exactamente esta clase de bug"*.

---

## 10. Comunicar un veredicto negativo sin filtrar la solución

**Respuesta corta: el sandbox devuelve todo y T05 filtra. Pero hay tres canales de fuga que hay que
cerrar explícitamente. [confirma la arquitectura, agrega detalle]**

No hay un estándar de industria acá; es una decisión de producto. La separación limpia ya la tenemos por
arquitectura:

- **El sandbox reporta hechos.** Qué test corrió, qué resultado dio, cuánto tardó, qué mensaje produjo.
- **T05 decide qué se muestra.** Es el dueño de las consignas y de los casos de prueba; es quien sabe
  cuáles son ocultos. La política de visibilidad es del dominio de T05, no del sandbox.

Esto es coherente con §4.3 del briefing: el sandbox es una función pura y no tiene dominio de negocio.
Meterle política de visibilidad sería darle dominio. Vale decirlo así en la defensa.

### El mecanismo

Los tests de JUnit tienen identificadores únicos y display names. Si el profesor marca los tests ocultos
con `@Tag("oculto")` (o los declara en un paquete/clase separada), el tag viaja en el reporte y T05 puede
colapsar los ocultos en *"3 de 5 pruebas ocultas fallaron"*.

### Los tres canales de fuga

1. **El mensaje de aserción.** `assertEquals(expected, actual)` produce un mensaje con el valor esperado.
   Para un test oculto, eso *es* la solución. T05 tiene que descartar `message` y `stackTrace`, no sólo
   el nombre del test.
2. **El stack trace.** Contiene nombres de métodos y a veces literales. Mismo tratamiento.
3. **`stdout` — el canal que se olvida.** Si la captura de salida es global al proceso, la salida
   producida durante los tests ocultos se mezcla con la de los visibles. Un alumno que imprima desde su
   propio código durante un test oculto ve la entrada. **Necesitamos captura de salida por test**, no
   global. JUnit 5 lo permite (`@ExtendWith` con captura de streams, o el mecanismo de capture de output
   de la Platform).

El tercer punto es el que se escapa fácil y es bueno nombrarlo en la defensa como algo que detectamos.

---

## 11. "Database per service" aplicado al broker

**Respuesta corta: nuestra lectura es la estándar. Pero un vhost por servicio nos rompe el EDA.
[desafía un detalle]**

"Database per service" protege una propiedad: **ningún servicio lee las tablas de otro**. Es una
propiedad sobre el *acceso a los datos*, no sobre la *cantidad de instancias de infraestructura*. Un
broker por servicio no tiene análogo, porque el broker **es el bus compartido**: si cada servicio tiene
el suyo, no hay bus, hay doce colas privadas y federación entre ellas.

La propiedad análoga es: ningún servicio consume la cola de otro, ni publica en el exchange de otro. Eso
se obtiene con **usuarios por servicio y ACLs**, no con más brokers.

Un vhost es una **separación lógica dentro de una única instancia de broker**: exchanges, colas,
bindings y permisos propios. El caso de uso canónico en la documentación es **multi-tenancy y separación
de entornos** (staging y producción compartiendo broker sin contaminación cruzada).

### El desafío al detalle

**Los exchanges no cruzan vhosts.** Un mensaje publicado en un exchange del vhost A no llega a una cola
del vhost B sin un *shovel* o *federation* explícito.

Nuestro sandbox publica `EjecucionFinalizada`, que consumen T05 y T03. Si cada servicio tuviera su vhost,
ese evento necesitaría un shovel para llegar a dos consumidores en dos vhosts distintos — es decir,
**un vhost por servicio rompe activamente la coreografía EDA de la materia.**

| Nivel | Qué usar | Por qué |
|---|---|---|
| Entornos (dev / demo / defensa) | **vhosts** | Es el caso de uso canónico; aislamiento total |
| Servicios dentro de un entorno | **un vhost compartido + usuario por servicio + ACLs** | Preserva el bus; el aislamiento lo dan los permisos |
| Colas | Nombradas por servicio consumidor | Ownership explícito |

Esto es más fuerte que "un solo broker con separación lógica", porque nombra **qué separación lógica** y
**por qué esa y no otra**, y muestra que evaluamos el vhost-por-servicio y lo descartamos con un
argumento técnico.

---

## 12. Sidecar en un lenguaje distinto al de la aplicación

**Respuesta corta: sí, se sostiene, y es de hecho el beneficio definitorio del patrón. [confirma — con
la advertencia de que es la pregunta menos verificada]**

La agnosticidad de lenguaje no es un efecto colateral tolerado del patrón sidecar: es uno de los
beneficios que la literatura le atribuye. El ejemplo canónico lo demuestra solo — **Envoy, el sidecar de
Istio, está escrito en C++ y se adjunta a servicios escritos en cualquier lenguaje**. El sidecar corre en
su propio proceso y su propio contenedor, comunicándose por localhost o por un socket: **la frontera es
el protocolo, no el runtime**. Si la frontera fuera el runtime, sería una biblioteca, no un sidecar.

**Advertencia honesta:** es la pregunta con menos búsqueda y la única sin cita fuerte. Para la defensa
conviene respaldo bibliográfico directo en:

- Burns & Oppenheimer, *"Design Patterns for Container-based Distributed Systems"*, HotCloud 2016 — el
  paper que introduce sidecar, ambassador y adapter como patrones nombrados. Es la cita de origen y
  corta la discusión.
- Richardson, *Microservices Patterns* — capítulo de observabilidad / cross-cutting concerns.

---

## Lo que cambiaría del diseño, en orden de impacto

1. **Presupuestos de tiempo separados por fase, con el reloj del alumno medido en CPU** (§7). Resuelve
   el "camino feliz da TIMEOUT" y elimina la varianza de 1.8–5.2 s por construcción, no por margen.
2. **Validar paquete/nombre de clase de la entrega del alumno** (§9). El contenedor no protege el
   veredicto; esto sí. Es barato y es la mitigación que aplicó Ares tras un CVSS 8.2.
3. **Agregar dos casos hostiles al set de prueba: symlinks y hard links dentro del tar** (§9). Es la
   clase de bug que tumbó a Judge0 tres veces, y nosotros inyectamos el bundle como tar.
4. **`--select-class` en vez de `--scan-classpath`** (§5). Ataca directo los 1.2–2.9 s medidos. Ojo con
   el issue de `--include-classname`.
5. **Cachear la suite de tests compilada por `(desafioId, version)`** (§5). Elimina una de las dos
   invocaciones de `javac`, y hace que el "extra" de artefactos participe del camino crítico.
6. **Probar `userns-remap` en el daemon** (§4). Una línea, y sube un escalón real contra la clase de bug
   dominante de runc.
7. **Captura de `stdout` por test, no global** (§10). Canal de fuga fácil de olvidar.
8. **Revisar la postura de vhosts: uno por entorno, no uno por servicio** (§11).

## Lo que se confirmó y no tocaría

- El sidecar-ejecutor con spec hardcodeada (§1) — con un argumento mucho mejor que "no hay herramienta".
- Nada corre privilegiado, el worker no tiene el socket (§2, validado por el escape de Judge0).
- Veredicto desde el reporte, nunca desde el exit code (§9).
- El contenedor como única frontera real; nada de aislamiento intra-JVM (§9, validado por los dos CVEs
  de Ares y por JEP 486).
- Descartar WASM (§3) — pero por reflection y closed-world, no por el temario.
- Un solo broker (§11).
- Sidecar en otro lenguaje (§12).

## Descartado y removido de este documento

Caminos que se evaluaron, no vamos a tomar, y **no aportan argumento** para la defensa. Se listan acá
para que quede registro de que se miraron; el detalle está en el original bajo `docs/_fuentes/`.

| Qué | Por qué se removió |
|---|---|
| `docker-proxy` de Dragos (filtra body con sintaxis de punto) | Proyecto chico y reciente; no califica como maduro ni es defendible como opción seria |
| Instalación y política Rego de `opa-docker-authz` | El mecanismo queda descartado por §1; el how-to no aporta |
| Inventario del ecosistema Java→WASM (CheerpJ, detalle de TeaVM/GraalVM) | El bloqueante es closed-world + reflection, no la madurez del tooling |
| Diseño del pool pre-creado "compatible con nuestra regla" (§6) | No se va a construir: el `create` no está en el camino crítico |
| Lectura de CPU desde cgroups como mecanismo de veredicto (§7) | Reemplazado por `RLIMIT_CPU`; queda como nota de una línea |
| TAP y Allure como alternativas de formato (§8) | CTRF es el único con tracción; los otros dos eran relleno comparativo |
| Detalle de stack interno de Judge0 (Rails/Resque) y de Piston (API Node) | No cambia ninguna decisión nuestra; lo que importa es el escape y el patrón |
| Health probing / reemplazo de contenedores rancios del pool (§6) | Depende del pool, que se descartó |

---

# Glosario

Vocabulario que aparece en este documento, explicado sin dar por sabido nada. Cuando el término es
importante para la defensa, agrego *por qué aparece acá*.

## Aislamiento y contenedores

**Contenedor** — Un proceso normal del sistema operativo al que el kernel le miente sobre el mundo:
ve su propio sistema de archivos, su propia red y sus propios procesos, aunque comparte el kernel con
todo lo demás. No es una máquina virtual: **no hay un segundo sistema operativo adentro**. Por eso es
barato de arrancar, y por eso un bug del kernel lo atraviesa.

**Imagen** — La plantilla congelada de la que sale un contenedor: el sistema de archivos con todo lo
que hay que tener instalado. En nuestro caso, la imagen tiene el JDK y el JAR de JUnit ya adentro.

**Kernel** — El núcleo del sistema operativo. Es quien realmente administra memoria, CPU, archivos y
red. Todos los contenedores de una máquina comparten el mismo. *Por qué importa:* ese "compartido" es
literalmente nuestro riesgo residual declarado.

**Namespaces** — El mecanismo del kernel Linux que produce la mentira: hay un namespace de red, uno de
procesos, uno de sistema de archivos, etc. Un contenedor es, esencialmente, un proceso con su propio
juego de namespaces.

**cgroups** (*control groups*) — El mecanismo hermano, que en vez de esconder cosas **limita** cuánto
puede consumir un grupo de procesos: cuánta memoria, cuánta CPU, cuántos procesos. Es lo que hay detrás
de `--memory`, `--cpus` y `--pids-limit`.

**Docker daemon** — El programa que corre permanentemente en la máquina y realmente crea, arranca y
mata contenedores. Cuando escribís `docker run`, el comando no hace nada: le manda un pedido al daemon.

**Socket de Docker** — El "teléfono" por el que se le habla al daemon (un archivo especial,
normalmente `/var/run/docker.sock`). *Por qué importa:* es la API completa del daemon y **no tiene
usuarios ni permisos**. Quien puede hablarle puede pedir un contenedor privilegiado y, con eso, tomar
la máquina entera. Por eso nuestro worker no lo tiene.

**runc** — El programa chiquito y de bajo nivel que Docker invoca para efectivamente crear el
contenedor (armar los namespaces, aplicar los cgroups, arrancar el proceso). Es el último eslabón, y
es donde aparecen los CVEs de §4.

**OCI** (*Open Container Initiative*) — El estándar que define qué es una imagen y qué es un
contenedor, para que Docker no sea el único que los pueda ejecutar. Cuando digo "contenedor OCI" me
refiero a la clase de motor, en contraste con microVM o gVisor.

**Contenedor privilegiado** (`--privileged`) — Un contenedor al que se le desactivan casi todas las
protecciones. Puede montar discos del host y modificarlo. *Por qué importa:* es lo que convirtió un bug
de lógica de Judge0 en un takeover total del host.

**Capabilities** — Linux parte el poder de "root" en unos 40 permisos separados (montar discos,
cambiar el reloj, abrir puertos bajos, etc.). `--cap-drop ALL` los saca todos: el proceso puede
llamarse root, pero no puede hacer nada de lo que root hace.

**Syscall** (*llamada al sistema*) — La única forma que tiene un programa de pedirle algo al kernel:
abrir un archivo, crear un proceso, mandar un paquete de red. Linux tiene más de 300.

**seccomp** — Un filtro que le dice al kernel qué syscalls tiene permitido usar un proceso. Docker
aplica uno por defecto. *Por qué importa:* medimos que ese default bloquea sólo ~44 de 300+, así que
"tenemos seccomp" es más débil de lo que suena.

**AppArmor / SELinux** — Dos sistemas de control de acceso obligatorio: reglas a nivel del sistema que
dicen qué archivos y recursos puede tocar un programa, independientemente de los permisos normales.
AppArmor es el habitual en Ubuntu, SELinux en Red Hat.

**User namespace / `userns-remap`** — Un namespace que **remapea los identificadores de usuario**: el
usuario root *dentro* del contenedor corresponde a un usuario común y corriente *afuera*. Así, si el
código escapa, escapa como don nadie y no como administrador. Es la mitigación de §4 que recomiendo probar.

**`--read-only`** — Arranca el contenedor con su sistema de archivos en sólo lectura. Nada se puede
escribir, salvo donde explícitamente se habilite.

**tmpfs** — Un sistema de archivos que vive en memoria RAM, no en disco. Es la excepción escribible que
se le da a un contenedor read-only: rápido, acotado, y desaparece cuando el contenedor muere.

**`--network none`** — Arranca el contenedor sin ninguna interfaz de red, ni siquiera para hablar con
la propia máquina. *Por qué importa:* es nuestra defensa central. Sin red, el código del alumno no puede
alcanzar a los otros once microservicios aunque se comprometa.

**Bind-mount** — Hacer que un archivo o carpeta del host aparezca en otro lugar, típicamente dentro del
contenedor. Es "montar" en el sentido de Unix: el mismo contenido visible desde dos rutas.

**Symlink** (*enlace simbólico*) — Un archivo que no tiene contenido propio: es un cartel que dice
"lo que buscás está en tal otra ruta". *Por qué importa:* si un programa escribe en un archivo sin
verificar que sea un archivo de verdad, y el atacante lo reemplazó por un symlink, la escritura termina
donde el atacante quiso. Es la clase de bug que tumbó a Judge0 dos veces y a runc tres.

**Hard link** (*enlace duro*) — Parecido pero más sutil: dos nombres distintos que apuntan al mismo
contenido real en disco. Borrar uno no borra el contenido. No es un cartel: es un segundo nombre legítimo.

**Path traversal** — El ataque de meter `../` en una ruta para salir de la carpeta donde se suponía que
estabas (`entregas/../../etc/passwd`). Ya lo probamos y estamos cubiertos.

**procfs / `/proc`** — Una carpeta falsa que el kernel expone para verse y configurarse a sí mismo.
Escribir en ciertos archivos de ahí ejecuta acciones reales del sistema — `core_pattern`, por ejemplo,
define qué programa se ejecuta cuando otro crashea. Conseguir escritura en `/proc` suele equivaler a
tomar la máquina.

**chroot** — Mecanismo viejo que cambia cuál carpeta ve un proceso como si fuera la raíz `/`. Es el
antepasado de los contenedores; solo, es débil.

**Escape / breakout** — Que el código de adentro del contenedor consiga ejecutar algo afuera, en el
host. Es el evento que todo nuestro diseño existe para evitar.

**microVM / Firecracker** — Una máquina virtual real pero minimalista, con su propio kernel, que
arranca en decenas de milisegundos. Es lo que usa AWS Lambda. Aísla mucho mejor que un contenedor
porque el kernel *no* se comparte, pero necesita virtualización por hardware.

**gVisor** — Punto intermedio: un kernel Linux reimplementado en espacio de usuario, que intercepta las
syscalls del contenedor en vez de dejarlas llegar al kernel real. Más seguro que runc, más lento.

**WASM / WebAssembly** — Un formato de código binario portable que corre en un sandbox muy restringido
por diseño. **WASI** es su interfaz para pedirle cosas al sistema operativo. La opción de §3, descartada.

**`isolate`** — El sandbox usado en las Olimpiadas de Informática (y por Judge0 y Piston). Hace por
ejecución lo mismo que un contenedor, pero sin crear un contenedor: namespaces y cgroups directos.

## Cómo se nombra y se mide la seguridad

**Vulnerabilidad** — Un defecto que permite hacer algo que no se debería poder hacer.

**Exploit** — El código o la técnica concreta que aprovecha una vulnerabilidad.

**CVE** (*Common Vulnerabilities and Exposures*) — El identificador público y único de una
vulnerabilidad. `CVE-2024-28185` = una vulnerabilidad específica, reportada en 2024.

**CVSS** — El puntaje de gravedad, de 0 a 10. **10.0 es lo máximo.** Los dos primeros CVEs de Judge0
son 10.0; el de Ares es 8.2.

**CWE** — La *clasificación* del tipo de error, distinta del CVE que es el caso puntual. `CWE-501
Trust Boundary Violation` significa "el sistema confió en algo que venía del lado no confiable".

**NVD / GHSA / advisory** — Dónde se publican. NVD es la base de datos del gobierno de EE.UU.; GHSA es
la de GitHub; un *advisory* es el comunicado que emite el propio proyecto afectado.

**Superficie de ataque** — El conjunto de puntos por donde alguien podría intentar entrar. Achicarla es
el objetivo de `--cap-drop ALL`, `--network none`, etc.

**Vector** — Un camino de ataque concreto. "El vector de red" = intentar alcanzar otros servicios.

**Modelo de amenaza** — La respuesta explícita a "¿de quién nos defendemos y qué queremos proteger?".
El nuestro: de un alumno que puede ser malicioso, protegiendo el host, los otros servicios y **la
integridad del veredicto**.

**Riesgo residual** — Lo que queda sin cubrir después de aplicar todas las mitigaciones, y que se acepta
conscientemente. Declararlo es señal de rigor; no declararlo es señal de que no se pensó.

**Hardening** — Endurecer: aplicar configuraciones que reducen lo que un atacante puede hacer.

**Mitigación** — Una medida que reduce el impacto o la probabilidad de un riesgo, sin necesariamente
eliminarlo.

**SSRF** (*Server-Side Request Forgery*) — Engañar al servidor para que él haga un pedido de red en
nombre del atacante, alcanzando cosas internas que el atacante no alcanzaría solo. Es el tercer CVE de Judge0.

**Race condition** (*condición de carrera*) — Un bug donde el resultado depende de quién llegue primero.
El atacante cambia algo justo entre que el programa lo verifica y lo usa. Es la familia de bugs de runc.

**Multi-tenancy** — Que varios clientes o usuarios distintos compartan la misma infraestructura.
**Co-mingling** es el problema asociado: que los datos o recursos de uno se mezclen con los de otro.

**AuthZ plugin** — Un componente que Docker consulta antes de ejecutar cada pedido, para preguntarle
"¿esto se permite?". **OPA** (*Open Policy Agent*) es el motor de políticas más usado, y **Rego** es su
lenguaje. Todo esto es §1, evaluado y descartado.

## Java y la JVM

**JVM** (*Java Virtual Machine*) — El programa que ejecuta código Java. Java no se compila a
instrucciones del procesador sino a un formato intermedio que la JVM interpreta.

**Bytecode / archivos `.class`** — Ese formato intermedio. `javac` convierte `.java` (texto que escribe
una persona) en `.class` (bytecode que ejecuta la JVM).

**`javac` / `java`** — El compilador y el ejecutor. Los dos programas que corremos dentro del
contenedor, y nada más. *Por qué importa:* `javac` es quien se come 1.0–2.3 s por invocación, y son dos.

**Classpath** — La lista de lugares donde la JVM busca los `.class` que necesita. **El orden importa:
gana el primero que aparece.** *Por qué importa:* es exactamente la palanca de la mitigación 2 de §9 —
poniendo los tests del profesor primero, su clase gana sobre una del alumno con el mismo nombre.

**Paquete** (*package*) — El "apellido" de una clase, que la agrupa y le da nombre completo
(`com.ejemplo.Solucion`). *Por qué importa:* si el alumno declara su clase en el mismo paquete que los
tests, puede hacerse pasar por una clase de soporte del profesor. Ese es el vector nuevo de §9.

**Sombrear** (*shadowing*) — Que una clase tape a otra del mismo nombre, porque aparece antes en el
classpath. Es el mecanismo del ataque anterior.

**Reflection** — La capacidad de un programa Java de inspeccionarse y manipularse a sí mismo en tiempo
de ejecución: descubrir qué clases existen, crear objetos e invocar métodos cuyo nombre no estaba
escrito en el código. *Por qué importa:* JUnit funciona enteramente así, y es lo que hace imposible
compilar todo esto a WASM.

**Class loader** — El componente de la JVM que carga clases a medida que se necesitan. Se puede
escribir uno propio, y esa es la vía de reemplazo que evaluaba Ares.

**Closed-world assumption** — El supuesto de que en tiempo de compilación ya se sabe qué clases van a
existir. Lo exigen los compiladores a WASM y `native-image`. **Es incompatible con reflection**, y con
recibir el código del alumno en runtime.

**SecurityManager** — El mecanismo clásico de Java para restringir qué podía hacer el código no
confiable (leer archivos, abrir red). **Está deshabilitado permanentemente desde Java 17/21.** Su
desaparición es la razón por la que el contenedor es nuestra única frontera.

**JEP** (*JDK Enhancement Proposal*) — El documento formal donde se propone y justifica un cambio en
Java. **JEP 411** deprecó el SecurityManager; **JEP 486** lo apagó definitivamente.

**`System.exit(0)`** — La instrucción que termina la JVM inmediatamente informando "todo bien". *Por
qué importa:* si leyéramos el veredicto del código de salida del proceso, cualquier alumno aprobaría
poniendo esa línea. Por eso leemos el reporte.

**Exit code** (*código de salida*) — El número que un proceso deja al terminar: 0 = bien, distinto de
0 = falló. Poco confiable acá, por lo anterior.

**`InvocationTargetException`** — La excepción con que Java envuelve un error ocurrido dentro de un
método invocado por reflection. Una subclase manipulada de ésta fue el primer escape de Ares.

**OOM** (*Out Of Memory*) — Quedarse sin memoria. **`OOMKilled`** es la marca que pone Docker cuando el
cgroup mató al contenedor por excederse. *Por qué importa:* medimos que con la JVM bien configurada
**ella muere primero**, así que ese flag viene en `false` y no sirve para detectar el caso.

**AppCDS** — Optimización de Java que precarga clases para acelerar el arranque. La habíamos previsto y
la descartamos al medir que el arranque no era el problema (38–82 ms).

**GraalVM / `native-image`** — Compilador que convierte una aplicación Java en un ejecutable nativo, sin
JVM. Rápido de arrancar, pero exige closed-world.

**TeaVM** — Compilador de bytecode Java a WASM. El más maduro, y aun así sólo listo para navegador.

**Maven** — La herramienta estándar de build y gestión de dependencias de Java. La descartamos: tarda
segundos resolviendo plugins y sin red no puede descargar nada. **Maven Enforcer** es su complemento de
reglas, que es lo que usó Ares para validar paquetes.

**stdin / stdout / stderr** — Los tres canales estándar de un proceso: entrada, salida normal y salida
de error. *Por qué importa:* el bundle entra por `stdin` (porque `docker cp` no funciona con
`--read-only`), y `stdout` es el tercer canal de fuga de §10.

**tar** — Formato que empaqueta muchos archivos y carpetas en un solo flujo de bytes. Es como mandamos
el bundle. *Por qué importa:* un tar puede contener symlinks y hard links, y ahí está el riesgo de §9.

## JUnit y pruebas

**JUnit 5** — El framework estándar de pruebas unitarias en Java. Internamente son dos partes:
**Jupiter** (cómo se escriben los tests, las anotaciones) y **JUnit Platform** (el motor que los
descubre y los ejecuta).

**ConsoleLauncher** — El ejecutable de línea de comandos de JUnit Platform. Es lo que corremos dentro
del contenedor (`junit-platform-console-standalone.jar`), en vez de Maven.

**Descubrimiento** (*discovery*) — La fase en que JUnit averigua qué tests existen. **`--scan-classpath`**
le dice que revise todo el classpath buscando clases de test — costoso: 1.2–2.9 s medidos.
**`--select-class`** le dice directamente cuáles son, y evita el escaneo. Esa es la mejora 4.

**`--include-classname`** — Un filtro adicional por expresión regular sobre el nombre de la clase. Hay
un issue conocido donde `--select-class` no alcanza sin esto y JUnit "no encuentra nada".

**Anotación** — Una marca que se escribe sobre una clase o método y que las herramientas leen
(`@Test`, `@Tag`). **`@Tag("oculto")`** es cómo el profesor marcaría los tests que el alumno no debe ver.
**`@ExtendWith`** es cómo se le enchufa un comportamiento extra a JUnit — por ejemplo, capturar la
salida test por test.

**Aserción** — La línea que verifica el resultado esperado (`assertEquals(4, sumar(2,2))`). *Por qué
importa:* cuando falla, el mensaje **incluye el valor esperado**. En un test oculto, ese mensaje es
literalmente la solución.

**Stack trace** — El listado de métodos por los que pasó la ejecución hasta el error. Útil para
depurar, y otro canal de fuga.

**XML de JUnit** — El formato de reporte de resultados que emite JUnit. *Dato incómodo:* no tiene
especificación oficial; es una convención de facto de treinta años con variantes por framework.

**CTRF** (*Common Test Report Format*) — El intento actual de estandarizar eso: un esquema JSON único,
agnóstico de lenguaje. Recomendación de §8: adoptar su *forma* internamente, no como contrato con T05.

**TAP / Allure / TRX** — Otros formatos de reporte. TAP es minimalista, Allure es rico pero requiere su
propio tooling, TRX es el de .NET.

**Flaky** — Un test que a veces pasa y a veces falla sin que el código cambie. Es lo que nos pasaría con
falsos TIMEOUT si no arreglamos los relojes de §7.

## Tiempo, recursos y rendimiento

**Reloj de pared** (*wall time*) — El tiempo que mediría un cronómetro en la pared. Incluye todo lo que
el programa pasó esperando, incluso sin usar el procesador.

**Tiempo de CPU** — Sólo el tiempo en que el procesador estuvo efectivamente trabajando para ese
programa. *Por qué importa:* **es inmune a que el host esté saturado.** Ese es el corazón de la
recomendación 1: medir el presupuesto del alumno en CPU elimina la varianza de 1.8–5.2 s por
construcción, no por margen.

**`RLIMIT_CPU` / `ulimit -t`** — El mecanismo con que se le pide al kernel "matá a este proceso cuando
consuma N segundos de CPU". Es lo que usa `isolate` por debajo, y lo que proponemos usar.

**`SIGXCPU` / señal** — Las señales son avisos que el kernel manda a un proceso. `SIGXCPU` es
específicamente "agotaste tu tiempo de CPU".

**Thread** (*hilo*) — Una línea de ejecución dentro de un proceso. Un programa puede tener varias
corriendo en paralelo. *Advertencia de §7:* el tiempo de CPU **se suma entre hilos**, así que dos hilos
girando queman el presupuesto al doble de velocidad.

**Camino crítico** — La secuencia de pasos que determina cuánto tarda el total. Optimizar algo que no
está en el camino crítico no mejora nada. *Por qué importa:* es todo el argumento de §6 — el `create`
del contenedor no está ahí, así que el pool caliente no compra nada.

**Contención** — Cuando varios procesos pelean por el mismo recurso (típicamente CPU) y todos se
enlentecen. Es la causa de nuestra varianza medida.

**Polling** — Preguntar repetidamente "¿ya está?" en vez de que te avisen. Funciona, pero es
ineficiente y llega tarde.

**Fork bomb** — Un programa que se reproduce sin parar hasta agotar la tabla de procesos del sistema.
Lo contiene `--pids-limit`, y ya lo verificamos.

**Caché / invalidación** — Guardar un resultado costoso para reutilizarlo. **La invalidación** es
decidir cuándo ese guardado dejó de ser válido. *Por qué importa:* si cacheamos los tests compilados por
`(desafioId, version)` y una versión puede editarse en el lugar, la caché serviría veredictos viejos —
y acá un veredicto equivocado es una nota equivocada.

## Microservicios, mensajería y patrones

**Microservicio** — Un servicio chico, desplegable por separado, dueño de sus propios datos.

**Bounded context** (DDD) — En Domain-Driven Design, una frontera dentro de la cual los términos del
negocio tienen un significado único y consistente. *Por qué importa:* la partición en 12 temas es
temática, no por bounded context, y el nuestro no tiene dominio de negocio propio.

**Anti-corruption layer (ACL)** — Una capa de traducción que impide que el modelo de un sistema externo
se filtre al nuestro. En nuestro caso: lo que convierte el XML de JUnit en nuestro modelo de veredicto.
**Ojo con la sigla:** en §11 "ACL" significa otra cosa completamente — *access control list*, la lista
de permisos de RabbitMQ. Son dos conceptos sin relación que comparten sigla.

**Función pura** — Una función que con la misma entrada da siempre la misma salida y no depende de
nada externo. *Por qué importa:* es lo que hace al sandbox reproducible y lo que evita la dependencia
circular con T05.

**Broker** — El servidor de mensajería intermediario (acá, **RabbitMQ**). Recibe mensajes de quien
publica y los entrega a quien corresponde.

**Exchange** — La "central de correo" del broker: recibe el mensaje publicado y decide a qué colas
copiarlo, según las reglas de ruteo.

**Cola** — La fila donde el mensaje espera hasta que un consumidor lo tome.

**Binding** — La regla que conecta un exchange con una cola ("copiá acá los mensajes de tal tipo").

**vhost** (*virtual host*) — Una separación lógica dentro de un mismo broker: cada vhost tiene sus
propios exchanges, colas y permisos, invisibles para los demás. *Por qué importa:* **los exchanges no
cruzan vhosts**, y por eso un vhost por servicio rompería la coreografía de eventos.

**Shovel / federation** — Los mecanismos de RabbitMQ para pasar mensajes de un vhost o broker a otro.
Es lo que haría falta —y sería un parche— si cada servicio tuviera su vhost.

**EDA** (*Event-Driven Architecture*) — Arquitectura donde los servicios se comunican publicando hechos
ocurridos ("EjecucionFinalizada") en lugar de llamarse directamente.

**Coreografía** — El estilo de EDA donde no hay director: cada servicio reacciona a los eventos que le
interesan. Lo opuesto es la **orquestación**, donde un coordinador central manda.

**Ack manual** — Que el consumidor confirme explícitamente "procesé este mensaje" recién cuando terminó,
en vez de que se dé por entregado al recibirlo. Si el consumidor se cae antes, el mensaje se reentrega.

**Prefetch** — Cuántos mensajes se le entregan a un consumidor antes de que confirme los anteriores.
Acotarlo evita que un worker acapare trabajo que no puede procesar.

**DLQ** (*Dead Letter Queue*) — La cola donde van a parar los mensajes que fallaron repetidamente, para
que no bloqueen el sistema reintentándose para siempre. Un mensaje así se llama *envenenado*.

**Outbox transaccional** — Patrón que resuelve "guardé en la base pero no llegué a publicar el evento":
el evento se escribe en una tabla dentro de la misma transacción, y un proceso aparte lo publica después.
Hace que el estado inconsistente sea inalcanzable.

**Sidecar** — Patrón donde un proceso auxiliar corre al lado de la aplicación, en su propio contenedor,
resolviendo algo transversal. *Por qué importa:* el nuestro es quien tiene el socket de Docker, y en §1
proponemos que además sea el ejecutor. **Envoy** (el sidecar de Istio, escrito en C++) es el ejemplo
canónico de que el sidecar no tiene por qué estar en el lenguaje de la aplicación.

**Circuit Breaker** — Patrón que corta las llamadas a una dependencia que está fallando, para no
apilar pedidos condenados. Lo aplicamos sobre el daemon de Docker.

**Health Check** — El endpoint donde el servicio dice si está sano. *Nuestro giro:* "saturado" no es
"enfermo" — un sandbox con la cola llena está funcionando perfectamente.

**Rate Limit** — Límite de cuánto se acepta. *Nuestro giro:* lo aplicamos por concurrencia (cuántas
ejecuciones a la vez) y no por frecuencia (cuántas por minuto).

**SAGA** — Patrón para transacciones distribuidas con pasos compensables. No aplica acá: somos un paso
de la coreografía y no tenemos nada que compensar.

**BFF** (*Backend For Frontend*) — Una capa de backend hecha a medida de una interfaz. No aplica: vive
en T05.

**API Gateway** — La puerta única de entrada al sistema desde afuera. Es el extra de T01.

**`202 Accepted`** — El código HTTP que significa "recibí tu pedido y lo voy a procesar, pero todavía
no está listo". Es lo que devuelve nuestra API, y es lo que hace al servicio asincrónico.

**Serverless / AWS Lambda** — Modelo donde uno sube una función y el proveedor se encarga de ejecutarla
bajo demanda. **Warming pool / active pool** son los dos conjuntos de instancias que Lambda mantiene:
las precalentadas esperando, y las ya asignadas a un usuario. El patrón de §6.

**Proxy / HAProxy** — Un intermediario que recibe pedidos y decide si los reenvía. HAProxy es uno muy
usado, y es la base de los proxies de socket de Docker que evaluamos y descartamos en §1.
