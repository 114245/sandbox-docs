# Briefing para investigación externa — `ms-sandbox` (Tema 06)

> **Para qué sirve este documento.** Es el contexto completo, autocontenido, para llevar a fuentes externas: buscadores, asistentes de IA, papers, foros, docentes, gente de la industria. Está escrito para que alguien que no conoce nada del proyecto pueda dar una respuesta útil sin pedir aclaraciones. Copiar y pegar entero, o la sección que corresponda.
>
> Última actualización: 27 de agosto de 2026.

---

## 1. Contexto académico

Trabajo Práctico Integrador (TPI) conjunto de dos materias de la **UTN Facultad Regional Córdoba**, 2° año, 4° cuatrimestre:

- **Programación 4** — aporta las unidades técnicas: Docker y contenedores, arquitectura de microservicios y sus patrones, observabilidad.
- **Metodología de Sistemas 2** — aporta el proceso: relevamiento, requerimientos, trazabilidad, gestión.

El curso completo construye **un solo producto**, repartido en **12 grupos**, uno por tema. Cada grupo es responsable de un microservicio y debe defenderlo: no alcanza con que funcione, hay que poder argumentar **por qué está diseñado así** y qué alternativas se descartaron.

Nuestro grupo tiene el **TEMA 06 — Sandbox / Runtime**. Somos 11 personas. Coordinamos principalmente con el **TEMA 05 — Desafíos Prácticos**, que es nuestro único consumidor real.

Dos aclaraciones que evitan malentendidos:

- **No es un producto que salga a producción.** Es un trabajo académico. Pero se evalúa con criterio de ingeniería: las decisiones tienen que ser defendibles, no solo funcionales.
- **La escala es chica y está fijada:** 120 usuarios registrados, hasta 120 sesiones concurrentes en el pico. No es un problema de escala masiva; es un problema de **corrección, aislamiento y diseño**.

---

## 2. El producto

**Plataforma de e-learning gamificada para enseñar programación y desarrollo de software.**

Cada curso es un roadmap de aprendizaje incremental. Los alumnos avanzan resolviendo desafíos, acumulan XP, monedas, vidas, insignias y equipamiento, compiten en un ranking por curso, y son asistidos —nunca reemplazados— por agentes de IA con restricciones pedagógicas estrictas.

Hay dos clases de desafío:

- **Teóricos**: ítems con corrección automática.
- **Prácticos**: se resuelven en un **IDE integrado con asistencia de IA**. Los tipos incluyen algoritmos con pruebas unitarias automáticas, refactorización, hackathons con límite de tiempo, "encuentra el bug", completado de bloques y simulación de code review.

**Los desafíos prácticos de tipo "algoritmos con pruebas unitarias automáticas" son los que nos dan trabajo:** alguien tiene que ejecutar el código que escribió el alumno, contra los tests que escribió el profesor, y devolver un veredicto confiable.

Reglas del producto que nos condicionan directamente:

- **No hay borrado físico de producción académica.** El código del alumno es elemento de juicio y se conserva.
- **La indisponibilidad de una dependencia externa no puede impedir que un alumno entregue.** Eso empuja el diseño hacia asincronía y degradación controlada.
- **El score de IA modifica el XP, y el XP determina promoción y regularidad.** Traducido: un veredicto equivocado del sandbox tiene consecuencia académica real. Un falso negativo le cuesta una vida a un alumno.

---

## 3. Los 12 temas y el mapa de servicios

| Tema | Servicio | Responsabilidad |
|---|---|---|
| 01 | `ms-identidad` | Registro, autenticación, roles, auditoría. *Extra:* API Gateway |
| 02 | `ms-cursos` | Cursos, comisiones, inscripción, ciclo de vida y archivado |
| 03 | `ms-desafios` | Motor de desafíos: ciclo de vida, publicación, entregas, **versionado** |
| 04 | `ms-teoricos` | Ítems teóricos, corrección, encuestas anónimas |
| 05 | `ms-practicos` | **Consignas de código, casos de prueba, formato de entrega, feedback.** *Extra:* anti-cheat por similitud |
| **06** | **`ms-sandbox`** | **Ejecución aislada; límites de CPU, memoria y tiempo; captura de salida.** *Extra:* almacenamiento de artefactos |
| 07 | `ms-eval-ia` | Rúbricas, invocación del modelo, golden set, calibración por curso |
| 08 | `ms-banco` | Ledger de movimientos, transacciones, saldos |
| 09 | `ms-mercado` | Catálogo, compra, inventario, subastas |
| 10 | `ms-progreso` | Grafo de contenidos, prerequisitos, XP, niveles, logros |
| 11 | `ms-social` | Chat, equipos, notificaciones, reportes |
| 12 | `ms-backoffice` | Administración de plataforma, reportes docentes |

Vale notar algo del encuadre: la partición en 12 temas es **temática**, no una descomposición por *bounded context*. Algunos temas no son un servicio de dominio, y el nuestro es uno de esos: **`ms-sandbox` no tiene dominio de negocio. Es un pool de cómputo con contrato.** No hay CRUD, no hay reglas de negocio propias; hay una función que recibe código y devuelve un veredicto.

---

## 4. Nuestro tema, en detalle

### 4.1 Qué pide el enunciado

```
TEMA 06 — Sandbox / Runtime
Detalle:  Ejecución aislada
          Límites de CPU, memoria y tiempo
          Captura de salida
Extra:    Almacenamiento de artefactos de ejecución
```

Eso es todo lo que dice el enunciado. **El resto es decisión nuestra**, y ahí está la parte interesante del trabajo.

### 4.2 El objetivo, en una frase

> **Construir un servicio que ejecute código no confiable, escrito por un alumno, junto a pruebas escritas por un profesor, en un entorno del que ese código no pueda escapar ni al que pueda hacer daño, y que devuelva un veredicto confiable en el que se pueda fundar una nota.**

Las tres palabras que cargan el peso:

- **No confiable** — el alumno puede equivocarse, pero también puede intentar hacer trampa deliberadamente. Los dos casos tienen que estar cubiertos.
- **Escapar** — un contenedor comprometido en una plataforma con service discovery podría alcanzar a los otros once servicios.
- **Confiable** — un veredicto que se puede falsear no sirve para calificar.

### 4.3 Quién nos usa y cómo

El único consumidor real es **T05 (Desafíos Prácticos)**. El flujo es:

```
alumno escribe código en el IDE
        ↓
T05 arma la entrega: código del alumno + casos de prueba del profesor
        ↓
POST /api/v1/sandbox/ejecuciones   →  202 Accepted, ejecución ENCOLADA
        ↓
[ el sandbox ejecuta de forma asíncrona ]
        ↓
T05 consulta el resultado, o recibe el evento EjecucionFinalizada
        ↓
T05 le muestra el feedback al alumno; T03 registra la entrega
```

**Contrato en una línea:** el mensaje es **autocontenido** — trae el código *y* los tests. El sandbox nunca le pregunta nada a T05 durante la ejecución. Eso lo convierte en una **función pura** (mismo input → mismo output), da reproducibilidad, y evita una dependencia circular `T05 → sandbox → T05`.

### 4.4 Lenguaje soportado

**Java únicamente, por ahora.** No por limitación de diseño sino de alcance: soportar un solo lenguaje bien vale más que soportar tres mal.

---

## 5. Restricciones ya fijadas

### 5.1 Stack (lo pone la cátedra, no está en discusión)

| Capa | Elección |
|---|---|
| Lenguaje | Java 21 (LTS) |
| Framework | Spring Boot 3.x |
| Base de datos | PostgreSQL — *database per service* |
| Broker | RabbitMQ |
| Resiliencia | Resilience4j |
| Observabilidad | Micrometer + OpenTelemetry → Prometheus / Grafana / Loki / Tempo |
| Empaquetado | Docker + docker-compose |
| Pruebas | JUnit 5 + Testcontainers |
| Frontend | Angular 21+ (fuera de nuestro alcance) |

Consecuencia particular del stack para nosotros: **corremos sobre la JVM y lanzamos contenedores que también son JVM.** Eso hace que el dimensionamiento de memoria y de tiempo no sea trivial (ver §7).

### 5.2 Escala

120 usuarios, hasta 120 sesiones concurrentes en el pico. Pero **120 sesiones no son 120 ejecuciones simultáneas**: la gente lee, escribe y piensa. El dimensionamiento actual asume un pool de 4–6 ejecuciones en paralelo con cola.

### 5.3 Restricciones de la unidad de Microservicios

El trabajo tiene que mostrar los patrones de la materia aplicados —o argumentar por qué **no** aplican, que también cuenta. Los que sí aplican en nuestro caso: **Sidecar** (proxy del socket de Docker), **Health Check** (con el giro de que "saturado" no es "enfermo"), **Circuit Breaker** (sobre el demonio de Docker, no sobre HTTP), **Rate Limit** (por concurrencia, no por frecuencia), **EDA**, **DDD** con un *anti-corruption layer* sobre JUnit. Los que no: **BFF** (es capa de frontend, vive en T05) y **SAGA** como coordinador (somos un paso de la coreografía, no tenemos nada que compensar).

---

## 6. Decisiones de diseño ya tomadas

Esto es lo que ya está decidido y argumentado. Sirve como punto de partida, y también como lista de cosas a desafiar si una fuente externa tiene un argumento mejor.

**Aislamiento** — contenedor Docker efímero, uno por ejecución, nunca reusado. Compuesto por: `--network none` (sin red, es la defensa central), `--read-only` + `tmpfs`, `--memory` con `--memory-swap` igual, `--cpus`, `--pids-limit`, `--cap-drop ALL`, `--security-opt no-new-privileges`, usuario sin privilegios. Se evaluaron y descartaron: `SecurityManager` de la JVM (deprecado y deshabilitado), `nsjail`/`bubblewrap` (más liviano pero no capitaliza la unidad de Docker), microVMs tipo Firecracker (necesitan virtualización anidada), y un Job de Kubernetes por ejecución (el scheduling lo mata). gVisor queda como extra si sobra tiempo.

**Riesgo residual asumido y declarado:** el kernel es compartido. Un exploit de kernel escapa del contenedor.

**Arquitectura interna** — API + worker, separados por una cola de RabbitMQ. La API acepta y responde `202`; el worker consume, ejecuta y persiste. Ack manual, prefetch acotado, DLQ para mensajes envenenados, **outbox transaccional** para que "guardé pero no publiqué" no sea un estado alcanzable.

**Acceso a Docker** — el worker **no** tiene el socket. Habla con un **sidecar** que sí lo tiene y expone una lista blanca. El motivo: el socket de Docker es la API HTTP completa del demonio y **no tiene modelo de autorización** — quien puede hablarle puede montar `/` en un contenedor privilegiado.

**Ejecución sin Maven** — `javac` + `junit-platform-console-standalone.jar` horneado en la imagen. Maven offline sigue tardando segundos en resolver plugins, y sin red no puede descargar nada.

**Veredicto desde el reporte, nunca desde el exit code** — porque `System.exit(0)` en el código del alumno falsea el exit code.

---

## 7. Lo que ya verificamos empíricamente

Corrimos un prototipo descartable del núcleo contra Docker real, con diez entregas de prueba (seis hostiles). Estos números son **medidos**, no estimados, y varios contradijeron lo que habíamos diseñado en papel. Sirven como base fáctica para cualquier consulta externa.

| Hallazgo | Detalle |
|---|---|
| **El tiempo se lo lleva el toolchain, no el código** | Con `a + b` y 3 tests: 4–8 s de reloj total. El código del alumno corre en microsegundos |
| **El arranque de la JVM no es el problema** | `java -version` dentro del contenedor: **38–82 ms**. Esto tumbó nuestra hipótesis de optimizar con AppCDS |
| **Dónde sí está el tiempo** | `javac`: 1.0–2.3 s por invocación (son dos). Descubrimiento de tests de JUnit: 1.2–2.9 s |
| **La CPU es la palanca real** | De 1 a 2 CPU: el reloj bajó de 8.5 s a 5.1 s. `javac` paraleliza; el código del alumno no |
| **Un timeout único no funciona** | Con el presupuesto que teníamos diseñado, **el camino feliz daba TIMEOUT**: compilar se comía el reloj del alumno |
| **La varianza es grande** | La misma entrega, corridas seguidas: la fase de tests varió entre 1.8 s y 5.2 s |
| **El OOM no llega como `OOMKilled`** | Con la JVM bien configurada, muere ella antes que el cgroup: `exitCode 3`, `OOMKilled: false` |
| **`docker cp` es incompatible con `--read-only`** | El demonio lo rechaza. El bundle terminó entrando por el **`stdin`** del contenedor, como tar |
| **Contención verificada** | Loop infinito, fork bomb, OOM, intento de red, `System.exit(0)`, path traversal: los seis contenidos |

---

## 8. Lo que queremos investigar

Estas son las preguntas abiertas. Están ordenadas por cuánto cambiarían el diseño si la respuesta fuera inesperada.

### Aislamiento y seguridad

1. **Filtrado del socket de Docker por contenido del body.** Los proxies de socket estándar (tipo `tecnativa/docker-socket-proxy`, basados en HAProxy) filtran por **path y método HTTP**, no por el cuerpo del request. Como hay que permitir `POST /containers/create`, un atacante que llegue al proxy puede pedir `Privileged: true`. ¿Existe una herramienta madura que filtre el body? ¿O la práctica real de la industria es invertir el diseño y que el sidecar **sea** el ejecutor, con la spec del contenedor hardcodeada?
2. **Cómo lo resuelven los que hacen esto en serio.** ¿Qué arquitectura usan realmente Judge0, Piston, HackerRank, LeetCode, Codewars, AWS Lambda? Nos interesa especialmente **si usan Docker o algo más fuerte** (gVisor, Firecracker, WASM), y qué los llevó a esa decisión.
3. **WebAssembly como alternativa.** ¿Está maduro el ecosistema para ejecutar Java compilado a WASM con límites de recursos? Sería un modelo de aislamiento distinto y probablemente más rápido, pero no capitaliza la unidad de Docker de la materia.
4. **Superficie residual real de un contenedor sin red, sin capabilities, read-only y con seccomp default.** ¿Cuáles son los vectores de escape conocidos que quedan, más allá de "un exploit de kernel"?

### Rendimiento

5. **Cómo reducir el costo de `javac` y del descubrimiento de JUnit**, que son los dos consumidores reales de tiempo. ¿Sirve el *compiler API* de Java en un proceso caliente? ¿Vale precompilar la suite de tests por versión y cachearla? ¿Hay forma de saltear el escaneo de classpath dando la lista de clases explícita?
6. **Pool de contenedores calientes.** Queremos ahorrar el `create` del camino crítico sin reusar nunca un contenedor entre dos alumnos. ¿Cuáles son los patrones conocidos y sus trampas?
7. **Cómo se fija un timeout con varianza alta.** Medimos 1.8 s a 5.2 s en corridas idénticas. ¿Qué práctica se usa para no producir falsos `TIMEOUT` intermitentes sobre código correcto?

### Contrato y corrección

8. **Normalización de reportes de test entre frameworks.** Hoy leemos el XML de JUnit 5. Si mañana entra otro lenguaje, ¿qué modelo de veredicto normalizado se usa habitualmente? ¿Hay un formato de intercambio estándar más allá del XML de JUnit / TAP?
9. **Anti-trampa en ejecución de código.** Ya cubrimos `System.exit(0)`. ¿Qué otros bypasses conocidos existen cuando el código del alumno y los tests corren **en la misma JVM**? Nos preocupa especialmente la reflection sobre el runner. ¿Vale la pena partir en dos procesos?
10. **Cómo se le comunica al alumno un veredicto negativo sin filtrar la solución.** Hay tests ocultos: el alumno debe saber que falló uno sin ver ni el mensaje ni la entrada.

### Diseño y patrones

11. **Contrapunto a "database per service" aplicado al broker.** Concluimos que la analogía, seguida fielmente, da **un solo broker con separación lógica**, no un broker por servicio. ¿Es la lectura estándar? ¿Qué compran realmente los vhosts de RabbitMQ?
12. **Sidecar en un lenguaje distinto al de la aplicación.** Nuestra postura es que es normal y parte del patrón. ¿Se sostiene esa lectura en la bibliografía de microservicios?

---

## 9. Cómo preguntar

Si vas a llevar esto a una fuente externa, algunas cosas que ayudan a que la respuesta sirva:

- **Decí que es un trabajo académico con escala chica.** Sin eso, la respuesta por defecto es Kubernetes y autoescalado, que acá es ruido.
- **Decí que Docker es parte del temario.** Una respuesta que diga "usá Firecracker" es técnicamente mejor y académicamente inútil, salvo como alternativa evaluada y descartada.
- **Pedí el porqué, no la receta.** Lo que se evalúa es el argumento. Una respuesta sin fundamento no se puede defender.
- **Pedí las alternativas descartadas.** En este trabajo, decir "evaluamos X y lo descartamos porque Y" vale tanto como la decisión tomada.

---

**Documentos internos del grupo, por si hace falta profundizar:**
`docs/arquitectura/01-panorama-microservicios-backend.md` (mapa de los 12 servicios y el stack) ·
`03-ms-sandbox-ejecucion.md` (aislamiento, contrato con T05, máquina de estados, API) ·
`04-ms-sandbox-worker.md` (el worker en detalle) ·
`05-ms-sandbox-patrones.md` (los patrones de la unidad, aplicados y descartados).
