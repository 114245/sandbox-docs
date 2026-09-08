# Ejecutor de `ms-sandbox` — implementación Node 22 + TypeScript

Implementación del contrato de [`08-spec-ejecutor.md`](../java%20sidecar%20test/08-spec-ejecutor.md).
El documento es la fuente de verdad; este README solo dice dónde vive cada requerimiento y qué quedó
abierto.

Alineado con la versión de la spec que incorpora §0 (cambios C1 a C6), y con la implementación Java
hermana en `../java sidecar test`.

## Correr

```bash
npm install
npm test                    # suite completa, ~2 min
npm run build               # dist/
npm start                   # arranca el ejecutor
npm run contar              # la tabla de líneas efectivas de §14
node scripts/verificar-a6.mjs   # A6 contra un daemon de Docker real
```

Dos tests consumen el reloj de ejecución completo de 60 s (`r8.2` de timeout y `a33` de escritura
trabada). Son controles de tiempo real, así que no se pueden acelerar sin falsearlos.

Variables de entorno de despliegue (no tocan ningún campo de la spec del contenedor, así que no
violan P1): `EJECUTOR_SOCKET` (por defecto `/run/ejecutor/ejecutor.sock`) y `DOCKER_SOCKET`
(por defecto `/var/run/docker.sock`).

## Dónde vive cada cosa

| Módulo | Líneas | Spec |
|---|---|---|
| `spec.ts` | 45 | §4.1, §4.2 — el JSON de `create`, constante salvo label y nombre |
| `constantes.ts` | 18 | §4.3 |
| `cliente-docker.ts` | 160 | §5 — las ocho llamadas, upgrade a `101` (R5.8), relojes por llamada (R8.3) |
| `demux.ts` | 43 | §6 completa |
| `reporte.ts` | 24 | §7.5–§7.8 |
| `ejecucion.ts` | 127 | §5, §7.1–§7.2, §8 — nonce, timeouts, limpieza garantizada |
| `servidor.ts` | 151 | §3, §9 — `/ejecutar`, `/salud`, semáforo y cola |
| `barrido.ts` | 21 | §10 |
| `main.ts`, `log.ts`, `errores.ts` | 37 | frontera, cableado, R11.6, R11.7 |
| **Total** | **626** | código efectivo, sin comentarios ni blancos |

Sin `dockerode` ni equivalente (R11.4). **Cero dependencias de runtime**: solo la librería estándar
de Node. `typescript` y `@types/node` son de desarrollo, y los tests usan el runner nativo
(`node:test`), así que R11.3 se cumple de forma trivial — no hay ninguna librería parseando bytes
controlados por un atacante.

El JSON lo serializa y parsea `JSON.stringify` / `JSON.parse`: el de la spec lo escribimos nosotros
y el que parseamos viene del daemon, que ya es parte de la base de confianza.

## El golden test A1/A2

`test/fixtures/spec-create-referencia.json` es **el mismo archivo** que
`java sidecar test/src/test/resources/spec-create-referencia.json`, copiado sin cambios. Las dos
implementaciones producen bytes idénticos, y eso es lo que A2 pide.

Que coincidan no es casualidad de formato: `JSON.stringify` emite las claves de un objeto literal en
orden de inserción y Jackson hace lo mismo con `ObjectNode`, así que el orden del código fuente es el
orden de los bytes en las dos.

## Cobertura de §13

| Test | Dónde | Estado |
|---|---|---|
| A1, A2 | `spec.test.ts` | ✅ archivo de referencia compartido con Java, byte a byte |
| A3 | `ejecucion.test.ts` | ✅ contra el daemon de prueba |
| A4, A5 | `ejecucion.test.ts` | ✅ `COMPLETADA` con `exitCode != 0` |
| A6 | `scripts/verificar-a6.mjs` | ⏳ escrito; requiere un daemon de Docker real para correrlo |
| A7 | `ejecucion.test.ts` — `a7 r6.1 ...` | ✅ `raw-stream` es `ERROR_DAEMON` |
| A8–A12 | `demux.test.ts` | ✅ streams sintéticos |
| A20, A21 | `reporte.test.ts`, `ejecucion.test.ts` | ✅ invariante I5 |
| A26, A27 | `servidor.test.ts` | ✅ |
| A28 | `barrido.test.ts` | ✅ el barrido borra por edad (I6) |
| A29 | `servidor.test.ts` — `/salud` 503 | ✅ parcial: falta el `502` contra un daemon detenido de verdad |
| A30 | `servidor.test.ts` | ✅ parcial: el socket deja de aceptar; el proceso completo no se prueba |
| **A31** *(C2)* | `ejecucion.test.ts` | ✅ `oomKilled` del `inspect`, y el `inspect` que falla no aborta |
| **A32** *(C1)* | `spec.test.ts` | ✅ incluye el assert sobre las constantes de R4.1 |
| **A33** *(C3)* | `ejecucion.test.ts` | ✅ la escritura abandona y el cupo se libera |
| A13–A19, A22–A25 | — | fuera del alcance acordado: necesitan la imagen `sandbox-runner` de producción |

## §14 — la comparación que pide R14.0

Líneas efectivas medidas igual en las dos (`npm run contar` / la tabla del README de Java).

| Módulo | Java est. | Node est. | Java real | **Node real** |
|---|---|---|---|---|
| Cliente HTTP sobre socket Unix | 200–250 | 20–30 | 271 | **160** |
| Spec del contenedor | 60 | 50 | 90 | **63** |
| Demultiplexador | 60 | 60 | 53 | **43** |
| Extracción del reporte | — | — | 27 | **24** |
| Orquestación de la secuencia | 80 | 80 | 137 | **127** |
| Servidor HTTP + cola | 70 | 50 | 217 | **151** |
| Barrido de huérfanos | 40 | 40 | 26 | **21** |
| Cableado y frontera | — | — | 48 | **37** |
| **Total propio** | **~510–560** | **~300–310** | **869** | **626** |

**Node quedó en 626 líneas: un 28 % por debajo de Java, y aun así un 56 % por encima de su propia
estimación.** Los dos desvíos tienen la misma causa y conviene decirla junta:

- **Donde Node gana está donde la spec lo anticipaba.** El cliente HTTP baja de 271 a 160 y el
  servidor de 217 a 151 porque `node:http` habla sockets Unix de los dos lados: `socketPath` en el
  cliente y `listen(path)` en el servidor. Java paga ese costo dos veces (JDK-8377806, y
  `com.sun.net.httpserver` que solo bindea `InetSocketAddress`), y por eso tiene que escribir a mano
  el parseo de cabeceras, el `chunked` y el upgrade. Eso es exactamente lo que §14 predijo.
- **Donde Node no gana es donde el trabajo no es del lenguaje.** Demultiplexador, extracción del
  reporte, orquestación y barrido quedan casi iguales (43 vs 53, 24 vs 27, 127 vs 137, 21 vs 26).
  Son la lógica de la spec, y la lógica de la spec cuesta lo mismo en los dos.

**Sobre R11.5 (< 400 líneas): tampoco se cumple en Node.** La estimación original de ~300 líneas
contaba solamente los módulos donde la librería estándar hace el trabajo, y omitía la orquestación
de §5 y §8 —los siete pasos, los tres relojes distintos, la limpieza garantizada— que no la hace
ninguna librería. Con las dos implementaciones terminadas y fallando el mismo objetivo por el mismo
motivo, **el que hay que revisar es el objetivo, no las implementaciones**, que es lo que R14.0
anticipaba. Un número honesto para el mecanismo tal como está especificado son ~600 líneas.

Sigue en pie el argumento de fondo de R11.5: la corrección de un control de seguridad se establece
leyéndolo. 626 líneas se leen en una sesión; lo que R14.1 pide es que efectivamente se lean.

## Lo que queda abierto

- **A6 no se corrió todavía.** El script está escrito y la imagen de prueba también, pero hace falta
  un daemon de Docker accesible. Hasta que corra, §7 sigue siendo una hipótesis: *si falla, §7 hay
  que rediseñarla y bloquea todo lo demás*.
- **A13–A19 y A22–A25** (entrada hostil y código de alumno hostil) necesitan la imagen
  `sandbox-runner` de producción, que todavía no existe.
- **R14.1**: la revisión línea por línea, por dos personas que no la escribieron, sobre la
  implementación que se elija.

## Decisiones que conviene mirar en la revisión

Cuatro puntos donde la implementación toma una decisión que la spec no fija palabra por palabra:

1. **El tope de la escritura del paso 4 (R8.5)** es lo que queda del reloj de ejecución. La spec pide
   "su propio tope de tiempo" sin dar una constante; acotar por el mismo presupuesto deja al paso 5
   resolviendo en `TIMEOUT` sin margen extra. Java tomó la misma decisión.
2. **`vencido` en `ErrorDaemon`** distingue "la llamada agotó su reloj" de "el daemon falló". Sin esa
   marca, el paso 5 no puede separar un `TIMEOUT` de un `ERROR_DAEMON` sin dejar la petición colgada
   los `TIMEOUT_DAEMON_MS` de más que Java evita cancelando el hilo.
3. **`socket.unshift(head)` en el attach (R5.9)** es defensivo: como nunca leemos de ese socket, no
   hay stream que preservar. Se hace igual porque cuesta una línea y porque la regla existe
   justamente para el bug intermitente que produce ignorarlo.
4. **El UUID se valida contra la forma canónica estricta.** No es cosmética: el id se concatena en la
   URL de `create` como nombre del contenedor, así que cualquier laxitud ahí es una inyección en la
   petición al daemon. Hay un test que lo cubre con `.../../x`.
