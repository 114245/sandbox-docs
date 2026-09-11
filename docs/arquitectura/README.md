# `ms-sandbox` — índice de la documentación

> **Tema 06 — Sandbox / Runtime.** Grupo 8, 11 integrantes.
> UTN FRC · Programación 4 + Metodología de Sistemas 2 · TPI 2026.
>
> **Qué es este documento.** El mapa. Qué documento es fuente de verdad de qué, en qué orden
> conviene leerlos, y —lo más importante— **una única tabla de qué está decidido y qué sigue
> abierto**. Antes esa tabla estaba repartida en tres archivos que se contradecían entre sí.
>
> Última revisión: **8 de septiembre de 2026** — incorpora el **V4 del Grupo 5** y la respuesta que
> les mandamos.
>
> ### ⚠ El V4 mueve el piso de varios documentos
>
> El 7‑sep‑2026 llegó `Propuesta_Integracion_G5_G6_Entrypoint_V4.pdf` y le contestamos con
> [`otros/Respuesta_G8_a_Propuesta_V4.md`](../../otros/Respuesta_G8_a_Propuesta_V4.md), donde
> **aceptamos el modelo de dos capas y elegimos la Opción 1** (catálogo de perfiles). Eso corre la
> frontera de dominio: dejamos de ser *corredor de Java con opinión* y pasamos a ser
> **infraestructura agnóstica de lenguaje**.
>
> La consecuencia para este índice: **`03`, `07`, `08`, `06` y `09` están escritos contra el
> contrato anterior.** Siguen siendo la fuente de verdad de todo lo que el V4 no toca —aislamiento,
> transporte, nonce, worker, outbox— pero su contrato con T05 y su máquina de estados están en
> revisión. Qué se cae exactamente, documento por documento, está en
> [`11-impacto-v4-g5.md`](./11-impacto-v4-g5.md) §7.
>
> **La reescritura profunda de esos documentos espera la contestación de G5**, por decisión de
> `11` §13.6: escribirlos ahora sería escribirlos contra un contrato que todavía se mueve. Mientras
> tanto, cada uno lleva un aviso de revisión en su encabezado.

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
| **Entender la integración con el Grupo 5 y qué nos cambia** *(empezar acá)* | [`11-impacto-v4-g5.md`](./11-impacto-v4-g5.md) |
| **Entender dónde se verifica que hubo ejecución de verdad (D16)** | [`12-d16-evidencia-de-ejecucion.md`](./12-d16-evidencia-de-ejecucion.md) |
| Ver el sándwich contado largo, con glosario y los cuatro tipos de desafío | [`10-propuesta-g5-sandwich.md`](./10-propuesta-g5-sandwich.md) |
| Leer lo que efectivamente se le mandó al Grupo 5 | [`otros/Respuesta_G8_a_Propuesta_V4.md`](../../otros/Respuesta_G8_a_Propuesta_V4.md) |

---

## 2. Fuente de verdad

Cuando dos documentos digan cosas distintas sobre un mismo tema, **manda el de esta columna**.

| Tema | Fuente de verdad | Los demás lo referencian |
|---|---|---|
| Panorama de los 12 servicios del curso | [`01-panorama-microservicios-backend.md`](./01-panorama-microservicios-backend.md) | — |
| Aislamiento, origen de los tests, medición de recursos | [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) | `00`, `05`, `06` |
| **Contrato con T05, máquina de estados, frontera de dominio** | [`11-impacto-v4-g5.md`](./11-impacto-v4-g5.md) **manda sobre `03` §5.1, §6 y §7** | — (se lo sacó a `03` el 8‑sep; ver `11` §7) |
| El worker: cola, DLQ, concurrencia, outbox, **la frontera con el ejecutor** | [`04-ms-sandbox-worker.md`](./04-ms-sandbox-worker.md) | `00`, `06`, `07` |
| Patrones de microservicios aplicados | [`05-ms-sandbox-patrones.md`](./05-ms-sandbox-patrones.md) | `00` |
| Vistas y diagramas | [`06-arquitectura-en-diagramas.md`](./06-arquitectura-en-diagramas.md) | `00` |
| La API y el outbox | [`07-arquitectura-api.md`](./07-arquitectura-api.md) | `04`, `06` |
| **El ejecutor: contrato, spec del contenedor, constantes** | [`08-spec-ejecutor.md`](./08-spec-ejecutor.md) | `04` §6 y §12, `05` §1, `06` §2 |
| Explicación divulgativa del worker ↔ ejecutor y del recorrido del tar | [`09-worker-ejecutor-explicado.md`](./09-worker-ejecutor-explicado.md) | — (no es fuente de verdad: si contradice a `04` o `08`, mandan ellos) |
| **Integración con el Grupo 5: el sándwich, el diff contra el V4, el catálogo de perfiles** | [`11-impacto-v4-g5.md`](./11-impacto-v4-g5.md) | `10` (**documento único y autocontenido** de la integración; cuando G5 conteste, lo que sobreviva se muda a `03`, `07` y `08`) |
| El sándwich contado largo: glosario, los cuatro tipos de desafío, la película completa | [`10-propuesta-g5-sandwich.md`](./10-propuesta-g5-sandwich.md) | — (versión **extendida y divulgativa** del `11` §2; si contradice al `11`, manda el `11`) |
| **Verificación de evidencia de ejecución: quién la hace y qué pasa si falla** | [`12-d16-evidencia-de-ejecucion.md`](./12-d16-evidencia-de-ejecucion.md) | `04` §6, `08` §5 (**propuesta cerrada, falta confirmar** — ver D16) |
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

> **⚠ D6 en revisión por el V4, y menos grave de lo que parecía.** El principio sigue en pie —el
> veredicto sale del reporte, nunca del código de salida— y **el lugar donde se decide tampoco se
> mueve**: el análisis de **D16** ([`12`](./12-d16-evidencia-de-ejecucion.md)) encontró que la
> verificación ya vive en el worker desde antes del V4, y que el conteo de la tapa era una copia
> redundante. Lo que se cae es esa copia, más el enunciado: D6 nombra a JUnit, y bajo el sándwich la
> regla se reformula como **"no hay `EXITO` sin evidencia legible de que corrió al menos una unidad
> de evaluación"**, con el formato declarado por el perfil.

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

### Abiertas por el V4 del Grupo 5

Todas nacen de [`11-impacto-v4-g5.md`](./11-impacto-v4-g5.md) §12. Las que dicen "Nosotros + T05"
están planteadas en la respuesta que ya les mandamos y esperan contestación.

| # | Decisión | Estado | Quién la cierra |
|---|---|---|---|
| **D16** | **¿Dónde se verifica "hay evidencia real de ejecución" cuando la tapa no sabe leer el reporte?** **Analizada y con propuesta cerrada el 8‑sep‑2026** en [`12-d16-evidencia-de-ejecucion.md`](./12-d16-evidencia-de-ejecucion.md): la verificación **ya estaba en el worker** —la tapa sólo tenía una copia redundante—, así que lo que se hace es borrar esa copia e indexar la del worker por `reportFormat`. Reemplaza a D6 bajo el sándwich | **Falta que el equipo confirme tres cosas** (`12` §9): la Opción C, el **fail-closed** (formato ilegible ⇒ `ERROR_INTERNO`, nunca `EXITO`) y que el criterio de aceptación de P9 se mida **en veredictos, no en códigos de salida**. Confirmadas, desbloquea P9 | Nosotros |
| **D17** | **¿Aceptamos la Opción 1 (catálogo de perfiles)?** Propuesta: sí, con perfiles **inmutables y versionados** (`POST` crea versión nueva, `PUT` se rechaza) y ciclo `BORRADOR → VALIDADA → ACTIVA → DEPRECADA` | Mandada en la respuesta §4.1. Espera confirmación | Nosotros + T05 |
| **D18** | **¿Los límites viven en el perfil o en el request?** Propuesta: **en el perfil**, con presupuestos separados de compilación y evaluación, y el reloj del alumno medido en **tiempo de CPU**, no de pared | Mandada en la respuesta §4.3. Espera confirmación | Nosotros + T05 |
| **D19** | **Bandas de códigos de salida:** `0` y `40–59` de ellos (su tabla), `20–31` nuestros, hueco `32–39`, cualquier otro es error de infraestructura | Mandada en la respuesta §2.3. Espera la tabla del `40–59` (**A3**) | Nosotros + T05 |
| **D20** | **Modelo asincrónico.** El V4 no lo menciona. Propuesta: el resultado viaja como evento `EjecucionFinalizada` por el bus de la plataforma (Kafka, del grupo de notificaciones), con el **resumen y no el detalle**; el reporte completo se busca por `GET` | Mandada en la respuesta §6. **Pendiente de contexto:** `07` §3.4 tiene el relay publicando a **RabbitMQ**, que es nuestra cola interna (`04` §3). Cómo se articula con el bus de la plataforma se resuelve con el panorama completo de eventos, no acá | Nosotros + T05 |
| **D21** | **Catálogo de imágenes base:** quién las nombra, quién las versiona, quién aprueba una nueva. Incluye si la base pasa a ser **Alpine** — nuestro entrypoint está en **bash** y el shell reducido de Alpine no lo corre tal cual | Abierta. No depende de G5 | Nosotros |

### Preguntas abiertas del contrato

Ninguna se resuelve rehaciendo código nuestro (`11` §12).

| # | Pregunta | Quién la contesta |
|---|---|---|
| **A1** | ¿El límite de CPU es por proceso o agregado? | Nosotros, con un caso de prueba |
| **A2** | ¿Cuánta CPU y memoria consumen realmente PMD y ArchUnit? | **El Grupo 5**, con mediciones |
| **A3** | ¿Qué etiquetas de fase y qué códigos `40–59` definen? | El Grupo 5 |
| **A4** | ¿Cuántos perfiles arrancamos, y con qué contenido? | Los dos |
| **A5** | **¿El botón "Ejecutar" del IDE pasa por el sandbox?** (definición 8 del `03` §7) | T05. Sigue abierta, el V4 no la toca, y **cambia el dimensionamiento por completo** |

---

## 4. Pendientes técnicos

| # | Pendiente | Estado |
|---|---|---|
| P2 | Casos hostiles a nivel **tar**: enlaces simbólicos y duros | Abierto, y **subió de prioridad con el V4.** Necesitan un harness que fabrique el tar a mano, porque `run.sh` lo arma solo. Además hay que **reemplazar la lista blanca de rutas** (hoy sólo acepta lo que empiece con `src/` o `test/`) por prohibiciones sobre el **tipo** de entrada: con `run.sh` en la raíz y configuraciones de G5 en cualquier lado, esa lista no sobrevive, y mientras era angosta tapaba el hueco de los enlaces (`11` §10) |
| P3 | La **suite hostil corriendo en CI** | Abierto. La suite existe y pasa 9/9; falta el pipeline. **Sumar ahí `scripts/integracion-runner.mjs`**, que es lo que cierra P0 y lo mantiene cerrado |
| P4 | Contrato **OpenAPI** y un stub para T05 | Abierto |
| P5 | Cachear la suite de tests compilada por versión de desafío | Bloqueado por **D13** |
| P6 | **R14.1** — revisión línea por línea de la implementación elegida, por dos personas que no la escribieron | Bloqueado por **D9** |
| **P9** | *(bloqueo en revisión: D16 tiene propuesta cerrada, ver `12`)* **Refactor del `entrypoint.sh` al sándwich:** sacar los pasos 2–5 (todo el conocimiento de Java —`javac` en dos fases, derivación de nombres de clase, `--select-class`, `--include-classname`— se va al `run.sh` de G5), invocar `cd /work/in && sh ./run.sh`, y generalizar el sobre: `exitCodeJava`, `clasesTest` y `testsEnReporte` dejan de tener sentido como campos fijos | **Bloqueado por D16.** No espera a G5: `11` §9 muestra que el mecanismo del contenedor es idéntico en las tres opciones. El criterio de aceptación ya está escrito en `11` §10 — `ok-suma`, `hostil-exit0`, `hostil-cpu` y `hostil-reporte-loop` tienen que dar **exactamente el mismo veredicto que hoy** |
| **P8** | **Los bundles hostiles tienen rutas hardcodeadas.** Al mover el reporte a `/work`, `hostil-reporte` y `hostil-reporte-loop` siguieron apuntando a `/tmp/reports` y **el ataque se desarmó solo**: la suite daba verde sin probar nada. Corregido, pero el acoplamiento sigue | **Mitigado, no resuelto.** La ruta debería salir de una variable que el entrypoint exporte, o el test debería fallar si el ataque no llega a destino |

### Cerrado

| # | Pendiente | Cómo cerró |
|---|---|---|
| **P0** | **El ejecutor y la imagen del runner nunca se corrieron juntos** | **Cerrado el 4-sep-2026.** El ejecutor Node contra `sandbox-runner:1.0.0` con `bundles/ok-suma`: `COMPLETADA`, `exitCode 0`, 3 tests, 0 fallas, veredicto `EXITO`. Arnés versionado en `integracion-runner.mjs`, del prototipo Node, eliminado del repo; ver historial de git |
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
| `ms-sandbox/imagenes/` | Las imágenes de ejecución, alineadas con la spec `08` §4.1: `java21-junit/Dockerfile` (capa 1 + herramientas) y `capa1/capa1.sh` (el entrypoint, con el nonce de §7) — **9/9 casos hostiles contenidos** (ver `ms-sandbox/pruebas/`). El `Dockerfile`, `entrypoint.sh`, `run.sh`, `build.sh`, `suite-hostil.sh` y `ver-reporte.sh` de una sola capa se eliminaron del repo al pasar al modelo de dos capas; ver historial de git |
| `ms-sandbox/perfiles/` | El catálogo de perfiles: `java21-junit@3.json` y el script de la capa 2 que referencia, `java21-junit.sh` |
| `ms-sandbox/pruebas/` | El banco de pruebas: `bundles/` (los nueve casos, camino feliz y hostiles) y `probar-capa1.sh`, el arnés de dos capas |
| `ms-sandbox/ejecutor/` | Implementación del ejecutor en Java 21. **869 líneas** efectivas, 58 tests. Verificada contra la imagen real corriéndola **como contenedor** (ver nota abajo) |
| `node-sidecar-test/` *(eliminado)* | Implementación del ejecutor en Node 22 + TypeScript, **626 líneas** efectivas, 65/65 tests, quedó de lado al validar la Opción 1 en Java; se eliminó del repo, ver historial de git |
| `hallazgos-investigacion-sandbox.md` | Investigación externa sobre el sandbox: CVEs de Judge0 y Ares, papers, fuentes. Lo que trajo está marcado **[IE]** en los documentos |
| `docs/respuesta-sidecar-ejecutor.md` | Respuesta a la investigación del **ejecutor**: el rediseño que elimina el `attach` hijacked, y tres correcciones al briefing |
| `docs/briefing-investigacion-*.md` | Los dos briefings autocontenidos que se llevaron a fuentes externas |
| `otros/Contrato_Sandbox_Tema05.md` | **⚠ OBSOLETO.** El primer borrador del contrato con T05: Python 3.11, `source_code` único y `test_cases[]` con `expected_stdout`. Nada de eso sobrevive al V4. Sobrevive sólo el **modelo asincrónico** y la distinción `infra_error` vs. falla del alumno. Lo reemplaza `otros/Respuesta_G8_a_Propuesta_V4.md` §2 y §6 |
| `otros/Respuesta_G8_a_Propuesta_V4.md` | **La respuesta que le mandamos al Grupo 5** el 7‑sep‑2026: aceptamos el modelo de dos capas y la Opción 1, y les pasamos la mitad del contrato del contenedor que no conocían (buzón de salida, códigos de salida, desvío de `stdout`, qué hace la capa 1 después). Tiene también su PDF |
| `Propuesta_Integracion_G5_G6_Entrypoint_V4.pdf` | Lo que mandó el Grupo 5. Analizado en `11` |
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
