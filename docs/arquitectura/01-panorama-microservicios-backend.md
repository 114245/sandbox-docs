# Panorama de arquitectura de microservicios — Backend

> **TPI Programación 4 + Metodología de Sistemas 2** — UTN FRC
> Plataforma de Aprendizaje Gamificado
> Documento de análisis. No contiene decisiones de implementación cerradas.

**Fuentes:** [`../_fuentes/TUP_PIV_BE_PROPUESTA_ARQ.md`](../_fuentes/TUP_PIV_BE_PROPUESTA_ARQ.md) (propuesta de arquitectura de la cátedra: reglas de plataforma y reparto de propiedad), `PRD-Plataforma-Gamificada-TP.md` (definición funcional), `temas_curso.md` (asignación de temas).
**Alcance:** solo backend. El frontend (Angular 21+) requiere un análisis separado.
**Grupo del autor:** Tema 06 — Sandbox / Runtime. Ver [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md).

---

## 0. Stack

Ni el PRD ni `temas_curso.md` fijan tecnología: los dos son documentos funcionales. El stack lo pone la cátedra, y estos documentos lo dan por decidido. Queda escrito acá para que no aparezca implícito en detalles sueltos de los otros archivos.

| Capa | Elección | Nota |
|---|---|---|
| Lenguaje | **Java 21** (LTS) | Virtual threads, records, pattern matching, *sealed types* |
| Framework | **Spring Boot 3.x** | Web, Data JPA, Validation, Actuator, Security |
| Base de datos | **PostgreSQL** | Una instancia lógica **por servicio**: *database per service*, sin joins entre dominios |
| Broker | **RabbitMQ** | Ver §4 |
| Resiliencia | **Resilience4j** | Circuit breaker, retry, bulkhead, rate limiter |
| Observabilidad | Micrometer + OpenTelemetry → Prometheus / Grafana / Loki / Tempo | Unidad de Observability |
| Empaquetado | Docker + `docker-compose` | Ver §8 |
| Pruebas | JUnit 5 + Testcontainers | Postgres y RabbitMQ reales en los tests de integración |
| Frontend | **Angular 21+** | Fuera del alcance de estos documentos |

**Dónde el stack cambia el diseño y no solo el código:** `ms-sandbox` corre sobre la JVM y lanza contenedores que también son JVM. Eso obliga a dimensionar la memoria del contenedor de ejecución contando heap + metaspace + stacks + la JVM misma —no solo el heap— y a decidir qué pasa cuando esa cuenta no cierra: con la JVM bien configurada, quien se queda sin memoria es la JVM y no el cgroup, así que el OOM **no** llega como `OOMKilled`. El costo de tiempo, en cambio, no está donde parecía: medido, el arranque de la JVM son decenas de milisegundos y el grueso se lo lleva el toolchain (`javac` y el descubrimiento de tests). Ver [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) §1.4 y §4.

---

## 1. Encuadre: el tema no es el servicio

La tentación obvia es **12 grupos → 12 microservicios**. Como decisión pedagógica está bien y probablemente sea lo esperado, pero conviene tenerlo consciente: la partición de temas es **temática** (el propio PRD lo aclara en la Presentación, punto 1), no una descomposición por *bounded context*.

Hay al menos cuatro temas que no son un servicio de dominio, y eso importa para el diseño (ver §3).

### Las reglas de plataforma

Seis reglas no se renegocian equipo por equipo. Dentro de esos límites, cada equipo decide el diseño interno de su servicio.

| Regla | Qué implica |
|---|---|
| El **API Gateway es la única puerta de entrada** | Ningún cliente llega a un microservicio por otro camino |
| Los servicios se **registran dinámicamente** | No hay direcciones fijas en configuración |
| **No hay comunicación directa entre microservicios** | Toda llamada sincrónica vuelve a pasar por el gateway |
| **Cada servicio es dueño exclusivo de su base** | Nadie lee la tabla del vecino ni comparte esquema |
| **Lo asincrónico viaja por el bus de eventos** | No por el gateway |
| **Cada entidad tiene un dueño único** | Ver §8.5 |

Una llamada directa entre microservicios pierde el balanceo, se acopla a un despliegue puntual, se saltea la validación centralizada y desaparece de la traza. El acoplamiento por base de datos es la misma falta, solo que más difícil de detectar.

### El curso-cohorte es el contexto de todo

Casi ninguna entidad existe fuera de un **curso-cohorte**: las recompensas se usan solo en el curso donde se obtuvieron, la calibración es por curso, el ranking es dentro de la cohorte, las mecánicas de enganche se desactivan por curso. **No existe saldo global.**

Eso lo convierte en la clave que viaja en cada operación y contra la que se acota cada consulta. Una entidad modelada sin esa clave no se puede acotar después sin migrar datos.

Hay que distinguir dos cosas que se parecen:

| | **Curso template** | **Curso-cohorte** |
|---|---|---|
| Qué es | Definición reutilizable de la materia | La instancia real donde ocurre el dictado |
| Se dicta | No | Sí |
| Ciclo de vida operativo | No tiene | Sí (§5) |
| Contiene | — | Teóricos, prácticos, desafíos, roadmap y matrícula |

De un template nacen tantas cohortes como veces se dicte la materia. El **clonado con linaje** es lo que permite el control de originalidad de T05 contra ediciones anteriores: sin linaje, ese alcance no se puede cumplir.

Y un límite importante: el curso-cohorte es el **contexto** de la conversación, **no el conducto**. T02 es dueño de su identidad y de su ciclo de vida, no del contenido que vive adentro, y no media las operaciones del dictado.

---

## 2. Mapa de servicios por capas

| Capa | Servicio | Grupo | Owner de datos (fuente de verdad) |
|---|---|---|---|
| **Borde / plataforma** | API Gateway | T01 (extra) | — (no persiste dominio) |
| | Infra común: broker, config, observabilidad | T11 (extra) + común | — |
| **Identidad y contexto** | `ms-identidad` | T01 | Usuario, credenciales, 2FA, rol, auditoría |
| | `ms-cursos` | T02 | Curso template y **curso-cohorte** (identidad y ciclo de vida), linaje de clonado, comisión, padrón, matrícula, inscripción, código de invitación |
| **Contenido y evaluación** | `ms-desafios` | T03 | Desafío (metadata, versión), publicación, asignación, Entrega y su estado |
| | `ms-teoricos` | T04 | Ítem teórico, corrección, encuestas (ver §3) |
| | `ms-practicos` | T05 | Consigna de código, casos de prueba, feedback, similitud anti-cheat |
| | `ms-sandbox` | T06 | Ejecución aislada + artefactos (pool de cómputo, no CRUD) |
| | `ms-eval-ia` | T07 | Rúbrica versionada, golden set, calibración, score de uso de IA |
| **Economía y gamificación** | **`ms-banco`** | **T08** | **Ledger de movimientos, saldos, reservas** |
| | `ms-mercado` | T09 | Catálogo, orden de compra, **inventario del alumno**, subastas |
| | `ms-progreso` | T10 | Grafo/roadmap, desbloqueo, **XP**, niveles, logros, **vidas**, rachas, misiones, ranking |
| **Interacción y gobierno** | `ms-social` | T11 | Chat, equipos, notificaciones, reportes, moderación, **contrato de eventos de la plataforma** |
| | `ms-backoffice` | T12 | Configuración global (parámetros `PAR-*`), asignación modelo↔función |

---

## 3. Los cuatro temas que no encajan limpio

### T06 — Sandbox no es un microservicio de dominio

Es un *worker pool* sin modelo de negocio: recibe un job (código + tests + límites), devuelve un resultado. Su contrato natural es **asíncrono** (encolar → callback/evento), no REST síncrono, porque una ejecución puede tardar segundos y bloquear un hilo del servicio llamador. Escala horizontal independiente y es el único componente con requisitos de aislamiento serio (contenedores efímeros, cgroups, sin red).

### T04 — Ítems teóricos y encuestas son dos dominios distintos

No comparten nada. Y las encuestas traen una restricción durísima: **RF-ENC-04/12 exigen que el anonimato sea una propiedad del modelo de datos**, con dos registros sin vínculo posible (marcador de cumplimiento ↔ respuesta). Eso significa, en la práctica, **dos bases físicamente separadas** — no dos tablas de la misma base con un FK "que no usamos".

> **Recomendación:** dentro del grupo 4, tratarlo como dos servicios, o mínimo dos datasources.

### T12 — Backoffice tiene alto riesgo de volverse un god-service

La mayor parte de lo que pide (reportes docentes, panel de métricas, exportación) es **agregación de datos ajenos**: no debe ser dueño de nada de eso, sino leer vía API/eventos. Lo único de lo que sí es dueño legítimo es la **configuración global**, y eso lo convierte en el servicio del que más depende todo el sistema.

Tiene además un **problema de secuencia que ningún otro tema tiene**: no puede mostrar nada hasta que los seis temas que le proveen datos (02, 03, 05, 08, 10, 11) expongan sus contratos de lectura. Si esos contratos no se acuerdan en el sprint 1, el equipo queda semanas sin nada demostrable. Es la dependencia de integración más temprana del reparto.

### T01 — El gateway no debería ser propiedad de un equipo de dominio

Es infraestructura compartida; conviene que T01 lo *administre* pero que su configuración de rutas la aporte cada equipo.

---

## 4. Comunicación: qué va síncrono y qué va asíncrono

La regla propuesta:

- **Síncrono (REST + Resilience4j)** solo para: gateway→servicio, validación de token, y **comandos que necesitan respuesta inmediata porque el usuario está esperando** (ej. "descontá 300 monedas para comprar esta vida" → necesito saber si alcanzó el saldo).
- **Asíncrono (broker: RabbitMQ)** para todo el *fan-out* de consecuencias. El PRD es una cascada permanente: desafío superado → XP → monedas → nivel → desbloqueo de sección → ranking → notificación. Si eso se hace con 6 llamadas HTTP encadenadas, se construyó un monolito distribuido: la latencia se suma y cualquier caída rompe la entrega.

**Quién gobierna el contrato de eventos.** El catálogo de eventos de la plataforma —qué eventos existen, con qué envelope y qué payload— lo define **T11**, que además es uno de sus consumidores. Es un rol incómodo y condiciona a cinco equipos, pero tiene la ventaja de que hay un solo lugar donde el contrato se decide. En la práctica significa que **un evento que no está registrado con T11 no existe**: publicar `EjecucionFinalizada` no es solo emitirlo, es haberlo dado de alta en ese catálogo.

**Por qué RabbitMQ y no Kafka.** Kafka es mejor para un log releíble por consumidores independientes, que es lo que pide el fan-out de eventos. Pero hay un servicio que además necesita el broker como **cola de trabajo**: `ms-sandbox` reparte ejecuciones entre workers, con jobs de duración impredecible que terminan desordenados. Eso pide ack por mensaje y competing consumers, no offsets ni particiones. Un solo broker que sirva a los doce servicios pesa más que la ventaja de Kafka en la mitad de los casos de uso — detalle en [`04-ms-sandbox-worker.md`](./04-ms-sandbox-worker.md) §3.

**Y la regla que va con el broker:** ninguna escritura de negocio y su publicación pueden vivir en transacciones distintas. Todo servicio que publique un evento consecuencia de un cambio propio lo hace por **outbox transaccional**. Sin eso, "guardé pero no publiqué" es un estado alcanzable, y en una cascada de seis pasos significa que la mitad del sistema no se entera.

**Un solo broker, y cómo se separa adentro.** La pregunta aparece sola: si vale *database per service*, ¿no debería haber un broker por servicio? No, y conviene tener escrito por qué, porque es una pregunta de defensa casi garantizada.

*Database per service* protege una propiedad concreta —**ningún servicio lee las tablas de otro**— que es sobre el acceso a los datos, no sobre la cantidad de instancias de infraestructura. El broker no tiene análogo, porque **es el bus compartido**: si cada servicio tiene el suyo, no hay bus, hay doce colas privadas y federación entre ellas. La propiedad equivalente —ningún servicio consume la cola de otro ni publica en el exchange de otro— se consigue con **usuarios por servicio y ACLs**, no con más brokers.

Y hay un detalle que conviene fijar antes de que alguien proponga lo contrario: **los exchanges no cruzan vhosts.** Un vhost por servicio rompería la coreografía — `EjecucionFinalizada` lo consumen T05 **y** T03, y con vhosts separados ese evento necesitaría un *shovel* configurado para llegar a dos consumidores. La postura:

| Nivel | Qué usar |
|---|---|
| Entornos (dev / demo / defensa) | **vhosts** — es el caso de uso canónico: aislamiento total entre entornos |
| Servicios dentro de un entorno | **vhost compartido + usuario por servicio + ACLs** — preserva el bus; el aislamiento lo dan los permisos |
| Colas | Nombradas por servicio consumidor, para que el ownership sea explícito |

Detalle en [`05-ms-sandbox-patrones.md`](./05-ms-sandbox-patrones.md) §6.

### Flujo central del sistema

```
Alumno entrega desafío práctico
  ms-practicos → ms-sandbox (job async) → resultado de tests
  ms-desafios marca la Entrega APROBADA
     └─ emite EntregaAprobada{alumnoId, cursoId, desafioId, intentoId,
                              dificultad, obligatorio, ocurridoEn}
          ├→ ms-banco     acredita monedas (PAR-03)              → MonedasAcreditadas
          ├→ ms-progreso  acredita XP base (PAR-01 ± PAR-04)     → XPAcreditado
          └→ ms-eval-ia   evalúa la transcripción
                └─ emite ScoreIACalculado{...}
                   (puede tardar, o quedar PENDIENTE por RF-IA-27)
                     └→ ms-progreso aplica el modificador PAR-05
                          como un MOVIMIENTO ADICIONAL de XP,
                          nunca como UPDATE del anterior
```

> ### Hallazgo clave
>
> Por **RF-IA-27** (el score de IA puede llegar diferido) combinado con **RF-CFG-06** (nada se recalcula retroactivamente), **el XP también tiene que ser un ledger, no un contador mutable**.
>
> Es decir: el grupo 10 va a terminar implementando el mismo patrón que el grupo 8. Vale la pena que se pongan de acuerdo temprano y compartan el diseño (incluso una librería común de ledger).

---

## 5. Los tres flujos que atraviesan medio sistema

Estos hay que diseñarlos **entre grupos**, no dentro de uno.

### a) Activación de curso (draft → activo) — RF-CUR-08b

Dos precondiciones bloqueantes, sin override, en **dos servicios distintos**: calibración aprobada (`ms-eval-ia`) + padrón cargado (`ms-cursos`). Orquestador natural: `ms-cursos`, que consulta a `ms-eval-ia` antes de permitir la transición.

### b) Cierre de curso (activo → archivado) — el flujo más complejo del sistema

**Precondiciones distribuidas:**

- Estado académico final de todos los alumnos (`ms-progreso` + confirmación del profesor) — RF-RNK-10
- Cero scores de IA pendientes (`ms-eval-ia`) — RF-IA-34
- Encuesta de cierre respondida antes de mostrar resultado — RF-ENC-11
- Subastas abiertas resueltas (`ms-mercado`) y sin reservas de fondos abiertas (`ms-banco`)

**Fan-out posterior:**

```
CursoArchivado
  ├→ ms-banco    congela las cuentas del curso (nada más se acredita ni se debita)
  ├→ ms-social   PURGA FÍSICA del chat social, salvo lo retenido por reporte (RF-CHT-08/14)
  ├→ ms-mercado  cierra catálogo e inventario a solo lectura
  └→ ms-cursos   invalida el código de invitación
```

Es una **saga** con verificación previa. Es el ejercicio de diseño distribuido más rico que tiene el TP.

### c) Retención y anonimización (RF-NFR-10, PAR-16/17)

La PII está desparramada en 5+ servicios (usuarios, transcripciones IA, chat alumno↔profesor, código del alumno, scores y apelaciones). No hay forma de resolverlo desde un solo lugar.

> **Contrato transversal propuesto:** todo servicio expone `POST /retencion/anonimizar {cursoId}` y responde qué anonimizó. `ms-backoffice` orquesta y audita.

---

## 6. Lo transversal

### Configuración global

Los parámetros `PAR-*` los administra **en exclusiva T12**, pero los aplican **T03, T05, T08 y T10**: esos cuatro tienen que leer la configuración de algún lado en vez de tenerla fija en el código. Opciones: Spring Cloud Config, o que `ms-backoffice` los exponga y cada servicio cachee con TTL + evento `ParametroCambiado` para invalidar.

Ejemplos de lo que gobiernan: XP por dificultad, penalidad del 30 % por entrega tardía, umbral de similitud del 70 %, techo de 3x en multiplicadores de evento.

> **A confirmar con la cátedra:** la propuesta de arquitectura enumera el registro como `PAR-01` a `PAR-24`; la Sección 4.1 del PRD vigente define 18 identificadores, sin saltos. Hace falta saber cuál es el alcance real antes de cerrar el contrato de lectura con T12.

> **Regla derivada de RF-CFG-06:** quien consume un parámetro debe **estampar el valor usado en el registro que produce** (el movimiento de monedas guarda que se acreditó con `PAR-03 = 100`). Así el "no se recalcula retroactivamente" es estructural, no una promesa.

### Borrado lógico (RF-NFR-01)

Transversal a los 12 servicios. Conviene una convención única y una librería compartida (`deleted_at` + filtro a nivel entidad). La única excepción — purga física del chat social — vive en `ms-social` y hay que documentarla como tal.

### Auditoría

Servicio o módulo dentro de T01, alimentado por eventos `AccionAuditada` que emiten todos.

### Observabilidad (unidad de Prog4)

`correlationId` inyectado en el gateway y propagado por HTTP **y por el broker** (en el envelope del evento). Micrometer Tracing + OpenTelemetry → Tempo/Jaeger, logs estructurados JSON → Loki, métricas → Prometheus/Grafana.

Sin trazas distribuidas, depurar la cascada de la §4 con 12 servicios es inviable.

### Resiliencia (RF-IA-27, RF-NFR-04)

Circuit breaker + fallback en toda llamada a proveedor de LLM. El fallback está *especificado en el PRD*: score neutro o cálculo diferido, nunca bloquear la entrega. Java 21 aporta **virtual threads**, que encajan bien acá: las llamadas a LLM y a sandbox son I/O-bound de segundos.

---

## 7. Riesgos de esta distribución

| Riesgo | Dónde aparece | Mitigación |
|---|---|---|
| **Monolito distribuido** | Si `ms-desafios` llama sync a 5 servicios en el request path | Async por defecto para consecuencias; sync solo para lo que el usuario espera |
| **God-service de Backoffice** | T12 acumulando datos de todos | T12 lee, no persiste (salvo configuración) |
| **Ranking chatty** | RF-RNK-11 desempata por insignias + vidas perdidas + ejercicios completados: 3 servicios | `ms-progreso` mantiene un **read model** local alimentado por eventos |
| **Base compartida** | La salida fácil cuando la integración duele | Prohibición explícita desde el día 1: una BD por servicio |
| **Deriva de contratos entre 12 equipos** | El riesgo real #1 del TP | Ver §8 |

---

## 8. Lo que conviene definir *como curso*, antes de escribir código

Con 12 grupos y 132 personas, el cuello de botella no va a ser técnico sino de integración. Cinco puntos que no se resuelven grupo por grupo — los cuatro primeros hay que acordarlos, el quinto ya está definido y solo hay que tenerlo presente:

1. **Catálogo de eventos**, con envelope estándar: `eventId`, `tipo`, `version`, `ocurridoEn`, `correlationId`, `causationId`, `actor`, `payload`. Publicado como AsyncAPI en un repo común. Lo define **T11** (§4), así que no se acuerda en asamblea: se negocia con ese equipo, y hay que hacerlo temprano porque condiciona a cinco.

2. **Contract-first**: OpenAPI de cada servicio commiteado antes de la implementación, en un repo `platform` que todos consumen. Ideal: *consumer-driven contract testing* (Spring Cloud Contract o Pact) — es lo que evita que la integración de la última semana sea una masacre.

3. **Un `docker-compose` global** que levante los 12 servicios + broker + Postgres + stack de observabilidad, más stubs (WireMock) para que cada grupo pueda desarrollar sin depender de que los otros 11 estén andando.

4. **Convenciones transversales**: soft delete, paginación, formato de error (RFC 7807), manejo de fechas/timezone, propagación de `correlationId`, versionado de API.

5. **Quién es dueño de qué dato.** Las tres fronteras que más se prestan a confusión están definidas, y conviene tenerlas presentes porque la intuición lleva a otro lado:

   | Dato | Dueño | El matiz |
   |---|---|---|
   | **vidas** | **T10** | T03 emite el hecho; **T10 decide su efecto** sobre vidas y XP. T03 no descuenta vidas, y T08 no las toca |
   | **XP** | **T10** | T07 evalúa y produce una nota; no conoce economía ni progreso |
   | **inventario** | **T09** | Junto con catálogo y órdenes de compra. T10 no lo toca |

   La regla que las ordena a las tres: **la economía tiene un solo punto de contacto por concepto.** Ni T04 ni T05 otorgan XP; ni T03 ni T08 mueven vidas. Quien produce el hecho no decide su consecuencia económica.

---

## 9. Pendiente

- **Frontend**: análisis separado. La decisión clave es si hay **un BFF por rol** (alumno / profesor / admin) o el Angular pega directo contra el gateway. Conviene decidirlo sabiendo ya cómo quedó esta partición.
- **Diagramas C4**: contexto + contenedores.
