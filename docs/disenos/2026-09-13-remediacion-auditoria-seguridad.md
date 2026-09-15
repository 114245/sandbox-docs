# Remediación de la auditoría de seguridad de `ms-sandbox`

> **Estado:** análisis. Todas las decisiones están pendientes. Sin cambios de código.
> **Fecha:** 13 de septiembre de 2026.
> **Fuente:** auditoría externa del 12 de septiembre de 2026, guardada en
> `historico/fuentes/REPORTE-SEGURIDAD-SANDBOX.md`.
> **Código verificado:** commit `8ecb662`.

## 1. Resumen

La auditoría reporta 16 hallazgos y un dictamen: **no apto todavía para calificación adversaria
real**. El aislamiento del host resistió todas las pruebas. Lo que falla es otra propiedad: **que la
nota sea auténtica**. Un alumno puede fabricar su propio veredicto y leer los tests ocultos sin salir
del contenedor.

Se revisaron los 16 hallazgos contra el código actual:

- **15 siguen vigentes.**
- **F-06** (fat JAR que no arranca) ya está resuelto en `805211a`.
- Tres afirmaciones del informe no se pudieron reverificar leyendo el código, porque requieren
  construir o escanear las imágenes: las versiones transitivas de F-07, la presencia de `pebble`
  en la imagen (F-08) y el comportamiento real de GNU tar frente a `..` (F-10).

| Grupo | Hallazgos | Tipo de arreglo |
|---|---|---|
| Integridad del veredicto | F-01, F-02, F-03 | **De arquitectura** (F-03 tiene además un arreglo puntual) |
| Entradas hostiles y disponibilidad | F-04, F-09, F-10, F-11 | Puntual |
| Configuración y cadena de suministro | F-05, F-07, F-08, F-16 | Puntual o de proceso |
| Operación | F-12, F-13, F-14, F-15 | Puntual o riesgo aceptado |
| Ya resuelto | F-06 | — |

## 2. Integridad del veredicto

### F-01 — Falsificación del reporte y la nota · Crítico

**Problema.** El código del alumno corre en la misma JVM que el lanzador de JUnit, con el mismo
UID 1000, y `/work/reports` es escribible. El alumno escribe un XML JUnit con `tests="3"` y cero
fallos, y termina con `Runtime.halt(0)` antes de que corran los tests reales. La auditoría lo
reprodujo: resultado `OK`, código 0, fase `FIN`, cero procesos sobrevivientes.

**Evidencia.**
- `ms-sandbox/perfiles/java21-junit.sh:162-171`: un solo `java -jar junit.jar` con los tests y la
  solución en el mismo classpath.
- `ms-sandbox/imagenes/java21-junit/Dockerfile:51`: `USER 1000:1000` para todo el contenedor.
- `java21-junit.sh:205-215` y `worker/.../VerificadorJunitXml.java:30-52`: las dos guardas suman
  `tests` de **cualquier** `.xml` del buzón, sin verificar quién lo escribió.

**Por qué no alcanza con separar procesos o usuarios.** Un test JUnit llama directamente a las
clases del alumno (`new Solucion().sumar(1, 2)`), así que ese código **siempre** corre dentro de la
JVM de los tests. Adentro de esa JVM, cualquier secreto o canal del runner se puede leer o imitar:
con `/proc/self/mem`, con `sun.misc.Unsafe` o escribiendo en los mismos descriptores. Firmar el
reporte dentro del contenedor tampoco sirve, porque la clave vive en memoria legible. Es la misma
conclusión de `03-ms-sandbox-ejecucion.md` (sección 1.6, "`SecurityManager` no es una opción"): en
Java 21 no existe un mecanismo soportado para aislar al alumno del runner dentro de la JVM.

**Alternativas evaluadas.**

| Opción | Cómo | A favor | En contra |
|---|---|---|---|
| **A — Verificación previa con lista blanca** | Después de `javac` y **antes** de ejecutar, un verificador revisa el bytecode del alumno contra una lista blanca de APIs del JDK. Sin archivos, reflexión, procesos, `Runtime`, `Unsafe` ni `ClassLoader` | Frontera en el tiempo: todo lo que corre antes del código del alumno es confiable. Mantiene JUnit. Costo bajo | Restringe las APIs que puede usar el alumno. El verificador pasa a ser parte de la base confiable |
| B — Separación de procesos con puente RPC | Los tests corren en una JVM confiable y las clases del alumno se reemplazan por proxies que llaman a otra JVM | La frontera más fuerte | Muy caro. Limita los tipos que cruzan (sólo serializables) |
| C — Tests de entrada y salida para la nota | El programa del alumno recibe entradas y un proceso confiable compara las salidas. JUnit queda sólo para el feedback | Modelo probado (jueces tipo Judge0) | Cambia el formato de tests con T05 y G5, y lo que se puede evaluar |

**Decisión pendiente (D-SEG-1).** La opción A es la candidata, siempre que los TPs no necesiten
archivos, reflexión ni procesos. Hay un borrador en la sección 7.

### F-02 — Exposición de los tests ocultos · Alto

**Problema.** El código fuente y las clases de los tests quedan en `/work/in/test`, legibles por el
mismo UID. La carga de la auditoría imprimió `SolucionTest.java` completo, incluida una cadena
centinela, y aun así terminó `OK`.

**Evidencia.** `capa1.sh:279` entra a `$DIR_IN` antes de invocar la capa 2, y
`java21-junit.sh:110` compila `$SANDBOX_IN/test` con el mismo UID que después corre al alumno.

**Solución.** Tiene la misma causa que F-01 y la misma solución. Sin APIs de archivo ni reflexión
(incluido `Class.getResourceAsStream`), el alumno no puede leer bytes de los tests. El alumno
**sí** sigue viendo los argumentos con los que un test llama a su código. Eso es inevitable con
cualquier opción y ya se trata en `03-ms-sandbox-ejecucion.md` (sección 3.2, "Tests visibles vs.
tests ocultos").

### F-03 — Descriptor heredado que evade el tope de salida · Alto

**Problema.** `capa1.sh:73` guarda el stdout real en el descriptor 3 (`exec 3>&1`) y nunca lo
cierra antes de invocar la capa 2 (`capa1.sh:278-283`). El alumno escribe en `/proc/self/fd/3` y
evade el tope de 65.536 bytes de la capa 2. El sobre interno informa `truncado:false`.

**Qué se puede y qué no.**
- **No puede falsificar el sobre.** El nonce es de 128 bits, se lee de stdin y no se exporta.
- **Sí puede borrar el diagnóstico.** Si escribe más de 1 MiB por el descriptor 3,
  `Acumulador.java` retiene sólo el principio del stream y el sobre real queda afuera. El
  ejecutor falla cerrado (reporte ausente), pero se pierden la fase, el detalle y los procesos
  sobrevivientes de esa ejecución.

**Solución.**
1. Cerrar el descriptor antes de la capa 2 (`exec 3>&-` en el subshell que la invoca) y usarlo
   sólo dentro de `emitir`.
2. Agregar un test de `/proc/self/fd/*` que verifique que el proceso no confiable tiene abiertos
   sólo stdin, stdout y stderr.

**Aclaración.** La lista blanca de F-01 **no** reemplaza este arreglo: `System.out` sigue
permitido y el descriptor heredado queda al alcance de la JVM.

## 3. Entradas hostiles y disponibilidad

### F-10 — Validación del tar incompleta y frágil · Medio/alto

**Problema.**
1. `capa1.sh:212-225` rechaza sólo las entradas cuya línea de `tar -tv` empieza con `l` o `h`.
   **FIFO, dispositivos y sockets pasan.** La auditoría confirmó que un FIFO se acepta y termina
   mal clasificado (código 40).
2. `ruta=${linea##* }` toma lo que viene después del **último espacio** de la línea. Un miembro
   llamado `../evil dir/x` queda como `dir/x` y **evade el chequeo de `..`**. La auditoría sólo
   probó `../` sin espacios, que sí se rechaza.
3. Un nombre con salto de línea parte una entrada en dos iteraciones del `while read`.

**Impacto real.** GNU tar 1.35 probablemente se niega a extraer miembros con `..`, pero no está
verificado en la imagen. Además, el script valida por su cuenta justamente para no depender de eso.
Aun con traversal, la raíz de solo lectura limita la escritura al tmpfs `/work`.

**Solución.**
1. **Lista blanca de tipos:** sólo archivos regulares (`-`) y directorios (`d`).
2. **Dejar de parsear la salida para humanos.** Opciones:
   - a) validar el tar con un programa dedicado que lea los campos de cada entrada. En la imagen
     de Java puede ser el mismo binario del verificador de F-01, aunque eso ata la capa 1 a Java;
   - b) validar en el worker, que es confiable y arma el tar, y mantener en la capa 1 sólo una
     segunda barrera simple.
3. Rechazar nombres con espacios, saltos de línea o caracteres fuera de `[A-Za-z0-9._/-]`. El
   worker arma los nombres, así que no hay caso legítimo que se pierda.

**Decisión pendiente (D-SEG-4):** dónde vive la validación estructurada del tar.

### F-09 — Buzón de reportes sin límites estructurales · Alto

**Problema.** La capa 1 empaqueta todo `/work/reports` sin filtrar (`capa1.sh:103-104`), y
`worker/.../Desempaquetador.java:50-69` sólo limita los **bytes** (8 MiB). No hay tope de
cantidad de entradas, tipo ni largo de nombre. Miles de archivos vacíos comprimen a pocos KB y
pueden agotar la memoria del worker.

**Agravante.** `commons-compress` 1.21 es una dependencia **de producción** del worker y tiene
CVE-2024-25710 y CVE-2024-26308 (ver F-07).

**Solución.**
1. En `Desempaquetador`: tope de entradas (unas decenas), tope de largo de nombre y de
   profundidad, y aceptar sólo archivos regulares. Todo con falla cerrada (`ErrorDeSobre`).
2. Actualizar `commons-compress` a una versión corregida.

### F-04 — Agotamiento del socket antes de la admisión · Alto

**Problema.**
- `Servidor.java:81-98` crea un hilo virtual por conexión, sin límite de tiempo de lectura.
- `Http.java:24-53` limita 8 KiB por línea, pero no la cantidad ni el total de headers.
- Los semáforos se toman **después** de leer los headers (`Servidor.java:163-169`).
- `Http.leerExacto` (`Http.java:88-97`) espera el body para siempre con el turno ya tomado.

**Escenario.** Con 24 conexiones (8 en ejecución más 16 en cola) que mandan headers válidos y
nunca el body, todo tráfico legítimo recibe 503.

**Además.** Si falla fijar el permiso `0660` del socket, `Servidor.java:72-79` sólo lo registra y
sigue: falla abierta.

**Mitigante.** Requiere acceso al socket Unix, que en el compose sólo comparten el ejecutor y el
worker.

**Solución.**
1. Plazos para headers y body, tope de headers (cantidad y bytes) y tope de conexiones antes de
   la admisión.
2. Fallar al arrancar si no se puede fijar o verificar el `0660`.
3. Alternativa evaluable: reemplazar el parser propio por un servidor HTTP maduro. Elimina la
   clase de bug, pero es más cambio.

### F-11 — Errores de Docker sin respuesta controlada · Medio/alto

**Problema.** En `Docker.java:230-247` (`adjuntar`) y `Docker.java:330-350` (`logs`), la llamada
`.exec()` queda fuera de la conversión a `ErrorDaemon`. Una `RuntimeException` sube hasta
`Servidor.atender`, que sólo captura `IOException`, y la conexión se cierra **sin respuesta HTTP**.

**No verificable acá.** La regresión del demultiplexado de docker-java que menciona el informe es
de la biblioteca, no de este repositorio.

**Solución.**
1. Aplicar la misma conversión a todas las llamadas `.exec()`.
2. Agregar una frontera única de excepciones en el servidor, que siempre responda un 502 con JSON
   estable.
3. Tests con inyección de fallas en cada transición de Docker.

## 4. Configuración y cadena de suministro

### F-05 — Validación insegura de perfiles · Alto

**Problema.** `Catalogo.java` valida sólo máximos. En concreto:
- `memoriaMb`, `cpuS` y `version` pueden ser cero o negativos. **`Memory=0` significa sin límite
  en Docker.**
- `reportFormat` no se valida y puede ser nulo.
- Un perfil duplicado pisa al anterior en silencio (`put` sobre `LinkedHashMap`).
- Un catálogo vacío arranca igual.
- No se exige que el nombre `<id>@<version>.json` coincida con el contenido.
- `perfilId` no se valida con la misma regla que el header `X-Perfil`.
- No se verifica al arrancar que la imagen exista.

**Solución.**
1. Esquema cerrado: mínimos y máximos, `reportFormat` como valor de una lista cerrada, rechazo de
   duplicados y de catálogo vacío, coincidencia entre archivo y contenido, y la misma regex del
   request.
2. Al arrancar, inspeccionar cada imagen referenciada (incluido su digest, ver F-08).

### F-07 — Dependencias con vulnerabilidades conocidas · Alto

**Problema.** Dependencias directas: Jackson 2.17.2 (ejecutor y worker), docker-java 3.4.1
(ejecutor) y commons-compress 1.21 (test en el ejecutor, **producción en el worker**). El informe
lista además commons-io 2.13.0, commons-lang3 3.12.0, Guava 19.0, Bouncy Castle 1.76 y httpclient5
5.0.3, que llegan transitivas vía docker-java y **no están fijadas** en ningún `pom.xml`. Esas
versiones no se reverificaron (no se corrió `mvn dependency:tree`).

**Por qué importa más acá.** El ejecutor tiene el socket de Docker (F-14): comprometerlo equivale a
controlar el host.

**Solución.**
1. Actualizar las directas y fijar las transitivas con `dependencyManagement`.
2. SBOM y escaneo (OSV u OWASP Dependency-Check) en CI (ver F-16).

### F-08 — Imagen base no reproducible · Alto

**Problema.**
- `FROM` sin digest en `imagenes/java21-junit/Dockerfile:19`, `ejecutor/Dockerfile:6,18` y
  `worker/Dockerfile:7`.
- Paquetes `apt` sin versión fija (`Dockerfile:25-27`).
- JUnit se descarga sin checksum; hay un `TODO(seguridad)` en `Dockerfile:23`.
- El perfil referencia la imagen por tag (`java21-junit@4.json:4`).
- Según el informe, la imagen trae `/usr/bin/pebble` con vulnerabilidades de Go. No se pudo
  confirmar sin escanear la imagen.

**Solución.**
1. Fijar digests en `FROM` y en el perfil.
2. Verificar el sha256 de `junit.jar`.
3. Quitar `curl` después del build y usar una imagen base mínima.
4. Escanear la imagen en CI.

### F-16 — Configuración duplicada a mano · Medio

**Problema.** El campo `script` de `perfiles/java21-junit@4.json` es una **copia a mano** de
`perfiles/java21-junit.sh`. Además, la suma de los plazos de la capa 2 debe ser menor que
`SANDBOX_EVAL_TIMEOUT_S` y nada lo verifica: el propio script dice que *"se sostiene a mano"*. No
hay CI en el repositorio.

**Por qué va antes que otros arreglos.** Cualquier arreglo en el `.sh` puede no llegar nunca al
perfil que realmente se ejecuta.

**Solución.**
1. Generar el JSON desde el `.sh` en el build, o referenciar el script por ruta y hash.
2. Verificar la invariante de plazos con un test.
3. CI con build del JAR, imágenes, suite adversaria y escaneo.

## 5. Operación

### F-12 — `/salud` superficial · Medio

**Problema.** `Servidor.java:196-207` hace ping a Docker y reporta los semáforos. No revisa que el
catálogo no esté vacío, que las imágenes existan ni hace una ejecución canario.

**Solución.**
1. Chequear que el catálogo no esté vacío y que las imágenes existan.
2. Canario periódico (crear, ejecutar y borrar) cuyo resultado alimente `/salud`.

### F-13 — `X-Ejecucion-Id` repetido · Medio

**Problema.** El ejecutor no deduplica. Dos requests con el mismo id chocan con el nombre de
contenedor `sandbox-<id>` o se ejecutan dos veces.

**Solución.** Declarar como invariante que la idempotencia la garantiza el worker (clave
`entregaId:intentoNro`, ver `03-ms-sandbox-ejecucion.md`, sección 2.2) y que el ejecutor
responde un error explícito, distinto de un `ErrorDaemon` genérico, ante un nombre duplicado.

### F-14 — Superficie privilegiada del ejecutor · Medio

**Problema.** El ejecutor corre como root con el socket de Docker. **Mitigación ya presente y
verificada:** `Spec.java:43-106` fija imagen de catálogo, entrypoint, mounts vacíos, `CapDrop(ALL)`,
red `none` y `Privileged(false)`, y nada de eso depende del request.

**Solución, en orden de costo.**
1. Mantener la especificación fija (ya está).
2. Proxy de la API de Docker con lista blanca de operaciones.
3. Host o VM dedicado para el daemon de evaluación.

**Decisión pendiente (D-SEG-6):** aceptar el riesgo residual para el TP o incorporar el proxy.

### F-15 — Carreras en el apagado · Medio

**Problema.** `Servidor.close()` (`Servidor.java:211-237`) espera hasta 60 s y, si no alcanza, sigue
igual. `Main.java:49-59` hace `hilos.shutdown()` **sin** `awaitTermination` y enseguida
`docker.close()`, así que puede cerrar el cliente con ejecuciones vivas. `Barrido` limpia huérfanos,
pero cada 5 minutos.

**Solución.** `awaitTermination` con el mismo presupuesto antes de cerrar Docker, o
`shutdownNow()` con interrupción explícita.

### Extra — Test con carrera

`BundlesIT.java:258-266` (`p4c_hostilReporteLoop`) espera `exitEval == 0`, pero a veces da 47. La
detección real (`VEREDICTO_NO_CONFIABLE`, `surv == 6`) funciona. **Solución:** aceptar `{0, 47}` o
quitar esa aserción.

## 6. Decisiones

Todas las decisiones están pendientes. Donde hay una opción candidata, se indica cuál es y por qué,
pero no está cerrada.

| # | Decisión | Opciones | Candidata | Con quién |
|---|---|---|---|---|
| D-SEG-1 | Cómo corregir F-01 y F-02 | A) verificación previa del bytecode contra una lista blanca del JDK; B) separación de procesos con puente RPC; C) tests de entrada y salida para la nota | **A**: es la única frontera confiable sin partir la JVM. Requiere confirmar que los TPs no necesitan archivos, reflexión ni procesos | Nosotros + T05 |
| D-SEG-2 | Quién mantiene la lista blanca (si se elige A) | a) sólo el sandbox, versionada con la imagen y el perfil, y T05 pide las altas; b) T05 habilita APIs por consigna dentro de un techo nuestro | **a**: control centralizado y trazabilidad de qué reglas corrigieron cada entrega | Nosotros + T05 |
| D-SEG-3 | Si usar una API prohibida consume intento | a) no consume, igual que `BUNDLE_INVALIDO`; b) cuenta como intento fallido | **a**: la entrega no llegó a evaluarse. Nosotros clasificamos y T10 aplica la consecuencia (igual que D7, sobre `VEREDICTO_NO_CONFIABLE`, en `docs/arquitectura/README.md`) | Nosotros + T10 |
| D-SEG-4 | Dónde vive la validación estructurada del tar (F-10) | a) programa dedicado en la imagen; b) en el worker, con una barrera simple en la capa 1 | — | Nosotros |
| D-SEG-5 | Si el verificador entrega los `.class` ya compilados a la capa 2 (si se elige A) | a) no: compilar dos veces y no tocar el contrato con G5; b) sí: ahorra 1–2 s pero cambia el contrato | **a** | Nosotros + G5 |
| D-SEG-6 | Superficie privilegiada del ejecutor (F-14) | Aceptar el riesgo residual, proxy de Docker o host dedicado | — | Nosotros |
| D-SEG-7 | Parser HTTP del socket (F-04) | Endurecer el propio o reemplazarlo por un servidor maduro | — | Nosotros |
| D-SEG-8 | Convención del bundle: `src/` para la solución y `test/` para los tests | Formalizarla en `08-spec-ejecutor.md`. Hoy no está escrita y la necesitaría el verificador | — | Nosotros + G5 |
| D-SEG-9 | Contenido inicial de la lista blanca (si se elige A) | Qué paquetes y miembros entran en `java21-junit@5` | — | Nosotros, con los enunciados de T05 |

## 7. Borrador de la opción A para F-01 y F-02

> Sujeto a D-SEG-1, D-SEG-2, D-SEG-3, D-SEG-5, D-SEG-8 y D-SEG-9. No es una decisión tomada.

**Dónde corre.** La capa 2 la escribe el Grupo 5 y para nosotros es opaca (`08-spec-ejecutor.md`,
cambio C9, modelo de dos capas). Por eso el verificador **no** puede vivir en la capa 2. Va en la
**imagen**, que es nuestra: la capa 1 ejecuta `/opt/sandbox/preverificar` antes de invocar la capa
2, si la imagen lo trae. La capa 1 sigue sin saber de Java.

**Qué hace.**
1. Compila `src/` a un directorio descartable con `javac -proc:none`, contra el classpath de la
   imagen y **sin** los tests.
2. Recorre cada `.class` y resuelve todas las referencias: clases, métodos, campos, superclases,
   interfaces y bootstraps de `invokedynamic`.
3. Compara contra la lista blanca. Ante una violación, la capa 1 termina con `API_NO_PERMITIDA` y
   la lista de APIs usadas. **La capa 2 nunca corre.**
4. Si pasa, descarta lo compilado y sigue el flujo actual.

**Condiciones para que el control sea real.**

| Condición | Por qué |
|---|---|
| Granularidad por miembro, no sólo por clase | `Scanner` completo habilita `new Scanner(Path)`; `Object` completo habilita `getClass()` y de ahí `Class.getMethod` |
| Revisar bytecode, no fuente | Incluye lo que genera el compilador: lambdas, concatenación, `switch` sobre strings |
| Resolver herencia | Extender `ClassLoader` o `Thread` también cuenta como uso |
| El alumno no referencia clases de los tests ni fuera del JDK | Evita atajos por helpers del profesor o bibliotecas de la imagen |
| `javac -proc:none` | Ningún *annotation processor* ejecuta código antes del verificador |
| Suite de evasión propia | El verificador pasa a ser parte de la base confiable |

**La lista blanca.** Archivo versionado dentro de la imagen, con dueño root y permisos `0444`, y su
hash en el perfil. Cerrada por defecto: paquetes "limpios" enteros y miembros sueltos de clases
peligrosas.

**Contratos.** Veredicto nuevo `API_NO_PERMITIDA` y un código nuevo en el sobre de la capa 1. G5 no
cambia nada.

**Pruebas.** Evasión del verificador (reflexión con strings, herencia, `invokedynamic` a mano,
`MethodHandles`, `Unsafe`, lambdas que envuelven APIs prohibidas). Las cargas `forja-sincrona` y
`lectura-tests` de la auditoría pasan a ser tests negativos obligatorios.

## 8. Orden sugerido

1. **F-16**: que los arreglos de scripts lleguen al perfil que se ejecuta.
2. **F-01 / F-02**: cerrar D-SEG-1 y después implementar. Bloquean la confiabilidad de la nota.
3. **F-03 y F-10**: tocan `capa1.sh`, conviene hacerlos juntos.
4. **F-09, F-05, F-04 y F-11**: independientes y baratos.
5. **F-07 y F-08**: cadena de suministro, junto con la CI.
6. **F-12, F-13, F-15** y el test con carrera.
7. **F-14**: según D-SEG-6.
