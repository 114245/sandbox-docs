# `ms-sandbox` — índice de la documentación

> **Tema 06 — Sandbox / Runtime.** Grupo 8, 11 integrantes.
> UTN FRC · Programación 4 + Metodología de Sistemas 2 · TPI 2026.
>
> **Qué es este documento.** El mapa. Qué documento es fuente de verdad de qué, en qué orden
> conviene leerlos, y —lo más importante— **una única tabla de qué está decidido y qué sigue
> abierto**. Antes esa tabla estaba repartida en tres archivos que se contradecían entre sí.
>
> Última revisión: **4 de septiembre de 2026** — incluye la integración ejecutor + imagen real (P0).

---

## 1. Por dónde empezar

| Si querés… | Leé |
|---|---|
| Entender el servicio completo de una sentada | [`00-propuesta-ms-sandbox.md`](./00-propuesta-ms-sandbox.md) |
| Ver la arquitectura dibujada | [`06-arquitectura-en-diagramas.md`](./06-arquitectura-en-diagramas.md) |
| Entender cómo se ejecuta el código aislado | [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) |
| Entender la mitad que no ejecuta nada | [`07-arquitectura-api.md`](./07-arquitectura-api.md) |
| Implementar el ejecutor | [`08-spec-ejecutor.md`](./08-spec-ejecutor.md) |
| Preparar la defensa de la unidad de patrones | [`05-ms-sandbox-patrones.md`](./05-ms-sandbox-patrones.md) |
| **Entender cómo se hablan el worker y el ejecutor, sin dar nada por sabido** | [`09-worker-ejecutor-explicado.md`](./09-worker-ejecutor-explicado.md) |
| **Entender qué nos pidió el Grupo 5 y qué le vamos a contestar** *(abierto)* | [`10-propuesta-g5-sandwich.md`](./10-propuesta-g5-sandwich.md) |

---

## 2. Fuente de verdad

Cuando dos documentos digan cosas distintas sobre un mismo tema, **manda el de esta columna**.

| Tema | Fuente de verdad | Los demás lo referencian |
|---|---|---|
| Panorama de los 12 servicios del curso | [`01-panorama-microservicios-backend.md`](./01-panorama-microservicios-backend.md) | — |
| Aislamiento, contrato con T05, origen de los tests, veredictos | [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) | `00`, `05`, `06` |
| El worker: cola, DLQ, concurrencia, outbox, **la frontera con el ejecutor** | [`04-ms-sandbox-worker.md`](./04-ms-sandbox-worker.md) | `00`, `06`, `07` |
| Patrones de microservicios aplicados | [`05-ms-sandbox-patrones.md`](./05-ms-sandbox-patrones.md) | `00` |
| Vistas y diagramas | [`06-arquitectura-en-diagramas.md`](./06-arquitectura-en-diagramas.md) | `00` |
| La API y el outbox | [`07-arquitectura-api.md`](./07-arquitectura-api.md) | `04`, `06` |
| **El ejecutor: contrato, spec del contenedor, constantes** | [`08-spec-ejecutor.md`](./08-spec-ejecutor.md) | `04` §6 y §12, `05` §1, `06` §2 |
| Explicación divulgativa del worker ↔ ejecutor y del recorrido del tar | [`09-worker-ejecutor-explicado.md`](./09-worker-ejecutor-explicado.md) | — (no es fuente de verdad: si contradice a `04` o `08`, mandan ellos) |
| **Integración con el Grupo 5: el `run.sh` inyectado y la contrapropuesta del sándwich** | [`10-propuesta-g5-sandwich.md`](./10-propuesta-g5-sandwich.md) | — (**estado ABIERTO**: nada de lo que dice está decidido; cuando se cierre, se muda a `08`) |
| Estado de decisiones | **este archivo** | todos |

**Nota sobre el `07`.** Hay dos: [`07-arquitectura-api.md`](./07-arquitectura-api.md) (documento, 652 líneas)
y [`07-arquitectura-api-lamina.md`](./07-arquitectura-api-lamina.md) (lámina de defensa, 526 líneas).
Cuentan lo mismo en dos registros. **La fuente de verdad es el documento**; la lámina se regenera
a partir de él. Unificarlos o separarlos formalmente sigue abierto (D14 abajo).

---

## 3. Estado de las decisiones

### Cerradas

| # | Decisión | Dónde vive | Cerrada |
|---|---|---|---|
| **D1** | **El sidecar es un ejecutor, no un proxy del socket.** Expone `POST /ejecutar`, arma él mismo la spec del contenedor —hardcodeada— y el worker deja de hablar Docker. Descartado `tecnativa/docker-socket-proxy` | `08` §1 (invariante P1), `05` §1, `06` §2 | 1-sep-2026 |
| **D2** | **El relay del outbox corre en los dos perfiles**, `api` y `worker`, cada uno publicando lo suyo. Antes estaba limitado a `api` con `@Profile` | `04` §10, `07` §3 | 1-sep-2026 |
| **D3** | **`VERSION_API_DOCKER = v1.43`**, fijada explícita en el path. El mínimo de API varía entre builds del Engine; no es una regla de 29.x. Verificado contra Docker real | `08` §4.3, §5 | 3-sep-2026 |
| **D4** | **El transporte hacia el ejecutor es un socket Unix**, no TCP. TCP queda documentado como variante degradada, con secreto compartido y red `internal` | `08` §2 | 2-sep-2026 |
| **D5** | **El bundle entra por `stdin`**, no por `docker cp`. Saca del camino el endpoint más peligroso y elimina el `attach` bidireccional | `03` §1.5, `08` §5 | 30-ago-2026 |
| **D6** | **El veredicto sale del XML de JUnit con `tests > 0`**, nunca del exit code. Verificado: `System.exit(0)` aprobaba | `03` §5.1, `00` §8 | 27-ago-2026 |

> Las fechas de **D1–D3** son las de la decisión explícita; las de **D4–D6** son las del documento
> que las fija, que es lo más preciso que tenemos.

### Abiertas

| # | Decisión | Estado | Quién la cierra |
|---|---|---|---|
| **D7** | **`VEREDICTO_NO_CONFIABLE`: ¿consume vida?** El estado existe en el runner (exit 30) pero no está en el contrato de `03` §5.1 ni acordado con T10 | Propuesta escrita en `06` §7. Falta verificar con un caso honesto que use `Runtime.exec` legítimamente | Nosotros + T10 |
| **D8** | **`TIMEOUT`: ¿uno o dos en el contrato?** El runner distingue `TIMEOUT_CPU` de `TIMEOUT_PARED`; el contrato tiene uno solo | Propuesta en `06` §7: uno solo, con `subtipo` informativo | Nosotros |
| **D9** | **Lenguaje del ejecutor: Java o Node.** Las dos implementaciones existen y pasan la spec | **Decisión de equipo**, con los once. El peso de la imagen **no** es criterio; sí lo es qué puede auditar el equipo (R14.1) | Los 11 |
| **D10** | **Política de `stdout` en modo `COMPLETO`.** Canal de fuga: la salida de un test oculto se mezcla con la de los visibles | Abierto. Cambia la forma del reporte, conviene cerrarlo antes de congelar el contrato | Nosotros + T05 |
| **D11** | **¿El sandbox filtra por visibilidad, o devuelve todo y filtra T05?** La investigación externa recomienda lo segundo | Tensión no resuelta, documentada en `03` §6 | Nosotros + T05 |
| **D12** | **Paquete reservado de los tests.** Sin que T05 lo declare, no podemos rechazar una solución que lo usurpe | Abierto. Mitigado —no cerrado— por el orden del classpath | T05 |
| **D13** | **¿Una versión publicada de un desafío es inmutable?** Bloquea el cacheo de la suite compilada | Abierto | T03 |
| **D14** | **Unificar o separar formalmente los dos `07`** | Abierto | Nosotros |
| **D15** | **¿1 CPU o 2 por contenedor?** `08` §4.1 fija `NanoCpus: 1000000000` (**1 CPU**); `03` §1.4a y §4.3 y `04` §11 dicen **2** | **Medido de nuevo el 4-sep-2026, ahora de punta a punta:** el mismo bundle tarda **8.4 s con 1 CPU** (compilación 5.1 s) contra **3.3 s con 2 CPU**. No rompe —los relojes de adentro son 20 s por compilación y 60 s de pared— pero se come la mitad del presupuesto sin necesidad. Cambiarlo rompe el golden test A1 a propósito | Nosotros |

---

## 4. Pendientes técnicos

| # | Pendiente | Estado |
|---|---|---|
| P2 | Casos hostiles a nivel **tar**: enlaces simbólicos y duros | Abierto. Necesitan un harness que fabrique el tar a mano, porque `run.sh` lo arma solo |
| P3 | La **suite hostil corriendo en CI** | Abierto. La suite existe y pasa 9/9; falta el pipeline. **Sumar ahí `scripts/integracion-runner.mjs`**, que es lo que cierra P0 y lo mantiene cerrado |
| P4 | Contrato **OpenAPI** y un stub para T05 | Abierto |
| P5 | Cachear la suite de tests compilada por versión de desafío | Bloqueado por **D13** |
| P6 | **R14.1** — revisión línea por línea de la implementación elegida, por dos personas que no la escribieron | Bloqueado por **D9** |
| **P8** | **Los bundles hostiles tienen rutas hardcodeadas.** Al mover el reporte a `/work`, `hostil-reporte` y `hostil-reporte-loop` siguieron apuntando a `/tmp/reports` y **el ataque se desarmó solo**: la suite daba verde sin probar nada. Corregido, pero el acoplamiento sigue | **Mitigado, no resuelto.** La ruta debería salir de una variable que el entrypoint exporte, o el test debería fallar si el ataque no llega a destino |

### Cerrado

| # | Pendiente | Cómo cerró |
|---|---|---|
| **P0** | **El ejecutor y la imagen del runner nunca se corrieron juntos** | **Cerrado el 4-sep-2026.** El ejecutor Node contra `sandbox-runner:1.0.0` con `bundles/ok-suma`: `COMPLETADA`, `exitCode 0`, 3 tests, 0 fallas, veredicto `EXITO`. Arnés versionado en `node-sidecar-test/scripts/integracion-runner.mjs` |
| **P1** | El entrypoint no implementaba el nonce de `08` §7 | **Cerrado.** Implementado y verificado: lee la primera línea de stdin en una variable no exportada y emite el reporte entre `---SANDBOX-<nonce>-INICIO/FIN---`. El `read` de bash no consume más allá del `
`, que era lo que §7 pedía probar |
| **P1b** | La spec montaba el tmpfs en `/work` y el entrypoint escribía en `/tmp` | **Cerrado a favor de la spec.** El entrypoint escribe todo bajo `/work`; `HOME` y `WORKDIR` acompañan |
| **P7** | La implementación **Java** seguía con la spec vieja del tmpfs | **Cerrado el 4-sep-2026.** Fix aplicado (más una segunda aserción propia en `SpecTest` que Node no tenía), golden A1 regenerado —**byte a byte idéntico al de Node**, como pide la spec— y **58 tests en verde**. Verificado además contra la imagen real: `ok-suma`, `hostil-exit0`, `hostil-cpu` y `hostil-memoria`, los cuatro con el mismo veredicto que Node |

**Resuelto y sacado de la lista:** validar el paquete del alumno (queda contenido por el orden del
classpath — sigue siendo deseable como defensa en profundidad, pero ya no es lo único que separa al
veredicto de ser falso), los tres relojes y el ulimit `cpu` (fijados en `08` §4), y el bloqueo de
`VERSION_API_DOCKER` (**D3**).

---

## 5. Qué hay fuera de `docs/arquitectura/`

| Ruta | Qué es |
|---|---|
| `sandbox/runner/` | La imagen de ejecución, alineada con la spec `08` §4.1: `Dockerfile`, `entrypoint.sh` (con el nonce de §7), `run.sh` y `suite-hostil.sh` — **9/9 casos contenidos** |
| `java sidecar test/` | Implementación del ejecutor en Java 21. **869 líneas** efectivas, 58 tests. Verificada contra la imagen real corriéndola **como contenedor** (ver nota abajo) |
| `node-sidecar-test/` | Implementación del ejecutor en Node 22 + TypeScript. **626 líneas** efectivas, 65/65 tests. Verificada contra la imagen real (`scripts/integracion-runner.mjs`) |
| `hallazgos-investigacion-sandbox.md` | Investigación externa sobre el sandbox: CVEs de Judge0 y Ares, papers, fuentes. Lo que trajo está marcado **[IE]** en los documentos |
| `docs/respuesta-sidecar-ejecutor.md` | Respuesta a la investigación del **ejecutor**: el rediseño que elimina el `attach` hijacked, y tres correcciones al briefing |
| `docs/briefing-investigacion-*.md` | Los dos briefings autocontenidos que se llevaron a fuentes externas |
| `otros/Contrato_Sandbox_Tema05.md` | El contrato con T05 |
| `docs/_fuentes/` | Propuesta de la cátedra, láminas, y las versiones de los documentos previas a la investigación |

> **Cómo se corre cada una en Windows.** El cliente de Docker de **Node** habla tanto sockets Unix como
> *named pipes*, así que se corre nativa apuntando a `\.\pipe\dockerDesktopLinuxEngine`. El de **Java**
> habla **sólo AF_UNIX** (`UnixDomainSocketAddress`), que no alcanza un *named pipe*: hay que correrla
> **como contenedor**, con `/var/run/docker.sock` montado y un volumen compartido para su propio socket.
> Es más incómodo para probar y **más parecido al despliegue real**. Vale como insumo para [D9](#3-estado-de-las-decisiones).
>
> Las dos implementaciones del ejecutor son deliberadas: la spec `08` es el contrato
> **independiente del lenguaje**, y que dos equipos la implementen por separado y pasen la misma
> suite es la evidencia de que la spec está bien escrita. La elección entre las dos es **D9**.
