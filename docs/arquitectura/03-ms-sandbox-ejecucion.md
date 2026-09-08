# `ms-sandbox` — Ejecución aislada de código Java

> **Tema 06 — Sandbox / Runtime.** Grupo 8.
> Alcance de este documento: **cómo se ejecuta el código aislado**, **cómo llega la entrega desde T05**, **de dónde salen los tests** y **qué alternativas de recursos existen**.
> Deliberadamente **fuera de alcance acá**: Gateway, Service Discovery y el resto de la arquitectura del curso (ver `01-panorama-microservicios-backend.md`).

**Supuestos vigentes**

| Supuesto | Estado |
|---|---|
| Lenguaje soportado | **Java únicamente**, por ahora |
| Grupo con el que se coordina | **Tema 05 — Desafíos Prácticos** |
| Arquitectura de plataforma | Definida por la cátedra: gateway como única entrada, sin comunicación directa entre servicios, base por servicio, bus para lo asincrónico. Ver [`01-panorama-microservicios-backend.md`](./01-panorama-microservicios-backend.md) §1 |
| Núcleo de ejecución | **Verificado contra Docker real** con 10 entregas de prueba. Lo medido está en §1.4 |
| Investigación externa | Incorporada. Los cambios que trajo están marcados con **[IE]** y detallados en [`hallazgos-investigacion-sandbox.md`](../../hallazgos-investigacion-sandbox.md) |

> **Nota sobre las marcas [IE].** Los puntos marcados así cambiaron por la investigación de fuentes
> externas (CVEs reales de Judge0 y Ares, papers, documentación oficial). El hallazgo transversal:
> **los cinco escapes documentados en plataformas equivalentes fueron bugs de lógica de la propia
> plataforma, no exploits de kernel.** Eso mueve el foco del riesgo desde "el kernel es compartido"
> hacia el código que arma el bundle, lo inyecta y lee el reporte — o sea, hacia §1.3, §1.5 y §2.2.

---

## Índice

1. [Cómo se ejecuta código aislado](#1-cómo-se-ejecuta-código-aislado)
2. [Cómo llega la entrega desde T05](#2-cómo-llega-la-entrega-desde-t05)
3. [De dónde salen los tests](#3-de-dónde-salen-los-tests)
4. [Alternativas a nivel de recursos](#4-alternativas-a-nivel-de-recursos)
5. [Máquina de estados de una ejecución](#5-máquina-de-estados-de-una-ejecución)
6. [Contrato completo de API](#6-contrato-completo-de-api)
7. [Definiciones abiertas con T05](#7-definiciones-abiertas-con-t05)

---

## 1. Cómo se ejecuta código aislado

### 1.1 De qué está hecho el aislamiento

"Sandbox" no es un mecanismo, son cinco mecanismos del kernel de Linux que Docker compone. Conviene tenerlos separados, porque en la defensa la pregunta es *qué* protege de *qué*.

| Mecanismo | Controla | Flag |
|---|---|---|
| **Namespaces** | Qué **ve** el proceso | |
| ├ PID | Solo sus propios procesos; el suyo es PID 1 | (implícito) |
| ├ Mount | Su propio árbol de archivos | (implícito) |
| ├ Network | **Sin red**: solo loopback, sin DNS, sin ruta | `--network none` |
| └ User | El root de adentro no es el root del host | `--user 1000:1000` |
| **cgroups v2** | Qué **consume** | `--memory`, `--cpus`, `--pids-limit` |
| **Capabilities** | Qué operaciones privilegiadas puede hacer | `--cap-drop ALL` |
| **seccomp** | Qué syscalls puede invocar (el perfil default de Docker bloquea ~44 de 300+) | default + `--security-opt no-new-privileges` |
| **Filesystem** | Dónde puede escribir | `--read-only` + `--tmpfs` |

**Lo que esto no cubre — [IE], ahora con nombre y número.** Antes acá decía "el kernel es compartido". Sigue siendo cierto, pero era una frase vaga. La superficie residual son **dos cosas nombrables**:

1. **El kernel del host, verbatim.** Un contenedor `runc` filtra 10 de 28 identificadores del host medidos (versión de kernel, modelo y microcódigo de CPU, RAM total, producto del BIOS, número de serie de los discos), y el string del kernel del guest es **idéntico byte a byte** al del host. Para nuestro modelo de amenaza el impacto es bajo —no hay secreto ahí— y cerrarlo requeriría microVM.
2. **`runc`**, el binario que efectivamente crea el contenedor. Tiene una familia de bugs recurrente y con nombre: **races de procfs y mounts** (CVE-2019-19921, CVE-2023-27561, CVE-2023-28642, y tres a la vez el 5 de noviembre de 2025: CVE-2025-31133, CVE-2025-52565, CVE-2025-52881 — esta última puede evadir AppArmor y SELinux).

> **El matiz que nos favorece, y que conviene decir en la defensa:** casi todos los CVEs de `runc` requieren que el atacante **controle la spec del contenedor** (mounts, symlinks en la config). En nuestro diseño el alumno nunca la controla: está hardcodeada del lado del sidecar. El mismo diseño que impide pedir `Privileged: true` cierra la clase de bug dominante de `runc`.

**Dato para calibrar el riesgo, no para cambiar de motor** ([arXiv:2606.08433](https://arxiv.org/abs/2606.08433), mayo 2026): en una ventana de 24 meses, **4 de 4 CVEs de `runc` fueron de escape**, contra 0 de 3 en gVisor. Elegimos la clase de motor con la peor postura de escape medida, y lo hacemos con conocimiento: lo compensamos con `--network none`, que corta el vector que nos importa, y lo declaramos como residual.

**Dos mitigaciones baratas que todavía no aplicamos** y que conviene evaluar antes de la demo:

| Mitigación | Costo | Qué compra |
|---|---|---|
| **`userns-remap` en `daemon.json`** | Una línea de config | El root del contenedor deja de ser root del host. Las advisories de `runc` lo nombran como uno de los mejores mecanismos contra breakouts. *Nota:* no habilita user-ns **para el alumno** — seccomp sigue bloqueando `unshare(CLONE_NEWUSER)` adentro |
| **Perfil seccomp propio** (`--security-opt seccomp=perfil.json`) | Un archivo | El default de Docker deja **361 syscalls permitidas**. Corremos `javac` y `java` y nada más: hay muchísimo para recortar |

Y una verificación operativa: **confirmar qué `runc` corre en la máquina de la demo.** Docker Engine 29.x trae `runc` 1.3.5, que incluye el trío de noviembre de 2025 — pero Ubuntu Noble marcó los tres como *"Ignored — backport too intrusive"* en el paquete de `apt`.

### 1.2 Por qué el aislamiento de red es la defensa central

En una arquitectura con Service Discovery, cualquier proceso con red puede resolver `ms-banco` por nombre y pegarle. Si el código del alumno tuviera red, `POST http://ms-banco/api/v1/creditos` sería un bypass de todas las reglas de negocio de la plataforma.

> **`--network none` no es una buena práctica genérica: es lo que impide que el sandbox sea un agujero en la integridad del sistema entero.**

Corolario operativo: el código y los tests **entran por copia**, nunca descargándose. Las imágenes por lenguaje vienen precocinadas con todo adentro.

### 1.3 Secuencia concreta de una ejecución

> Esta secuencia está **verificada contra Docker real** con diez entregas de prueba, seis de ellas hostiles. Dos de los cambios respecto de la versión original de este documento —la entrada por `stdin` y el reloj partido por fase— salieron de ahí, no de la teoría (§1.4). Un tercero —**que el reloj del alumno se mida en tiempo de CPU**— salió de la investigación externa y **todavía no está verificado contra Docker**: es lo primero a probar (§1.4d).

```
 1. El worker recibe el job: el broker le entrega un mensaje de `sandbox.jobs`.

 2. VALIDA el bundle antes de tocar Docker (§2.2):
      cada `ruta` sin `..`, sin barra inicial, terminada en `.java`;
      al menos un archivo con `rol: TEST`;
      [IE] ningún archivo con rol SOLUCION declara un PAQUETE RESERVADO
           (el de los tests del profesor) → si lo hace, 400. Ver §1.6d.

 3. Arma el bundle en memoria, como un tar:
      src/   ← archivos con rol SOLUCION
      test/  ← archivos con rol TEST
      (junit-console.jar ya vive DENTRO de la imagen)

 4. docker create -i --network none --read-only --user 10001:10001
                  --memory 512m --memory-swap 512m --cpus 2
                  --pids-limit 64 --cap-drop ALL
                  --security-opt no-new-privileges
                  --tmpfs /tmp:rw,nosuid,size=128m
                  --label sandbox.job={ejecucionId}
                  -e TIMEOUT_COMPILE_MS=20000     ← reloj de PARED, plataforma
                  -e CPU_TESTS_S=10               ← [IE] reloj de CPU, ALUMNO
                  -e TIMEOUT_TESTS_MS=30000       ← reloj de PARED, backstop
                  java-runner:21

 5. docker start -ai {containerId}
      └─ el tar del bundle se escribe en el STDIN del contenedor;
         el script de arranque lo desempaqueta dentro del tmpfs.

 6. El script corre las fases y le pone reloj a cada una:
      compilar solución → compilar tests → correr tests
      [IE] la fase de tests se acota por TIEMPO DE CPU, no por reloj de pared:
           `ulimit -t $CPU_TESTS_S` antes de invocar java  → RLIMIT_CPU
           el kernel manda SIGXCPU al agotarse. Es el mismo mecanismo
           que usa `isolate` (Judge0, Piston) por debajo.
           El reloj de pared queda como backstop, generoso, para el
           proceso que DUERME en vez de quemar CPU.

 7. El contenedor emite el reporte por STDOUT, codificado, en línea.
      No hay `docker cp` de vuelta: el rootfs es read-only
      y el tmpfs muere con el contenedor.

 8. El worker espera con un reloj de pared propio, como RED DE ÚLTIMA
    INSTANCIA (suma de los relojes de adentro + margen).
      └─ si se vence → docker kill, y el veredicto es ERROR_INTERNO,
         no TIMEOUT: significa que falló el reloj de adentro.

 9. docker inspect → .State.ExitCode  y  .State.OOMKilled

10. docker rm -f {containerId}

11. Normaliza el reporte y persiste el resultado.
```

Cuatro detalles que parecen menores y no lo son:

- **El reloj está partido en tres, y solo uno es del alumno — [IE].** Antes eran dos, los dos de pared. Compilar sigue siendo costo de plataforma (§1.4a), pero además **el reloj del alumno pasó a medirse en tiempo de CPU**, que es lo que hace `isolate` y por lo tanto Judge0 y Piston. El motivo está en §1.4d: el tiempo de CPU es inmune a la contención del host **por construcción**, así que elimina la causa de los `TIMEOUT` intermitentes en vez de taparla con margen.
- **El reloj de pared del worker cambió de rol.** Antes era *el* timeout; ahora es la red de seguridad por si el `timeout` de adentro no dispara. Cuando salta, el problema es del sandbox, no de la entrega.
- **`docker inspect` desambigua, pero ya no alcanza.** Exit `137` = SIGKILL, que puede ser el kill por timeout **o** el OOM-killer del cgroup, y `.State.OOMKilled` los separa. Pero con la JVM bien configurada el OOM ni siquiera llega por ahí: ver §1.6b.
- **El label `sandbox.job` va desde el `create`.** Es lo que después le permite al janitor barrer huérfanos y al sidecar acotar sobre qué contenedores acepta órdenes.

### 1.4 Lo que se midió, y qué cambió por eso

Se corrió un prototipo descartable del núcleo —imagen `eclipse-temurin:21-jdk` con `junit-platform-console-standalone` horneado, más un worker que arma el contenedor y normaliza el reporte— contra diez entregas de prueba. Los diez casos quedaron contenidos y clasificados. Estos son los hallazgos que **contradicen** lo que este documento suponía.

#### a) El presupuesto de tiempo no puede ser uno solo

El primer intento del camino feliz —tres tests de `a + b`, con los límites que §4.3 proponía (256 MB, 1 CPU, 10 s)— **dio `TIMEOUT`**. Compilar se había comido 8.8 s del presupuesto. El código del alumno corre en microsegundos: prácticamente **todo el reloj es toolchain**.

| Límites | Muro | compilar solución | compilar tests | JVM vacía | correr tests |
|---|---|---|---|---|---|
| 256 MB / 1 CPU | 8.5 s | 1.7 s | 2.2 s | **77 ms** | 2.9 s |
| 512 MB / 2 CPU | 5.1 s | 1.3 s | 1.3 s | **55 ms** | 1.4 s |
| 1 GB / 4 CPU | 4.2 s | 1.1 s | 1.0 s | **38 ms** | 1.2 s |

> **Un `timeoutMs` único le cobra al alumno el costo de compilar.** El reloj que T05 propone tiene que significar **solo la fase de tests**. La compilación tiene un presupuesto fijo de plataforma, porque no depende de lo que el alumno escribió sino de cuánta CPU le dimos al contenedor.

#### b) El arranque de la JVM no es el problema

`java -version` dentro del contenedor: **38–82 ms**. Eso invalida a AppCDS como palanca principal, que era lo que proponían §4.2 y [`04-ms-sandbox-worker.md`](./04-ms-sandbox-worker.md) §16. El costo real está en otras dos partes: **`javac`** (1.0–2.3 s por invocación) y el **escaneo de classpath de JUnit** (1.2–2.9 s).

#### c) Las dos pasadas de `javac` cuestan poco y valen mucho

Compilar solución y tests por separado permite separar la culpa: si falla la primera es del alumno (`ERROR_COMPILACION`), si falla la segunda es de la suite de T05 (`SUITE_INVALIDA`). Cuesta un arranque de JVM extra. Medido sobre tres corridas de cada variante: **~0.6 s sobre ~5.5 s, un 10%**.

> Se **mantienen las dos pasadas.** Saber de quién es la culpa vale más que ese 10%.

#### d) La varianza es grande, y eso cambia cómo se fija el número

La misma entrega, con los mismos límites, en corridas seguidas: la fase de tests varió entre **1.8 s y 5.2 s**. Un timeout ajustado al promedio convierte entregas correctas en `TIMEOUT` de forma intermitente — el peor modo de falla posible, porque no es reproducible y el alumno no puede distinguirlo de un bug suyo.

**[IE] La conclusión de acá cambió, y es el cambio más importante que trajo la investigación externa.**

Este documento decía: *"el presupuesto se fija con el peor caso observado más un margen"*. Eso trata el síntoma. La causa de esa varianza es **contención del host** —otras ejecuciones del pool compitiendo por CPU— y hay una forma de eliminarla en vez de acolcharla:

> **El tiempo de CPU no cuenta el tiempo en que el sistema operativo le dio el procesador a otro.** Si el presupuesto del alumno se mide en CPU y no en reloj de pared, dos corridas idénticas dan el mismo número **aunque el host esté saturado**. Los `TIMEOUT` intermitentes desaparecen porque desaparece su causa.

Es exactamente lo que hace `isolate`, el sandbox que usan Judge0 y Piston, con tres límites de semántica distinta: `--time` (CPU), `--extra-time` (gracia antes de matar, que permite reportar el tiempo real igual) y `--wall-time` (reloj de pared, que **no** se detiene cuando el proceso pierde la CPU).

Y los aplican **por fase**: Judge0 1.4 tenía, solo para compilar, 5 s de CPU + 2 s de extra + 10 s de wall, con la ejecución en un presupuesto aparte. La solución de la industria a *"compilar se come el reloj del alumno"* no es agrandar el presupuesto: es separarlos y medir el del alumno en CPU.

Docker no expone un límite de CPU como `isolate`, pero el mecanismo de abajo sí está disponible: **`RLIMIT_CPU`** vía `ulimit -t` en el entrypoint. El kernel manda `SIGXCPU` al agotarse. Es literalmente lo que usa `isolate` por debajo.

> **Advertencia de dimensionamiento:** el tiempo de CPU **se suma entre threads**. Con `--cpus 2`, un alumno que lanza dos threads girando quema el presupuesto al doble de velocidad. Para anti-trampa eso es lo deseable — pero significa que el número **no se puede dimensionar mirando una corrida single-thread**. Hay que medirlo con la carga concurrente real.

### 1.5 Cómo entra el bundle: `stdin`, no `docker cp`

Esta sección decía antes que `docker cp` le ganaba al bind mount. Sigue siendo cierto que el bind mount se descarta, pero `docker cp` **no funciona**, y el prototipo lo encontró de la única manera confiable: probándolo.

| | Bind mount (`-v host:/work`) | `docker cp` | **`stdin` (tar)** |
|---|---|---|---|
| ¿Expone un path del host? | **Sí** | No | No |
| ¿Convive con `--read-only`? | Sí | **No** | Sí |
| ¿Convive con `--tmpfs` en el destino? | n/a | **No** | Sí |
| Qué necesita del sidecar | montajes | `PUT /archive`, que es una escritura | nada extra |

**Los dos motivos por los que `docker cp` queda afuera** son fallas concretas, no objeciones de diseño:

1. Con el rootfs read-only el demonio rechaza la copia: `Error response from daemon: container rootfs is marked read-only`. Y `--read-only` no se negocia.
2. Aunque el rootfs fuera escribible, la secuencia anterior copiaba a `/work` y **después** montaba un `tmpfs` encima de `/work` al arrancar. El montaje tapa lo copiado: los archivos del alumno desaparecerían antes de que el script los mire.

> **El bundle entra por el `stdin` del contenedor, como un tar, y el script de arranque lo desempaqueta en el `tmpfs`.** No toca el rootfs, no necesita montajes, y el reporte vuelve por `stdout`.

Sale gratis un beneficio para el sidecar: si nadie hace `docker cp`, la lista blanca del proxy **no necesita habilitar `PUT /containers/{id}/archive`**, que es literalmente una escritura al filesystem de un contenedor. Ver [`05-ms-sandbox-patrones.md`](./05-ms-sandbox-patrones.md) §1.

#### [IE] El tar es entrada hostil, y es exactamente la clase de bug que tumbó a Judge0

Elegir el tar por `stdin` resolvió el problema de `--read-only`, pero **movió el riesgo, no lo eliminó**. La extracción de un tar con paths controlados por el atacante es la familia de bugs que produjo los dos CVEs 10.0 de Judge0 y los tres de `runc` de noviembre de 2025.

Probamos path traversal (`../`) y está contenido. Pero **`../` es solo una de tres variantes**, y las otras dos no lo probamos:

| Variante | Cómo funciona | ¿Probada? |
|---|---|---|
| Path traversal | `../../opt/junit/junit.jar` sale de la carpeta por ruta relativa | ✅ Sí (§1.4) |
| **Entrada symlink** | El tar contiene un symlink `Solucion.java → /etc/passwd`, y después una entrada regular que escribe **sobre ese path**. El `../` no aparece por ningún lado | ❌ **No** |
| **Hard link** | Una entrada del tar es un hard link a un archivo fuera del directorio de extracción | ❌ **No** |

El symlink es el vector exacto de CVE-2024-28185: Judge0 escribía un archivo en el directorio del sandbox, el alumno dejaba ahí un symlink apuntando afuera, y la escritura salía. El parche cambió el usuario Unix, y el investigador lo bypasseó con `chown` sobre un symlink (CVE-2024-28189).

Estamos **probablemente** cubiertos —GNU tar con `--no-same-owner`, dentro de un contenedor read-only, escribiendo en un `tmpfs`— pero "probablemente" no es una respuesta de defensa.

> **Acción: agregar symlink y hard link a la suite de entregas maliciosas.** Son dos casos más sobre los diez que ya existen. Y decirlo así en la defensa: *"agregamos dos casos hostiles después de estudiar los CVEs de Judge0, porque su escape fue exactamente esta clase de bug"*.

### 1.6 Los cinco tropiezos específicos de Java

Esto es lo que no aparece en un tutorial genérico de sandboxing.

#### a) Sin red, Maven no descarga nada

Es *el* problema de Java aislado. Dos salidas:

1. Hornear un `~/.m2` prepoblado en la imagen y correr `mvn -o` (offline).
2. **Saltear Maven por completo:** `javac` + `junit-platform-console-standalone.jar`, un único jar ya dentro de la imagen.

**Se recomienda la 2.** Maven levanta una JVM, resuelve plugins y tarda segundos aun offline. El camino directo:

```sh
# 1) compilar la SOLUCION sola  -> si falla, la culpa es del alumno
javac -d /tmp/classes $(find /tmp/in/src -name '*.java')        # exit 20

# 2) compilar los TESTS         -> si falla, la culpa es de la suite de T05
javac -cp /opt/junit/junit.jar:/tmp/classes -d /tmp/classes \
      $(find /tmp/in/test -name '*.java')                       # exit 21

# 3) correr los tests y emitir el reporte XML
#    [IE] sin escaneo: se le dan las clases por nombre (ver abajo)
ulimit -t "$CPU_TESTS_S"          # RLIMIT_CPU: el reloj del alumno (§1.4d)
java -jar /opt/junit/junit.jar execute \
     --class-path /tmp/classes \
     --select-class=tp.SolucionTest \
     --include-classname='.*' \
     --reports-dir=/tmp/reports
```

Rápido, determinista, y separa limpio **tres culpas distintas**: `ERROR_COMPILACION` (falla el paso 1, es del alumno), `SUITE_INVALIDA` (falla el paso 2, es de T05) y `TESTS_FALLIDOS` (falla el paso 3). Las dos pasadas de `javac` cuestan ~0.6 s más que una sola; el porqué de pagarlos está en §1.4c.

**[IE] Mejor que acotar el escaneo: no escanear.** Este documento decía *"`--scan-class-path` apunta al directorio de clases, no al classpath completo"* — dejarle escanear también el jar de JUnit llevaba el descubrimiento de ~1.2 s a ~2.9 s (§1.4a). Eso sigue siendo cierto, pero se quedó corto: **el `ConsoleLauncher` acepta que le digan las clases directamente, y si no se pasa `--scan-classpath`, no escanea nada.**

`-c` / `--select-class` es repetible, y existen también `--select-method`, `--select-package` y `--select-file`. Y **conocemos los nombres de las clases de test**: los escribió el profesor y vienen en el bundle con `rol: TEST`. No hay ninguna razón para que JUnit los busque.

Esto ataca directo los 1.2–2.9 s medidos, que es el segundo consumidor de tiempo después de `javac`.

> **Trampa documentada, y vale la pena saberla antes de perder una tarde:** hay un issue conocido ([junit-framework#2289](https://github.com/junit-team/junit-framework/issues/2289)) donde `--select-class` por sí solo no alcanza y hay que agregar además `--include-classname` con un regex que matchee el nombre de la clase. Por eso está en el comando de arriba. **Si `--select-class` "no encuentra nada", es esto** — no es que la técnica no sirva.

Dato de contexto: la documentación actual de JUnit ya es de la línea **6.0.x**. Si estamos sobre 5.x, vale medir si el descubrimiento mejoró de por sí.

#### b) La JVM sí respeta los cgroups, y por eso puede quedar corta

Desde Java 10 la JVM lee el límite de memoria del contenedor y toma por default **el 25%** como heap máximo. Con `--memory 256m`, el alumno tiene ~64 MB de heap y le explota código correcto.

→ Contenedor en ~512 MB y `-XX:MaxRAMPercentage=60`. La memoria del contenedor tiene que cubrir heap + metaspace + stacks + la JVM misma.

**Y hay un corolario que el prototipo encontró: si la JVM está bien configurada, el OOM no llega como OOM-kill.** Con `MaxRAMPercentage` por debajo del límite del contenedor, la que se queda sin memoria es la JVM, no el cgroup. Agregando `-XX:+ExitOnOutOfMemoryError`, una entrega que fuga memoria termina así:

```
exitCode: 3        .State.OOMKilled: false
```

`OOMKilled` en `false`. Si el veredicto se decide solo mirando ese flag —que es lo que decía §1.3—, una entrega que reventó por memoria se clasificaría como error interno del sandbox, y **no consumiría vida** (§5.1). El alumno tendría intentos infinitos fugando memoria.

> **`LIMITE_MEMORIA` tiene dos caminos de entrada, y hay que mapear los dos:** `.State.OOMKilled == true` (lo mató el cgroup) **o** `exitCode == 3` (lo mató `ExitOnOutOfMemoryError` antes). Sin `ExitOnOutOfMemoryError` la JVM sobrevive al `OutOfMemoryError`, sigue renqueando y termina agotando el reloj: el mismo problema se reportaría como `TIMEOUT`.

#### c) `System.exit(0)` en el código del alumno es un bypass

Termina la JVM antes de que se escriba el reporte, y el exit code queda en `0`. Si el veredicto sale del exit code, **se aprueba una entrega vacía**.

> **Regla: el veredicto sale del archivo de reporte, nunca del exit code. Sin reporte = `ERROR`, jamás éxito.**

**Verificado.** Una entrega con `System.exit(0)` en el método a implementar sale del contenedor con `exitCode: 0`, `OOMKilled: false` y **cero tests corridos**. Leyendo el exit code, aprueba. La guarda que efectivamente la frena es exigir el XML de JUnit **con `tests > 0`**; el veredicto resultante es `SALIDA_ANTICIPADA` (§5.1), que es distinto de `ERROR_INTERNO` porque sí es responsabilidad del alumno.

#### d) El código del alumno y los tests corren en la misma JVM — [IE] reescrita

Esta sección decía *"no se elimina sin partir en dos procesos"* y dejaba abierta la pregunta de si valía la pena partirlo. **La investigación externa la cierra, y además encontró un vector que no estábamos cubriendo.**

##### La respuesta a "¿partimos en dos procesos?" es no

Existe la implementación de referencia de exactamente este problema, es académica y está en producción: **Ares** (Artemis Java Test Sandbox, `de.tum.in.ase:artemis-java-test-sandbox`), una extensión de JUnit 5 de la Technische Universität München para correr código de alumnos de forma segura.

**La rompieron dos veces, con dos trucos distintos:**

- **CVE-2024-23683** (< 1.7.6) — escape creando una **subclase especial de `InvocationTargetException`**. Ejecuta Java arbitrario cuando la víctima corre el código supuestamente sandboxeado.
- **CVE-2024-23682** (< 1.8.0, CVSS 8.2, CWE-501 *Trust Boundary Violation*) — escape **incluyendo class files en un paquete en el que Ares confía**. La mitigación fue validar paquetes con el Maven Enforcer.

Y el propio proyecto declaró que su diseño no tiene futuro: en [la discusión #113](https://github.com/ls1intum/Ares/discussions/113) el mantenedor dice que casi toda la seguridad de Ares está implementada por su subclase de `SecurityManager`, que no hay reemplazo directo planificado, y que **Ares tal como está implementado no tiene futuro a largo plazo**.

> **Conclusión:** aislar el código del alumno del runner *dentro* de la JVM **no es alcanzable en Java 21 con ningún mecanismo soportado**. Ares lo intentó con el mecanismo que existía, lo rompieron dos veces, y el mecanismo ya no existe. **El contenedor no es la mejor frontera: es la única.**

Partir en dos procesos *dentro* del contenedor no compra frontera de seguridad —mismo contenedor, mismo namespace, mismo usuario—. Compraría aislar el **fallo** (que un `System.exit` o un OOM del alumno no tumbe al runner), pero eso ya está resuelto leyendo el veredicto del reporte y no del exit code (§1.6c).

##### El vector nuevo: el contenedor protege el host, **no protege el veredicto**

Este es el hallazgo que más nos cambia, porque **no lo teníamos identificado en ningún lado**.

CVE-2024-23682 es *inyectar class files en un paquete confiable*. Traducido a nuestro caso:

> Nada impide hoy que un alumno declare su clase en el **mismo paquete que los tests del profesor**, o con el nombre de una clase auxiliar de la que los tests dependen. Como todo se compila junto en el mismo classpath, la clase del alumno puede **sombrear o reemplazar** una clase de soporte del profesor. Los tests pasan. El contenedor está perfectamente aislado. **El veredicto es falso — y el veredicto es la nota.**

Esto no lo cierra ningún flag de Docker. No es un problema de aislamiento: es un problema de **integridad del veredicto**, que es una propiedad distinta y que nuestro modelo de amenaza tenía enunciada (§4.2 del briefing: "confiable") pero no defendida.

Lo cierran tres medidas baratas, todas en el armado del bundle o en el sidecar:

| # | Medida | Dónde | Nota |
|---|---|---|---|
| 1 | **Validar el paquete y el nombre de clase** de los archivos con `rol: SOLUCION` contra lo que declara la consigna. Rechazar si declara un paquete reservado | API, antes de tocar Docker (§2.2) | Es literalmente la mitigación que aplicó Ares tras un CVSS 8.2 |
| 2 | **Compilar en directorios de salida separados** y componer el classpath con los tests **primero** | Script de arranque | El classpath resuelve por orden: el `.class` del profesor gana |
| 3 | **Escribir el reporte a una ruta que el código del alumno no pueda tocar** | Script de arranque | Este es el bug de Judge0. Con `--read-only` + `tmpfs` se controla, pero hay que **verificar que el `tmpfs` del reporte no sea el mismo working dir** donde corre el código |

La 3 merece atención especial porque hoy **no está verificada**: §1.3 manda el reporte a `/tmp/reports` y el código del alumno corre con `/tmp` escribible. Si el alumno puede escribir `/tmp/reports/*.xml`, puede escribir su propio veredicto — que es, exactamente, el escape de Judge0 aplicado a nosotros.

Lo que ya teníamos y se mantiene: tests en `0444` y ejecución como uid no privilegiado. Son necesarios pero no suficientes: **ninguno de los tres vectores de arriba pasa por modificar el archivo de test.**

#### e) `SecurityManager` no es una opción — [IE] con la cita formal

Deprecado desde Java 17 y deshabilitado de fábrica en las versiones nuevas. **No existe el sandbox dentro de la JVM** — por eso el contenedor no es una opción entre varias, es la única.

Las citas para la defensa, que valen más que la afirmación suelta:

- **[JEP 411](https://openjdk.org/jeps/411)** — deprecó terminalmente el `SecurityManager` en Java 17.
- **[JEP 486](https://openjdk.org/jeps/486)** — lo **deshabilitó de forma permanente**: la especificación se revisó para que los desarrolladores **no puedan habilitarlo**, y las bibliotecas de la plataforma ya no le delegan decisiones de acceso a recursos.
- La documentación de Oracle para Java 21 lo dice explícitamente: **no hay reemplazo**.

El camino de reemplazo que evaluaba Ares —y que muestra lo caro que sería intentarlo— es usar el sistema de módulos con un **class loader propio para el módulo del alumno, que inspeccione el bytecode al cargar cada clase** y lo manipule para lanzar excepciones donde corresponda. Eso está muy por encima del alcance del TP, y es la razón concreta por la que no lo intentamos.

---

## 2. Cómo llega la entrega desde T05

### 2.1 La decisión de fondo: ¿el request se basta a sí mismo?

| Opción | Cómo |
|---|---|
| **A — Autocontenido** | T05 manda código **y** tests en el body |
| **B — Registro** | T05 registra la suite una vez; el request lleva una referencia y el sandbox cachea |
| **C — Fetch** | El sandbox le pide los tests a T05 al momento de ejecutar |

**C se descarta de entrada:** crea `T05 → sandbox → T05`, una dependencia circular que además atraviesa el Gateway dos veces, es candidata a deadlock bajo carga y mete a T05 en el camino crítico de cada ejecución.

**Se recomienda A.** Con 120 usuarios y archivos de pocos KB, el payload no es problema, y a cambio se ganan tres cosas grandes:

- **Función pura**: mismo input → mismo output. Sin estado compartido ni caché que invalidar.
- **Reproducibilidad gratis**: se guarda el bundle exacto ejecutado (o su hash) y se puede re-correr una entrega de hace semanas de forma idéntica.
- **Independencia de disponibilidad**: si T05 se cae con jobs en cola, los jobs se ejecutan igual. El bundle viaja dentro del mensaje: el worker nunca vuelve a preguntarle nada a T05.

B es una optimización legítima *después*, si el volumen lo justifica. No es el punto de partida.

### 2.2 Puntos del contrato que importan

- **`rol` por archivo, no por convención de nombre.** Si el sandbox adivina qué es test mirando si el nombre termina en `Test`, el alumno controla esa decisión.
- **`suiteVersion` viaja aunque los tests vengan inline.** Es trazabilidad pura, pero permite responder *"¿con qué versión de los tests se corrigió esta entrega?"* — engancha con el *Versionado* que es alcance de T03.
- **`cursoId` es campo de correlación, no de dominio.** Se guarda y se indexa para poder cancelar en bloque las ejecuciones de un curso archivado (§5). No se valida, no se consulta contra T02 y no condiciona la ejecución: un `cursoId` desconocido no es motivo de rechazo. Es lo que permite acotar por curso sin conocer qué es un curso.
- **`Idempotency-Key` con clave natural `entregaId:intentoNro`**: si T05 reintenta el POST por un timeout de red, no se ejecuta dos veces.
- **`limites` los propone T05**, pero el sandbox los **recorta contra un techo de plataforma**. Nadie de afuera fija los recursos del sandbox.

Y tres puntos más que salieron de probarlo contra Docker real (§1.4):

- **El reloj que T05 propone es solo el de la fase de tests.** El presupuesto de compilación no viaja en el mensaje: es de la plataforma, porque no depende del alumno (§1.4a).
- **`ruta` es entrada hostil y hay que validarla en la API.** Hoy no está validada. Se probaron dos entregas maliciosas y las dos quedaron contenidas **por accidente**, no por diseño: `../../../opt/junit/junit.jar` lo frenó GNU tar al desempaquetar —devolviendo `ERROR_INTERNO`, que es el mensaje equivocado para un bundle mal formado, porque no consume vida (§5.1)— y `/opt/junit/pwn.java` se normalizó a `src/opt/junit/pwn.java` y terminó como error de compilación. Confiar en que el desempaquetador rechace lo que la API dejó pasar es tener la validación en el lugar equivocado. **Regla: `ruta` relativa, sin `..`, sin barra inicial, terminada en `.java`; se rechaza con `400` antes de tocar Docker.**
- **`suiteVersion` tiene que resolver a una imagen.** El jar de JUnit vive dentro de la imagen de ejecución (§1.6a), así que la versión del framework la fija la imagen, no el bundle. Si T05 pide una `suiteVersion` que ninguna imagen sirve, es un `422`, no un intento a ciegas.

Y uno más, que salió de la investigación externa:

- **[IE] El paquete declarado por el código del alumno es entrada hostil, igual que `ruta`.** Validar la ruta no alcanza: un archivo en `src/tp/Solucion.java` puede declarar `package tp.tests;` adentro y sombrear una clase de soporte del profesor (§1.6d). **Regla: los archivos con `rol: SOLUCION` no pueden declarar el paquete reservado de los tests; se rechaza con `400 PAQUETE_RESERVADO` antes de tocar Docker.** Requiere que T05 declare cuál es el paquete de los tests — ver §7, definición 14.

El contrato completo está en la [sección 6](#6-contrato-completo-de-api).

---

## 3. De dónde salen los tests

### 3.1 Cadena de origen

```
PROFESOR arma el desafío en T05
   └─ escribe/carga los archivos de test
        → quedan versionados en la BD de T05, como parte de la versión del desafío
                     ↓
ALUMNO entrega
   └─ T05 arma el bundle (código del alumno + tests de esa versión del desafío)
                     ↓
   └─ POST al sandbox
```

**Los tests no se precargan nunca en el sandbox.** Llegan con cada job.

Lo que **sí está precargado en la imagen** es todo lo caro y estable: el JDK, `junit-console.jar`, el script de arranque y, opcionalmente, un archivo AppCDS. La imagen se construye una vez en el pipeline de CI y no se toca por ejecución.

Esto es lo que mantiene al servicio **sin estado de dominio**: no conoce desafíos, ni alumnos, ni cursos. Recibe archivos, los corre, devuelve un veredicto.

### 3.2 Tests visibles vs. tests ocultos

Definición a cerrar con T05 **antes** de congelar el contrato:

| Modo | Qué corre | Qué se le muestra al alumno |
|---|---|---|
| **`VISIBLE`** — botón "Ejecutar" del IDE | Solo los tests de ejemplo | Todo: nombres, mensajes de assert, `stdout` |
| **`COMPLETO`** — entrega formal | Ejemplos + tests ocultos | Resultado agregado; de los ocultos, **nunca el mensaje ni la entrada** |

Si no está separado, pasa una de dos cosas: el alumno ve los tests ocultos en el mensaje de error y programa contra ellos en vez de resolver el problema, o se le ocultan también los visibles y no puede depurar nada.

**Fuga por vía indirecta:** `stdout` puede contener la entrada del caso oculto si el alumno hace `System.out.println` de lo que recibe. Hay que definir con T05 qué se trunca y qué se oculta **por modo**.

#### [IE] Los tres canales de fuga, y el que nos faltaba

La investigación externa confirma la arquitectura y nombra tres canales. Los dos primeros ya los teníamos; el tercero **no estaba cubierto y es el que se olvida**:

| # | Canal | Por qué filtra | Estado |
|---|---|---|---|
| 1 | **El mensaje de aserción** | `assertEquals(expected, actual)` produce un mensaje **con el valor esperado**. Para un test oculto, ese mensaje *es* la solución | ✅ Ya contemplado (§6.2: los ocultos van sin `mensaje`) |
| 2 | **El stack trace** | Contiene nombres de métodos y a veces literales del caso | ✅ Mismo tratamiento |
| 3 | **`stdout`** | **Si la captura de salida es global al proceso**, la salida producida durante los tests ocultos se mezcla con la de los visibles. Un alumno que imprima desde su propio código durante un test oculto **ve la entrada del caso** | ❌ **Abierto** |

El punto 3 es sutil: no alcanza con truncar o con ocultar el `stdout` en modo `COMPLETO`, porque en modo `VISIBLE` el alumno **debe** ver su salida para poder depurar — y si la captura es global, ahí adentro viene también lo que se imprimió durante los ocultos.

> **La solución es capturar la salida por test, no por proceso.** JUnit 5 lo permite: con `@ExtendWith` que intercepte los streams, o con el mecanismo de captura de output de la Platform. Así cada entrada del reporte trae su propio `stdout`, y filtrar los ocultos filtra también su salida.

Esto hay que decidirlo **antes** de congelar el formato del reporte, porque cambia la forma del objeto `salida` de §6.2: pasa de ser un `stdout` único a nivel ejecución, a un `stdout` por test más el remanente del proceso.

### 3.3 Las dos preguntas para T05

1. **¿Qué escribe el profesor: los tests, o los casos?**
   - *Clases JUnit* → poder total, pero código arbitrario que puede romper el runner.
   - *Pares entrada/salida esperada* → T05 (o el sandbox) generan el JUnit desde una plantilla: más seguro y uniforme, menos flexible.
   - **Para arrancar, la plantilla es más sana.**
2. **¿Cuál es la firma que el alumno debe implementar?** Alguien tiene que fijar el contrato entre el código del alumno y los tests (nombre de clase, de método, tipos). Sin eso definido, ningún test compila.

---

## 4. Alternativas a nivel de recursos

### 4.1 Escalera de aislamiento

| Enfoque | Aislamiento | Arranque | Veredicto |
|---|---|---|---|
| Proceso JVM con `SecurityManager` | **Ninguno** | 0 | Descartado: no existe más |
| `nsjail` / `bubblewrap` | Bueno | ~10 ms | Más liviano que Docker, sin demonio — pero todo manual y no capitaliza la unidad de Docker |
| **Contenedor Docker efímero** | Bueno | ~200–400 ms | **La opción. Además es el temario de la materia** |
| gVisor (`runsc`) | Muy bueno | Más lento | Cambio de runtime, poco esfuerzo — buen "extra" si sobra tiempo |
| Kata / Firecracker (microVM) | El mejor | ~125 ms + KVM | Necesita virtualización anidada; probablemente no disponible |
| Job de Kubernetes por ejecución | Bueno | Segundos | El scheduling por ejecución lo mata |

#### [IE] Lo que hacen realmente los que hacen esto en serio, y por qué no los copiamos

Hay un renglón que faltaba en esta escalera, y conviene tenerlo porque **es lo que va a preguntar el tribunal si conoce el tema**: ni Judge0 ni Piston levantan un contenedor por ejecución.

Los dos usan **`isolate`** —el sandbox del sistema CMS de la Olimpiada Internacional de Informática: namespaces, chroot, cgroups y usuarios sin privilegios— *adentro de* un contenedor Docker. Docker les da el **entorno** (imagen con todos los compiladores, reproducibilidad); `isolate` les da el **aislamiento por ejecución**. AWS Lambda está un escalón más arriba, con microVMs de Firecracker.

O sea: **nadie hace `docker run` por ejecución en el camino crítico.** Eso desafía nuestro diseño de frente, y la respuesta no es defensiva sino cuantitativa:

> "Judge0 y Piston usan `isolate` dentro de Docker porque a escala de miles de submissions por hora el `docker create` por ejecución no es viable. A 120 usuarios y 4–6 ejecuciones concurrentes medimos que el `create` no está en el camino crítico —el tiempo se lo llevan `javac` y el descubrimiento de JUnit—, así que elegimos el contenedor efímero, que además nos da `--network none` y `--read-only` sin código nuestro. La misma decisión a escala de LeetCode sería equivocada."

Eso es una decisión **con su condición de validez explícita**, que es lo que se evalúa. Y hay un dato que la refuerza: **el `docker-compose.yml` de Judge0 corre el contenedor con `privileged: true`**, y eso es lo que convirtió sus bugs de lógica en takeover completo del host. Nuestro diseño —nada privilegiado, el worker sin el socket— es exactamente la decisión que ellos no tomaron.

### 4.2 Cómo bajar el costo sin bajar el aislamiento

El presupuesto real, medido (§1.4a), es ~4–8 s por ejecución con **casi todo el tiempo en el toolchain**: `javac` dos veces y el escaneo de classpath de JUnit. El contenedor y la JVM aportan poco.

Las palancas, reordenadas por lo que se midió:

1. **Saltear Maven** (§1.6a). Sigue siendo la mayor ganancia de todas, y ya está adoptada.
2. **Darle más CPU al contenedor.** Es la palanca más grande que queda y no cuesta escribir una línea: pasar de 1 a 2 CPU bajó el muro de 8.5 s a 5.1 s. `javac` paraleliza; el código del alumno no. Ver el techo de plataforma en §4.3.
3. **[IE] Eliminar el escaneo de classpath de JUnit, no acotarlo.** Antes acá decía "acotar `--scan-class-path` al directorio de clases", que llevaba el descubrimiento de 2.9 s a 1.2 s. Se puede hacer mejor: **`--select-class` con los nombres que ya tenemos en el bundle, y JUnit no escanea nada** (§1.6a). Ojo con la trampa de `--include-classname`.
4. **[IE] Cachear la suite de tests compilada por `(desafioId, suiteVersion)`.** Palanca nueva, y es la estructural. Medimos **dos** invocaciones de `javac` de 1.0–2.3 s cada una: una es del alumno, la otra es de los tests del profesor — y **la del profesor es idéntica en todas las entregas de un mismo desafío**. La primera ejecución de una versión compila los tests y guarda los `.class`; las siguientes los reciben ya compilados y solo compilan el código del alumno. **Elimina una de las dos invocaciones completas.**

   Encaja con dos piezas que ya existen: T03 hace **versionado** (hay un identificador estable por versión), y el **extra de este tema es "almacenamiento de artefactos de ejecución"** — los `.class` de la suite *son* un artefacto. Bonus argumentativo: convierte el extra de un adorno en una pieza del camino crítico de rendimiento, que es mucho más defendible que "también guardamos los logs".

   > **Riesgo a cerrar antes de implementarlo:** la invalidación. Si la clave es `(desafioId, suiteVersion)` y T03 garantiza que **una versión publicada es inmutable**, el problema desaparece. Si una versión puede editarse en el lugar, la caché envenena veredictos — y acá un veredicto envenenado es una nota mal puesta. **Verificar ese supuesto con T03** (§7, definición 15).
5. **AppCDS en la imagen.** ~~La tercera palanca~~ — **degradada por medición.** El arranque desnudo de la JVM son 38–82 ms (§1.4b), así que el techo de lo que AppCDS puede recortar del arranque está ahí. Podría ayudar algo con la carga de clases de JUnit, pero eso **no se midió**, y no es donde está el tiempo. Queda como extra si sobra tiempo, no como recomendación.
6. **Pool caliente de contenedores.** ~~La cuarta palanca~~ — **[IE] descartado por medición.** Ver abajo.

#### [IE] El pool caliente: evaluado y descartado

Estaba listado como palanca 4. La investigación externa lo confirma como patrón real —y lo descarta con nuestros propios números.

El patrón tiene nombre y es el de AWS Lambda: **warming pool / active pool**, descrito en sus patentes ([US 9,928,108](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9928108), [US 10,303,492](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10303492)). Y algo que conviene citar: la regla que nos habíamos puesto —*"un contenedor, una ejecución; nunca reusar entre dos alumnos"*— **es la misma que se puso AWS**, explícitamente para evitar el co-mingling de recursos entre usuarios.

La trampa está documentada en la literatura de seguridad de serverless ([arXiv:2107.03832](https://arxiv.org/pdf/2107.03832)): los contenedores calientes reducen el arranque **a costa de garantías de seguridad**, porque queda un `/tmp` escribible que persiste entre invocaciones y permite ataques de larga duración. La mitigación que nombra el paper es, justamente, deshabilitar el reuso.

Pero el motivo por el que lo descartamos no es ese, y esa distinción importa:

> "Evaluamos el pool caliente. Es el patrón de AWS Lambda, con la misma regla de no reusar entre usuarios que nos habíamos puesto, y la literatura documenta su trampa. **Medimos que el `create` no está en nuestro camino crítico**: el tiempo se lo llevan `javac` y el descubrimiento de JUnit. Lo descartamos por falta de beneficio medido, no por complejidad."

*"Medimos y no valía la pena"* es una respuesta de ingeniería. *"Lo implementamos porque estaba en la lista"* no lo es.

**Lo que no es una palanca:** compilar solución y tests en una sola pasada de `javac`. Se midió y ahorra ~0.6 s de ~5.5 s, a cambio de perder la distinción entre `ERROR_COMPILACION` y `SUITE_INVALIDA` (§1.4c).

### 4.3 Dimensionamiento

Los números de esta sección eran una estimación y resultaron **mal calibrados**: con 512 MB / 1 CPU y 10 s de reloj único, el camino feliz daba `TIMEOUT` (§1.4a). Corregidos contra lo medido:

```
Por ejecución:           512 MB RAM  +  2 CPU   durante ~4–8 s
Pool de 6 simultáneas:   ~3 GB RAM   +  hasta 12 cores en pico
```

**[IE] Los relojes, que ahora son tres y de dos naturalezas distintas.** Antes eran dos, los dos de reloj de pared. El cambio de fondo: **el presupuesto del alumno se mide en tiempo de CPU** (§1.4d).

| Reloj | Naturaleza | Quién lo fija | Valor propuesto | Quién lo aplica | Qué significa que se venza |
|---|---|---|---|---|---|
| `cpuPruebasS` | **Tiempo de CPU** | T05 propone, el sandbox recorta | 10 s (techo) | **El kernel**, vía `RLIMIT_CPU` dentro del contenedor | El código del alumno no termina → `TIMEOUT` |
| `timeoutCompilacionMs` | Reloj de pared | **Plataforma.** No viaja en el mensaje | 20 000 ms | Script de arranque | Toolchain saturado → `ERROR_INTERNO` |
| `timeoutPruebasMs` | Reloj de pared (**backstop**) | Plataforma | 30 000 ms, generoso | Script de arranque | El proceso duerme en vez de quemar CPU → `TIMEOUT` |
| Reloj de pared del worker | Reloj de pared (red de última instancia) | Plataforma | suma + margen | Worker | Falló el reloj de adentro → `ERROR_INTERNO` |

Por qué hacen falta los dos tipos, y no alcanza con uno:

- **El de CPU** es el que decide el veredicto del alumno, y es inmune a la contención del host. Elimina los `TIMEOUT` intermitentes **por construcción**, no por margen.
- **El de pared** es el backstop imprescindible: un proceso que hace `Thread.sleep(Long.MAX_VALUE)` **no consume CPU** y viviría para siempre bajo un límite de CPU solo. Por eso se deja generoso: no es el que decide, es el que evita que un contenedor quede colgado.

> **El techo de `cpuPruebasS` no se dimensiona con una corrida single-thread.** El tiempo de CPU **se suma entre threads** (§1.4d): con `--cpus 2`, dos threads girando queman el presupuesto al doble de velocidad. Para anti-trampa es lo deseable, pero hay que calibrarlo midiendo con concurrencia real.

El "2 CPU" tampoco es gratis: RF-NFR-03 pide 120 sesiones concurrentes, pero **120 sesiones no son 120 ejecuciones simultáneas** —la gente lee, escribe y piensa—. Con una tasa razonable de entregas, un pool de 4–6 con cola absorbe el pico; el techo de cores es el que hay que mirar al dimensionar el host.

Lo importante no es acertar el número, es **el comportamiento cuando se llena**:

- cola con profundidad acotada (`x-max-length` en la declaración de la cola);
- `429` con `Retry-After` al pasarse;
- profundidad de cola como métrica de primera clase en Grafana — la exporta el propio broker;
- **tope de jobs en vuelo por alumno** — si uno solo puede encolar 20 ejecuciones, se come el pool él solo.

El número se ajusta con la prueba de carga; el mecanismo tiene que estar bien desde el diseño.

---

## 5. Máquina de estados de una ejecución

```
        POST /ejecuciones
               │
               ▼
          ENCOLADA ──────────────► CANCELADA
               │                   (curso archivado, entrega anulada)
               │ el worker recibe el mensaje
               ▼
         EN_EJECUCION
               │
   ┌───────────┼───────────┬─────────────┬────────────┬──────────────┐
   ▼           ▼           ▼             ▼            ▼              ▼
EXITO      TESTS_     ERROR_        SALIDA_       TIMEOUT      LIMITE_
           FALLIDOS   COMPILACION   ANTICIPADA                 MEMORIA
   └───────────┴───────────┴─────────────┴────────────┴──────────────┘
                           │
                    estados terminales
                    imputables al ALUMNO
                           │
   ┌───────────────────────┴───────────────────────┐
   ▼                                               ▼
ERROR_INTERNO                              SUITE_INVALIDA
(falla del sandbox)                        (falla de T05)
        └───────────────────┬───────────────────────┘
                            ▼
              NO consumen vida ni reintento
```

### 5.1 Estados terminales y su semántica

La columna de la derecha es la que importa, y es la que hay que acordar con T03/T05: **de quién es la culpa**.

| Estado | Causa | Cómo se detecta | ¿Consume vida o reintento? |
|---|---|---|---|
| `EXITO` | Todos los tests pasaron | Reporte XML con `tests > 0`, sin fallas ni errores | No — es aprobación |
| `TESTS_FALLIDOS` | Compiló, corrió, falló algún test | Reporte XML con `failures` o `errors` > 0 | **Sí** — fallo real del alumno |
| `ERROR_COMPILACION` | `javac` falló sobre los archivos `rol: SOLUCION` | Exit code de la **primera** pasada de `javac` | **Sí** — fallo real del alumno |
| `TIMEOUT` | El código del alumno no termina | **[IE]** `SIGXCPU` por `RLIMIT_CPU` (agotó su presupuesto de **CPU**), **o** el reloj de pared de backstop (durmió sin consumir CPU). Los dos adentro del contenedor (§4.3) | **Sí** |
| `LIMITE_MEMORIA` | El código del alumno no cabe | `.State.OOMKilled == true` **o** `exitCode == 3` (§1.6b) | **Sí** |
| `SALIDA_ANTICIPADA` | El proceso salió con éxito sin correr tests: `System.exit(0)` | `exitCode == 0` **y** reporte ausente o con `tests == 0` (§1.6c) | **Sí** — es evasión, no infraestructura |
| `SUITE_INVALIDA` | No compilan los **tests** | Exit code de la **segunda** pasada de `javac` | **NO** — la suite la manda T05, no el alumno |
| `ERROR_INTERNO` | Falla del sandbox: Docker caído, imagen ausente, reporte ilegible, presupuesto de compilación agotado, bundle mal formado | Todo lo demás | **NO. Nunca.** |
| `CANCELADA` | Anulada antes de ejecutar | — | No |

Tres de estos nueve estados aparecieron al probar el prototipo contra Docker real, y los tres existen porque **el veredicto obvio era el equivocado**:

- `SALIDA_ANTICIPADA` existe porque `System.exit(0)` sale con exit code `0` y aprobaría.
- `SUITE_INVALIDA` existe porque un error en los tests de T05 se leería como error de compilación del alumno y le consumiría una vida por algo que no escribió.
- `LIMITE_MEMORIA` ganó un segundo camino de detección porque con la JVM bien configurada `OOMKilled` queda en `false` y la entrega se clasificaría como falla de infraestructura.

> **`ERROR_INTERNO` y `SUITE_INVALIDA` no consumen vida ni reintento.** Tiene que quedar escrito de ambos lados del contrato, en el de ustedes y en el de T03/T05. Es la regla que evita que un alumno pierda una vida por una caída de infraestructura o por un test mal escrito.

### 5.2 Jobs zombis

Un worker puede morir con un job en `EN_EJECUCION`. Con la cola en el broker y **ack manual**, el mensaje nunca se confirmó: al cortarse la conexión vuelve a la cola y otro worker lo toma. No hace falta escribir nada para eso.

Lo que sí queda como trabajo propio es el caso del worker **vivo pero colgado**, donde la conexión sigue abierta y el broker lo ve sano. Ahí va un **watchdog**: ejecuciones en `EN_EJECUCION` con `iniciadoEn` más viejo que `timeout × 2` se marcan `ERROR_INTERNO`. No re-encola —de eso se ocupa el broker— solo evita que el alumno espere una respuesta que no va a llegar.

Y el reintento tiene tope: al agotarlo, el mensaje va a la **DLQ** y la fila queda `ERROR_INTERNO`. Un mensaje archivado en la DLQ con la ejecución todavía en `ENCOLADA` es la misma falla con otro disfraz. Detalle completo en [`04-ms-sandbox-worker.md` §7](04-ms-sandbox-worker.md).

---

## 6. Contrato completo de API

Prefijo: `/api/v1/sandbox`

### 6.1 Crear una ejecución

```jsonc
POST /api/v1/sandbox/ejecuciones
Idempotency-Key: {entregaId}:{intentoNro}
Content-Type: application/json

{
  "lenguaje": "JAVA_21",
  "modo": "COMPLETO",                 // COMPLETO | VISIBLE
  "archivos": [
    // `ruta`: relativa, sin `..`, sin barra inicial, terminada en `.java`.
    // Se valida en la API, antes de tocar Docker (§2.2).
    { "ruta": "tp/Solucion.java",     "rol": "FUENTE", "contenido": "..." },
    { "ruta": "tp/SolucionTest.java", "rol": "TEST",   "contenido": "...",
      "visibilidad": "OCULTO" }       // VISIBLE | OCULTO   (solo para rol TEST)
  ],
  "limites": {                        // propuestos por T05, recortados por el sandbox
    "cpuPruebasS": 10,                // [IE] TIEMPO DE CPU, no reloj de pared, y
                                      // SOLO la fase de tests. Compilar no se le
                                      // cobra al alumno: ese presupuesto es de la
                                      // plataforma y no viaja acá (§1.4a).
                                      // Se mide en CPU para que la contención del
                                      // host no produzca TIMEOUT falsos (§1.4d).
                                      // OJO: se suma entre threads.
    "memoriaMb": 512,
    "cpus":      2.0
  },
  "trazabilidad": {
    "cursoId":      "...",        // correlación: permite cancelar por curso archivado
    "desafioId":    "...",
    "suiteVersion": 7,
    "entregaId":    "...",
    "intentoNro":   2
  }
}
```

```
→ 202 Accepted
  Location: /api/v1/sandbox/ejecuciones/{ejecucionId}

  { "ejecucionId": "...", "estado": "ENCOLADA", "posicionEnCola": 3 }
```

Otras respuestas:

| Código | Cuándo |
|---|---|
| `400` | Falta `Idempotency-Key`, el bundle no tiene ningún archivo con `rol: TEST`, o alguna `ruta` es inválida (absoluta, con `..`, o que no termina en `.java`) |
| `409` | Misma `Idempotency-Key` con contenido distinto |
| `422` | Lenguaje no soportado, límites fuera de rango, o `suiteVersion` que no resuelve a ninguna imagen de ejecución |
| `429` | Cola llena, o tope de jobs en vuelo del alumno. Incluye `Retry-After` |

Si la `Idempotency-Key` se repite **con el mismo contenido**, se devuelve `202` con la ejecución original: reintentar es seguro.

### 6.2 Consultar una ejecución

```jsonc
GET /api/v1/sandbox/ejecuciones/{ejecucionId}

→ 200 OK
{
  "ejecucionId": "...",
  "estado": "TESTS_FALLIDOS",
  "resumen": { "total": 10, "pasados": 7, "fallidos": 3, "omitidos": 0 },
  "tests": [
    { "nombre": "sumaDosPositivos", "resultado": "PASO",
      "duracionMs": 4, "visibilidad": "VISIBLE" },
    { "nombre": "sumaConNegativos", "resultado": "FALLO",
      "duracionMs": 3, "visibilidad": "VISIBLE",
      "mensaje": "expected: <2> but was: <-2>" },
    { "nombre": "casoBorde",        "resultado": "FALLO",
      "duracionMs": 2, "visibilidad": "OCULTO" }
      // ← sin `mensaje`: es oculto y el modo es COMPLETO
  ],
  "salida": {
    "stdout": "...",
    "stderr": "...",
    "truncado": true,
    "limiteBytes": 65536
  },
  "recursos": {
    "cpuPruebasMs": 1210,             // [IE] CPU consumida por el alumno: es el
                                      // número contra el que se evalúa el límite,
                                      // y es estable entre corridas
    "tiempoPruebasMs": 1840,          // reloj de pared de la misma fase: informativo,
                                      // varía con la carga del host (1.8–5.2 s)
    "tiempoCompilacionMs": 2900,      // costo de plataforma, informativo
    "memoriaPicoKb": 148000
  },
  "limitesAplicados": { "cpuPruebasS": 10, "memoriaMb": 512, "cpus": 2.0 },
  "trazabilidad": { "cursoId": "...", "desafioId": "...", "suiteVersion": 7,
                    "entregaId": "...", "intentoNro": 2 },
  "correlationId": "...",
  "encoladaEn": "...", "iniciadaEn": "...", "finalizadaEn": "..."
}
```

Mientras no terminó, se devuelve el mismo objeto con `estado: ENCOLADA | EN_EJECUCION` y sin `tests`/`salida`/`recursos`.

**El filtrado de lo oculto lo hace el sandbox, no el consumidor.** Es la única forma de garantizar que un test oculto no se filtre por un bug de otro grupo.

> **[IE] Tensión no resuelta, y hay que decidirla con T05.** La investigación externa recomienda lo contrario: que **el sandbox devuelva todo y T05 filtre**, con el argumento de que la política de visibilidad es dominio de T05 —es quien sabe qué caso es oculto— y que meterle política de visibilidad al sandbox le da dominio de negocio, contradiciendo §4.3 del briefing ("el sandbox es una función pura sin dominio").
>
> **El argumento es bueno, pero no alcanza para dar vuelta la decisión.** Contra-argumento, que es el que sostiene lo que está escrito arriba: filtrar acá es *defensa en profundidad*. Si T05 tiene un bug en el filtrado, con nuestra postura no pasa nada; con la de ellos, se filtra la solución de un test oculto y el daño académico ya está hecho. Y el dato que decide: **el `modo` (`VISIBLE`/`COMPLETO`) y la `visibilidad` por archivo ya viajan en el request** — o sea, T05 ya nos dice qué es oculto, y aplicar esa marca no es tener dominio, es cumplir un parámetro.
>
> **Postura propuesta: mantener el filtrado en el sandbox**, y decir en la defensa que se evaluó la alternativa. La distinción fina que vale la pena enunciar: *el sandbox no decide qué es oculto —eso lo dice T05 en el request— pero sí ejecuta el ocultamiento, porque es el único lugar donde un bug ajeno no puede saltearlo.*
>
> Lo que sí adoptamos sin discusión de esa misma sección es la **captura de `stdout` por test** (§3.2), que es un canal de fuga que teníamos abierto con las dos posturas.

### 6.3 Resto de endpoints

| Método | Ruta | Para qué |
|---|---|---|
| `POST` | `/ejecuciones` | Encolar |
| `GET` | `/ejecuciones/{id}` | Estado y resultado |
| `POST` | `/ejecuciones/{id}/cancelar` | Cancelar si aún no arrancó |
| `GET` | `/ejecuciones/{id}/artefactos` | Artefactos guardados (*extra* del tema) |
| `GET` | `/lenguajes` | Lenguajes y límites soportados — permite a T05 no hardcodear nada |
| `GET` | `/actuator/health` | `liveness` / `readiness` separados |

### 6.4 Evento publicado (si hay broker)

```jsonc
EjecucionFinalizada {
  "eventId": "...", "tipo": "EjecucionFinalizada", "version": 1,
  "ocurridoEn": "...", "correlationId": "...",
  "payload": { "ejecucionId": "...", "entregaId": "...", "intentoNro": 2,
               "estado": "TESTS_FALLIDOS",
               "resumen": { "total": 10, "pasados": 7, "fallidos": 3 } }
}
```

El evento lleva el **resumen**, no el detalle. Quien necesite el detalle hace el `GET`. Así el payload se mantiene chico y el filtrado por visibilidad sigue centralizado en el sandbox.

### 6.5 Contrato de error

`application/problem+json` (RFC 7807) con códigos estables:

`LENGUAJE_NO_SOPORTADO` · `LIMITES_FUERA_DE_RANGO` · `BUNDLE_SIN_TESTS` · `RUTA_INVALIDA` · **`PAQUETE_RESERVADO`** · `SUITE_VERSION_DESCONOCIDA` · `IDEMPOTENCY_KEY_AUSENTE` · `IDEMPOTENCY_CONFLICTO` · `COLA_LLENA` · `TOPE_ALUMNO_EXCEDIDO` · `EJECUCION_INEXISTENTE` · `EJECUCION_NO_CANCELABLE`

`RUTA_INVALIDA` y `SUITE_VERSION_DESCONOCIDA` son los dos códigos que salieron de probar el prototipo (§1.4). El primero cierra una validación que hoy se apoyaba, sin saberlo, en que el desempaquetador de tar rechazara lo que la API dejó pasar.

**[IE] `PAQUETE_RESERVADO`** es el tercero, y salió de la investigación externa (§1.6d). Cierra el vector que ningún flag de Docker cubre: un archivo con `rol: SOLUCION` que declara el paquete de los tests del profesor, para sombrear una clase de soporte y aprobar sin resolver nada. Es la misma mitigación que aplicó Ares después de un CVSS 8.2.

---

## 7. Definiciones abiertas con T05

| # | Definición | Postura propuesta |
|---|---|---|
| 1 | ¿El profesor escribe clases JUnit o pares entrada/salida? | Plantilla desde pares, al menos para empezar |
| 2 | Firma que el alumno debe implementar (clase, método, tipos) | La fija T05 y viaja en el enunciado |
| 3 | Formato de los tests: ¿inline en el request o registro? | **Inline** (§2.1) |
| 4 | Marcado `VISIBLE` / `OCULTO` por archivo de test | Obligatorio, lo provee T05 |
| 5 | Qué se muestra de un test oculto que falla | Solo nombre y resultado. Nunca mensaje ni entrada |
| 6 | Política de `stdout` en modo `COMPLETO` | Truncar y evaluar si se oculta del todo |
| 7 | ¿Quién fija los límites por desafío? | T05 propone, el sandbox recorta |
| 8 | ¿El botón "Ejecutar" del IDE pasa por el sandbox? | **Definición de alcance crítica** — cambia el dimensionamiento por completo |
| 9 | Tipos no ejecutables (modelado, code review, hackathon) | Fuera del sandbox |
| 10 | `ERROR_INTERNO` no consume vida ni reintento | Escrito de ambos lados del contrato |
| 11 | ¿El reloj que manda T05 cubre compilar o solo correr tests? | **Solo correr tests.** Compilar es costo de plataforma (§1.4a) |
| 12 | `SUITE_INVALIDA` tampoco consume vida | Si no compilan los tests, la culpa es de T05. Requiere que T05 lo acepte |
| 13 | ¿Qué versiones de suite se soportan? | Las que tengan imagen de ejecución. `suiteVersion` desconocida es `422`, no un intento a ciegas |
| 14 | **[IE] ¿Cuál es el paquete reservado de los tests?** | T05 tiene que declararlo para que podamos rechazar una solución que lo usurpe (`PAQUETE_RESERVADO`, §1.6d). **Sin esto, el vector de sombreado de clases queda abierto** |
| 15 | **[IE] ¿Una `suiteVersion` publicada es inmutable?** | Pregunta para **T03**, no para T05. De la respuesta depende si podemos cachear la suite compilada (§4.2). Si una versión se puede editar en el lugar, la caché envenena veredictos y hay que indexar por hash del contenido, no por versión |
| 16 | **[IE] ¿Captura de `stdout` por test o global?** | **Por test.** Es la única forma de no filtrar la entrada de un caso oculto por el `stdout` de modo `VISIBLE` (§3.2). Cambia la forma del objeto `salida` en §6.2 |
| 17 | **[IE] ¿Quién filtra los tests ocultos: el sandbox o T05?** | **El sandbox**, como defensa en profundidad. Se evaluó la alternativa y el razonamiento está en §6.2. Requiere que T05 lo acepte explícitamente, porque le saca una responsabilidad que podría querer |

---

## Próximos pasos posibles

- Contrato **OpenAPI** completo escrito, más un **stub** (WireMock o perfil `fake`) para que T05 no dependa de la implementación real.
- Suite de **entregas maliciosas de prueba** corriendo en CI — es la mejor evidencia posible para la defensa. Ya existe una versión descartable que cubre loop infinito, fork bomb, OOM, intento de red, `System.exit(0)`, ruta con `..` y ruta absoluta, con los diez casos contenidos y clasificados (§1.4); falta portarla a JUnit + Testcontainers y meterla en el pipeline.
- **Validación de `ruta` en la API** (`RUTA_INVALIDA`), que hoy no existe y de la que dependen dos de esos diez casos.

**[IE] Lo que agregó la investigación externa, en orden de impacto:**

1. **Relojes separados por fase, con el del alumno medido en CPU** (§1.4d, §4.3). Es el único cambio que toca comportamiento observable: resuelve el `TIMEOUT` del camino feliz y elimina la varianza de 1.8–5.2 s por construcción. **Empezar por acá.**
2. **Validación de paquete/nombre de clase de la entrega** (§1.6d, `PAQUETE_RESERVADO`). El contenedor no protege el veredicto; esto sí. Bloqueado por la definición 14 con T05.
3. **Verificar que el reporte no sea escribible por el código del alumno** (§1.6d, medida 3). Hoy el reporte va a `/tmp/reports` y el alumno tiene `/tmp` escribible: **es el bug de Judge0 aplicado a nosotros, y no está verificado.**
4. **Dos casos hostiles más en la suite: symlink y hard link dentro del tar** (§1.5). Baratos y son la clase de bug que tumbó a Judge0 tres veces.
5. **`--select-class` en lugar de `--scan-class-path`** (§1.6a). Ataca los 1.2–2.9 s medidos. Ojo con `--include-classname`.
6. **Caché de la suite compilada por `(desafioId, suiteVersion)`** (§4.2). Elimina una de las dos invocaciones de `javac`. Bloqueado por la definición 15 con T03.
7. **Probar `userns-remap` en el daemon** (§1.1). Una línea de config, sube un escalón real contra la clase de bug dominante de `runc`.
8. **Captura de `stdout` por test** (§3.2). Canal de fuga abierto; cambia la forma del reporte, así que conviene decidirlo antes de congelar el contrato.
- Diagrama **C4 nivel 3**: componentes de `ms-sandbox` (API, relay del outbox, cola del broker, worker, ejecutor, almacén de artefactos).
- Redacción de los **RF faltantes** para MSI, empezando por la degradación ante indisponibilidad del sandbox (análoga a RF-IA-27).

---

**Ver también**
- `04-ms-sandbox-worker.md` — el worker en detalle: por qué existe, la cola de RabbitMQ, el outbox, la DLQ, el janitor, concurrencia y pruebas.
- `05-ms-sandbox-patrones.md` — los patrones de la unidad de Microservicios aplicados a este servicio.
- `01-panorama-microservicios-backend.md` — mapa de los 12 servicios del curso.
