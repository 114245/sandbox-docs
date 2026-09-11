# D16 — Dónde se verifica que hubo ejecución de verdad

> **Tema 06 — Sandbox / Runtime.** Grupo 8, 11 integrantes.
> UTN FRC · Programación 4 + Metodología de Sistemas 2 · TPI 2026.
>
> **Qué es este documento.** El cierre de **D16** y la evidencia que permitió implementar el
> refactor del entrypoint (P9).
>
> **Estado: decisión cerrada; P9 implementado y probado.** La integración pendiente está del lado
> del worker: seleccionar el verificador por `reportFormat` y fallar de forma segura ante evidencia
> ilegible o un formato desconocido (§9).
>
> **La premisa está verificada contra la imagen real** (§8): se neutralizó la guarda de la tapa y
> `hostil-exit0` siguió mandando afuera el XML con `tests="0"`, que es lo que el worker necesita
> para dictar `SALIDA_ANTICIPADA` por su cuenta. El control con `ok-suma` manda `tests="3"`.
>
> Escrito el **8 de septiembre de 2026**.

---

## Índice

1. [La pregunta está mal planteada, y eso importa](#1-la-pregunta-está-mal-planteada-y-eso-importa)
2. [Qué hace hoy cada capa, exacto](#2-qué-hace-hoy-cada-capa-exacto)
3. [Las tres opciones, y por qué dos se caen](#3-las-tres-opciones-y-por-qué-dos-se-caen)
4. [La decisión](#4-la-decisión)
5. [El caso crítico: fail-closed](#5-el-caso-crítico-fail-closed)
6. [Qué reemplaza a `testsEnReporte`](#6-qué-reemplaza-a-testsenreporte)
7. [Correcciones derivadas](#7-correcciones-derivadas)
8. [Cómo se prueba — y el resultado](#8-cómo-se-prueba--y-el-resultado)
9. [Qué queda abierto para integrar el worker](#9-qué-queda-abierto-para-integrar-el-worker)

---

## 1. La pregunta está mal planteada, y eso importa

D16 quedó escrita así:

> *¿Dónde se verifica "hay evidencia real de ejecución" cuando la tapa no sabe leer el reporte?*

La respuesta inmediata sería *"se muda del entrypoint al worker"*. Al ir a mirar el código resulta
que **ya está en el worker**, y desde antes del V4.

[`04-ms-sandbox-worker.md`](./04-ms-sandbox-worker.md) §6 lo dice sin ambigüedad, en la guarda que
encabeza el mapeo de veredictos:

> Aunque el sobre diga `OK`, no hay `EXITO` sin **`tests > 0` leído del XML de verdad**, no del
> contador que el runner trae ya calculado. **El contador es defensa en profundidad; la fuente es
> el XML.**

O sea que hoy la regla de D6 se aplica **dos veces**: una en el entrypoint —que cuenta y emite
`testsEnReporte`— y otra en el worker, que parsea el XML por su cuenta y no le cree al contador. La
que decide es la segunda.

**Eso cambia el tamaño de la decisión.** D16 no es "mudar una verificación", que sería trabajo y
riesgo. Es **borrar la copia redundante y arreglar la que queda**, que hoy tiene el formato
cableado. Es bastante más barato de lo que parecía — y, sobre todo, **no hay una ventana en la que
la propiedad no esté sostenida por nadie**, que era el riesgo real de mudar una guarda de seguridad.

La pregunta bien planteada es entonces triple:

1. La verificación fina se queda en el worker: **¿cómo se indexa por formato de reporte?**
2. Si la tapa deja de contar, **¿qué queda de defensa en profundidad?**
3. La tapa hoy usa ese conteo para emitir `SALIDA_ANTICIPADA`. **¿Quién produce ese estado ahora?**

---

## 2. Qué hace hoy cada capa, exacto

El bloque final del `entrypoint.sh` de una sola capa (líneas 419‑456; eliminado del repo, ver
historial de git) hacía **cinco** cosas, y conviene
mirarlas de a una, porque no todas corren la misma suerte.

| # | Guarda | Qué mira | ¿Necesita saber el formato? | ¿Puede vivir afuera? |
|---|---|---|---|---|
| 1 | Buzón vacío → `SIN_REPORTE` (28) | `ls -A /work/reports` | **No.** Cuenta archivos, no los interpreta | Sí, pero conviene que quede |
| 2 | Relojes y señales → `TIMEOUT_*`, `LIMITE_MEMORIA`, `MUERTO_POR_SENAL` | exit code + CPU medida con `time` | **No** | **No.** La CPU por fase sólo se mide adentro |
| 3 | `tests == 0` → `SALIDA_ANTICIPADA` (29) | `grep tests="N"` sobre los XML | **Sí. Es la única** | Sí — y ya está duplicada afuera |
| 4 | Procesos sobrevivientes → `VEREDICTO_NO_CONFIABLE` (30) | recorre `/proc` | **No** | **No, y es físicamente imposible.** El `/proc` del contenedor sólo existe adentro, y la barrida tiene que correr *después* de que `run.sh` devuelve el control |
| 5 | Emitir el sobre entre las marcas del nonce | — | No | No. El nonce no sale de la tapa |

**El hallazgo de esta tabla es que sólo la guarda 3 sabe de JUnit.** Las otras cuatro son agnósticas
del lenguaje y del formato, y dos de ellas —la 2 y la 4— **no se pueden mudar aunque quisiéramos**:
dependen de información que sólo existe adentro del contenedor.

Es una buena noticia para el refactor: la parte del entrypoint que hay que volver agnóstica ya lo es
casi entera. Lo que hay que sacar de ahí son **tres líneas de `grep`**, no una arquitectura.

> **El orden importa, y hoy está bien.** La guarda 2 corre **antes** que la 3. Por eso un
> `TIMEOUT_CPU` que alcanzó a escribir un reporte de 0 pruebas se clasifica como timeout y no como
> salida anticipada — que es la primera de las dos trampas que documenta `04` §6. Ese orden se
> conserva tal cual, y es lo que hace que la guarda 3 quede, al llegar a ella, sin ambigüedad: **si
> el pipeline terminó bien y aun así no corrió ninguna prueba, es el alumno.**

---

## 3. Las tres opciones, y por qué dos se caen

### Opción A — la tapa sigue contando, parametrizada por el perfil

El perfil declara `reportFormat` y la imagen trae un contador por formato; el entrypoint elige cuál
usar.

**Se cae por tres motivos, en orden de gravedad:**

1. **Reinstala adentro del contenedor exactamente lo que el sándwich saca.** Todo el punto del
   modelo de dos capas es que la tapa no sepa qué corrió adentro. Un `case` sobre `reportFormat` en
   el entrypoint es conocimiento de dominio con otro nombre.
2. **Cada formato nuevo pasa a ser una imagen nueva.** Agregar `pmd-xml` requeriría construir y
   desplegar imágenes, cuando el objetivo declarado del catálogo de perfiles es que el Grupo 5
   publique sin depender de un despliegue nuestro.
3. **Es parsear XML en shell.** Hoy se sostiene porque es un `grep` sobre un formato conocido y el
   resultado es sólo defensa en profundidad. Como fuente del veredicto sería frágil.

### Opción B — que lo declare la capa 2

El `run.sh` del Grupo 5 escribe cuántas pruebas corrieron en `$SANDBOX_STATUS/`, y nosotros lo
leemos.

**Se cae por uno solo, y es definitivo.** La capa 2 **está tratada como no confiable** — se lo
dijimos al Grupo 5 con todas las letras en la respuesta al V4 §4.7, y es la propiedad que hace que
esta integración sea barata para los dos. Pedirle a la capa 2 que declare la evidencia de su propia
ejecución es pedirle al vigilado que se vigile. Es la misma familia de error que aprobar por exit
code: **la cosa que se verifica no puede ser la que aporta la prueba.**

Vale anotarla igual, porque es la opción **tentadora**: es la más barata de todas, no requiere
parsear nada, y el día que alguien proponga *"que nos lo diga el script y listo"*, el motivo del
rechazo tiene que estar escrito.

> Que sea no confiable **como fuente del veredicto** no la inhabilita como fuente de *diagnóstico*.
> Los archivos `$SANDBOX_STATUS/fase` y `detalle` los seguimos copiando al sobre tal cual, porque
> sirven para el mensaje al alumno y no para decidir si aprobó.

### Opción C — el worker verifica, indexado por el formato del perfil

El worker ya recibe `reportesTarGzB64` y ya parsea. Se le agrega un **verificador por formato**,
elegido con el `reportFormat` que declara la versión del perfil.

**Es la única que conserva la propiedad.** El worker corre **afuera del contenedor**, fuera del
alcance del código del alumno y del script del Grupo 5. Y el `reportFormat` no viene del request:
viene de una **versión de perfil inmutable**, registrada por nosotros y validada por el smoke test.

---

## 4. La decisión

> **D16 — CERRADA.** La verificación de evidencia real de ejecución **queda entera en el
> worker**, en un verificador seleccionado por el `reportFormat` de la versión de perfil que corrió.
> El entrypoint deja de contar pruebas y deja de emitir `testsEnReporte`. Las guardas 1, 2, 4 y 5
> del §2 **se quedan donde están**, sin cambios.

La forma concreta, del lado del worker:

```
VerificadorDeEvidencia
  soporta(formato)   -> bool
  verificar(reportes) -> Evidencia { corridas: int, fallidas: int, legible: bool }
```

- **`junit-xml`** es el primero, y es el que ya está escrito: **suma** `tests` y `failures`+`errors`
  **a través de todos los XML del buzón**. No es código nuevo — es el que existe, movido detrás de
  la interfaz. La palabra *suma* no es decorativa: `ok-suma` deja **tres** XML y **dos tienen
  `tests="0"`**, así que un verificador que mirara archivo por archivo rechazaría el camino feliz.
  Está medido en §8.3.
- **`pmd-xml`, `archunit-xml`, …** se agregan cuando haga falta, sin tocar imágenes ni el
  entrypoint. Esto es lo que compramos con la decisión.
- **`legible: false`** es el caso importante, y lo trata el §5.

La regla de negocio, que es D6 reformulada sin nombrar a JUnit:

> **No hay `EXITO` sin evidencia legible de que corrió al menos una unidad de evaluación.**
> El veredicto sale del reporte; el código de salida es diagnóstico.

Y la cadena de reproducibilidad queda cerrada gratis: cada ejecución ya guarda la versión y el hash
del perfil con el que corrió (respuesta al V4 §4.1), y el `reportFormat` es parte de esa versión
inmutable. **Se puede saber siempre con qué verificador se juzgó una entrega de hace tres semanas.**

---

## 5. El caso crítico: fail-closed

Este es el punto que convierte a D16 de trámite en decisión.

Hoy la guarda contra `System.exit(0)` está sostenida por **dos** cosas, y la tapa es una de ellas.
Después del refactor queda sostenida por **una sola**. Entonces:

> **¿Qué pasa si el perfil no declara `reportFormat`, o declara uno que el worker no sabe leer?**

Sin una respuesta explícita, el camino natural del código es el peor: el verificador no encuentra
nada, devuelve 0 problemas, el sobre dice `OK`, el buzón no está vacío — y **una entrega con
`System.exit(0)` vuelve a aprobar.** Que es exactamente el bug que se descubrió el 27‑ago y que dio
origen a D6. Lo perderíamos por omisión, no por decisión.

**La regla, entonces:**

> **Un reporte que no se puede verificar no es un reporte aprobado.** Si no hay verificador para el
> formato declarado, o el verificador no puede leer lo que llegó, el veredicto **no puede ser
> `EXITO`**. Es `ERROR_INTERNO` —el problema es nuestro, no del alumno— y por lo tanto **no consume
> intento**.

Que sea `ERROR_INTERNO` y no un fallo del alumno importa: un perfil mal configurado es culpa nuestra
o del Grupo 5, y hacérselo pagar al que entregó sería la misma injusticia que ya está resuelta para
la suite que no compila.

**Y el lugar donde esto se hace barato es el registro del perfil, no la ejecución.** El smoke test
que propusimos en la respuesta al V4 §4.7 —correr el perfil contra un bundle bueno y uno malo y
verificar que **no dan el mismo veredicto**— es estructuralmente imposible de pasar si el
verificador no funciona: sin evidencia legible los dos bundles dan `ERROR_INTERNO`, o sea el mismo
resultado, y el perfil **no llega a `VALIDADA`**.

> Eso hace que el fail-closed no sea una trampa que aparece con la entrega de un alumno, sino un
> error en segundos al publicar el perfil. Es el argumento que ya le vendimos al Grupo 5 como
> ventaja, y resulta que también es lo que sostiene D6. **Conviene que quede escrito que el smoke
> test no es un lujo operativo: es parte de la guarda.**

---

## 6. Qué reemplaza a `testsEnReporte`

Sacar el contador de la tapa deja un hueco real. Era defensa en profundidad barata, y algo tiene que
ocupar ese lugar sin volver a saber de formatos.

**Lo que se va del sobre:** `testsEnReporte`, `clasesTest` y `exitCodeJava` — los tres presuponen
que adentro corrió Java (P9).

**Lo que entra, y es agnóstico:**

| Campo | Qué es | Para qué sirve |
|---|---|---|
| `archivosEnBuzon` | cuántos archivos quedaron en `/work/reports` | Cruce de transporte: si el worker desempaqueta menos de los que la tapa contó, se perdió algo entre las dos capas |
| `bytesEnBuzon` | tamaño total | Distingue "dejó un archivo vacío" de "dejó un reporte". Un buzón de 0 bytes es tan poco reporte como un buzón vacío |
| `codigoRunSh` | el código con el que salió `run.sh`, crudo | Reemplaza a `exitCodeJava`. Es el insumo de la banda `40–59` del Grupo 5, y **sigue siendo diagnóstico, nunca veredicto** |

Los dos primeros son `ls | wc -l` y una suma de tamaños: no interpretan nada y no van a envejecer
con el formato. No sustituyen al conteo real de pruebas —nada lo hace desde adentro sin saber el
formato— pero cubren lo que sí se puede cubrir desde ahí, que es la integridad del transporte.

> **Y una guarda de tamaño que conviene agregar mientras se toca esto:** el buzón se empaqueta entero
> y viaja en base64 adentro del sobre. Hoy el único tope real es el truncado de las salidas. Un
> `run.sh` que deje 200 MB en `/work/reports` se los manda al worker por `stdout`. Es un tope nuevo,
> agnóstico, y va junto con `bytesEnBuzon` porque el número ya está calculado.

---

## 7. Correcciones derivadas

El cierre de D16 arrastró cuatro correcciones. El ejecutor y la capa 1 ya incorporan el refactor;
las que corresponden al worker siguen siendo requisitos de integración.

### 7.1 `04` §6 — la fila `SALIDA_ANTICIPADA` del mapeo

Hoy la tabla mapea `resultado: SALIDA_ANTICIPADA` del sobre → veredicto `SALIDA_ANTICIPADA`. Pero
ese `resultado` lo produce la guarda 3, que es la que se va. **La tapa deja de emitirlo.**

Pasa a derivarlo el worker: sobre `OK` + buzón con contenido + evidencia legible + **0 unidades
corridas** → `SALIDA_ANTICIPADA`, consume intento. La distinción contra `TIMEOUT_CPU` la sigue
haciendo la tapa antes, con la CPU medida (§2), así que la segunda trampa de `04` §6 no vuelve.

### 7.2 El criterio de aceptación estaba escrito en códigos de salida

La tabla dice que `hostil-exit0` tiene que dar *"salida anticipada (**exit 29**)"* hoy y
*"idéntico"* después. **Con esta decisión el exit code cambia**: la tapa ya no tiene motivo para
salir 29, sale 0 y manda el sobre con el buzón adentro.

> El criterio correcto es **"el mismo veredicto"**, no "el mismo código de salida". El código de
> salida del contenedor es diagnóstico —lo dice el propio entrypoint en el comentario de
> `codigo_de`— y atarle el criterio de aceptación a un refactor que justamente redistribuye quién
> clasifica qué es medir la cosa equivocada.

El criterio de aceptación del ejecutor quedó expresado en términos del resultado observable. El
worker deberá conservar el veredicto al integrar el nuevo sobre.

### 7.3 Las bandas de códigos de salida: dos que quedan libres

De nuestra banda `20–31` (D19), dos pierden sentido bajo el sándwich:

| Código | Estado | Qué le pasa |
|---|---|---|
| `20` `ERROR_COMPILACION` | **se va** | Compilar es capa 2. Es el `41` de la banda del Grupo 5 en el ejemplo de la respuesta §4.6 |
| `29` `SALIDA_ANTICIPADA` | **se va** | Es un hecho del reporte, y el reporte lo lee el worker |

No hay que reciclarlos: quedan reservados y sin uso, igual que el hueco `32–39`. Reusar un código
con otro significado es la clase de cosa que se paga leyendo logs viejos a las tres de la mañana.

### 7.4 `README` — D16 cerrada

El índice registra la decisión cerrada y P9 implementado.

---

## 8. Cómo se prueba — y el resultado

> **Corrido el 8‑sep‑2026 contra `sandbox-runner:1.0.0` y Docker 29.7.2. La premisa del §1 quedó
> confirmada.** Lo que sigue documenta el test, el ajuste que hubo que hacerle y los números.

### 8.1 El test tal como estaba escrito no se podía correr

La primera versión de esta sección decía: *"sacar la guarda 3 del entrypoint y verificar que el
veredicto sigue siendo `SALIDA_ANTICIPADA`"*. **Eso no se puede observar: el worker todavía no
existe como código.** El repositorio tiene la imagen del runner y el ejecutor Java 21 —que por R3.1
tiene prohibido decidir veredictos— y nada más. El worker, por ahora, es
documentación.

El test se reformuló para medir la misma propiedad sin la pieza que falta:

> **¿Le llega al worker, en el sobre y sin la guarda de la tapa, la evidencia suficiente para
> derivar `SALIDA_ANTICIPADA` por su cuenta?**

Si el XML con `tests="0"` viaja igual, la respuesta es sí y la premisa se sostiene. Si el reporte
llegara ausente o vacío, D16 volvería a ser una mudanza de guarda de seguridad.

El arnés era `run.sh`, el harness de una sola capa (eliminado del repo junto con `entrypoint.sh` y
`build.sh` al adoptar el modelo de dos capas; ver historial de git), que era justamente *"lo que
después va a hacer el worker, a mano"*. Se neutralizó **sólo la decisión** de la guarda 3 —el
`morir`— y se dejó el conteo en el sobre, para poder comparar el número.

> **Ojo con el nombre.** Ese `run.sh` era el arnés de prueba nuestro, y no tenía nada que ver con el
> `run.sh` del sándwich, que es el script de evaluación del Grupo 5. La colisión de nombres fue
> desafortunada; el harness de dos capas equivalente es `pruebas/probar-capa1.sh`.

### 8.2 Los números

| | `hostil-exit0` **hoy** | `hostil-exit0` **sin guarda 3** | `ok-suma` **sin guarda 3** |
|---|---|---|---|
| exit del contenedor | **29** | **0** | 0 |
| `resultado` del sobre | `SALIDA_ANTICIPADA` | **`OK`** | `OK` |
| `testsEnReporte` | 0 | 0 | 3 |
| ¿viaja el reporte? | sí | **sí** | sí |
| XML en el buzón | `TEST-junit-platform-suite.xml` | **idéntico** | 3 archivos |
| `tests=` en el XML | `0` | **`0`** | `3`, `0`, `0` |

**La columna del medio es el resultado.** Sin la guarda, la tapa informa `OK` y sale `0` para una
entrega que no corrió una sola prueba —que es exactamente el bypass de `System.exit(0)`— **pero el
XML con `tests="0"` viaja igual adentro del sobre.** El worker tiene todo lo que necesita.

Y la tercera columna es el control que le da sentido: con la misma imagen sin guarda, `ok-suma`
manda `tests="3"`. Las dos entregas llegan afuera con `resultado: OK` y exit `0`, **y lo único que
las distingue es el contenido del reporte.** Que es precisamente lo que dice la decisión: el
veredicto sale del reporte.

### 8.3 Un hallazgo del control, y es una trampa para quien implemente

`ok-suma` no deja un XML: deja **tres**, uno por engine de JUnit, y **dos de ellos tienen
`tests="0"`**.

> Un verificador que aplicara *"si algún archivo dice `tests="0"`, no hay evidencia"` **rompería el
> camino feliz**. La regla correcta es **sumar el atributo a través de todos los archivos del
> buzón**, que es lo que hace hoy el `awk` del entrypoint y lo que `04` §6 le atribuye al worker.

Con la suma, los dos casos quedan bien separados: `hostil-exit0` suma **0** y `ok-suma` suma **3**.
Va como requisito explícito del `VerificadorDeEvidencia` de `junit-xml`, porque es la clase de
detalle que no se descubre leyendo la spec y sí rompiendo el build.

### 8.4 Lo que falta probar

Los pasos 4 y 5 —perfil con `reportFormat` desconocido ⇒ `ERROR_INTERNO`, y ese perfil no llega a
`VALIDADA`— **no se pudieron correr y no es un olvido**: no existen todavía ni los perfiles ni el
worker. Quedan como criterio de aceptación de ese trabajo, y hay que escribirlos junto con él.

### 8.5 Reproducirlo

> **Nota de la reorganización del repo.** `run.sh`, `build.sh` y `entrypoint.sh` (el runner de una
> sola capa) se eliminaron del repo al adoptar el modelo de dos capas; el bloque de abajo es el
> registro histórico de cómo se reprodujo el experimento en su momento, no un comando ejecutable hoy.
> Ver historial de git para recuperar esos scripts si hiciera falta repetir el experimento.

```sh
cd sandbox/runner
./run.sh bundles/hostil-exit0                    # línea de base: exit 29, SALIDA_ANTICIPADA

git checkout -b experimento/d16                  # neutralizar el 'morir' de la guarda 3
IMAGEN=sandbox-runner:d16-exp ./build.sh
IMAGEN=sandbox-runner:d16-exp ./run.sh bundles/hostil-exit0   # exit 0, OK, y el XML igual viaja
IMAGEN=sandbox-runner:d16-exp ./run.sh bundles/ok-suma        # control

git checkout main && docker rmi sandbox-runner:d16-exp
```

El `reportesTarGzB64` del sobre se abre con `base64 -d | tar -xzf -`. Sin `jq` instalado, el propio
`run.sh` imprime el JSON crudo.

---

## 9. Qué queda abierto para integrar el worker

**D16 cerró con estos tres criterios.** El ejecutor y la capa 1 ya respetan el reparto; el worker
todavía debe implementar la verificación fina y el comportamiento *fail-closed*:

| # | Criterio cerrado | Riesgo que evita |
|---|---|---|
| 1 | **La verificación fina vive sólo en el worker** (Opción C), y la tapa se queda con las guardas agnósticas. **Verificado en §8:** sin la guarda de la tapa, la evidencia llega igual | Con A, cada formato nuevo pasa a requerir una imagen nueva. Con B, se pierde la propiedad entera |
| 2 | **Fail-closed:** formato desconocido o reporte ilegible ⇒ `ERROR_INTERNO`, nunca `EXITO`, y no consume intento | Es el único que **no** admite otra respuesta: la alternativa reintroduce el bug de `System.exit(0)` por omisión |
| 3 | **El criterio de aceptación de P9 se mide en veredictos, no en códigos de salida** (§7.2) | El refactor "falla" el test estando bien, y la reacción natural es dejar el conteo en la tapa "para que pase" |

**Lo que este documento no cierra:**

- **Qué formatos arrancamos.** Hoy alcanza `junit-xml`. Cuáles más y cuándo depende de **A2** y
  **A4**, que las contesta el Grupo 5.
- **El tope de tamaño del buzón** (§6). Es un número que hay que elegir y medir, y va en `08` §4 como
  constante, no acá.
- **D7 sigue abierta** y no la toca esta decisión: `VEREDICTO_NO_CONFIABLE` sale de la guarda 4, que
  se queda en la tapa. Si consume vida o no se sigue definiendo con T10.

---

## Documentos relacionados

- [`04-ms-sandbox-worker.md`](./04-ms-sandbox-worker.md) §6 — el mapeo de veredictos y la guarda que ya existe.
- [`08-spec-ejecutor.md`](./08-spec-ejecutor.md) §5 — el sobre, que cambia de campos.
- [`otros/Respuesta_G8_a_Propuesta_V4.md`](../../otros/Respuesta_G8_a_Propuesta_V4.md) §4.6 y §4.7 — `reportFormat` y el smoke test, tal como se los planteamos al Grupo 5.
- `entrypoint.sh` (de una sola capa, eliminado del repo; ver historial de git) líneas 419‑456 — las cinco guardas del §2.
- [`README.md`](./README.md) — estado de decisiones.
