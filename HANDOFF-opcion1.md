# Handoff — Opción 1 del V4 (catálogo de perfiles)

**Para:** el próximo agente / la próxima sesión
**Fecha:** 10 de septiembre de 2026
**Estado:** capa 1 del contenedor construida y verificada. **Ejecutor Java: portado a `docker-java` y
hablando el framing de tres documentos** (10/09). Falta el catalogo de perfiles (Paso 1) y
`Servidor`/`X-Perfil` (Paso 2). El detalle de lo que se gano y lo que se perdio en el port esta en
`java sidecar test/README.md`, que quedo actualizado; lo de aca abajo se conserva como el
razonamiento previo.

---

## 0. Leé esto primero

Este trabajo es una **validación de factibilidad**, no la implementación final. Textual del
usuario: *"todo esto es a modo de validar que lo que planificamos funciona; en el proyecto real
re-ensamblaríamos esto"*. Consecuencias prácticas:

- No hay documento de diseño formal ni plan escrito, a propósito.
- **`java sidecar test/08-spec-ejecutor.md` NO se toca** hasta que la validación cierre. La idea es
  actualizarla entera de una vez con lo aprendido, en vez de parchearla mientras cambia.
- La implementación **Node (`node-sidecar-test/`) quedó de lado**. No se borra. Consecuencia: §14 y
  R14.0/R11.5 de la spec pierden su fundamento — existían para comparar dos implementaciones.

Lo que sigue está ordenado para que puedas trabajar sin releer los PDF ni la spec entera.

---

## 1. De qué va todo esto, en un párrafo

G8 (nosotros, Tema 06) corre código de alumnos en contenedores aislados. G5 (Tema 05) define qué
significa aprobar. El V4 de G5 aceptó nuestro modelo de **dos capas** dentro del contenedor: la
capa 1 es nuestra (nonce, entrada, aislamiento, salida), la capa 2 es de ellos (compilar, correr
tests, dictaminar). De las tres opciones que ofrecía el V4 elegimos la **Opción 1: catálogo de
perfiles**, donde el script de evaluación de G5 vive en nuestra base y en cada ejecución nos mandan
sólo un `profileId`.

Documentos fuente, por orden de utilidad:

| Documento | Qué es |
|---|---|
| `otros/Respuesta_G8_a_Propuesta_V4.md` | **El contrato.** Lo que la capa 1 implementa. Empezá acá |
| `propuesta-arquitectura-sandbox.md` | Arquitectura que circula en el grupo. **Tiene agujeros, ver §4** |
| `Propuesta_Integracion_G5_G6_Entrypoint_V4.pdf` | La propuesta de G5 |
| `java sidecar test/08-spec-ejecutor.md` | La spec del ejecutor. Desactualizada respecto de todo esto |

---

## 2. Decisiones ya tomadas — no las re-litigues

Todas acordadas con el usuario en sesión. Si algo parece discutible, el motivo está escrito.

| # | Decisión | Motivo |
|---|---|---|
| 1 | **Catálogo de perfiles LOCAL en el ejecutor**, directorio read-only, un archivo por versión (`java21-junit@3.json`). Se carga entero al arrancar; archivo inválido ⇒ el ejecutor no arranca | Descartado que el worker mande imagen+límites (perdería P1) y que el ejecutor consulte una API en runtime (dependencia de red en el componente privilegiado) |
| 2 | **El hash del perfil se calcula (sha256), no se declara** | Un campo declarado se puede mentir |
| 3 | **El script de la capa 2 llega por stdin como tercer documento** | Ver §3. Descartado inyectarlo en el tar (metería parsing de tar en el componente privilegiado) y hornearlo en la imagen (mata la autonomía de publicación de G5) |
| 4 | **P1 reformulado (P1')**: ningún byte de la spec del contenedor viene del request; los **cuatro** campos variables (`Image`, `Memory`, `MemorySwap`, `Ulimits[cpu]`) vienen del catálogo, y el request sólo elige una clave | Techos duros `MEMORIA_MAX_MB`/`CPU_MAX_S` en `Constantes`; un perfil que los supera no carga |
| 5 | **Request**: header obligatorio `X-Perfil: <id>@<version>`, `^[a-z0-9-]+@[0-9]+$`. 400 si falta o mal formado, **422 nuevo** si no está en el catálogo | La versión es obligatoria: sin ella no hay trazabilidad |
| 6 | **Response**: se agregan `perfilId`, `perfilVersion`, `perfilHash` **al final** | Al final, para que el test de orden de campos se extienda en vez de reescribirse |
| 7 | **Se usa `docker-java`** | Decidido el 10/09. Ver §5, tiene condiciones |

---

## 3. El framing de stdin — lo más importante que hay que entender

La capa 1 recibe **tres documentos pegados**, sin separadores:

```
<nonce>\n              32 hex
<n>\n                  cuántos BYTES mide el script de la capa 2
<script de n bytes>    el run.sh del perfil, opaco
<tar>                  el bundle: "lo que quede del stream"
```

**Por qué el largo adelante y no una marca de fin.** El script lo escribe G5: es texto arbitrario y
puede contener cualquier línea, incluida la que eligiéramos como separador. Y usar el nonce como
delimitador está prohibido — se lo mostraríamos a la capa 2, que es justamente lo que el nonce
existe para evitar. El largo no tiene alfabeto: son *n* bytes opacos. Es `Content-Length`.

**Verificado empíricamente** (sondeo del 09/09, tres imágenes reales):

| Lector | busybox (alpine) | dash (temurin) |
|---|---|---|
| `dd iflag=fullblock bs=$N count=1` | ✅ | ✅ **← el elegido** |
| `dd bs=1 count=$N` | ✅ | ✅ (correcto pero lento) |
| `head -c $N` | ❌ **se come 710 bytes del tar** | ✅ |

`head -c` en busybox lee un bloque de 1024 B y descarta el sobrante, que sobre un pipe no se puede
devolver: el tar llega decapitado. Alpine es la base que G5 propone en §6 del V4, así que ese bug
habría aparecido en producción, no en desarrollo. **No lo reintroduzcas.**

En Java: `n = script.getBytes(UTF_8).length`. Bytes, no caracteres — un acento en un comentario
mueve el número.

---

## 4. Los agujeros de `propuesta-arquitectura-sandbox.md`

El documento describe bien la separación de responsabilidades, pero su §4 (flujo) y su §3 (código
de ejemplo) tienen problemas que hay que corregir antes de construir sobre ellos. **No los copies.**

### Críticos

1. **El nonce queda al alcance del alumno.** La Etapa 2 embebe el nonce como literal en un
   `entrypoint.sh` que va **adentro del `bundle.tar`** y se extrae en `/workspace`, la misma carpeta
   donde están los archivos del alumno. Un `cat /workspace/entrypoint.sh` devuelve el nonce, y con
   el nonce el alumno imprime sus propias marcas y su propio reporte. Es exactamente lo que R7.3
   prohíbe. **Corrección: el nonce viaja por stdin y nunca toca el disco.**

2. **Race de orden en la Etapa 3.** El código hace `startContainerCmd` y *después*
   `copyArchiveToContainerCmd`. El entrypoint arranca con el contenedor, o sea antes de que existan
   `/workspace/run.sh` y los archivos. **Corrección: todo entra por stdin, que además evita el
   conflicto conocido entre `docker cp` y `--read-only`.**

3. **El resultado sale por `stdout`** (`cat reports/TEST-*.xml` en el `runScript` de ejemplo). El
   stdout está contaminado por lo que imprime el alumno; las marcas impiden *falsificar* pero no
   *contaminar*. **Corrección: la capa 1 desvía stdout/stderr a archivos antes de invocar la capa 2,
   y el resultado vuelve como archivo en `/work/reports`.** Es el único punto que la §7 de
   `Respuesta_G8_a_Propuesta_V4.md` marca como *"lo único que les cambia el diseño"* a G5.

4. **`indexOf` de la primera aparición** para las dos marcas. R7.5 pide **la última de inicio y la
   primera de fin posterior a ella**.

5. **No hay barrido de procesos sobrevivientes.** El ataque de reescritura del reporte pasa sin
   detección. Está medido: `hostil-reporte-loop` deja 6 procesos vivos y la capa 2 devuelve 0.

### Importantes

6. Sin `ulimit cpu`: sólo 30 s de reloj de pared. Es la varianza que medimos — de 1,8 a 5,2 s para
   el mismo bundle según la carga del host.
7. `removeContainerCmd().withForce()` en el `finally` puede borrar antes de leer los logs. El orden
   correcto es `wait → inspect → logs → delete`, con `AutoRemove: false`.
8. Sin `inspect` no hay `OOMKilled`, y R3.4 dice que el worker necesita ese dato **además** del
   `exitCode` para distinguir "se quedó sin memoria" de "el código falló".
9. `--scan-class-path` en el `runScript` de ejemplo: es el costo de descubrimiento (1,2–2,9 s) que
   el spike midió y que evitamos derivando las clases de los `.class` compilados.
10. `COMPILATION_ERROR` como status de G8 contradice el §1 del propio documento: G8 no sabe qué es
    compilar. Eso es la banda 40-59 de la capa 2.

### Un cambio que sí es mejora

Que **G5 mande archivos crudos y G8 arme el tar** es mejor que lo que asumíamos. Sólo hay que ser
explícito en qué componente lo arma: **si lo arma el worker, el ejecutor sigue recibiendo el tar
opaco e I7 sobrevive.** Si lo armara el ejecutor, I7 se cae.

---

## 5. La decisión de `docker-java`, con sus condiciones

Se decidió **usar `docker-java`**. Tres cosas que tenés que hacer al respecto:

1. **Derogar R11.4 en la spec, con el motivo escrito.** Hoy prohíbe explícitamente `docker-java`
   argumentando que *"pone un `withPrivileged(true)` en el mismo proceso que tiene el socket"*. Ese
   argumento es débil y conviene decirlo: quien ya ejecuta código en el ejecutor tiene el socket y
   puede mandar el JSON que quiera a mano. La librería no agrega capacidad, agrega comodidad.
2. **Rehacer el golden A1/A2.** Es la pérdida real. Hoy `Spec.java` produce bytes que se comparan
   contra `src/test/resources/spec-create-referencia.json`, y eso es lo que convierte la invariante
   **I1** de afirmación en evidencia. Con `docker-java` no controlás la serialización.
   **Mitigación acordada: serializar vos mismo el `CreateContainerCmd` con Jackson y hacer el golden
   contra eso.** No son los bytes del cable, pero recupera casi toda la evidencia. Sin esto, I1 y I2
   quedan sin test y hay que decirlo en la defensa.
3. **R8.5 se pone incómodo**: el bucle de escritura de stdin lo maneja la librería. Necesitás un
   `InputStream` propio que corte en EOF al vencer el tope, o perdés la garantía de que un
   contenedor que no consume stdin no retiene su cupo de concurrencia para siempre.

**El premio:** el transporte `httpclient5` habla **named pipe en Windows**. Eso resuelve el
problema logístico de siempre — hoy el ejecutor Java no puede hablar con Docker Desktop
(`UnixDomainSocketAddress` contra un named pipe) y hay que correrlo adentro de un contenedor para
probarlo. Con `docker-java` corre nativo.

Se van `ClienteDocker` (174), `Http` (108) y `Demultiplexor` (53) ≈ **335 líneas**. El total baja de
869 a ~530.

---

## 6. Qué está construido y verificado

Todo en `sandbox/runner/`. **El `entrypoint.sh` viejo quedó intacto a propósito, como línea de base.**

| Archivo | Qué es |
|---|---|
| `capa1.sh` | **La capa 1 de G8**, 397 líneas POSIX sh. El nuevo ENTRYPOINT |
| `perfiles/java21-junit.sh` | La capa 2 de referencia — lo que en el modelo real escribe G5. Códigos de la banda 40-59 |
| `Dockerfile.capa1` | → imagen `sandbox-runner:2.0.0-capa1` |
| `probar-capa1.sh` | Arma el stdin de tres documentos a mano. **Es el molde de lo que tiene que hacer `Ejecucion.java`** |
| `entrypoint.sh` | El viejo, de una sola capa. No lo toques, es la línea de base |

### Verificación contra Docker real (09/09)

```
bundle                exit  resultado capa 1           exitEval  surv
ok-suma                 0   OK (3 tests)                  0       0
hostil-exit0           47   DETENIDO_POR_EVALUACION      47       0
hostil-cpu             44   DETENIDO_POR_EVALUACION      44       0
hostil-memoria         43   DETENIDO_POR_EVALUACION      43       0
hostil-sleep           45   DETENIDO_POR_EVALUACION      45       0
hostil-reporte         30   VEREDICTO_NO_CONFIABLE        0       6
hostil-reporte-loop    30   VEREDICTO_NO_CONFIABLE        0       6
hostil-paquete          0   OK                            0       0
hostil-red              0   OK                            0       0
tar con symlink        22   BUNDLE_INVALIDO               —       —
tar con hardlink       22   BUNDLE_INVALIDO               —       —
tar con ..             22   BUNDLE_INVALIDO               —       —
```

Los cuatro primeros son **equivalentes** a la línea de base anterior, traducidos de la banda 20-31
a la 40-59 (29→47, 26→44, 25→43).

**El resultado más interesante son los dos de reescritura del reporte:** `exitEval=0` significa que
la capa 2 dijo "todo bien", y la capa 1 la sobrescribió igual porque detectó seis procesos vivos.
Es el argumento de §2.4 funcionando: no se puede prevenir, se puede detectar, y la detección vive
en la capa que la capa 2 no controla.

### Dos cambios de criterio que introdujo el refactor

- **La capa 1 ya no exige que todo cuelgue de `src/` y `test/`.** Que exista `src/` es conocimiento
  de evaluación. Ahora sólo exige rutas relativas, sin `..` y sin enlaces. Bajo la Opción 1 el
  `files[]` de G5 puede traer cualquier ruta.
- **La guarda del `System.exit(0)` (exigir `tests > 0`) se mudó a la capa 2.** Leer un XML de JUnit
  es saber el `reportFormat`, o sea conocimiento de evaluación. Sigue existiendo, pero ahora es
  responsabilidad de G5 — y es la razón concreta por la que les pedimos `reportFormat` en §4.6.

### Bandas de códigos de salida, como quedaron

| Rango | Dueño | Valores en uso |
|---|---|---|
| `0` | — | llegó al final |
| `20–31` | **capa 1** | 22 BUNDLE_INVALIDO, 23 EVALUACION_ANOMALA, 27 TIMEOUT_PARED, 28 SIN_REPORTE, 30 VEREDICTO_NO_CONFIABLE, 31 MUERTO_POR_SENAL |
| `32–39` | — | hueco a propósito, para crecer sin renegociar |
| `40–59` | **capa 2 (G5)** | en el perfil de referencia: 40 no compila, 41 suite rota, 42 timeout compilación, 43 memoria, 44 CPU, 45 pared, 46 sin reporte, 47 salida anticipada |

---

## 7. Qué falta — el plan de la próxima sesión

En orden. Los pasos 1 y 2 no dependen de Docker y se prueban con `mvn test`.

### Paso 1 — El catálogo de perfiles

Directorio read-only, un archivo por versión:

```jsonc
// java21-junit@3.json
{
  "perfilId": "java21-junit",
  "version": 3,
  "imagen": "sandbox-runner:2.0.0-capa1",
  "script": "#!/bin/sh\n…",          // el contenido de perfiles/java21-junit.sh
  "reportFormat": "junit-xml",
  "limites": { "memoriaMb": 512, "cpuS": 20 }
}
```

Carga completa al arrancar. Validaciones que **deben** hacer fallar el arranque, no la ejecución:
JSON inválido, `memoriaMb > MEMORIA_MAX_MB`, `cpuS > CPU_MAX_S`, violación de R4.1 (`cpuS`
demasiado cerca de los 60 s de pared), script mayor a `MAX_SCRIPT_BYTES`. El hash se calcula al
cargar.

### Paso 2 — `Spec.java` y `Servidor.java`

- `Spec`: los cuatro campos variables salen del perfil; **todo lo demás sigue constante**.
- Golden **por perfil**, con la mitigación de §5 punto 2.
- El test que reemplaza a P1: dos perfiles distintos producen dos `create` que difieren **sólo** en
  `Image`, `Memory`, `MemorySwap` y `Ulimits[cpu]`, e idénticos en todo lo demás.
- `Servidor`: `X-Perfil` → 400 / 422.
- Response: `perfilId`, `perfilVersion`, `perfilHash` al final.

### Paso 3 — `Ejecucion.java`: el framing de tres documentos

Portar `probar-capa1.sh` a Java. Es mecánico: el script ya demuestra el orden exacto de los bytes.

### Paso 4 — Contra Docker real

1. **El framing**, con un perfil de juguete que haga `ls -la $SANDBOX_IN` y copie algo al buzón.
2. **El sobre-consumo, byte a byte**: un tar cuyo primer byte sea conocido, verificar que sigue ahí.
   El test anterior podría no notar que se comió un solo byte.
3. **Los nueve bundles**, contra la tabla de §6 de este documento.

### Cabos sueltos menores

- `hostil-paquete` y `hostil-red` dieron `OK` y **no hay línea de base registrada** para ellos:
  nunca habían pasado por el ejecutor, sólo por `run.sh`. Confirmalo con el usuario.
- `sandbox/runner/README.md` todavía describe el modelo de una sola capa.
- La spec `08-spec-ejecutor.md`, entera, cuando la validación cierre: §3.1, §3.3, §4, §7.2, §11.4,
  §13, §14.

---

## 8. Cómo correr las cosas

```bash
cd sandbox-docs/sandbox/runner
docker build -f Dockerfile.capa1 -t sandbox-runner:2.0.0-capa1 .
./probar-capa1.sh bundles/ok-suma
./probar-capa1.sh bundles/hostil-cpu perfiles/java21-junit.sh
```

```bash
cd "sandbox-docs/java sidecar test"
mvn test        # los *IT se saltean si no hay socket del daemon
mvn package     # target/ejecutor.jar
```

### Gotchas del entorno

- **Windows + Git Bash.** `export MSYS_NO_PATHCONV=1` antes de cualquier `docker run -v`, o las
  rutas se traducen mal. **Pero desactivalo para llamadas locales a `tar`**, porque con la variable
  puesta interpreta `C:` como un host remoto y falla con `Cannot connect to C: resolve failed`.
- **`jq` no está instalado.** `probar-capa1.sh` cae a mostrar el JSON crudo.
- **busybox `tar` no tiene `--transform`** (es de GNU tar). Para armar tars maliciosos de prueba usá
  `eclipse-temurin:21-jre`, no `alpine`.
- Git Bash no crea symlinks reales: los tars con enlaces hay que armarlos adentro de un contenedor.
- **El `sh` de las imágenes es `dash`**, aunque el `entrypoint.sh` viejo declare `#!/bin/bash`.
  Funcionaba sólo por el shebang: usaba arrays y `mapfile`. `capa1.sh` y el perfil son POSIX puro.
- Escribir archivos de shell con heredocs muy largos desde la herramienta de Bash rompe el parseo;
  usá la herramienta de escritura de archivos y después `sed -i 's/\r$//'`.

---

## 9. Preguntas abiertas para el usuario

Ninguna bloquea el trabajo de §7.

1. Cómo llega un perfil publicado por G5 hasta el directorio del ejecutor: volumen compartido,
   rebuild, recarga en caliente. Es decisión de despliegue y quedó explícitamente fuera de alcance.
2. Los presupuestos separados de compilación y evaluación (§4.3 de la respuesta a G5). El perfil los
   puede declarar, pero quien los usa es la capa 2 — es otro refactor.
3. El nombre del evento en el bus y si además del evento quieren el `GET` como respaldo (§6 de la
   respuesta a G5). Sigue sin respuesta de G5.
