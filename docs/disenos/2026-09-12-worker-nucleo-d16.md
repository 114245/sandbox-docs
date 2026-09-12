# Diseño — el núcleo del worker que valida D16

> **Tema 06 — Sandbox / Runtime.** Grupo 8, 11 integrantes.
> UTN FRC · Programación 4 + Metodología de Sistemas 2 · TPI 2026.
>
> **Qué es este documento.** El diseño de la primera rodaja de implementación del worker: la parte
> que cierra **D16** —*dónde se verifica que hubo ejecución de verdad*, la decisión que está escrita
> en [`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md)— y que
> hoy **no existe en código**.
>
> No es arquitectura normativa. Es un diseño de implementación, con fecha, que se archiva cuando la
> implementación termina y su contenido se absorbe en los documentos que corresponda.
>
> **Por qué esta rodaja y no el worker entero.** Después del refactor del entrypoint (**P9** — el
> pendiente *"refactor del entrypoint al modelo de dos capas"*, ya cerrado), la guarda contra
> `System.exit(0)` quedó sostenida por **una sola cosa**: el verificador del worker. Y esa cosa
> todavía no está escrita. Mientras tanto, la propiedad no la sostiene nadie.
>
> Escrito el **12 de septiembre de 2026**.

---

## Índice

1. [Qué entra y qué no](#1-qué-entra-y-qué-no)
2. [El módulo](#2-el-módulo)
3. [El núcleo](#3-el-núcleo)
4. [El cliente del ejecutor](#4-el-cliente-del-ejecutor)
5. [El mapeo de veredictos](#5-el-mapeo-de-veredictos)
6. [Fail-closed](#6-fail-closed)
7. [Pruebas](#7-pruebas)
8. [Decisiones de este diseño](#8-decisiones-de-este-diseño)
9. [Lo que este diseño no cierra](#9-lo-que-este-diseño-no-cierra)

---

## 1. Qué entra y qué no

### Entra

| Pieza | Por qué |
|---|---|
| El **cliente del ejecutor** por socket Unix | Sin él no hay sobre real que verificar, y el sobre inventado no prueba nada |
| El **`VerificadorDeEvidencia`** con su implementación `junit-xml` | Es literalmente lo que D16 dejó pendiente |
| El **mapeo de veredictos** en dos pasos | Es donde vive el orden de guardas que no se negocia |
| El comportamiento **fail-closed** | Es el punto que convierte a D16 de trámite en decisión |

### No entra

RabbitMQ, Postgres, el outbox, el relay, el watchdog, el janitor y la API. Todo eso está especificado
en [`04-ms-sandbox-worker.md`](../arquitectura/04-ms-sandbox-worker.md) —*el worker: cola, DLQ,
concurrencia, outbox*— y en [`07-arquitectura-api.md`](../arquitectura/07-arquitectura-api.md) —*la
API y el outbox*—, y es la rodaja siguiente.

**Consecuencia deliberada:** al terminar esta rodaja el worker **todavía no consume de una cola**. Lo
que se obtiene es el núcleo que decide veredictos, ejercitable a mano contra el ejecutor real y
contra los nueve bundles de referencia. Eso alcanza para cerrar D16 y no alcanza para desplegar
nada, que es exactamente lo que se buscaba.

### Una decisión que esta rodaja no necesita tomar

**Spring Boot o JDK puro.** Los documentos describen el worker con idiomas de Spring por todos lados
—`@RabbitListener`, `@Transactional`, `@Scheduled`, Spring Data JPA— pero nunca lo deciden. Sin cola
ni base no hay adaptadores que Spring resuelva, así que la decisión **se difiere a la rodaja de cola
y persistencia**, que es donde realmente se paga. Este módulo es JDK puro.

---

## 2. El módulo

`ms-sandbox/worker/`, hermano de `ms-sandbox/ejecutor/`.

| | |
|---|---|
| Build | Maven, artefacto `sandbox:worker:1.0.0` |
| Java | 21 (`maven.compiler.release`) |
| Paquete | `sandbox.worker`, plano, sin sub-paquetes |
| Idioma | Español en clases, métodos, variables y comentarios |
| Dependencias | `jackson-databind` 2.17.2, `commons-compress` 1.21 |
| Pruebas | JUnit 5, `*Test` unitarios y `*IT` de integración |

Las convenciones son las del ejecutor, verificadas contra `ms-sandbox/ejecutor/pom.xml` y su árbol de
fuentes: paquete plano, nombres en español, comentarios que citan el requisito que implementan
(`// R5.13: unix:// o npipe:// unicamente`), tests con nombres largos anclados al criterio de
aceptación, y **dobles escritos a mano en vez de una librería de mocking** —el ejecutor tiene
`DaemonDePrueba` y `ClienteHttpDePrueba`, y no tiene Mockito.

**`commons-compress` cambia de alcance.** En el ejecutor es dependencia de test; acá pasa a `main`,
porque desempaquetar el buzón de reportes es trabajo de producción del worker.

**No entra `docker-java`.** El worker no habla Docker: esa es la frontera que fijó **D1** —*el
sidecar es un ejecutor, no un proxy del socket*—. Mantenerla afuera también evita heredar la deuda de
**R11.4**, el requisito de tamaño del fat jar que quedó derogado cuando `docker-java` llevó el
artefacto de 2 MB a 21 MB.

---

## 3. El núcleo

La separación que ordena todo el módulo: **el núcleo no hace I/O**. Recibe records y devuelve
records. Se prueba en milisegundos, sin Docker, sin sockets y sin archivos.

### Tipos

| Clase | Qué es |
|---|---|
| `Sobre` | Los 13 campos de la respuesta del ejecutor — [`08-spec-ejecutor.md`](../arquitectura/08-spec-ejecutor.md) §3.1, *"La respuesta"* |
| `SobreCapa1` | El sobre interno que viaja dentro del campo `reporte`, `schema: sandbox.capa1/v2` |
| `Buzon` | Los reportes ya desempaquetados: nombre de archivo → bytes |
| `Evidencia` | `{ corridas: int, fallidas: int, legible: boolean }` — [`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md) §4, *"La decisión"* |
| `Veredicto` | Los estados terminales — [`03-ms-sandbox-ejecucion.md`](../arquitectura/03-ms-sandbox-ejecucion.md) §5.1, *"Los estados terminales"* |
| `Fallo` | Lo que devuelve el juez: `{ veredicto: Veredicto, consumeIntento: boolean }` |

`Sobre` se queda con los 13 campos tal como los fija la spec del ejecutor, en ese orden:
`ejecucionId`, `resultado`, `exitCode`, `oomKilled`, `duracionMs`, `stdout`, `stderr`, `reporte`,
`reporteAusente`, `salidaTruncada`, `perfilId`, `perfilVersion`, `perfilHash`.

`SobreCapa1` es lo que viaja **adentro** del campo `reporte`, que el ejecutor trata como opaco por
**R3.5** —*el ejecutor no debe interpretar el contenido de `reporte`*—. La spec no lo define; la
fuente real es `ms-sandbox/imagenes/capa1/capa1.sh`, función `emitir()`:

| Campo | Qué es |
|---|---|
| `schema` | `"sandbox.capa1/v2"` |
| `resultado` | El enum de la capa 1, abajo |
| `detalle` | Texto de diagnóstico de la capa 1 |
| `exitEval` | **El código de salida de la capa 2.** La banda 40-59 es de G5 |
| `procesosSobrevivientes` | Cuántos procesos quedaron vivos tras la capa 2 |
| `faseDeclarada` / `detalleDeclarado` | Lo que la capa 2 dejó en `$SANDBOX_STATUS`. **No confiable** |
| `recursos` | `{ msEval, cpuEvalMs }` |
| `reportesTarGzB64` | **El buzón entero**, empaquetado sin mirarlo |
| `stdoutB64` / `stderrB64` | La salida de la fase de evaluación |
| `truncado` | Si la capa 1 recortó alguna salida |

### La interfaz de D16

Tal cual la fija [`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md)
§4, *"La decisión"*:

```java
interface VerificadorDeEvidencia {
    boolean soporta(String formato);
    Evidencia verificar(Buzon buzon);
}
```

| Clase | Qué es |
|---|---|
| `VerificadorJunitXml` | La única implementación por ahora |
| `Verificadores` | El registro. `para(formato)` devuelve `Optional<VerificadorDeEvidencia>` |

**`VerificadorJunitXml` suma a través de todos los XML del buzón.** No es un detalle de
implementación: el bundle de referencia `ok-suma` —el camino feliz de `ms-sandbox/pruebas/bundles/`—
deja **tres** XML y **dos tienen `tests="0"`**. Un verificador que mirara archivo por archivo y
rechazara al primero con cero pruebas rompería el camino feliz. Está medido en
[`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md) §8.3.

### El juez

```java
Fallo Juez.juzgar(Sobre sobre, Optional<Evidencia> evidencia)
```

Función pura. No sabe de sockets, ni de tar, ni de formatos de reporte. Es la parte que D16 define y
la que interesa poder testear sin levantar nada.

---

## 4. El cliente del ejecutor

### El contrato

Una sola operación, `POST /ejecutar`, sobre HTTP/1.1 por socket Unix en
`/run/ejecutor/ejecutor.sock`. Lo fija [`08-spec-ejecutor.md`](../arquitectura/08-spec-ejecutor.md)
§2 y §3.1 —*el transporte y la operación*—, con TCP explícitamente prohibido por **D4**, la decisión
que restringe el transporte a `unix://` y `npipe://` porque el cierre abortivo del canal adjunto
puede truncar `stdin`.

```
POST /ejecutar HTTP/1.1
Content-Type: application/octet-stream
X-Ejecucion-Id: <uuid v4>
X-Perfil: <perfilId>@<version>
Content-Length: <n>

<bytes del tar sin comprimir>
```

### Clases

| Clase | Qué es |
|---|---|
| `ClienteEjecutor` | Abre el socket, escribe el pedido, lee la respuesta, devuelve `Sobre` |
| `Http` | Las primitivas de lectura de HTTP/1.1, **copiadas** del ejecutor |
| `Empaquetador` | Arma el tar desde un directorio |

### Por qué copiar `Http`

`ms-sandbox/ejecutor/src/main/java/sandbox/ejecutor/Http.java` ya tiene escrita **la mitad cliente**:
`leerCabeza`, `codigo("HTTP/1.1 200 OK")`, `leerCuerpo` con `Content-Length`, `chunked` y hasta EOF,
y `leerExacto`. Su propio comentario lo dice: *"lo mínimo de HTTP/1.1 que hace falta para hablar con
el daemon y para atender al worker"*.

Son ~90 líneas que el worker necesita idénticas. Las tres salidas posibles:

| Opción | Por qué no / por qué sí |
|---|---|
| Depender del jar del ejecutor | **No.** Le arrastra `docker-java` entero al worker, 21 MB de deuda ya anotada en **R11.4** |
| Extraer un módulo `comun/` | **No.** Un tercer módulo Maven, su pom y su ciclo de build por 90 líneas no se paga |
| **Copiar** | **Sí.** Honesto para dos módulos, sin acoplamiento, con un comentario que apunta al original |

Si aparece un tercer consumidor, la copia se convierte en el argumento para extraer el módulo. Con
dos, no.

### El timeout, que es la única parte con sustancia

El contrato pide que el cliente espere **estrictamente más** que el ejecutor: 75 s contra los 60 s de
`TIMEOUT_EJECUCION_MS`. La escalera completa está en
[`04-ms-sandbox-worker.md`](../arquitectura/04-ms-sandbox-worker.md) §12, *"la frontera con el
ejecutor"*: 60 s el ejecutor < 75 s el cliente < `consumer_timeout` del broker.

**El problema:** un `SocketChannel` en modo bloqueante no tiene `SO_TIMEOUT`, y
`Channels.newInputStream()` se cuelga indefinidamente. No hay una perilla que girar.

**La solución:** la lectura corre en un hilo virtual de Java 21 y se espera con
`future.get(75, SECONDS)`. Si vence, se cierra el canal desde el hilo que espera; el lector despierta
con `AsynchronousCloseException` y se traduce a `ERROR_INTERNO`. Evita escribir un `Selector` a mano
y deja la desigualdad estricta expresada en una constante, no en un comentario.

### Qué devuelve el cliente según la respuesta

`ClienteEjecutor` no dicta veredictos: devuelve un `Sobre` o levanta una excepción. La tabla de
[`04-ms-sandbox-worker.md`](../arquitectura/04-ms-sandbox-worker.md) §12, *"los cuatro finales
posibles"*, ampliada con los códigos de error de
[`08-spec-ejecutor.md`](../arquitectura/08-spec-ejecutor.md) §3.3, *"los códigos de estado"*:

| Respuesta | Qué devuelve el cliente | Veredicto |
|---|---|---|
| `200` | El `Sobre` parseado | Lo decide el juez, §5 |
| `503` + `Retry-After` | `EjecutorSaturado` (lleva el `Retry-After`) | **Ninguno.** No se llama al juez |
| `502` | `ErrorDeEjecutor` | `ERROR_INTERNO`, no consume intento |
| `422` | `ErrorDeEjecutor` — el perfil no está en el catálogo | `ERROR_INTERNO`, no consume intento |
| Socket caído o timeout del cliente | `ErrorDeEjecutor` | `ERROR_INTERNO`, no consume intento |
| `400`, `411`, `413` | `ErrorDeProgramacion` | **Ninguno.** Es un bug nuestro, no una entrega |

**`EjecutorSaturado` y `ErrorDeProgramacion` no producen veredicto y no llegan al juez.** El primero
se reintenta cuando exista la cola; el segundo va a la DLQ. Las dos cosas son de la rodaja siguiente:
acá se propagan y se registran.

---

## 5. El mapeo de veredictos

Dos pasos, como [`04-ms-sandbox-worker.md`](../arquitectura/04-ms-sandbox-worker.md) §6, *"interpretar
la respuesta"*.

### Paso 1 — el nivel del ejecutor

`RECHAZADA` no aparece en esta tabla: el ejecutor lo devuelve como `503` y el cliente corta antes,
sin llamar al juez. Ver §4.

| Sobre | Veredicto | ¿Consume intento? |
|---|---|---|
| `resultado: ERROR_DAEMON` | `ERROR_INTERNO` | No |
| `resultado: TIMEOUT` | `ERROR_INTERNO` | No |
| `resultado: COMPLETADA` + `oomKilled: true` | `LIMITE_MEMORIA` | Sí |
| `resultado: COMPLETADA` + `reporteAusente: true` | `ERROR_INTERNO` | No |
| `resultado: COMPLETADA` + reporte presente | → Paso 2 | |

**El `TIMEOUT` del ejecutor no es el `TIMEOUT` del alumno.** El ejecutor tiene un reloj de respaldo de
60 s que sólo se dispara si fallaron los relojes de adentro del contenedor. Que se dispare es un
problema nuestro, no una entrega lenta: por eso `ERROR_INTERNO` y no `TIMEOUT`.

### Paso 2 — el `resultado` de la capa 1

> ⚠ **Esta tabla corrige a [`04-ms-sandbox-worker.md`](../arquitectura/04-ms-sandbox-worker.md) §6.**
> Ese documento lista valores (`TIMEOUT_CPU`, `ERROR_COMPILACION`, `SUITE_INVALIDA`,
> `LIMITE_MEMORIA`, `SALIDA_ANTICIPADA`) que **la capa 1 ya no emite**: son de antes del V4, cuando
> el entrypoint sabía Java. El enum real de `capa1.sh` tiene **ocho** valores y las distinciones
> finas se mudaron a `exitEval`. Hay que corregir `04` §6.

| `resultado` de la capa 1 | Veredicto | ¿Consume intento? |
|---|---|---|
| `OK` + **sin evidencia** (`Optional` vacío o `legible: false`) | `ERROR_INTERNO` | No |
| `OK` + `corridas == 0` | `SALIDA_ANTICIPADA` | Sí |
| `OK` + `corridas > 0`, sin fallidas | `EXITO` | No |
| `OK` + fallidas > 0 | `TESTS_FALLIDOS` | Sí |
| `DETENIDO_POR_EVALUACION` | Según `exitEval`, tabla de abajo | Según `exitEval` |
| `TIMEOUT_PARED` | `TIMEOUT` | Sí |
| `VEREDICTO_NO_CONFIABLE` | `VEREDICTO_NO_CONFIABLE` | **Abierto** — ver §9 |
| `SIN_REPORTE` | `ERROR_INTERNO` | No |
| `MUERTO_POR_SENAL` | `LIMITE_MEMORIA` si `oomKilled`, si no `ERROR_INTERNO` | Según cuál |
| `BUNDLE_INVALIDO` | `ERROR_INTERNO` | No |
| `EVALUACION_ANOMALA` | `ERROR_INTERNO` | No |

### Paso 3 — la banda 40-59, cuando la capa 2 frenó a propósito

Cuando `resultado` es `DETENIDO_POR_EVALUACION`, la capa 1 **propaga el código de la capa 2 tal
cual** —su comentario lo dice: *"el código de G5 se propaga TAL CUAL: es su tabla, no la
traducimos"*—. La tabla es la que implementa `ms-sandbox/perfiles/java21-junit.sh`:

| `exitEval` | Qué pasó | Veredicto | ¿Consume intento? |
|---|---|---|---|
| `40` | No compila la solución del alumno | `ERROR_COMPILACION` | Sí |
| `41` | No compila la suite de pruebas | `SUITE_INVALIDA` | **No** — la suite es de la cátedra |
| `42` | `javac` superó el reloj de plataforma | `ERROR_INTERNO` | **No** — el presupuesto es nuestro |
| `43` | La JVM se quedó sin memoria | `LIMITE_MEMORIA` | Sí |
| `44` | Se agotó el reloj de CPU del alumno | `TIMEOUT` | Sí |
| `45` | Backstop de pared de la suite | `TIMEOUT` | Sí |
| `46` | La JVM terminó sin escribir reporte | `ERROR_INTERNO` | No |
| `47` | Hay reporte pero con `tests = 0` | `SALIDA_ANTICIPADA` | Sí |
| `48`–`59` | Sin asignar | `ERROR_INTERNO` | No |

> **`SALIDA_ANTICIPADA` se puede derivar por dos caminos, y es a propósito.** La capa 2 la declara
> con `exitEval: 47`, y el worker la deriva igual de `corridas == 0`. **No son redundantes: el
> segundo es el que vale.** El primero es la capa 2 vigilándose a sí misma, que es exactamente lo
> que [`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md) §3
> descarta al rechazar la Opción B. Si `exitEval: 47` dijera una cosa y el conteo de los XML otra,
> **manda el conteo de los XML**.

> **Lo mismo con `nota.json`.** El guion de la capa 2 deja en el buzón un `nota.json` con un campo
> `tests` ya calculado. **Ese campo no se lee nunca.** Es exactamente el *"contador precalculado"*
> que la regla madre prohíbe usar. El verificador tiene que ignorar todo lo que no sea `*.xml`.

> **El orden de guardas cae solo, y conviene saber por qué.** Los timeouts no llegan como `OK`:
> llegan como `TIMEOUT_PARED` o como `exitEval` 44/45, así que se resuelven antes de que la fila de
> `corridas == 0` se pueda siquiera evaluar. La estructura del `switch` **es** la guarda de orden.
> El test existe igual, porque el día que alguien aplane el mapeo a una cadena de `if` sobre
> `corridas`, la propiedad se pierde en silencio.

### La regla madre

> **No hay `EXITO` sin `corridas > 0` leído del XML de verdad**, nunca de un contador precalculado
> que venga en el reporte. El contador es defensa en profundidad; la fuente es el XML.

Es **D6** —*el veredicto sale del reporte, nunca del código de salida*— reformulada por D16 sin
nombrar a JUnit: *no hay `EXITO` sin evidencia legible de que corrió al menos una unidad de
evaluación*.

### El orden que no se negocia

**Los timeouts se evalúan antes que `corridas == 0`.** Una corrida que agotó la CPU y alcanzó a
dejar un reporte de cero pruebas es `TIMEOUT`, no `SALIDA_ANTICIPADA`.

El orden viene de la tabla de las cinco guardas del entrypoint en
[`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md) §2, *"qué hace
hoy cada capa, exacto"*, y su nota al pie: *"el orden importa, y hoy está bien"*. Al mover la guarda
al worker, el orden se muda con ella.

**Va como test explícito y con nombre**, porque es el tipo de invariante que un refactor rompe sin
que nadie lo note y que ningún test de camino feliz vuelve a tocar.

---

## 6. Fail-closed

La regla, de [`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md)
§5, *"el caso crítico"*:

> **Un reporte que no se puede verificar no es un reporte aprobado.** Si no hay verificador para el
> formato declarado, o el verificador no puede leer lo que llegó, el veredicto **no puede ser
> `EXITO`**. Es `ERROR_INTERNO` —el problema es nuestro, no del alumno— y **no consume intento**.

### Cómo lo sostiene el tipo, no la disciplina

El riesgo que describe ese documento es que el camino natural del código sea el peor: el verificador
no encuentra nada, devuelve cero problemas, el sobre dice `OK`, el buzón no está vacío, y una entrega
con `System.exit(0)` vuelve a aprobar. Se perdería **por omisión, no por decisión**.

Dos elecciones de diseño lo previenen en el tipo:

1. **`Verificadores.para(formato)` devuelve `Optional`.** Un formato sin verificador no devuelve un
   verificador que no encuentra nada: no devuelve verificador. Quien llama tiene que decidir
   explícitamente qué hacer con el `Optional` vacío, y la única respuesta compilable útil es
   `ERROR_INTERNO`.
2. **`Juez.juzgar` recibe `Optional<Evidencia>`.** No existe una `Evidencia` con `corridas = 0` que
   signifique *"no encontré nada y por eso está todo bien"*. La ausencia de evidencia es un tipo
   distinto de la evidencia de que no corrió nada.

`legible: false` cubre el tercer caso: hubo verificador, había archivos, y no se pudieron leer.
También termina en `ERROR_INTERNO`.

---

## 7. Pruebas

| Prueba | Qué fija |
|---|---|
| `VerificadorJunitXmlTest` | La **suma a través de todos los XML**; ignorar `nota.json`; salteados no cuentan; XML roto o sin XML → `legible: false`; sin entidades externas |
| `VerificadoresTest` | Formato desconocido → `Optional` vacío. Es el fail-closed en el registro |
| `DesempaquetadorTest` | `reportesTarGzB64` → `Buzon`; base64 roto y buzón gigante → `ErrorDeSobre` |
| `BandaDeEvaluacionTest` | La tabla 40-59 entera, y que un código sin asignar sea `ERROR_INTERNO` |
| `JuezTest` | La tabla entera de §5, el fail-closed, el orden (timeout con cero pruebas es `TIMEOUT`) y que el conteo de los XML le gane a `exitEval: 47` |
| `ClienteEjecutorTest` | Contra un `EjecutorDePrueba` sobre socket Unix: headers, códigos de error y el tope de lectura |
| `EmpaquetadorTest` | El tar que se arma es el que el ejecutor acepta |
| `D16IT` | **La prueba que cierra D16**, contra el ejecutor real |

### `D16IT`, que es el punto de todo esto

Dos bundles de `ms-sandbox/pruebas/bundles/`, contra el ejecutor y la imagen reales:

- **`hostil-exit0`** —llama a `System.exit(0)` sin correr ningún test— **no puede dar `EXITO`**.
- **`ok-suma`** —el camino feliz, tres XML, dos con `tests="0"`— **tiene que dar `EXITO`**.

Son los pasos 4 y 5 de la validación de
[`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md) §8.4, que
quedaron sin correr con una razón explícita: *"no existen todavía ni los perfiles ni el worker"*. Los
perfiles ya existen, en `ms-sandbox/perfiles/`. Con esta rodaja existe el worker.

**Criterio de aceptación de la rodaja:** `D16IT` en verde. Con eso D16 pasa de decisión cerrada a
propiedad sostenida por código.

### Convención

Los `*IT` se auto-saltean sin Docker, como los del ejecutor. La suite tiene que seguir corriendo
entera en una máquina sin daemon.

---

## 8. Decisiones de este diseño

| # | Decisión | Por qué |
|---|---|---|
| 1 | El cliente del ejecutor entra en esta rodaja | Da el sobre real. La mitad del cliente ya está escrita en `Http.java` del ejecutor, así que cuesta poco |
| 2 | `Http` se copia, no se comparte ni se extrae | Depender del ejecutor arrastra `docker-java`; un módulo `comun/` por 90 líneas no se paga |
| 3 | El timeout se resuelve con hilo virtual + `future.get` | `SocketChannel` bloqueante no tiene `SO_TIMEOUT`; la alternativa es un `Selector` a mano |
| 4 | `Optional` en el registro y en el juez | Hace que el fail-closed lo sostenga el compilador y no la disciplina de quien escribe |
| 5 | JDK puro, sin Spring | Sin cola ni base no hay nada que Spring resuelva. La decisión se difiere a la rodaja siguiente |
| 6 | El núcleo no hace I/O | Permite probar la tabla de veredictos completa en milisegundos, sin Docker |

---

## 9. Lo que este diseño no cierra

### El sobre interno no está especificado en ningún lado

[`08-spec-ejecutor.md`](../arquitectura/08-spec-ejecutor.md) **R3.5** —el requisito que define el
campo `reporte`— **descarta explícitamente el ejemplo que muestra** y aclara que la forma real del
sobre interno (`schema`, `fase`, `resultado`, `recursos`, y los XML como tar.gz en base64) la define
la imagen del runner, no la spec.

O sea que el parseo se va a escribir **contra lo que devuelva el ejecutor real**, no contra un
contrato escrito. No bloquea, pero tiene una consecuencia que conviene aprovechar:

> **Esta implementación produce el esquema que a la spec del ejecutor le falta.** Cuando `D16IT` esté
> en verde, el esquema del sobre interno debería escribirse en `08` §5 como contrato formal. Es
> documentación que sale gratis del trabajo.

### El tope de tamaño del buzón

No existe la constante. [`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md)
§6 lo plantea —*"un `run.sh` que deje 200 MB en `/work/reports` se los manda al worker por
`stdout`"*— y §9 lo deja explícitamente afuera: *"es un número que hay que elegir y medir, y va en
`08` §4 como constante, no acá"*. El único tope real hoy es el truncado de las salidas.

Esta rodaja lo deja como está y lo anota. Elegir el número pide medir.

### D7 — `VEREDICTO_NO_CONFIABLE`, ¿consume vida?

Sigue abierta y se define con T10. El `Juez` lo va a mapear a `VEREDICTO_NO_CONFIABLE` con
`consumeIntento` en un valor **marcado como provisional en el código**, para que el día que D7 cierre
se sepa dónde tocar.

### El `reportFormat` todavía no se lee del perfil

La decisión D16 —[`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md)
§3, *"las tres opciones"*— es explícita: *"el `reportFormat` no viene del request: viene de una
versión de perfil inmutable"*. **Esta rodaja no lo hace.** El worker no tiene catálogo de perfiles,
y la respuesta del ejecutor —`08-spec-ejecutor.md` §3.1— trae `perfilId`, `perfilVersion` y
`perfilHash`, pero **no** `reportFormat`.

`Nucleo` usa `junit-xml` como constante nombrada. No debilita nada —es *más* restrictivo que
resolverlo por perfil— pero significa que **el camino de producción del fail-closed por formato
desconocido sólo se ejercita desde tests**. Se cierra en la rodaja del catálogo de perfiles, junto
con la cola y la base.

### Qué formatos además de `junit-xml`

Depende de **A2** —*cuánta CPU y memoria consumen realmente PMD y ArchUnit*— y **A4** —*cuántos
perfiles arrancamos y con qué contenido*—, dos preguntas abiertas que contesta el Grupo 5. Hoy alcanza
`junit-xml`, y toda la gracia del registro por formato es que agregar el siguiente no toque ni el
juez ni la imagen.

---

## Documentos relacionados

- [`12-d16-evidencia-de-ejecucion.md`](../arquitectura/12-d16-evidencia-de-ejecucion.md) — la decisión D16 entera: la interfaz, el fail-closed y qué quedó pendiente para el worker.
- [`04-ms-sandbox-worker.md`](../arquitectura/04-ms-sandbox-worker.md) — el worker completo: §6 el mapeo de veredictos, §12 la frontera con el ejecutor y la escalera de timeouts.
- [`08-spec-ejecutor.md`](../arquitectura/08-spec-ejecutor.md) — el contrato del ejecutor: §2 el transporte, §3 la operación y los códigos de estado, §5 los 13 campos del sobre.
- [`03-ms-sandbox-ejecucion.md`](../arquitectura/03-ms-sandbox-ejecucion.md) §5.1 — los estados terminales y cuáles consumen intento.
- [`README.md`](../arquitectura/README.md) — el estado de todas las decisiones y pendientes.
