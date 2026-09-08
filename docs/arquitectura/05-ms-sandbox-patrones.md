# `ms-sandbox` — Patrones de microservicios aplicados

> **Tema 06 — Sandbox / Runtime.**
> Los patrones de la unidad de Microservicios, cruzados contra este servicio: cuáles aplican, **cómo** aplican, y cuáles **no aplican y por qué**.

**Premisa del documento:** el error clásico de TP es forzar los diez patrones. Un profesor lo nota. Decir *"este no aplica, y este es el motivo"* con fundamento vale más que un Circuit Breaker decorativo.

> **Estado de las decisiones:** [`README.md`](./README.md). Si algo de acá se contradice con ese archivo, manda el README.

---

## Veredicto rápido

| Patrón | ¿Aplica a `ms-sandbox`? | Dónde |
|---|---|---|
| **Sidecar** | ⭐ **Sí, y es el mejor caso del tema** | El **ejecutor**: el worker deja de hablar Docker |
| **Health Check** | ⭐ **Sí, con un giro propio** | Saturado ≠ enfermo; ejecución canaria |
| **Circuit Breaker** | ⭐ **Sí, pero no donde uno espera** | Sobre el ejecutor y el demonio, no sobre otro servicio |
| **DDD** | Sí, con matiz | Bounded context limpísimo + **ACL** sobre JUnit |
| **Rate Limit** | Sí | Concurrencia por alumno > requests por segundo |
| **EDA** | Sí | Productor y consumidor; además el broker **es** la cola de trabajo |
| **SAGA** | Parcial — un **paso**, no un coordinador | No hay nada que compensar |
| **Service Discovery** | Sí, con una observación fina | El **worker no se registra** |
| **API Gateway** | Dado, poco propio | Lo que sí se define: qué **no** se expone |
| **BFF** | ❌ **No aplica** | Es capa de frontend; vive en T05 |

---
---

# Parte I — Donde el tema brilla

## 1. Sidecar — el ejecutor de contenedores

Es **el mejor patrón del tema**, porque resuelve un problema real y específico, no uno genérico. Y porque el camino que se recorrió para resolverlo —empezar por el sidecar de manual, encontrarle el agujero, e invertirlo— es más defendible que la solución sola.

> **Estado: decidido.** El sidecar de `ms-sandbox` es un **ejecutor**, no un proxy del socket ([`README.md`](./README.md) D1). Su contrato completo, independiente del lenguaje, está en [`08-spec-ejecutor.md`](./08-spec-ejecutor.md). Esta sección cuenta **por qué**, que es lo que se defiende.

### El problema

El worker necesita lanzar contenedores, así que alguien necesita `/var/run/docker.sock`. Y ese socket no es "un socket con permisos": es la **API HTTP completa de Docker**, que **no tiene modelo de autorización**. No hay usuarios, ni roles, ni scopes. Quien puede hablarle puede todo, incluido `POST /containers/create` con `Privileged: true` y `Binds: ["/:/host"]`, que es root en el host en una sola llamada.

> **El sidecar no es "un socket más seguro". Es la capa de autorización que Docker no trae.**

### La solución: el sidecar no filtra la API de Docker, la reemplaza

```
┌──────────────── misma unidad de despliegue ─────────────────┐
│                                                              │
│   ms-sandbox-worker  ──POST /ejecutar──►  ejecutor           │
│   (SIN el socket)     socket Unix          (ÚNICO con el     │
│                       /run/ejecutor/        socket)          │
│                                        │                     │
└────────────────────────────────────────┼─────────────────────┘
                                         ▼
                                  /var/run/docker.sock
```

El worker manda el tar de la entrega y un `X-Ejecucion-Id`. **Nada más.** No manda imagen, ni memoria, ni timeout, ni red, ni comando. El ejecutor arma la spec del contenedor con constantes escritas en su propio código fuente, corre los siete pasos y devuelve el resultado crudo — `stdout`, `stderr`, el reporte, `exitCode`, `oomKilled` — sin emitir veredicto.

Esa propiedad tiene nombre en la spec y todo lo demás está subordinado a ella:

> **P1 — Invariante de spec fija.** Ningún byte de la spec del contenedor proviene de quien llama. La configuración de seguridad del contenedor está escrita en el código fuente del ejecutor y es idéntica en todas las ejecuciones.

El costo es real y se acepta explícitamente: **cambiar el límite de memoria requiere recompilar y redesplegar el ejecutor.** Si alguna vez se agrega un parámetro que modifique la spec, el componente dejó de ser un ejecutor y volvió a ser un proxy de la API de Docker.

### La alternativa que descartamos, y por qué el recorte es el argumento

El diseño anterior era el sidecar de manual: **`tecnativa/docker-socket-proxy`**, HAProxy con una lista blanca por variables de entorno, sin escribir una línea de código.

```yaml
docker-socket-proxy:
  image: tecnativa/docker-socket-proxy
  environment:
    CONTAINERS: 1      # /containers/*
    POST: 1            # sin esto, la API queda read-only
    EXEC: 0            # docker exec: NO
    IMAGES: 0
    VOLUMES: 0
    NETWORKS: 0
```

Es tentador —*"usamos el componente estándar, acá está la lista blanca"*— y tiene un agujero que no se puede tapar. HAProxy filtra por **path y método HTTP**. No mira el cuerpo del request.

| Lo que hace falta | ¿El proxy de estantería lo hace? |
|---|---|
| Permitir `create`, `start`, `kill`, `inspect`, `rm`, `logs` | **Sí.** Es filtrado por path y método |
| Negar `exec`, `commit`, `build`, `push` | **Sí.** Ídem |
| Negar volúmenes y redes | **Sí.** Son familias de endpoints enteras |
| Limitarse a contenedores con el label `sandbox.job` | **No.** El label viaja en el body del `create` y en el query del `list` |
| Negar `Privileged: true` o `Binds: ["/:/host"]` | **No.** Son campos del JSON del body |

Esa última fila es el agujero: como `POST /containers/create` **hay que permitirlo** —es el trabajo del worker—, quien hable con el proxy puede pedir un contenedor privilegiado con `/` montado. El proxy reduce la superficie de la API; no vuelve seguro el socket.

### [IE] La evidencia que convierte la inversión en el argumento fuerte del tema

La investigación externa reubicó la decisión: **invertir el sidecar no es un workaround por falta de tooling, es la mitigación estructural de una clase de vulnerabilidad documentada.**

Primero, el dato que corrige una omisión nuestra: **sí existe el mecanismo estándar para filtrar el body**, y no es un proxy. Docker tiene un framework de **authorization plugins (AuthZ)**, con [`opa-docker-authz`](https://github.com/open-policy-agent/opa-docker-authz) como implementación de referencia, que evalúa políticas Rego sobre el body (`input.Body.HostConfig.Privileged`). O sea, el proxy tipo `tecnativa` es la versión pobre del mecanismo real.

Segundo, y es lo que importa: **ese mecanismo falló tres veces en ocho años, siempre por la misma razón.**

| CVE | Cómo se bypassea |
|---|---|
| **CVE-2018-16398** | Twistlock AuthZ Broker manejaba mal los regex: `containers/aa/pause?aaa=\/start` pasaba una política que permitía `start` y prohibía `pause`. **Es un bypass del filtrado por path — el enfoque exacto de `tecnativa`** |
| **CVE-2024-41110** (crítico) | Con `Content-Length: 0` el daemon reenvía el request al plugin **sin body**: el plugin no ve nada y aprueba, el daemon procesa el body completo. Descubierto en 2018, parcheado en 18.09.1, **el fix no se propagó a 19.03+**, y la regresión se detectó recién en abril de 2024. **Cinco años de ventana** |
| **CVE-2026-34040** (abril 2026) | Si el body supera 1 MB, el middleware de Docker lo descarta **antes** de pasárselo al plugin. Afecta a **todos** los plugins por igual —OPA, Casbin, custom— porque el bug está en Docker, no en el plugin |

El patrón es único y se puede enunciar en una frase:

> **Filtrar el body es un problema de parseo sobre input influido por el atacante, y las tres veces falló por lo mismo: el filtro y el daemon no interpretan el mismo byte stream.** Un sidecar que **no acepta una spec de contenedor** no tiene ese problema, porque no tiene nada que parsear.

Y hay un tercer beneficio, que conecta con el aislamiento: la clase de bug dominante de `runc` —las races de procfs y mounts, tres CVEs a la vez en noviembre de 2025— **requiere en general que el atacante controle la spec del contenedor**. Un ejecutor con la spec hardcodeada no solo previene `Privileged: true`: **cierra esa familia entera** ([`03`](./03-ms-sandbox-ejecucion.md) §1.1).

**Dato adicional, incómodo para la opción de estantería:** el README de `tecnativa/docker-socket-proxy` señala que el propio proxy necesita `--privileged` en algunos entornos SELinux/AppArmor. **Un componente de seguridad que corre privilegiado para poder proteger es difícil de defender** — y es exactamente lo que convirtió los bugs de lógica de Judge0 en takeover del host.

> **Cómo presentarlo:** la inversión del sidecar no es *"más código propio a cambio de no depender de la lista blanca"*. Es *"eliminamos por construcción dos clases de vulnerabilidad documentadas: el bypass de AuthZ por desincronización de parseo, y la configuración maliciosa de `runc`"*. Es el argumento más fuerte del tema, y tiene tres CVEs y un historial de cinco años detrás.

### El residual, dicho antes de que lo pregunten

El ejecutor sigue siendo el proceso que tiene el socket, así que comprometerlo es comprometer el host. Lo que cambia es **quién puede llegar a él** y **qué puede pedirle**:

- **El contenedor del alumno no lo alcanza.** Corre con `--network none` y sin el socket montado; no llega ni aunque escape de la JVM. Es consecuencia de la spec, no algo que el ejecutor verifique en runtime.
- **El worker sí lo alcanza, pero no puede expresar nada peligroso.** Su único verbo es "corré este tar". Un worker comprometido consigue ejecutar código en un contenedor que ya iba a existir igual, con los mismos límites.
- **La superficie de red es un socket Unix**, no un puerto TCP ([`08`](./08-spec-ejecutor.md) §2). Un endpoint HTTP sin autenticación en una red de Docker es alcanzable por todos los contenedores de esa red; el socket reduce el conjunto a "quien tenga el volumen montado".

Y una honestidad más, porque un tribunal la puede pedir: el ejecutor **no valida ni desempaqueta el tar**. Extraerlo significaría parsear input hostil adentro del proceso privilegiado, que es justo lo que el diseño evita. La validación estructural del bundle es del worker, aguas arriba; el ejecutor solo impone un tope de bytes.

### En qué lenguaje se escribe

Que el sidecar esté en otro lenguaje que la aplicación **no es una inconsistencia: es parte del patrón.** Un sidecar es deliberadamente agnóstico del lenguaje y tiene ciclo de vida propio — de hecho el otro sidecar previsto, el collector de OpenTelemetry, tampoco es Java.

**[IE] La lectura se sostiene, y conviene tener la cita a mano.** La agnosticidad de lenguaje no es un efecto colateral tolerado del patrón: es uno de los beneficios que la literatura le atribuye. El ejemplo canónico lo demuestra solo — **Envoy, el sidecar de Istio, está escrito en C++ y se adjunta a servicios de cualquier lenguaje**. El razonamiento formal: el sidecar corre en su propio proceso y su propio contenedor, comunicándose por localhost o por un socket, así que **la frontera es el protocolo, no el runtime**. Si la frontera fuera el runtime, sería una biblioteca y no un sidecar.

> **Advertencia honesta:** de las doce preguntas que se llevaron a investigación externa, ésta es la que volvió con menos respaldo — es la única sin una cita fuerte. Si alguien la va a defender, vale conseguir **Burns & Oppenheimer, *"Design Patterns for Container-based Distributed Systems"*, HotCloud 2016**, que es el paper que introduce sidecar, ambassador y adapter como patrones nombrados. Es la fuente primaria y corta la discusión.

**Cuál de los dos, todavía no está decidido.** Se escribieron las **dos** implementaciones contra la misma spec, y las dos la pasan:

| | Java 21 | Node 22 + TS |
|---|---|---|
| Líneas efectivas | 869 | 626 |
| Tests | 58 | 65 |

Que dos equipos implementen el mismo contrato por separado y produzcan componentes indistinguibles **es la evidencia de que la spec está bien escrita** — y es un resultado que se puede mostrar. La elección entre las dos es **decisión de equipo, con los once** ([`README.md`](./README.md) D9), y el criterio no es el peso de la imagen: es **qué código puede auditar el equipo**, porque un componente de seguridad tiene que ser chico y leíble de una sentada. Las dos se pasaron del objetivo de 400 líneas de la spec; el número honesto para el mecanismo tal como está especificado son ~600.

### La lista blanca no se achicó: desapareció

Vale contar la secuencia, porque muestra el orden correcto de las decisiones.

Primero, una decisión de otro documento le sacó un endpoint de encima al proxy: como el bundle entra por el **`stdin`** del contenedor y no por `docker cp` ([`03`](./03-ms-sandbox-ejecucion.md) §1.5), la lista blanca dejó de necesitar `PUT /containers/{id}/archive` — literalmente una escritura al filesystem de un contenedor, y el endpoint más peligroso de los que antes hacían falta.

Después, al invertir el sidecar, **la lista blanca dejó de existir**. No hay endpoints de Docker que habilitar o negar, porque el worker ya no habla Docker.

> Es el mejor argumento del tema a favor de decidir el aislamiento primero: la superficie que hay que defender la fija el diseño del ejecutor, no el filtro que se le pone adelante.

### Por qué es un sidecar de manual

- Vive **junto** al servicio principal, en la misma unidad de despliegue y ciclo de vida.
- Le agrega una capacidad transversal (acceso **mediado** a la infraestructura) **sin tocar el código** del worker.
- Se puede reemplazar o endurecer sin recompilar nada del worker — de hecho hay dos implementaciones intercambiables.
- Hace al worker testeable contra un ejecutor falso: son 60 líneas de servidor sobre un socket.

**Segundo sidecar**, este genérico y compartido con los otros grupos: el collector de **OpenTelemetry / Promtail**, que recolecta trazas y logs sin que la aplicación sepa a dónde van.

---

## 2. Health Check — la trampa del "saturado"

La distinción liveness/readiness, que en un CRUD es casi decorativa, acá **importa de verdad**.

```
/actuator/health/liveness   → ¿el proceso está vivo?
/actuator/health/readiness  → ¿puede aceptar trabajo?
```

### Regla 1 — Docker no va en `liveness`

Si el demonio se cae y se reporta liveness DOWN, el orquestador reinicia el proceso, que sigue sin Docker, y entra en **crash loop**. Reiniciarse no arregla una dependencia externa. Docker va en `readiness`.

### Regla 2 — pool lleno ≠ enfermo

Es el error que casi todos cometen. Si un worker saturado reporta DOWN y se desregistra, la carga se corre a los demás, que se saturan, se desregistran, y **cae en cascada todo el servicio por estar funcionando a full**.

| Situación | `readiness` | Respuesta |
|---|---|---|
| Pool lleno | **UP** | `429` + `Retry-After` |
| Cola llena | **UP** | `429` |
| Base caída | DOWN | — |
| Demonio de Docker caído | DOWN | — |
| Imagen del runner ausente | DOWN | — |
| **Ejecutor caído o sin responder** | DOWN | — |
| **Ejecutor saturado** (`503 RECHAZADA`) | **UP** | `nack` + requeue con backoff |

> **Saturación se responde con `429`, nunca con DOWN.**

**Después de D1 el worker ya no consulta a Docker: consulta al ejecutor.** El `/salud` del ejecutor ([`08`](./08-spec-ejecutor.md) §3.4) es quien sabe si el demonio responde y si la imagen está, y el `readiness` del worker se apoya en él. La regla 1 se sostiene igual —una dependencia externa caída no se arregla reiniciando— y ahora hay dos dependencias externas en vez de una, con la misma respuesta.

### Regla 3 — la ejecución canaria

Esto es lo que diferencia el health check de este servicio de cualquier otro.

Al arrancar, **antes de declararse listo**, el worker corre un job trivial conocido (una suma que debe dar verde) por **todo el pipeline real**: crear contenedor, copiar archivos, compilar, ejecutar, parsear el reporte. Si no da el resultado esperado, no se declara listo.

```java
@Component
class CanarioHealthIndicator implements HealthIndicator {
    // corre un job conocido al arranque y cada N minutos:
    // detecta imagen rota, JDK mal instalado, demonio degradado,
    // parser desalineado — ANTES de que lo sufra un alumno
}
```

Detecta la clase entera de fallas que un `/health` que solo pinguea la base **nunca ve**: la imagen se construyó mal en el último pipeline, todo compila, y nada corre. Repetida cada N minutos, es **monitoreo sintético**.

---

## 3. Circuit Breaker — sobre la infraestructura, no sobre otro microservicio

Hay que ser honestos primero: **el sandbox casi no llama a nadie.** No consume ningún otro servicio de la plataforma. El Circuit Breaker de manual —Resilience4j sobre un `RestClient` hacia otro microservicio— no tiene dónde ponerse.

Pero hay una aplicación genuina, y es más interesante que la típica: **el breaker va sobre la ejecución en sí** — es decir, sobre las llamadas del worker al ejecutor, que son las que fallan cuando el demonio de Docker está caído o degradado.

> **Nota de precisión, después de D1.** Antes de invertir el sidecar el breaker envolvía la llamada directa del worker a Docker; ahora envuelve el `POST /ejecutar` hacia el ejecutor. **El mecanismo y el motivo son idénticos** —lo que se corta es el reclamo de jobs— pero hay dos modos de falla en vez de uno, y conviene distinguirlos:
>
> | Lo que ve el worker | Qué pasó | Cuenta para el breaker |
> |---|---|---|
> | `ERROR_DAEMON` ([`08`](./08-spec-ejecutor.md) §3.2) | El daemon de Docker falló o no respondió | **Sí.** Es la falla de infraestructura de siempre |
> | El socket del ejecutor no responde | El ejecutor está caído o trabado | **Sí** |
> | `503 RECHAZADA` ([`08`](./08-spec-ejecutor.md) §3.3) | Saturación: la cola del ejecutor está llena | **No.** Es backpressure, no falla. `nack` con requeue y backoff |
>
> Esa última fila es el error fácil de cometer: con 120 sesiones concurrentes los rechazos por saturación **ocurren por diseño**, y un breaker que los contara abriría el circuito justo cuando el servicio está funcionando a full. Es la misma trampa del "saturado ≠ enfermo" de §2, un nivel más abajo.

### Por qué hace falta

Si el demonio está caído o degradado —y el ejecutor lo reporta como `ERROR_DAEMON`—, sin breaker cada mensaje recibido falla, gasta sus 3 reintentos y termina en la DLQ con la ejecución en `ERROR_INTERNO`. En dos minutos se vació la cola convirtiendo trabajo válido en basura — y encima lo hizo *ordenadamente*, porque el requeue del broker es rápido.

```
CERRADO   → operación normal
            5 fallos consecutivos de Docker
              ↓
ABIERTO   → el worker DEJA DE RECLAMAR jobs
            los jobs quedan en la cola (no fallados)
            readiness → DOWN
              ↓ cada 30 s
SEMI      → intenta una ejecución canaria
            ok → CERRADO   |   falla → ABIERTO
```

> **El breaker corta el *reclamo*, no los *jobs*.** Un job nunca se marca fallado porque se cayó la infraestructura.

Es el mismo principio de *"`ERROR_INTERNO` no consume vida"*, aplicado un nivel más arriba.

### El breaker del otro lado

T05 debería tener un breaker **hacia** el sandbox. El aporte de este grupo es **definir el fallback**: cuando el sandbox no responde, la entrega se acepta igual y la ejecución queda pendiente — exactamente lo que el PRD ya decidió para la IA en **RF-IA-27**. El breaker de T05 no debe devolverle un error al alumno.

---
---

# Parte II — Los que aplican con matices

## 4. DDD

### El matiz honesto, dicho por ustedes primero

**El sandbox no es un subdominio *core*: es un subdominio genérico.** Existe Judge0, existe Piston — se podría comprar. Eso no lo hace menos valioso, lo **ubica**: no encapsula la ventaja competitiva del negocio, encapsula una capacidad técnica.

Dicho eso, tienen el **bounded context más limpio de los 12 servicios**: no comparte una sola entidad con nadie. No sabe qué es un alumno, un curso ni una moneda.

| Elemento | En `ms-sandbox` |
|---|---|
| **Lenguaje ubicuo** | Ejecución, Veredicto, Suite, Slot, Lease, Artefacto, Límites — ninguno es término del dominio educativo, y eso es correcto |
| **Agregado raíz** | `Ejecucion`. Su frontera de consistencia es una ejecución; nada abarca dos |
| **Value objects** | `Limites`, `Recursos`, `Veredicto` |
| **Eventos de dominio** | `EjecucionFinalizada` |
| **Anti-Corruption Layer** | ⭐ ver abajo |

### El ACL es la mejor pieza de DDD del servicio

La normalización del XML de JUnit **es** un Anti-Corruption Layer: impide que el modelo de JUnit se filtre a la plataforma.

Sin él, `ms-desafios` termina parseando `<testcase classname=...>` y queda acoplado a una versión de una librería que nunca eligió. Con él, cambiar de framework, de versión o de lenguaje **no se propaga a nadie**.

> **Cuidado con la sigla, porque en este documento significa dos cosas.** Acá **ACL = Anti-Corruption Layer** (patrón de DDD). En §6, cuando se habla de permisos de RabbitMQ, **ACL = Access Control List**. Son conceptos sin ninguna relación y conviene no mezclarlos en la defensa.

#### [IE] El argumento que le faltaba: el XML de JUnit no tiene especificación

Hay un dato que fortalece mucho la justificación del ACL y que no estaba: **el XML de JUnit no tiene especificación oficial.** Es un formato de facto de más de treinta años, y cada framework soporta su propia variante.

O sea, el ACL no está protegiendo a la plataforma de *"una versión de una librería"*: la está protegiendo de **un formato que nadie especificó y que nadie garantiza estable**. Eso es un argumento bastante más fuerte.

#### [IE] Qué forma debería tener la salida del ACL: la de CTRF

El ACL produce "nuestro modelo de veredicto normalizado", pero el documento nunca decía qué forma tiene. Hay un estándar abierto emergente para exactamente esto: **CTRF (Common Test Report Format)**, un esquema JSON único, agnóstico de lenguaje y de framework ([ctrf.io](https://ctrf.io/docs/intro)). Lo mínimo por test es *name, duration, status*; el resto es opcional, e incluye campos de `retries` y `flaky`.

Para Java hay implementación nativa (`io.github.alexshamrai:junit-ctrf-reporter`, con Jupiter Extension y Platform TestExecutionListener), y hay señal de adopción citable: Microsoft.Testing.Platform incorporó un provider de CTRF desde la 2.3.0.

**La recomendación tiene dos mitades, y la segunda es la importante:**

| | Decisión |
|---|---|
| ¿CTRF como formato de cable con T05? | **No.** Nuestro contrato con T05 es del dominio —"veredicto de una entrega"— y no un formato genérico de test. Adoptarlo ahí sería filtrarle a T05 un modelo que no le sirve |
| ¿CTRF como forma del modelo interno que produce el ACL? | **Sí.** Es exactamente el modelo que un ACL sobre JUnit debería producir |

El beneficio es argumentativo y es concreto: poder decir *"nuestro modelo de veredicto normalizado sigue la forma de CTRF, el estándar abierto emergente para reportes de test"* convierte una decisión propia en una decisión con respaldo. Y si mañana entra otro lenguaje, existe un conversor oficial `junit-to-ctrf`, así que el camino de migración ya está trazado — que era justamente la promesa del ACL.

---

## 5. Rate Limit — el matiz es *concurrencia*, no *frecuencia*

Un rate limit clásico protege un recurso barato de mucho tráfico. El recurso de este servicio es **CPU física**, que no se multiplexa. Por eso hacen falta las dos cosas, y son distintas:

| Control | Qué limita | Dónde | Por qué |
|---|---|---|---|
| Rate limit | Requests / tiempo | Gateway (por usuario) | Abuso, bucles del frontend |
| **Límite de concurrencia** | Jobs **en vuelo** por alumno | Sandbox | **Un alumno no se come el pool** |
| Cola acotada | Trabajo pendiente total | Sandbox | Backpressure honesto |

El del medio es el que de verdad salva: con 6 slots, un alumno que encola 20 ejecuciones deja a los otros 119 esperando. Un tope de 1–2 en vuelo por alumno lo resuelve, y es **fairness**, no anti-abuso.

Si el botón "Ejecutar" del IDE pasa por el sandbox, hace falta además un token bucket por alumno: alguien lo va a apretar 30 veces por minuto.

---

## 6. EDA

### Como productor

`EjecucionFinalizada` con el **resumen**; el detalle por `GET` (así el filtrado por visibilidad sigue centralizado en el sandbox). Si se publica, va con **outbox transaccional**: el resultado y el evento se escriben en la misma transacción, o no se escribe ninguno.

### Como consumidor

| Evento que consume | Qué hace |
|---|---|
| `EntregaAnulada` | Cancelar la ejecución si sigue `ENCOLADA` |
| `CursoArchivado` | Cancelar en masa las encoladas de ese curso |

### El broker no es solo para eventos

Acá hay algo que distingue a este servicio de los otros once: para el resto, el broker es el canal de **notificación** — publican que algo pasó. Para el sandbox es además el canal de **trabajo**: `sandbox.jobs` es la cola de la que come el worker ([`04-ms-sandbox-worker.md` §3](04-ms-sandbox-worker.md)).

Son dos usos con propiedades distintas y conviene no mezclarlos al explicarlos:

| | `sandbox.jobs` (trabajo) | `EjecucionFinalizada` (evento) |
|---|---|---|
| Consumidores | **Uno** se lo lleva | **Todos** los interesados lo reciben |
| Topología | Cola directa | Fanout / topic exchange |
| Si nadie escucha | Se acumula y espera | Se pierde, y está bien |
| Ack | Manual, al terminar | Al procesar cada consumidor lo suyo |

**Y el mismo outbox sirve para los dos.** La API lo usa para publicar el job, el worker para publicar el resultado: una tabla, un relay, dos usos. Es el argumento más económico a favor del patrón.

### [IE] Un solo broker, y qué separación lógica exactamente

Hay una pregunta que aparece sola cuando se enseña *database per service*: si cada servicio tiene su base, ¿no debería tener su broker? La conclusión del grupo era **un solo broker con separación lógica**, y la investigación externa la confirma como la lectura estándar — pero afina el "separación lógica", que era la parte vaga.

Por qué la analogía no se traslada: *database per service* protege una propiedad concreta —**ningún servicio lee las tablas de otro**— que es sobre el **acceso a los datos**, no sobre la cantidad de instancias de infraestructura. Un broker por servicio no tiene análogo, porque el broker **es el bus compartido**: si cada uno tiene el suyo, no hay bus, hay doce colas privadas y federación entre ellas. La propiedad análoga —ningún servicio consume la cola de otro ni publica en el exchange de otro— se obtiene con **usuarios por servicio y ACLs** (acá sí, *access control lists*), no con más brokers.

**Y el detalle que hay que corregir antes de que alguien lo proponga:** un vhost por servicio **rompería activamente nuestra coreografía**.

> **Los exchanges no cruzan vhosts.** Un mensaje publicado en un exchange del vhost A no llega a una cola del vhost B sin un *shovel* o *federation* explícito. Nuestro `EjecucionFinalizada` lo consumen **T05 y T03**: con un vhost por servicio, ese evento necesitaría un shovel configurado para llegar a dos consumidores en dos vhosts distintos.

La postura que conviene defender:

| Nivel | Qué usar | Por qué |
|---|---|---|
| Entornos (dev / demo / defensa) | **vhosts** | Es el caso de uso canónico en la documentación: multi-tenancy y separación de entornos, aislamiento total |
| Servicios dentro de un entorno | **vhost compartido + usuario por servicio + ACLs** | Preserva el bus; el aislamiento lo dan los permisos, con granularidad fina (leer de esta cola, escribir en este exchange, nada más) |
| Colas | Nombradas por servicio consumidor | Ownership explícito |

Esto es más fuerte que decir "un solo broker con separación lógica", porque nombra **qué** separación lógica y **por qué esa y no otra** — y muestra que se evaluó el vhost-por-servicio y se descartó con un argumento técnico, no por comodidad.

---

## 7. SAGA — conviene ser preciso

Una saga es una transacción distribuida con **compensación**. Pregunta honesta: ¿qué compensa el sandbox si algo falla después? Ejecutar código no deja efectos de lado que deshacer — el contenedor se destruye y no queda nada.

> **`ms-sandbox` es un *paso* de una coreografía, no un participante de saga con compensación.**

La coreografía completa sí existe, y es el flujo central del sistema:

```
Entrega  →  Ejecución  →  Veredicto  →  Aprobación  →  ┬→ Monedas (T08)
                                                        ├→ XP (T10)
                                                        └→ Score IA (T07)
```

Es **coreografía**, no orquestación: nadie dirige, cada servicio reacciona a un evento.

### Dónde aparece la compensación de verdad

**No en el sandbox**, sino aguas abajo. Si un veredicto resulta inválido (el profesor corrigió un test mal escrito y hay que re-corregir), lo que se compensa es la **acreditación**: un movimiento de reversión en el ledger de T08 y de T10.

### El aporte del sandbox a que la saga sea sana

Son dos propiedades, y conviene enunciarlas así:

1. **Idempotencia** — `entregaId:intentoNro` como clave natural: un reintento de red no dispara una segunda ejecución ni un segundo evento.
2. **Nunca emitir un veredicto falso** — es la regla de `System.exit(0)`: ante la duda, `ERROR_INTERNO`.

De ahí se deriva un criterio de diseño:

> **Es preferible fallar que aprobar de más.** Un veredicto de éxito equivocado dispara acreditaciones en tres servicios, y compensarlo cuesta muchísimo más que reintentar la ejecución. En una saga coreografiada, un evento erróneo se propaga a servicios que ya no se controlan.

---

## 8. Service Discovery — la observación fina

Dos cosas que muestran haber entendido el patrón, en vez de solo agregar `@EnableDiscoveryClient`:

### a) El worker no necesita registrarse

Nadie lo llama: **jala** trabajo de la cola. El registro sirve para que te encuentren, y a él no hay que encontrarlo. **Solo la API se registra.** Es coherente con separarlos en dos perfiles ([`04-ms-sandbox-worker.md` §10](04-ms-sandbox-worker.md)).

**El ejecutor tampoco se registra**, y por un motivo más fuerte: no es alcanzable por nombre. Se lo encuentra por un **socket Unix en un volumen compartido**, no por resolución de servicio ([`08`](./08-spec-ejecutor.md) §2). Registrarlo sería publicarlo a los otros once servicios, que es exactamente lo contrario de lo que se busca.

### b) Los contenedores de ejecución están fuera del registro, y ese es el punto de seguridad

Con Service Discovery, cualquier proceso con red resuelve `ms-banco` por nombre. Si el código del alumno tuviera red, `POST http://ms-banco/api/v1/creditos` sería un bypass de todas las reglas de negocio de la plataforma.

> **`--network none` deja de ser higiene genérica y pasa a ser lo que sostiene la integridad del sistema entero.**

---

## 9. API Gateway — lo que sí es decisión propia

Poco es del grupo, pero tres cosas sí:

- **No se expone ningún endpoint al navegador.** Todo el tráfico del sandbox es servicio-a-servicio. Un alumno no le pega jamás.
- **Nunca se bloquea el Gateway.** El `POST` devuelve `202` de inmediato. Ninguna ruta del sandbox puede quedar colgada 15 segundos ocupando un slot que necesitan los otros 11 grupos. **Es el mejor argumento para que el contrato asíncrono se acepte sin discusión.**
- **La identidad sale del token, nunca del body.** Nadie de afuera dice a nombre de quién ejecutar.

---
---

# Parte III — El que no aplica

## 10. BFF

**No es de este servicio, y conviene decirlo explícitamente.**

Un BFF adapta un backend a las necesidades de un cliente concreto: agrega, recorta y da forma para una pantalla. El sandbox **no tiene cliente de UI** — su consumidor es otro microservicio.

El BFF del IDE, si existe, es de **T05**: es quien junta el enunciado, el estado de la entrega, los intentos restantes y el resultado de ejecución en la vista que ve el alumno.

Lo único que se le podría parecer en el sandbox es el filtrado por `modo` (`VISIBLE` / `COMPLETO`). **No llamarlo BFF:** es una regla de dominio de integridad académica que se aplica en el servidor precisamente para *no* delegarla al frontend. Estirar la definición debilita el argumento en vez de fortalecerlo.

---
---

# Cómo presentarlo

## Con qué liderar, en orden

1. **El sidecar ejecutor** — resuelve un problema de seguridad real y específico del tema. Se lidera con la versión fuerte: no *"filtramos la API de Docker"*, sino *"invertimos el sidecar para eliminar por construcción dos clases de vulnerabilidad documentadas"*, con los tres CVEs de bypass de AuthZ y la familia de `runc` detrás. **[IE]** Y se cierra con el resultado: dos implementaciones independientes contra la misma spec, indistinguibles.
2. **Health Check con ejecución canaria** — demuestra entender la diferencia entre *"el proceso vive"* y *"el servicio sirve"*.
3. **Circuit Breaker sobre la ejecución, cortando el reclamo y no los jobs** — uso no obvio y correcto, con la distinción fina entre `ERROR_DAEMON` (cuenta) y `503 RECHAZADA` (no cuenta).
4. **ACL sobre JUnit** — DDD aplicado a algo concreto, no citado. **[IE]** Reforzado con que el XML de JUnit no tiene especificación oficial, y con CTRF como forma del modelo que produce.

## Las afirmaciones deliberadas

- **"BFF no aplica: vive en T05."**
- **"No somos participante de saga porque no tenemos nada que compensar. Somos un paso de la coreografía, y nuestro aporte es idempotencia y no mentir el veredicto."**
- **[IE] "Evaluamos vhost por servicio y lo descartamos: los exchanges no cruzan vhosts, así que rompería la coreografía. Usamos vhosts por entorno y ACLs por servicio."** Es el tipo de recorte que muestra que se entendió qué compra cada mecanismo.
- **[IE] "El contenedor protege el host, pero no protege el veredicto."** Esta es la más valiosa de todas, porque nombra un límite del propio diseño y trae la solución: la validación de paquete que impide que el alumno sombree una clase del profesor ([`03`](./03-ms-sandbox-ejecucion.md) §1.6d). Reconocer que el aislamiento y la integridad del veredicto son **dos propiedades distintas** es lo que separa este trabajo de uno que solo configuró flags de Docker.

Ese tipo de recorte fundamentado es lo que separa un trabajo que **entendió** los patrones de uno que los **enumeró**.

---

**Ver también**
- `README.md` — índice, fuente de verdad por tema y estado de todas las decisiones.
- `08-spec-ejecutor.md` — el contrato del ejecutor: spec del contenedor, constantes, criterios de aceptación.
- `03-ms-sandbox-ejecucion.md` — aislamiento, contrato con T05, origen de los tests, API.
- `04-ms-sandbox-worker.md` — el worker en detalle: la cola de RabbitMQ, el outbox, la DLQ, el janitor, concurrencia.
- `01-panorama-microservicios-backend.md` — mapa de los 12 servicios del curso.
