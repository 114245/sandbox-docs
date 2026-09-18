# Aislamiento de `ms-sandbox`

> Qué defiende el aislamiento del código del alumno, la especificación fija del contenedor, el
> diseño de imagen en dos capas, el catálogo de perfiles, los relojes y el contrato entre las dos
> capas de la imagen, incluido el sobre que sale por `stdout`.

## Decisiones

| # | Decisión | Por qué | Descartado |
|---|---|---|---|
| **D5** | El bundle entra al contenedor por `stdin`, como un tar, nunca por `docker cp` ni por red | Saca del camino el endpoint más peligroso de Docker | Montar una carpeta del host; `docker cp` (falla contra el rootfs de solo lectura, y aunque se permitiera, el montaje de `/work` tapa lo copiado) |
| **D8** | Un solo estado `TIMEOUT`, medido en tiempo de CPU del alumno | Medir en CPU y no en reloj de pared elimina los `TIMEOUT` intermitentes por contención del host | Un timeout único de reloj de pared |
| **D15** | 2 CPU por contenedor en `java21-junit` | Medido de punta a punta: el mismo bundle tarda 3,3 s con 2 CPU y 8,4 s con 1 | 1 CPU |
| **D17** | Catálogo estático de tres perfiles (`java21-junit`, `java21-pmd`, `java21-checkstyle`), cargado al arranque, sin CRUD | Alcanza para el MVP y no expone administración | CRUD dinámico de perfiles |
| **D18** | Los límites viven en el perfil, nunca en el request. Presupuestos separados de compilación y de tests | T05 no puede pedir más recursos de los que el sandbox decide dar | Que el request proponga límites y el sandbox los recorte |
| **D22** | El sandbox devuelve salida cruda y no emite un resultado de negocio | Ejecutar es dominio del sandbox; interpretar el resultado académico es dominio de T05 | Que el sandbox parsee el reporte de la herramienta y decida si el alumno aprobó |
| **A1** *(cerrada)* | El `ulimit cpu` del contenedor es un freno anti-descontrol por proceso, no un presupuesto agregado | `RLIMIT_CPU` se hereda a los hijos pero el contador no: cada programa nuevo arranca en cero. El techo agregado real es el reloj de pared del ejecutor multiplicado por las CPU del perfil | Tratar el `ulimit cpu` como el presupuesto del alumno |

## 1. Qué protege

El código del alumno no puede llegar a la red, al host ni a otros servicios de la plataforma
(HU-06). Esta es la defensa central del diseño, y no una buena práctica genérica: en una
arquitectura con service discovery, cualquier proceso con red puede resolver otro servicio por
nombre y explotar sus endpoints, con lo que un contenedor comprometido podría, por ejemplo,
acreditarse monedas o experiencia sin haber resuelto nada. La mayoría de los escapes reales de un
entorno de este tipo necesitan un canal de red o un servicio del host al que llegar; sin ese canal,
el peor código imaginable no alcanza a nadie.

Una lista corta de lo que el aislamiento tiene que contener, con el motivo de cada una:

| Amenaza | Por qué importa |
|---|---|
| Que el código del alumno abra una conexión saliente | Es el canal por el que un contenedor comprometido alcanzaría a otro servicio de la plataforma |
| Que el código del alumno lea o modifique archivos fuera de su propio directorio de trabajo | Protege tanto al host como al reporte que la ejecución tiene que devolver intacto |
| Que un proceso del alumno sobreviva a la ejecución y siga corriendo después | Un proceso vivo después de que terminó la herramienta puede reescribir el buzón de reportes |
| Que la entrega, como archivo, contenga una entrada capaz de escribir fuera del árbol donde se extrae | Un tar es entrada hostil: una ruta absoluta, un `..` o un enlace pueden convertir la extracción en una escritura arbitraria |
| Que el código del alumno consuma memoria o procesos sin límite | Una entrega puede afectar a las demás ejecuciones del mismo host si comparte el límite de recursos |

El riesgo de un exploit de kernel —dos contenedores comparten el mismo kernel del host— existe en
cualquier diseño basado en contenedores y se acepta como residual (§10); lo que el aislamiento de
red cierra es el vector que más importa para esta plataforma: que el código no confiable alcance a
otro servicio.

## 2. La spec del contenedor, en síntesis

Cada ejecución corre en un contenedor con esta especificación fija:

| Campo | Por qué |
|---|---|
| Sin interfaz de red | La defensa central: sin red no hay exfiltración ni alcance a otro servicio de la plataforma |
| Rootfs de solo lectura | Que un path traversal exitoso en el tar igual falle al escribir |
| Todas las capabilities de Linux quitadas | El proceso no puede hacer ninguna operación privilegiada |
| `no-new-privileges` | Cierra la vía de recuperar privilegios vía un binario `setuid` |
| Usuario no root | El proceso no corre con el usuario privilegiado del contenedor |
| `tmpfs` en `/work` | Único punto escribible; vive en memoria y muere con el contenedor |
| Límite de PIDs | Frena una bomba de procesos (`fork`, o `Runtime.exec` en Java) |
| Memoria, CPU y `ulimit cpu` del perfil | Vienen del perfil elegido, nunca del request (§4) |

Ningún byte de esta especificación viene de quien llama (I2): el request solo elige un perfil de un
catálogo cerrado (§4), y un perfil únicamente puede mover memoria, CPU, el `ulimit cpu` y la imagen.
El JSON completo que arma el ejecutor, campo por campo y con el detalle de por qué cada uno está ahí,
vive en [`04-ejecutor.md`](./04-ejecutor.md) (spec del contenedor); esta sección no lo duplica.

Tres consecuencias de esta lista, que conviene dejar explícitas:

- **La diferencia entre un menú y un formulario.** T05 no puede pedir 8 GiB de memoria ni un tiempo
  de ejecución mayor al de su perfil: elige `java21-junit`, `java21-pmd` o `java21-checkstyle`, y qué
  significa cada uno lo decide un archivo versionado en el repositorio del sandbox, revisado como se
  revisa cualquier otro cambio de código.
- **El `tmpfs` de `/work` no es gratis.** Tiene un tope de tamaño explícito, porque sin él una
  entrega que intenta llenar el disco terminaría llenando la memoria del host: las páginas del
  `tmpfs` se cobran al mismo cgroup de memoria que el resto del contenedor.
- **Cambiar cualquiera de estos valores implica reconstruir y redesplegar la imagen o el catálogo de
  perfiles**, nunca un parámetro del request. Es el costo que se acepta a cambio de que la spec de
  seguridad sea, de verdad, invariable.

## 3. La imagen en dos capas

Un solo contenedor, dos capas de software adentro.

**Capa 1 — aislamiento, idéntica para cualquier perfil.** Es nuestro entrypoint. En orden:

1. Lee del `stdin` del contenedor el nonce, el guion de la capa 2 y el tar del bundle, en ese orden
   y sin separadores (D5).
2. Valida el tar antes de extraerlo: solo acepta archivos regulares y directorios; rechaza
   symlinks, hard links, FIFOs, dispositivos, sockets, rutas absolutas y rutas con `..`; aplica
   topes de cantidad de entradas, largo de nombre y profundidad (HU-06). Si algo de esto falla, el
   bundle se rechaza antes de invocar la capa 2. Si además el worker duplica esta validación antes
   de mandar el bundle está abierto (A10).
3. Corre la capa 2 con un entorno limpio, parada en el directorio donde extrajo el tar.
4. Al terminar la capa 2, barre los procesos que quedaron vivos y los cuenta. No es una defensa
   preventiva: un proceso que reescribe el buzón de reportes en bucle gana igual aunque después se
   lo mate, porque la escritura ya ocurrió. Lo que sí se puede es detectar que la reescritura pudo
   haber pasado, contando cuántos procesos sobrevivieron.
5. Empaqueta el buzón de reportes sin leer su contenido.
6. Emite el sobre entre los marcadores del nonce (§6) y termina.

**Capa 2 — la herramienta del perfil, la escribimos nosotros.** No es código de un tercero: cada uno
de los tres perfiles del catálogo (§4) trae su propio guion de capa 2, versionado junto con la
imagen. Para `java21-junit` compila y corre los tests con JUnit, con ArchUnit disponible en el
classpath de tests. Para `java21-pmd` y `java21-checkstyle` corre la herramienta de análisis
estático con las reglas o la configuración que trae el bundle; estos dos perfiles no ejecutan código
del alumno, pero corren bajo el mismo aislamiento que los demás porque el archivo de reglas y el de
configuración son entrada del bundle, y ambas herramientas la parsean sin haberla escrito nosotros.
No ejecutar código del alumno reduce la superficie de ataque, pero no la elimina: un archivo de
reglas o de configuración sigue siendo texto que llega de afuera, y eso alcanza para justificar el
mismo nivel de aislamiento que el perfil que sí corre código.

Las tres imágenes se versionan y se construyen en el pipeline junto con el resto del servicio
(T-07-13); qué esquema de versión usan es un detalle de despliegue que no fija esta sección.

### Secuencia de una ejecución

De punta a punta, con la capa 2 ocupando un único paso de toda la secuencia:

| Momento | Quién actúa | Qué pasa |
|---|---|---|
| t0 | El ejecutor, afuera del contenedor | Crea el contenedor con la spec fija del perfil elegido y lo arranca (§2) |
| t1 | Capa 1 | Lee la primera línea de `stdin`: el nonce. Lo guarda en una variable de shell no exportada |
| t2 | Capa 1 | Lee el largo declarado y esa cantidad exacta de bytes: el guion de la capa 2 |
| t3 | Capa 1 | Lee el resto del stream: el tar. Lo valida (tipos de entrada, rutas) antes de extraerlo |
| t4 | Capa 1 | Extrae el bundle, prepara las carpetas del contrato (§6) y desvía la salida de la capa 2 a archivos |
| t5 | Capa 2 | Compila, ejecuta o analiza según el perfil, y deja lo suyo en el buzón de reportes |
| t6 | Capa 1 | Barre los procesos que quedaron vivos y los cuenta |
| t7 | Capa 1 | Empaqueta el buzón de reportes y trunca las salidas si superaron el tope |
| t8 | Capa 1 | Emite el sobre entre los marcadores del nonce y termina; el contenedor muere |

El punto que sostiene toda la defensa contra la falsificación del sobre es t4: antes de invocar a la
capa 2, la salida real del contenedor deja de estar conectada a su proceso. Todo lo que la capa 2 y
el código del alumno impriman cae en un archivo que la capa 1 lee recién en t7. El único que escribe
en la salida real del contenedor es la capa 1, en t8.

## 4. Catálogo de perfiles

El catálogo tiene exactamente tres perfiles, cargados al arrancar, sin alta ni baja en caliente
(D17):

| `profileId` | Ejecuta código del alumno | Roles del bundle |
|---|---|---|
| `java21-junit` | Sí | `solution` (`.java`) y `test` (`.java`) |
| `java21-pmd` | No | `solution` (`.java`) y `config` (`.xml`, reglas de PMD) |
| `java21-checkstyle` | No | `solution` (`.java`) y `config` (`.xml`, configuración de Checkstyle) |

El formato exacto del archivo de catálogo y el mecanismo de búsqueda por `profileId` están
especificados en [`04-ejecutor.md`](./04-ejecutor.md) (catálogo de perfiles); acá solo se listan los
límites por perfil:

| Perfil | Memoria | CPU | Timeouts |
|---|---|---|---|
| `java21-junit` | 512 MB | 2 (D15) | Compilación: 20 s de pared. Tests: 10 s de CPU. Respaldo de pared de tests: 30 s |
| `java21-pmd` | A definir por medición (A2) | A definir por medición (A2) | A definir por medición (A2) |
| `java21-checkstyle` | A definir por medición (A2) | A definir por medición (A2) | A definir por medición (A2) |

El catálogo falla al arrancar el proceso, nunca en una ejecución individual, si cualquiera de los
tres perfiles tiene memoria, CPU o versión ausente, en cero, negativa o por encima del techo de
plataforma (HU-07 CA5). Un perfil mal cargado que aceptara memoria en cero, por ejemplo, podría
significar "sin límite" para el motor de contenedores, y eso anularía el propósito completo de esta
historia sin que nadie lo note hasta que fuera tarde: por eso la validación se hace una sola vez, al
arrancar, y es ruidosa e inmediata en vez de manifestarse como una falla intermitente en producción.

Un `profileId` que no está en este catálogo se rechaza antes de tocar Docker, reutilizando el mismo
código de error estable con el que el resto del servicio señala un perfil no soportado (HU-07 CA6);
no crea ningún contenedor ni consume ningún recurso del pool.

## 5. Relojes

Los cinco relojes de `java21-junit`, de adentro hacia afuera, más el reloj final del ejecutor:

| # | Reloj | Naturaleza | Valor | Dónde vive |
|---|---|---|---|---|
| 1 | Compilación | Pared | 20 s | Capa 2 |
| 2 | Tests | **CPU** | 10 s | Capa 2 — el presupuesto del alumno |
| 3 | Tests, respaldo | Pared | 30 s | Capa 2 |
| 4 | Contenedor (`ulimit cpu`) | CPU, por proceso | 20 s por proceso | Spec del contenedor — freno anti-descontrol por fase, ver A1 más abajo |
| 5 | Respaldo de la capa 1 | Pared | 55 s | Capa 1 — debe superar 20 + 30 |
| 6 | Ejecutor | Pared | 70 s | Ejecutor (`EXECUTION_TIMEOUT_MS`) — debe superar 55 |

El presupuesto del alumno se mide en tiempo de CPU y no en reloj de pared porque el tiempo de CPU no
cuenta el tiempo en que el sistema operativo le dio el procesador a otra ejecución del pool: dos
corridas idénticas del mismo bundle dan el mismo número aunque el host esté saturado. Medido: la
misma entrega, en corridas seguidas, varió entre 1,8 y 5,2 segundos por contención del host. Un
reloj de pared convertiría esa varianza en `TIMEOUT` intermitentes sobre código correcto, que es el
peor modo de falla posible porque no es reproducible y el alumno no puede distinguirlo de un error
propio. Medir en CPU elimina la causa en vez de acolcharla con margen.

La compilación no se le cobra al alumno porque su costo depende de cuántos archivos trae el
ejercicio del profesor, no de si el alumno resolvió bien el problema: dos alumnos igual de buenos
tendrían presupuestos distintos según el tamaño del ejercicio si compilar saliera de su reloj.

Cada vencimiento se mapea a un estado técnico. El mapeo completo, con todas las causas que llevan a
cada estado, está en [`05-worker.md`](./05-worker.md) §5; en resumen: el reloj 2 y el 3 llevan a
`TIMEOUT`; el reloj 1, el 4 (visto como freno, no como presupuesto), el 5 y el 6 son costo de
plataforma y llevan a `INTERNAL_ERROR` cuando son ellos los que vencen.

Hacen falta dos naturalezas de reloj, no una, porque hay dos formas distintas de no terminar nunca:
un proceso que quema procesador sin parar, y un proceso que espera sin hacer nada (por ejemplo, un
`Thread.sleep` con un valor enorme). Un reloj de CPU solo no corta al segundo, porque no consume
nada que ese reloj esté midiendo; un reloj de pared solo vuelve a exponer al primero a la varianza
por contención que el reloj 2 existe justamente para evitar. Por eso el reloj 3 (respaldo de pared de
los tests) convive con el reloj 2 (CPU de los tests): cada uno tapa la mitad del problema que el otro
no ve.

**A1 — cerrada.** `RLIMIT_CPU` (`ulimit -t`) es por proceso: el límite se hereda a los hijos, pero el
contador no, y cada programa nuevo arranca en cero. Con tres fases en `java21-junit` (dos
invocaciones de `javac` y la JVM de los tests), el consumo total de CPU puede llegar a
aproximadamente 3 veces el valor del `ulimit`. El techo agregado real no es el `ulimit`: es el reloj
de pared del ejecutor multiplicado por las CPU del perfil (70 s × 2 CPU). El `ulimit cpu` del
contenedor es entonces un freno anti-descontrol por fase, no un presupuesto; el presupuesto del
alumno es el reloj 2, aplicado por la capa 2 solo a la fase de tests. Queda pendiente un caso de
prueba que confirme esto de punta a punta: un bundle cuyo guion de capa 2 queme CPU en cuatro
procesos consecutivos, verificando que el contenedor sobrevive más allá del valor del `ulimit` y que
lo que finalmente lo corta es el reloj 6.

Vale la pena decirlo con todas las letras porque es fácil de leer al revés: que el `ulimit cpu` sea
por fase y no agregado no es una debilidad del diseño, es la razón de ser del reloj 6. Uno cubre lo
que el otro no puede cubrir, y ninguno de los dos reemplaza al otro.

## 6. Contrato capa 1 ↔ capa 2 y el sobre

### Qué encuentra la capa 2 al arrancar

La capa 1 la invoca con un entorno limpio y estas variables exportadas:

| Variable | Para qué |
|---|---|
| `SANDBOX_IN` | Directorio donde se extrajo el bundle; la capa 2 arranca parada ahí |
| `SANDBOX_REPORTS` | El buzón de salida: todo lo que quede ahí vuelve en el sobre |
| `SANDBOX_TMP` | Borradores; se pierde con el contenedor |
| `SANDBOX_STATUS` | Avisos opcionales de la capa 2, incluido el archivo del código de la herramienta (ver abajo) |
| `SANDBOX_LIBS` | Herramientas de solo lectura (el jar de JUnit, PMD, Checkstyle) |
| `SANDBOX_MEM_MB` | Memoria del contenedor, para que la capa 2 dimensione la JVM |

Ninguna de estas variables es secreta: saber dónde queda el buzón de reportes no le sirve de nada a
quien quiere hacer trampa, y por eso viajan como variables exportadas y no como el nonce, que sí
viaja fuera de cualquier canal que el alumno pueda leer.

### Dónde deja lo que produce, y cómo avisa cómo le fue

Todo lo que quede en `$SANDBOX_REPORTS` vuelve empaquetado; todo lo demás se pierde con el
contenedor. La capa 2 escribe además el código de salida de la herramienta que corrió en un archivo
bajo `$SANDBOX_STATUS` (por ejemplo, `tool-exit-code`), que la capa 1 copia al sobre como
`toolExitCode`.

`toolExitCode` es, para `java21-junit`, el código de salida de la **última** herramienta que corrió:
el de `javac` si la solución o los tests no compilaron, o el del lanzador de JUnit en caso
contrario. Con ese código, más `stderr` y los reportes (que no existen en el primer caso), T05
distingue "no compila" de "fallaron los tests". Para `java21-pmd` y `java21-checkstyle`,
`toolExitCode` es directamente el código de salida de la herramienta.

El propio proceso de la capa 2 termina con un código de un conjunto fijo, que es lo que la capa 1
traduce a `result` en el sobre: `0` significa que la herramienta corrió hasta el final, sea cual sea
su código de salida — eso ya quedó capturado en `toolExitCode`, no en el código de la capa 2 —, y hay
un puñado de códigos fijos para las condiciones que la capa 2 detecta ella misma por sus propios
mecanismos de reloj: presupuesto de CPU de los tests agotado, respaldo de pared de los tests vencido,
la JVM sin memoria y el reloj de compilación vencido. Cualquier otro código de la capa 2 es un error
de la capa 2. Los números concretos, consistentes con el guion de referencia de `java21-junit`
(`javac` con reloj de plataforma, `ulimit -t` para el reloj del alumno, `timeout` como respaldo de
pared, y `-XX:+ExitOnOutOfMemoryError` para que el agotamiento de memoria de la JVM termine el
proceso con un código reconocible):

| Código de la capa 2 | Condición |
|---|---|
| `0` | La herramienta corrió hasta el final |
| `42` | Venció el reloj de compilación |
| `43` | La JVM se quedó sin memoria (`ExitOnOutOfMemoryError`) |
| `44` | Se agotó el reloj de CPU de los tests |
| `45` | Venció el respaldo de pared de los tests |
| cualquier otro | Error de la capa 2 |

### El sobre: `sandbox.layer1/v3`

Un único objeto JSON en una línea, entre los marcadores del nonce, con estos campos en este orden:

```json
{"schema":"sandbox.layer1/v3","result":"FINISHED","detail":"",
 "toolExitCode":0,"survivingProcesses":0,
 "resources":{"evalMs":3810,"cpuEvalMs":5120},
 "reportsTarGzB64":"H4sI...","stdoutB64":"","stderrB64":"","truncated":false}
```

| Campo | Tipo | Significado |
|---|---|---|
| `schema` | string | Siempre `"sandbox.layer1/v3"` |
| `result` | enum, tabla abajo | Cómo terminó el proceso de la capa 2, visto desde la capa 1 |
| `detail` | string | Texto de diagnóstico de la capa 1, escapado, ≤ 512 B |
| `toolExitCode` | int \| null | Código de salida de la herramienta (ver arriba). `null` si la capa 2 no llegó a correrla |
| `survivingProcesses` | int | Procesos vivos después de la capa 2, ya matados por la capa 1 |
| `resources.evalMs` | int | Reloj de pared de la capa 2, en milisegundos |
| `resources.cpuEvalMs` | int | CPU de usuario más sistema de los hijos de la capa 2, en milisegundos |
| `reportsTarGzB64` | string | `$SANDBOX_REPORTS` entero, empaquetado sin mirar adentro, `tar.gz` en base64. `""` si el buzón quedó vacío |
| `stdoutB64` / `stderrB64` | string | Streams de la capa 2, truncados adentro del contenedor a 65.536 B, en base64 |
| `truncated` | bool | `true` si alguno de los dos streams superó ese tope |

Y los valores de `result`, con el estado al que los mapea el worker:

| Qué pasó en la capa 2 | `result` | Estado en el worker |
|---|---|---|
| La herramienta corrió hasta el final (cualquier código de salida) | `FINISHED` | `COMPLETED` |
| Presupuesto de CPU de los tests agotado | `CPU_LIMIT` | `TIMEOUT` |
| Respaldo de pared de los tests | `WALL_LIMIT` | `TIMEOUT` |
| La JVM se quedó sin memoria | `OUT_OF_MEMORY` | `MEMORY_LIMIT` |
| Venció el reloj de compilación | `COMPILE_TIMEOUT` | `INTERNAL_ERROR` |
| Tar o guion inválidos | `INVALID_BUNDLE` | `INTERNAL_ERROR` |
| Saltó el respaldo de 55 s de la capa 1 | `LAYER1_TIMEOUT` | `INTERNAL_ERROR` |
| SIGKILL sin respaldo | `KILLED` | `MEMORY_LIMIT` si `oomKilled`, si no `INTERNAL_ERROR` |
| La capa 2 salió con un código inesperado | `LAYER2_ERROR` | `INTERNAL_ERROR` |

Un buzón vacío es `FINISHED` con reportes vacíos: no es un valor especial. Los procesos
sobrevivientes no cambian `result` — se matan y se cuentan en `survivingProcesses`, para logs y
métricas —, salvo que si sobrevivió al menos uno, ninguna interpretación posterior del reporte puede
tratarse como confiable, porque no hay forma de descartar que ese proceso lo haya reescrito. El
worker tolera campos desconocidos del sobre y trata un `result` desconocido o ausente como
`INTERNAL_ERROR`; un cambio incompatible del sobre sube la versión del `schema`.

**El código de salida del contenedor** es una tabla operativa simple, no la fuente de verdad:

| `result` | Código de salida del contenedor |
|---|---|
| `FINISHED` | `0` |
| `COMPILE_TIMEOUT` | `42` |
| `OUT_OF_MEMORY` | `43` |
| `CPU_LIMIT` | `44` |
| `WALL_LIMIT` | `45` |
| `INVALID_BUNDLE` | `22` |
| `LAYER1_TIMEOUT` | `27` |
| `KILLED` | `31` |
| `LAYER2_ERROR` | `23` |

Los códigos evitan a propósito `137` y `1`. `137` es lo que Docker informa cuando el cgroup mata el
contenedor por memoria, y `1` es el código genérico de cualquier programa que falla, incluida la
propia capa 1. Usarlos haría ambiguo el diagnóstico.

El worker lee el sobre, nunca el código de salida del contenedor, para decidir el estado técnico: el
código de salida es diagnóstico de operación, no protocolo.

### Un ejemplo de punta a punta

Una entrega de `java21-junit` cuya solución compila pero falla dos de cinco tests. La secuencia,
siguiendo los pasos de §3:

1. La capa 2 compila la solución y los tests, y corre el lanzador de JUnit. El lanzador termina con
   un código de salida distinto de cero, porque hubo fallas, y deja el reporte en el buzón.
2. La capa 2 escribe ese código de salida en `$SANDBOX_STATUS/tool-exit-code` y termina su propio
   proceso con `0`: la herramienta corrió hasta el final.
3. La capa 1 no ve procesos sobrevivientes, empaqueta el buzón de reportes y arma el sobre con
   `result: "FINISHED"` y `toolExitCode` igual al código que dejó el lanzador de JUnit.
4. El worker mapea `FINISHED` a `COMPLETED`. El estado técnico de la ejecución es `COMPLETED`: la
   herramienta terminó. Que dos tests hayan fallado no es un dato que el sandbox interprete.
5. T05 abre el reporte adjunto al sobre y decide, con esa información, cómo mostrarle el resultado
   al alumno.

Si en cambio la solución no hubiera compilado, la secuencia es la misma hasta el paso 1, salvo que
ahí quien deja su código de salida es `javac`, no el lanzador de JUnit, y el buzón de reportes queda
vacío porque nunca se llegó a correr nada. El estado técnico sigue siendo `COMPLETED` — la
herramienta (en este caso, `javac`) también corrió hasta el final —, y es la combinación de
`toolExitCode` distinto de cero con la ausencia de reportes lo que le permite a T05 distinguir esta
situación de la anterior.

## 7. Salida

Se capturan 65.536 bytes por flujo (`stdout` y `stderr`); cualquier descriptor de archivo heredado
por el proceso de la herramienta se cierra antes de invocarla, para que un alumno no pueda evadir ese
tope escribiendo directamente a un canal heredado (HU-08 CA2). Sin ese cierre, un descriptor abierto
heredado del proceso padre sería un canal de escritura que no pasa por el acumulador de salida con
tope, y el límite de 65.536 bytes dejaría de servir de nada frente a una entrega que lo conoce y lo
evita a propósito.

El paquete de reportes tiene un tope de 8 MiB, y el worker lo desempaqueta con falla cerrada: tope de
cantidad de entradas, de largo de nombre y de profundidad, y solo acepta archivos regulares (HU-08
CA3). Un paquete que exceda cualquiera de esos topes, o que contenga una entrada de otro tipo, se
rechaza entero — no se procesa parcialmente ni se recorta a lo que entra en el tope. Ni el ejecutor
ni el worker interpretan el contenido de ese paquete (D22): lo entregan crudo, y es T05 quien decide
qué significa. Este desempaquetado es una operación distinta de la validación del bundle de entrada
que hace la capa 1 en §3: una pasa código y tests hacia adentro del contenedor, la otra saca el
resultado hacia afuera, y las dos aplican el mismo criterio de lista blanca por un motivo idéntico —
la entrada, en los dos sentidos, no la escribimos nosotros.

## 8. Amenazas contenidas y mediciones

| Amenaza | Cómo se contiene |
|---|---|
| Intento de conexión de red | El contenedor no tiene interfaz de red |
| Bomba de procesos (fork bomb) | Límite de PIDs del contenedor |
| Llenar `/work` | El `tmpfs` tiene un tope de tamaño; sus páginas se cobran al cgroup de memoria |
| Path traversal en el tar | Rechazo de rutas absolutas y con `..` antes de extraer, más rootfs de solo lectura como respaldo |
| Symlink, hard link o FIFO en el tar | Lista blanca de tipos de entrada: solo archivos regulares y directorios |
| Falsificar los marcadores del sobre sin el nonce | El alumno no puede producir el nonce: no lo ve, viaja por una variable de shell no exportada |
| Leer el nonce desde `/proc` | La variable del nonce nunca se exporta, así que no queda en el entorno de ningún proceso hijo |
| Sombrear una clase de soporte del profesor | Orden del classpath: los `.class` del profesor van primero. Falta un segundo barrera con el rechazo por paquete reservado (`RESERVED_PACKAGE`), pendiente de que T05 declare cuál es ese paquete (D12) |

Mediciones que respaldan estas decisiones:

- **D15**: el mismo bundle tarda 3,3 s con 2 CPU contra 8,4 s con 1 CPU.
- **Los dos caminos de memoria agotada**: con la JVM bien configurada (`-XX:+ExitOnOutOfMemoryError`),
  quien se queda sin memoria es la JVM antes que el cgroup, y el contenedor termina con código `3` y
  `OOMKilled: false`; sin esa configuración, el cgroup mata al proceso y el contenedor termina con
  `OOMKilled: true`. El estado `MEMORY_LIMIT` tiene que contemplar los dos caminos.
- **Varianza del reloj de CPU**: la misma entrega, en corridas seguidas, varió entre 1,8 y 5,2
  segundos por contención del host — ver §5.

Sobre la última fila de la tabla: el orden del classpath (tests primero) ya cierra la mayoría de los
casos de sombreado, porque el `.class` del profesor gana la resolución. Lo que queda pendiente es la
segunda barrera —rechazar de entrada un archivo del alumno que declare el paquete reservado de los
tests— porque hoy no existe una definición de cuál es ese paquete: es información que tiene que
declarar T05, no algo que el sandbox pueda inventar.

## 9. Tropiezos específicos de Java

Desde la versión 10, la JVM respeta el límite de memoria del contenedor y toma por defecto el 25 %
como heap máximo. Con el límite de memoria de `java21-junit`, eso deja poco margen para heap,
metaspace y las pilas de los hilos, así que el perfil configura un porcentaje de heap más alto sobre
el límite del contenedor (`MaxRAMPercentage`), en vez de dejar el valor por defecto de la JVM
(T-07-10).

## 10. Deuda conocida y riesgo residual

En `java21-junit`, el código del alumno corre en el mismo proceso y con el mismo usuario que el
runner de los tests. Eso significa que la salida cruda puede ser forjada por código de alumno
adversarial —escribiendo directamente su propio reporte, por ejemplo—, y que ese mismo código puede
leer el código fuente de los tests ocultos. Un verificador de bytecode contra una lista blanca de
APIs permitidas cerraría esta brecha, y queda fuera de alcance del MVP por el esfuerzo que implica.
Consecuencia directa: T05 no debe tratar la salida cruda que devuelve el sandbox como una
calificación auténtica todavía; es evidencia sin verificar, y el MVP está pensado para una demo, no
para calificar a un alumno que intenta activamente hacer trampa.

El riesgo de kernel compartido —dos ejecuciones en contenedores distintos comparten el mismo
kernel— es residual y queda documentado, no cerrado, junto con la decisión pendiente de si conviene
sumar un proxy del motor de contenedores con lista blanca de operaciones (T-06-13). Es un riesgo
distinto del de la falsificación de la salida: uno vive en el motor de contenedores y afecta a
cualquier perfil por igual, el otro vive en que `java21-junit` comparte proceso y usuario entre el
código evaluado y quien lo evalúa, y solo hay una mitigación de fondo para el segundo.

Alternativas de aislamiento más fuerte, evaluadas y descartadas para el MVP:

| Alternativa | Por qué se descarta acá |
|---|---|
| gVisor | Cambio de runtime con buen costo/beneficio, pero no forma parte del alcance del MVP |
| Kata Containers / Firecracker (microVMs) | El aislamiento más fuerte disponible, pero necesitan virtualización anidada que probablemente no esté disponible en el entorno de despliegue |

Ninguna de las dos cierra, además, el problema descrito arriba: aíslan mejor el contenedor del host,
pero el código del alumno seguiría corriendo en el mismo proceso que el runner de tests dentro de
`java21-junit`. Es un aislamiento distinto del que hace falta para esa deuda en particular, y por eso
no está en el camino crítico para cerrarla.

## Abierto

| # | Pregunta | Quién la cierra |
|---|---|---|
| **A2** | Memoria, CPU y timeouts de `java21-pmd` y `java21-checkstyle`, a definir por medición | Nosotros |
| **A10** | Componente que hace la validación estructural del bundle (tipos de entrada del tar): si vive en el worker, en la capa 1, o en ambos como defensa en profundidad | Nosotros |
| **D21** | Catálogo de imágenes base: quién las nombra, las versiona y aprueba una nueva | Nosotros |
| **D12** | Paquete reservado de los tests del profesor, para el rechazo `RESERVED_PACKAGE` | T05 |

Ninguna de las cuatro preguntas de esta tabla necesita rehacer código ya escrito para cerrarse: A2 y
D21 se resuelven midiendo o decidiendo, A10 es una decisión interna de dónde vive una validación que
de todos modos hay que construir, y D12 depende de un dato que solo T05 puede declarar.
