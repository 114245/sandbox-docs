# Ejecutor de `ms-sandbox` — implementación Java 21

Implementación del contrato de [`08-spec-ejecutor.md`](08-spec-ejecutor.md). El documento es la
fuente de verdad; este README solo dice dónde vive cada requerimiento y qué quedó abierto.

Alineado con la versión de la spec que incorpora §0 (cambios C1 a C6). **La spec todavía no
incorpora el modelo de dos capas de la Opción 1 del V4, el catálogo de perfiles, ni la derogación
de R11.4**: se actualiza entera cuando cierre la validación. Mientras tanto, este README y
[`../../HANDOFF-opcion1.md`](../../HANDOFF-opcion1.md) son lo que describe el estado real.

## Correr

```bash
mvn test                    # suite completa; los *IT se saltean si no hay daemon escuchando
mvn package                 # target/ejecutor.jar (fat jar)
powershell scripts/verificar-a6.ps1   # A6 contra un daemon de Docker real
```

**Corre nativo en Windows.** Antes había que meter la suite adentro de un contenedor con
`/var/run/docker.sock` montado, porque Java no podía hablarle al named pipe de Docker Desktop. El
transporte `httpclient5` de `docker-java` sí lo habla: `Docker.conectar` elige `npipe` o `unix`
según la plataforma.

La suite tarda unos 3 minutos: tres tests consumen el reloj de ejecución completo de 60 s
(`r82` de timeout, `r510` de inspect en timeout, y `a33` de escritura trabada). Son controles de
tiempo real, así que no se pueden acelerar sin falsearlos.

Variables de entorno de despliegue (no tocan ningún campo de la spec del contenedor, así que no
violan P1):

| Variable | Por defecto | Qué es |
|---|---|---|
| `EJECUTOR_SOCKET` | `/run/ejecutor/ejecutor.sock` | el socket propio, el que escucha el worker |
| `DOCKER_HOST` | `npipe:////./pipe/docker_engine` en Windows, `unix:///var/run/docker.sock` en el resto | el daemon |
| `EJECUTOR_PERFILES` | `/opt/ejecutor/perfiles` | el directorio read-only del catálogo de perfiles (Paso 1 de la Opción 1), un archivo `<perfilId>@<version>.json` por versión |

## El modelo de dos capas

El contenedor recibe **tres documentos pegados** por stdin, sin separadores:

```
<nonce>\n              32 hex
<n>\n                  cuántos BYTES mide el guion de la capa 2
<guion de n bytes>     el run.sh del perfil, opaco
<tar>                  el bundle: "lo que quede del stream"
```

El largo va adelante y no hay marca de fin porque el guion lo escribe G5: es texto arbitrario y
puede contener cualquier línea, incluida la que eligiéramos como separador. Usar el nonce como
delimitador está prohibido —se lo mostraríamos a la capa 2, que es lo que el nonce existe para
evitar—. El largo no tiene alfabeto: son *n* bytes opacos. Es `Content-Length`.

Se cuenta en **bytes**, no en caracteres: un acento en un comentario del guion mueve el número.
`ProtocoloIT` mete un acento a propósito para que ese bug no pueda pasar desapercibido.

## El catálogo de perfiles (Paso 1 y 2 de la Opción 1)

`Catalogo` carga entero, al arrancar, el directorio de `EJECUTOR_PERFILES`: un archivo
`<perfilId>@<version>.json` por versión, con `imagen`, `script`, `reportFormat` y `limites`
(`memoriaMb`, `cpuS`). La clave de búsqueda es `<perfilId>@<version>`, la misma cadena que manda
el request en el header `X-Perfil`.

El **hash del guion se calcula al cargar**, nunca se declara en el JSON: un campo declarado podría
mentir. Se emite en la respuesta como `perfilHash`.

Cinco cosas hacen fallar el **arranque** del proceso (nunca una ejecución individual, el mismo
criterio que ya tenía `Main` con el guion único de antes):

- JSON inválido.
- `limites.memoriaMb` mayor que `Constantes.MEMORIA_MAX_MB`.
- `limites.cpuS` mayor que `Constantes.CPU_MAX_S`.
- Violación de R4.1: `cpuS` demasiado cerca de los 60 s de reloj de pared (`cpuS >= reloj` o
  `cpuS*2 > reloj`), la misma relación que antes afirmaba `SpecTest#a32` sobre una constante fija,
  ahora aplicada a cada perfil.
- Guion mayor a `Constantes.MAX_SCRIPT_BYTES`.

`Spec.configurar` toma el perfil elegido y varía exactamente **cuatro** campos del `create` —
`Image`, `Memory`, `MemorySwap` y `Ulimits[cpu]`—; todo lo demás sigue constante entre ejecuciones,
sea cual sea el perfil. `SpecTest#p1_dosPerfilesDifierenSoloEnLosCuatroCamposVariables` es el test
que lo prueba, y reemplaza al golden fijo único: ahora el golden (`spec-create-referencia.json`) es
del perfil de referencia `java21-junit@4` puntualmente.

`Servidor` valida el header `X-Perfil` (`^[a-z0-9-]+@[0-9]+$`) después de `X-Ejecucion-Id` y antes
de `Content-Length`: **400** si falta o el formato es inválido, **422** si el formato es válido pero
la clave no está en el catálogo. La respuesta de §3.1 se extendió con `perfilId`, `perfilVersion` y
`perfilHash` al final, para que el test que fija el orden de los campos se extienda en vez de
reescribirse.

## Dónde vive cada cosa

| Módulo | Líneas | Spec |
|---|---|---|
| `Spec` | 74 | §4.1, §4.2 — el `create`; constante salvo label, nombre y los cuatro campos del perfil |
| `Constantes` | 21 | §4.3 |
| `Http` | 108 | HTTP/1.1 a mano, **solo para el servidor propio**: cabeceras, `Content-Length`, chunked |
| `Docker` | 187 | §5 — las ocho llamadas sobre `docker-java` |
| `Entrada` | 62 | el framing de tres documentos, R7.1–R7.3, R8.5 |
| `Catalogo` | 108 | Paso 1 de la Opción 1 — carga y valida el catálogo de perfiles al arrancar |
| `Acumulador` | 47 | §6 — lo que quedó de nuestro lado del demultiplexado |
| `Reporte` | 27 | §7.5–§7.8 |
| `Ejecucion` | 115 | §5, §7.1–§7.2, §8 — nonce, timeouts, limpieza garantizada, perfil por ejecución |
| `Servidor` | 230 | §3, §9 — `/ejecutar`, `/salud`, semáforo, cola, validación de `X-Perfil` |
| `Barrido` | 26 | §10 |
| `Motor`, `Main`, `Log`, `ErrorDaemon` | 61 | frontera, cableado, R11.6, R11.7 |
| **Total** | **~1066** | código efectivo, sin comentarios ni blancos |

**El código no se achicó, y ahora crece por el catálogo, no por el port.** El handoff estimaba
pasar de 869 a ~530 líneas con `docker-java`; el número real venía en 938 desde el port y ahora
suma `Catalogo` entero (108 líneas nuevas) más la plomería del perfil por ejecución en `Ejecucion`
y `Servidor`. Dos motivos para el salto original, y conviene seguir diciendo los dos:

- `Entrada` (62 líneas) es **funcionalidad nueva**, no el port: es el framing de tres documentos.
- El port en sí es prácticamente neutro: se fueron `ClienteDocker` (174) y `Demultiplexor` (53) y
  entraron `Docker` (187) y `Acumulador` (47). La librería ahorra el HTTP crudo pero hay que
  reconstruirle encima la semántica que no trae: el EOF de stdin, el tope por llamada y la guarda
  contra la salida sin enmarcar.
- `Http` **no se fue**: lo usa `Servidor` para hablar con el worker, que no tiene nada que ver con
  Docker. El handoff lo contaba como ahorro y estaba equivocado.

Lo que sí se ahorró es `Ejecucion`, que bajó de 152 a 106: ya no hay watchdogs, ni hilos para
acotar el `wait`, ni escritura manual de stdin.

## Dependencias

`docker-java-core` + `docker-java-transport-httpclient5` (3.4.1), `jackson-databind` en runtime,
JUnit 5 en tests. **R11.4 queda derogada**: prohibía `docker-java` argumentando que la librería
«pone un `withPrivileged(true)` en el mismo proceso que tiene el socket». El argumento es débil —
quien ya ejecuta código en este proceso tiene el socket y puede mandar el JSON que quiera a mano; la
librería no agrega capacidad, agrega comodidad—. Lo que sí costaba era el golden A1/A2, y se
recuperó entero (ver abajo).

El fat jar pasó de ~2 MB a **21 MB**: `docker-java-core` arrastra guava, commons-compress,
commons-lang3, commons-io y bouncycastle, que este ejecutor no usa. Se puede podar con exclusiones,
pero hay que verificar una por una contra el arranque real del cliente.

## El golden A1/A2 sobrevivió, y es más fuerte que antes

Era la pérdida que el handoff daba por hecha. No ocurrió: `CreateContainerCmdImpl` **es** el modelo
del cuerpo del pedido (sus campos llevan `@JsonProperty` con los nombres de la API y la clase está
anotada `@JsonAutoDetect(... NONE)`, así que nada más se serializa). `Spec.bytesDeCreate` lo
serializa con `DockerClientConfig.getDefaultObjectMapper()`, que es literalmente el mapper con el
que la librería arma el pedido.

Dos tests lo cierran:

- `SpecTest#a1_jsonDeCreateEsIdenticoEntreEjecuciones` compara esos bytes contra
  `src/test/resources/spec-create-referencia.json`.
- `EjecucionTest#elCuerpoDeCreateEsElGolden` compara esos mismos bytes contra **lo que el daemon
  recibió de verdad por el socket**.

Juntos son la evidencia de I1 e I2: la spec del cable es la spec del archivo.

**El archivo de referencia se regeneró** y ya no es byte a byte el que compartía la implementación
Node: el cuerpo ahora incluye todos los campos nulos del modelo de `docker-java`, y `RestartPolicy`
viaja como `{"Name":""}` en vez de `{"Name":"no"}` (para el daemon es lo mismo: `""` es la ausencia
de política, que es lo que pone `docker run` sin `--restart`). Como la implementación Node quedó de
lado, el archivo dejó de ser un contrato entre implementaciones y pasó a ser el golden de esta sola.

## Lo que se perdió, dicho explícitamente

`Demultiplexor` desapareció y con él sus tests de corrupción del stream:

| Antes | Ahora |
|---|---|
| R6.2 — largo de frame fuera de rango ⇒ `ErrorDaemon` | lo maneja la librería, sin tope propio |
| R6.5 — encabezado o payload truncado ⇒ `ErrorDaemon` | la librería corta y devuelve lo que llegó, en silencio |
| R6.1 — `Content-Type` inesperado en `/logs` ⇒ `ErrorDaemon` | ya no se mira el content-type |

Lo que **sí** se conservó es la guarda que importaba: si la salida no viene enmarcada, `docker-java`
la entrega como frames `RAW` y `Acumulador` la rechaza (`AcumuladorTest`,
`EjecucionTest#r61_salidaSinEnmarcarEsErrorDaemon`). Adivinar que eso es stdout es como se corrompe
un resultado en silencio, y ese camino sigue cerrado.

## El EOF de stdin: lo único que `docker-java` no hace

`HijackingHttpRequestExecutor` tira del `InputStream` de stdin hasta el final y **deja la conexión
abierta**: no hay media-clausura. El cliente a mano hacía `shutdownOutput()` y el contenedor veía
EOF. Sin eso, `capa1.sh` se cuelga en el `cat` del bundle hasta el reloj de pared.

La solución es la spec misma: el contenedor se crea con `StdinOnce`, así que **cerrar la conexión
adjunta es el EOF**. `Ejecucion` la cierra en un `finally` apenas la entrega termina (o vence).
`ProtocoloIT#elCierreDelCanalAdjuntoEsElEofDelContenedor` es el test que lo fija.

El mismo cierre resuelve R8.5. El bucle de escritura vive en un hilo de la librería, trabado en un
`write` que no se puede interrumpir; el tope no puede vivir en el stream. Vive en el llamador:
`Entrada.esperarEntrega(tope)` y, al vencerse, el cierre del canal hace reventar la escritura.

## Cobertura de §13

| Test | Dónde | Estado |
|---|---|---|
| A1, A2 | `SpecTest`, `EjecucionTest#elCuerpoDeCreateEsElGolden` | ✅ referencia regenerada, ver arriba |
| A6 | `ProtocoloIT` | ✅ contra Docker real, nativo en Windows, con los tres documentos |
| A7 | `EjecucionTest#r61_salidaSinEnmarcarEsErrorDaemon` | ✅ por tipo de frame, ya no por content-type |
| A8–A12 | `AcumuladorTest` | ⚠️ parcial: el desenmarcado es de la librería (ver «Lo que se perdió») |
| A21 | `ReporteTest`, `EjecucionTest` | ✅ |
| A26, A27 | `ServidorTest` | ✅ |
| **A31** *(C2)* | `EjecucionTest#a31_oomKilledVieneDelInspect`, `#a31_elInspectQueFallaNoAbortaLaEjecucion` | ✅ más `#r510_elInspectTambienCorreEnTimeout` |
| **A32** *(C1)* | `SpecTest#a32_ulimitCpuYSuRelacionConElRelojDePared` | ✅ el ulimit sale del perfil; el assert de R4.1 quedó sobre `Constantes.CPU_MAX_S`, el techo que `Catalogo` hace cumplir a cualquier perfil |
| **A33** *(C3)* | `EjecucionTest#a33_laEscrituraQueNoAvanzaNoRetieneElCupo` | ✅ ahora por cierre del canal, no por half-close |
| A3–A5, A13–A20, A22–A25, A28–A30 | — | fuera del alcance acordado: necesitan la imagen `sandbox-runner` de producción |

Además de la suite de §13, hay tests que fijan invariantes que la spec afirma pero no numera:
orden `attach` → `start` (R5.1), `wait` → `inspect` → `logs` → `delete` (R5.4, R5.10), versión de la
API en todas las rutas (R5.0), el tar viaja opaco (I7), que el nonce no se filtra al `create`
(R7.3), y el orden exacto de los trece campos de la respuesta (los diez de §3.1 más
`perfilId`/`perfilVersion`/`perfilHash` del catálogo de perfiles, al final).

## Paso 4 del handoff — validado contra Docker real (10/09)

`BundlesIT` corre contra `sandbox-runner:2.0.0-capa1` (la imagen real de dos capas, 755 MB), **no**
el fixture de busybox que usa `ProtocoloIT`. Usa dos catálogos de perfiles: `perfiles/` (producción,
`java21-junit@4`) para los nueve bundles, y un perfil de juguete propio,
`src/test/resources/perfiles-it/framing-real@1.json`, para el framing y la integridad byte a byte —
ese perfil no compila nada, así que separa "¿llegaron los bytes?" de "¿se comportó bien la
evaluación?".

**1. El framing, contra la imagen real** — `p4a_elFramingLlegaEnteroALaImagenReal`: el bundle
`ok-suma` llega entero a `sandbox-runner:2.0.0-capa1`; el guion de juguete lista `$SANDBOX_IN` y el
listado real contiene `src/tp/Solucion.java` y `test/tp/SolucionTest.java`. ✅

**2. El sobre-consumo, byte a byte** — `p4b_elPrimerByteDelTarNoSePierdeEnLaCostura`: un tar de un
solo archivo cuyo primer byte del archivo entero (offset 0, el primer carácter del campo `name` del
header ustar) es `'Z'` y cuyo primer byte de contenido es `'M'`. Los dos se verifican por separado
después de la ejecución real: si la costura guion-tar se hubiera comido un byte, el nombre del
archivo habría llegado corrido o el tar directamente no habría parseado (`BUNDLE_INVALIDO`, 22). Los
dos bytes llegaron intactos. ✅

**3. Los nueve bundles, contra la tabla de `../../HANDOFF-opcion1.md` §6** — medidos por primera vez A
TRAVÉS DEL EJECUTOR (la tabla original se midió con `probar-capa1.sh`, sin el ejecutor en el medio):

| bundle | exit (esperado) | exit (medido) | resultado capa 1 (medido) | exitEval (medido) | surv (medido) | ¿coincide? |
|---|---|---|---|---|---|---|
| ok-suma | 0 | 0 | OK | 0 | 0 | ✅ |
| hostil-exit0 | 47 | 47 | DETENIDO_POR_EVALUACION | 47 | 0 | ✅ |
| hostil-cpu | 44 | 44 | DETENIDO_POR_EVALUACION | 44 | 0 | ✅ |
| hostil-memoria | 43 | 43 | DETENIDO_POR_EVALUACION | 43 | 0 | ✅ |
| hostil-sleep | 45 | 45 | DETENIDO_POR_EVALUACION | 45 | 0 | ✅ |
| hostil-reporte | 30 | 30 | VEREDICTO_NO_CONFIABLE | 0 | 6 | ✅ |
| hostil-reporte-loop | 30 | 30 | VEREDICTO_NO_CONFIABLE | 0 | 6 | ✅ |
| hostil-paquete | 0 | 0 | OK | 0 | 0 | ✅ (primera medición a través del ejecutor, ver abajo) |
| hostil-red | 0 | 0 | OK | 0 | 0 | ✅ (primera medición a través del ejecutor, ver abajo) |

**Cero divergencias.** Los nueve bundles reprodujeron la tabla del handoff exactamente, con el
ejecutor Java de por medio (no solo `probar-capa1.sh`). No hubo que torcer ningún assert ni la
imagen para que cerrara.

**El cabo suelto que el handoff §7 pedía confirmar**: `hostil-paquete` (el shadowing de `tp.Ayuda`,
CVE-2024-23682-style) y `hostil-red` (el aislamiento de `--network none`) nunca habían corrido a
través del ejecutor, solo con `run.sh` a mano. Ahora sí: los dos dieron `OK` con `exitEval=0` y
`surv=0`, confirmando empíricamente que **con el ejecutor real de por medio** (a) los tests del
profesor ganan el classpath sobre la clase de soporte que el alumno intenta sombrear, y (b)
`--network none` corta la conexión saliente y la assertion invertida del bundle pasa. Antes de este
test esto era una expectativa razonable, no una medición.

### Dos intermitencias que aparecieron al cerrar el Paso 4

Con `BundlesIT` adentro, la suite completa empezo a fallar de a ratos. Aparecieron **dos** carreras,
las dos en codigo de test y las dos preexistentes: `BundlesIT` no las causo, solo les saco la ventaja
de CPU con la que venian ganando.

**1. El reporte llegaba nulo** (`elReporteSeSeparaConElNonceDeLaEjecucion`, `expected:
<testsuite name="REAL"/> but was: <null>`). El helper `ejecutarConReporte` armaba los logs en un hilo
aparte que giraba esperando `stdinRecibido`, y le compraba ventaja poniendo `demoraWaitMs = 300` en
el `/wait`. Con los contenedores reales de `BundlesIT` compitiendo por CPU, esos 300 ms dejaron de
alcanzar y `/logs` se servia con el `logs` vacio por defecto.

`DaemonDePrueba` expone ahora `alRecibirStdin`, un hook que corre en el hilo del attach apenas se
cierra el canal. Pero el hook solo no alcanza, y conviene decirlo porque el primer intento se quedo
ahi: el hook corre en el hilo del attach y `/logs` se sirve en otro, asi que la carrera entre hilos
seguia viva, y sin el `demoraWaitMs` era mas probable, no menos. Lo que la cierra son dos cosas
juntas: el hook corre **antes** de levantar `stdinCerrado` (ese flag pasa a significar "los bytes
llegaron y el hook ya armo los logs"), y el handler de `/logs` **espera esa barrera** cuando hay hook
registrado. El sincronismo es una barrera explicita, no un sleep.

**2. El nonce se leia de un arreglo vacio** (`r73_elNonceNoTocaLaSpecDelContenedor`,
`StringIndexOutOfBoundsException: Range [0, 32) out of bounds for length 0`). Cinco tests leian
`daemon.stdinRecibido` apenas volvia `ejecutar()`, y el hilo que atiende el attach puede no haber
terminado de leer. `esperarStdin()` espera a `stdinCerrado` con tope de 10 s antes de devolver los
bytes, y el attach resetea `stdinRecibido`/`stdinCerrado` al empezar, para que una segunda ejecucion
no lea los bytes de la primera (`elNonceEsDistintoCadaVez` hace exactamente eso).

`demoraWaitMs` sigue existiendo para los tests que si quieren un `wait` lento.

**La leccion vale mas que los dos arreglos.** La primera corrida completa dio 71/71 y parecia
terminado. Las dos carreras aparecieron al repetir la suite, en corridas distintas y en tests
distintos, y el primer arreglo de la primera parecio funcionar una corrida antes de volver a fallar.
Una sola corrida verde no prueba que una intermitencia se fue. La evidencia aceptada aca fueron
**tres corridas completas consecutivas** en verde, sin contenedores colgados.

## Decisiones que la spec dejaba abiertas

- **`TAG_FIJO`.** Resuelto por el catálogo de perfiles: la imagen ya no sale de `Constantes.IMAGEN`
  (que se conserva como referencia histórica y como default de fixtures de test), sino del campo
  `imagen` del perfil que elige `X-Perfil`. El perfil de referencia `java21-junit@4` apunta a
  `sandbox-runner:2.0.0-capa1`.
- **`X-Ejecucion-Id`.** Se valida contra el UUID canónico estricto. No es cosmética: el id se
  concatena en la URL de `create` como nombre del contenedor.
- **El tope de la escritura de R8.5.** La spec pide que el paso 4 tenga «su propio tope de tiempo»
  pero no define una constante en §4.3. Se acota por **lo que queda del reloj de ejecución**, en vez
  de inventar un valor nuevo.
- **Los timeouts por llamada de R8.3.** Ya no hay un watchdog por operación: el `responseTimeout`
  del transporte es uno solo y se dimensiona por la llamada más larga (el `wait`). El tope fino del
  `wait` lo pone el llamador con `awaitCompletion`. Es más grosero que antes para las llamadas
  cortas y hay que decirlo.

## Pendientes

1. ~~La spec `08-spec-ejecutor.md` está desactualizada~~ **resuelto** (commit `b008f58`): se
   actualizó entera al catálogo de perfiles — §3, §4, §7, §11-§14 — y sigue creciendo con cada
   cambio (última incorporación: R5.12 / A40, ítem 4 de esta lista).
2. **R11.5 (< 400 líneas) sigue sin cumplirse**, y ahora por más: ~1066 de código efectivo, con
   `Catalogo` como el módulo nuevo más grande. El argumento de §14 no cambia; el número sí.
3. ~~R10.2 quedó desactualizada por C1~~ **resuelto**: el texto actual de R10.2 ya dice «10 veces
   mayor» (600 s contra 60 s), no «20 veces».
4. ~~La versión mínima de API que acepta el daemon varía entre builds del Engine~~ **hecho**: el
   arranque verifica ahora `MinAPIVersion <= VERSION_API_DOCKER <= ApiVersion` contra el daemon
   (`Docker.verificarVersion`, R5.12 / A40) y falla ruidoso, sin reintentos, antes de abrir el
   socket del ejecutor o programar el barrido.
5. **El fat jar de 21 MB.** Podar las dependencias que `docker-java-core` arrastra y no usamos.
6. **R14.1**: falta la revisión línea por línea por dos personas que no escribieron el código.
7. ~~Paso 4 del handoff~~ **hecho** (10/09): `BundlesIT`, contra `sandbox-runner:2.0.0-capa1` real,
   con los nueve bundles de `pruebas/bundles/` y el perfil de producción `java21-junit@4`.
   Ver «Paso 4 del handoff — validado contra Docker real» arriba. Cero divergencias contra la tabla
   de `../../HANDOFF-opcion1.md` §6, y `hostil-paquete`/`hostil-red` quedaron confirmados por primera
   vez a través del ejecutor (antes solo habían corrido con `run.sh`).
