# `ms-sandbox` — Patrones de microservicios aplicados

> **Tema 06 — Sandbox / Runtime.**
> Los patrones de la unidad de Microservicios, cruzados contra este servicio: cuáles aplican, **cómo** aplican, y cuáles **no aplican y por qué**.

**Premisa del documento:** el error clásico de TP es forzar los diez patrones. Un profesor lo nota. Decir *"este no aplica, y este es el motivo"* con fundamento vale más que un Circuit Breaker decorativo.

---

## Veredicto rápido

| Patrón | ¿Aplica a `ms-sandbox`? | Dónde |
|---|---|---|
| **Sidecar** | ⭐ **Sí, y es el mejor caso del tema** | Proxy del socket de Docker |
| **Health Check** | ⭐ **Sí, con un giro propio** | Saturado ≠ enfermo; ejecución canaria |
| **Circuit Breaker** | ⭐ **Sí, pero no donde uno espera** | Sobre el demonio de Docker, no sobre HTTP |
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

## 1. Sidecar — el proxy del socket de Docker

Es **el mejor patrón del tema**, porque resuelve un problema real y específico, no uno genérico.

### El problema

El worker necesita lanzar contenedores, así que necesita `/var/run/docker.sock`. Y ese socket no es "un socket con permisos": es la **API HTTP completa de Docker**, que **no tiene modelo de autorización**. No hay usuarios, ni roles, ni scopes. Quien puede hablarle puede todo, incluido `POST /containers/create` con `Privileged: true` y `Binds: ["/:/host"]`, que es root en el host en una sola llamada.

> **El sidecar no es "un socket más seguro". Es la capa de autorización que Docker no trae.**

### La solución

```
┌──────────────── misma unidad de despliegue ─────────────────┐
│                                                              │
│   ms-sandbox-worker  ──HTTP──►  docker-socket-proxy          │
│   (SIN el socket)                (ÚNICO con el socket)       │
│                                        │                     │
└────────────────────────────────────────┼─────────────────────┘
                                         ▼
                                  /var/run/docker.sock
```

### En qué lenguaje se escribe: en ninguno

El estándar de facto es **`tecnativa/docker-socket-proxy`**: HAProxy con la configuración manejada por variables de entorno. No se escribe una línea.

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
    INFO: 0
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock:ro
```

Para el alcance del TP es la respuesta correcta, y además la más defendible: *"usamos el componente estándar, acá está la lista blanca"* pesa más que un proxy casero que no se puede demostrar correcto.

### Qué filtra de verdad, y qué no

Acá hay que ser honestos, porque este documento **prometía de más**. HAProxy filtra por **path y método HTTP**. No mira el cuerpo del request.

| Lo que hace falta | ¿El proxy de estantería lo hace? |
|---|---|
| Permitir `create`, `start`, `kill`, `inspect`, `rm`, `logs` | **Sí.** Es filtrado por path y método |
| Negar `exec`, `commit`, `build`, `push` | **Sí.** Ídem |
| Negar volúmenes y redes | **Sí.** Son familias de endpoints enteras |
| Limitarse a contenedores con el label `sandbox.job` | **No.** El label viaja en el body del `create` y en el query del `list` |
| Negar `Privileged: true` o `Binds: ["/:/host"]` | **No.** Son campos del JSON del body |

Esa última fila es el agujero real: como `POST /containers/create` **hay que permitirlo** —es el trabajo del worker—, quien hable con el proxy puede pedir un contenedor privilegiado con `/` montado.

> **El sidecar reduce la superficie de la API; no vuelve seguro el socket.** Lo que compra es **radio de explosión**: convierte *"comprometer el worker = root en el host"* en *"comprometer el worker = crear contenedores dentro de una lista blanca"*. Sigue siendo malo, pero es otra categoría.

Y conviene decir en la defensa por qué el residual es aceptable: **el contenedor del alumno no puede llegar al proxy**. No tiene red (`--network none`) ni el socket montado, así que no lo alcanza ni aunque escape de la JVM. La amenaza que el sidecar mitiga es un **worker comprometido**, que es secundaria; la principal ya está cerrada por otro lado ([`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) §1.2).

### Si igual se decide escribir uno

Escribir el propio se justifica por una sola razón: **inspeccionar el body del `create`**. Y ahí sí importa el lenguaje.

| | Go | Java 21 + Spring | Rust | nginx + Lua |
|---|---|---|---|---|
| Imagen | ~10 MB (`scratch`) | ~200 MB | ~8 MB | ~30 MB |
| RAM en reposo | ~5 MB | ~100 MB+ | ~3 MB | ~10 MB |
| Líneas para proxy + validación del body | ~150 | ~300 | ~200 | ~120 (Lua) |
| Arranque | ms | segundos | ms | ms |

**Go sería la elección.** `httputil.ReverseProxy` más `net.Dial("unix", ...)` dan el proxy en veinte líneas, y parsear el JSON del `create` para rechazar `Privileged`, `Binds`, `CapAdd` y `PidMode: host` son otras cien. Además es el idioma nativo del ecosistema de contenedores.

Que el sidecar esté en otro lenguaje que la aplicación **no es una inconsistencia: es parte del patrón.** Un sidecar es deliberadamente agnóstico del lenguaje y tiene ciclo de vida propio — de hecho el otro sidecar previsto, el collector de OpenTelemetry, tampoco es Java.

**Java es la opción tentadora y equivocada.** Se puede —hay sockets de dominio Unix desde Java 16, con `UnixDomainSocketAddress`— pero sería una JVM entera **por réplica de worker** para hacer matching de paths: el sidecar consumiría más que el proceso que protege y arrancaría más lento que él. Un componente de seguridad tiene que ser chico y auditable de una sentada.

Y si lo que se busca es cerrar el agujero del body, hay una opción **más fuerte que filtrar**: que el sidecar no proxee la API de Docker sino que **sea el ejecutor** — expone un `POST /ejecutar` con el bundle y arma él mismo la spec del contenedor, fija y hardcodeada. El worker deja de hablar Docker. No hay campo peligroso que filtrar porque el que llama **no puede expresarlo**. Cuesta más código propio; a cambio no depende de que la lista blanca esté completa.

### La lista blanca se achicó sola

Una decisión de otro documento le sacó un endpoint de encima al proxy. Como el bundle entra por el **`stdin`** del contenedor y no por `docker cp` ([`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) §1.5), la lista blanca **no necesita habilitar `PUT /containers/{id}/archive`**, que es literalmente una escritura al filesystem de un contenedor y el endpoint más peligroso de los que antes hacían falta.

> Es el mejor argumento del tema a favor de decidir el aislamiento primero: la superficie que el sidecar tiene que defender la fija el diseño del ejecutor, no el proxy.

### Por qué es un sidecar de manual

- Vive **junto** al servicio principal, en la misma unidad de despliegue y ciclo de vida.
- Le agrega una capacidad transversal (acceso **mediado** a la infraestructura) **sin tocar el código** del worker.
- Se puede reemplazar o endurecer sin recompilar nada.
- Hace al worker testeable contra un proxy falso.

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

> **Saturación se responde con `429`, nunca con DOWN.**

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

## 3. Circuit Breaker — sobre Docker, no sobre HTTP

Hay que ser honestos primero: **el sandbox casi no llama a nadie.** Es una función pura sin dependencias salientes. El Circuit Breaker de manual —Resilience4j sobre un `RestClient` hacia otro microservicio— no tiene dónde ponerse.

Pero hay una aplicación genuina, y es más interesante que la típica: **el breaker va sobre el demonio de Docker.**

### Por qué hace falta

Si el demonio está caído o degradado, sin breaker cada mensaje recibido falla, gasta sus 3 reintentos y termina en la DLQ con la ejecución en `ERROR_INTERNO`. En dos minutos se vació la cola convirtiendo trabajo válido en basura — y encima lo hizo *ordenadamente*, porque el requeue del broker es rápido.

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

1. **Sidecar de proxy de Docker** — resuelve un problema de seguridad real y específico del tema.
2. **Health Check con ejecución canaria** — demuestra entender la diferencia entre *"el proceso vive"* y *"el servicio sirve"*.
3. **Circuit Breaker sobre el demonio, cortando el reclamo y no los jobs** — uso no obvio y correcto.
4. **ACL sobre JUnit** — DDD aplicado a algo concreto, no citado.

## Las dos afirmaciones deliberadas

- **"BFF no aplica: vive en T05."**
- **"No somos participante de saga porque no tenemos nada que compensar. Somos un paso de la coreografía, y nuestro aporte es idempotencia y no mentir el veredicto."**

Ese tipo de recorte fundamentado es lo que separa un trabajo que **entendió** los patrones de uno que los **enumeró**.

---

**Ver también**
- `03-ms-sandbox-ejecucion.md` — aislamiento, contrato con T05, origen de los tests, API.
- `04-ms-sandbox-worker.md` — el worker en detalle: la cola de RabbitMQ, el outbox, la DLQ, el janitor, concurrencia.
- `01-panorama-microservicios-backend.md` — mapa de los 12 servicios del curso.
