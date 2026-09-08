# `ms-sandbox` — Propuesta de arquitectura

> **Tema 06 — Sandbox / Runtime.** Grupo 8, 11 integrantes.
> UTN FRC · Programación 4 + Metodología de Sistemas 2 · TPI 2026.
>
> **Qué es este documento.** La propuesta completa del servicio, para leer de una sentada.
> Es el punto de entrada: cada decisión está resumida con su fundamento, y cuando hace falta
> profundizar, un puntero lleva al documento técnico correspondiente.
> No repite las mediciones, los CVEs ni el detalle de implementación — eso vive en los otros cuatro.

---

## 1. Qué construimos

> ## ⚠ Una parte de esta propuesta cambió (8‑sep‑2026)
>
> Después de escribir esto acordamos con el Grupo 5 el **modelo de dos capas**: adentro del
> contenedor corren dos programas, nuestro entrypoint y un script de evaluación que escriben ellos.
> El servicio deja de saber Java y pasa a ser **infraestructura agnóstica de lenguaje**.
>
> Lo que eso cambia acá: **§2, el contrato con T05**, está superado —el request pierde el lenguaje,
> los roles y la visibilidad por archivo, y gana un `profileId`—; **§8** menciona la regla de que
> el veredicto sale del XML de JUnit, que se sostiene como principio pero cambia de lugar (**D16**);
> y **§10** quedó corto, porque aparecieron seis decisiones nuevas y una familia de endpoints.
>
> **§4, §5, §6, §7 y §11 no se mueven** — el aislamiento, el hallazgo que reordenó el diseño, las
> mediciones y la regla que atraviesa todo siguen siendo exactamente lo que eran. De hecho ganan
> peso: pasan a ser casi lo único que aportamos al veredicto.
>
> El panorama completo está en [`11-impacto-v4-g5.md`](./11-impacto-v4-g5.md), y el estado de
> decisiones actualizado en el [`README.md`](./README.md) §3.

> **Un servicio que ejecuta código no confiable, escrito por un alumno, junto a pruebas escritas
> por un profesor, en un entorno del que ese código no puede escapar ni al que puede hacer daño,
> y que devuelve un veredicto confiable en el que se puede fundar una nota.**

Tres palabras cargan el peso de esa frase, y cada una define una parte del diseño:

| Palabra | Qué significa para nosotros |
|---|---|
| **No confiable** | El alumno puede equivocarse, pero también puede intentar hacer trampa a propósito. Los dos casos tienen que estar cubiertos, y son problemas distintos |
| **Escapar** | La plataforma tiene service discovery: un contenedor comprometido podría alcanzar a los otros once servicios y, por ejemplo, acreditarse monedas |
| **Confiable** | Un veredicto que se puede falsear no sirve para calificar. **El score modifica el XP, y el XP determina promoción y regularidad** |

El enunciado del tema pide cuatro cosas —ejecución aislada, límites de CPU/memoria/tiempo, captura de salida, y como extra el almacenamiento de artefactos—. **Todo lo demás de este documento es decisión nuestra**, y ahí está el trabajo.

### El encuadre que conviene decir de entrada

La partición del curso en 12 temas es **temática, no por bounded context**. Algunos temas no son un servicio de dominio, y el nuestro es uno de esos:

> **`ms-sandbox` no tiene dominio de negocio. Es un pool de cómputo con contrato.**
> No hay CRUD, no hay reglas de negocio propias. Hay una función que recibe código y devuelve un veredicto.

Eso no lo hace menos valioso: lo ubica. En términos de DDD es un **subdominio genérico** —existe Judge0, existe Piston, se podría comprar— y a cambio tiene el **bounded context más limpio de los doce servicios**: no comparte una sola entidad con nadie. No sabe qué es un alumno, un curso ni una moneda.

**Alcance:** Java únicamente, por ahora. No es una limitación de diseño sino de foco — soportar un lenguaje bien vale más que soportar tres mal.

📄 *Detalle: [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) §4.3 · [`05-ms-sandbox-patrones.md`](./05-ms-sandbox-patrones.md) §4*

---

## 2. Cómo se usa: el contrato con T05

Nuestro único consumidor real es **T05 — Desafíos Prácticos**.

```
alumno escribe código en el IDE
        ↓
T05 arma la entrega: código del alumno + tests del profesor
        ↓
POST /api/v1/sandbox/ejecuciones   ──vía API Gateway──►  202 Accepted, ENCOLADA
        ↓
[ el sandbox ejecuta de forma asíncrona ]
        ↓
T05 consulta el resultado (vía gateway), o recibe el evento
EjecucionFinalizada (vía bus, que no pasa por el gateway)
        ↓
T05 le muestra el feedback al alumno; T03 registra la entrega
```

**Toda llamada sincrónica pasa por el gateway**, incluida esta: no hay comunicación directa entre microservicios. El evento de vuelta, en cambio, viaja por el bus, que es el camino de lo asincrónico.

### La decisión de fondo: el mensaje se basta a sí mismo

El request trae **el código y los tests**. El sandbox nunca le pregunta nada a T05 durante la ejecución.

Se evaluaron tres opciones y se eligió la autocontenida por tres beneficios que se refuerzan entre sí:

- **Es una función pura** — mismo input, mismo output. Sin estado compartido ni caché que invalidar.
- **Reproducibilidad gratis** — se guarda el bundle exacto y se puede re-correr una entrega de hace semanas de forma idéntica.
- **Independencia de disponibilidad** — si T05 se cae con jobs en cola, los jobs se ejecutan igual.

La alternativa de que el sandbox le pida los tests a T05 se descartó de entrada: crea una **dependencia circular** `T05 → sandbox → T05`, atraviesa el gateway dos veces y mete a T05 en el camino crítico de cada ejecución.

### El `cursoId` viaja, pero no lo interpretamos

No tenemos entidades de dominio, así que la regla de acotar todo por curso-cohorte no nos aplica del modo en que aplica a los demás. Pero **sí necesitamos el `cursoId` como campo de correlación**: cuando un curso se archiva hay que poder cancelar sus ejecuciones encoladas, y para eso hay que poder agruparlas.

> Lo guardamos y lo indexamos; no lo consultamos contra nadie, no lo validamos y no somos dueños de él. Es una etiqueta opaca, y esa es exactamente la diferencia entre correlacionar y conocer el dominio.

### Por qué asíncrono, y por qué eso no es negociable

El `POST` devuelve `202` de inmediato. Ninguna ruta del sandbox puede quedar colgada quince segundos ocupando un slot del gateway que necesitan los otros once grupos. **Es el mejor argumento para que el contrato asíncrono se acepte sin discusión.**

📄 *Detalle: [`03`](./03-ms-sandbox-ejecucion.md) §2 y §6 (contrato completo de API)*

---

## 3. La arquitectura, en un diagrama

```
   T05 ──HTTP──► ┌─────────────┐
                 │ API GATEWAY │  única puerta de entrada
                 └──────┬──────┘
                        │
                        ▼
                 ┌─────────────────────────────────────────────┐
                 │  API          acepta, valida, responde 202  │
                 └──────────────────────┬──────────────────────┘
                                        │ outbox transaccional
                                        ▼
                        ┌───────────────────────────┐
                        │  RabbitMQ  sandbox.jobs   │  ← cola de TRABAJO
                        └─────────────┬─────────────┘
                                      │ ack manual, prefetch 1
                                      ▼
                 ┌─────────────────────────────────────────────┐
                 │  WORKER      no tiene el socket de Docker   │
                 └──────────────────────┬──────────────────────┘
                                        │ HTTP
                                        ▼
                 ┌─────────────────────────────────────────────┐
                 │  SIDECAR     el único con el socket         │
                 └──────────────────────┬──────────────────────┘
                                        ▼
                 ┌─────────────────────────────────────────────┐
                 │  CONTENEDOR EFÍMERO                          │
                 │  sin red · read-only · sin capabilities      │
                 │  un contenedor, una ejecución, y se destruye │
                 └─────────────────────────────────────────────┘
```

Cuatro decisiones estructurales, cada una respondiendo a un problema concreto:

**API y worker separados por una cola.** Sin la cola, una ráfaga de entregas agota los threads HTTP, nada limita la concurrencia, los reintentos hacen espiral y un deploy pierde entregas. La cola convierte los cuatro problemas en uno solo ya resuelto: backpressure.

> La cola figura en el reparto del tema como alcance *"para más adelante"*, pero está acá desde el primer sprint por una razón de plataforma: **es lo que hace posible el `202`**, y sin el `202` una ruta del sandbox puede quedar colgada quince segundos ocupando un slot del gateway que necesitan los otros once grupos. No es una optimización de rendimiento nuestra; es lo que evita que un pico de cierre se lleve puesta la única puerta de entrada del sistema.

**Outbox transaccional.** El resultado y el evento se escriben en la misma transacción, o no se escribe ninguno. Sin eso, *"guardé pero no publiqué"* es un estado alcanzable — y en una cascada de seis servicios significa que la mitad del sistema no se entera.

**El worker no toca Docker directamente.** El socket de Docker es la API HTTP completa del demonio y **no tiene modelo de autorización**: quien puede hablarle puede montar `/` en un contenedor privilegiado. El sidecar es la capa de autorización que Docker no trae.

**Un contenedor por ejecución, nunca reusado.** Es la regla que hace que el trabajo de un alumno no pueda contaminar el de otro.

📄 *Detalle: [`04-ms-sandbox-worker.md`](./04-ms-sandbox-worker.md) — el worker completo · [`05`](./05-ms-sandbox-patrones.md) §1 — el sidecar*

---

## 4. Cómo se aísla el código

"Sandbox" no es un mecanismo: son cinco mecanismos del kernel de Linux que Docker compone. Conviene tenerlos separados, porque la pregunta de defensa es *qué protege de qué*.

| Mecanismo | Qué controla |
|---|---|
| **Namespaces** | Qué **ve** el proceso — sus propios procesos, su propio filesystem, **y ninguna red** |
| **cgroups** | Qué **consume** — memoria, CPU, cantidad de procesos |
| **Capabilities** | Qué operaciones privilegiadas puede hacer — ninguna, se sacan todas |
| **seccomp** | Qué llamadas al sistema puede invocar |
| **Filesystem** | Dónde puede escribir — solo en un disco en memoria que muere con el contenedor |

### El aislamiento de red es la defensa central, no una buena práctica genérica

Con service discovery, cualquier proceso con red resuelve `ms-banco` por nombre y le pega. Si el código del alumno tuviera red, `POST http://ms-banco/api/v1/creditos` sería un bypass de todas las reglas de negocio de la plataforma.

> **Sin red, el peor código imaginable no alcanza a nadie.** Es lo que sostiene la integridad del sistema entero, y por eso es la primera línea del diseño y no la última.

Corolario operativo: el código y los tests **entran por copia**, nunca descargándose. La imagen viene precocinada con el JDK y JUnit adentro.

### El riesgo que asumimos, declarado

El kernel es compartido. Un exploit de kernel escapa del contenedor, y también existe una familia conocida de bugs en `runc`, el componente que arma el contenedor.

Lo importante no es que exista el riesgo —existe en toda solución basada en contenedores— sino **poder decir cuánto vale y por qué lo aceptamos**:

- Está medido. Un estudio comparativo de 2026 muestra que en 24 meses **4 de 4 CVEs de escape de esta clase de motor fueron de escape**, contra 0 de 3 en la alternativa más segura.
- Elegimos igual esta clase de motor, con conocimiento: la alternativa fuerte (microVMs) necesita virtualización anidada que probablemente no tengamos, y **Docker es el temario de la materia**.
- Lo compensamos donde importa: sin red, el vector que nos preocupa —alcanzar a los otros once servicios— está cortado.

Además hay dos endurecimientos baratos identificados y todavía no aplicados: **`userns-remap`** (una línea de configuración, hace que el root del contenedor no sea root del host) y un **perfil seccomp propio** (el default de Docker deja 361 llamadas al sistema permitidas; nosotros solo corremos `javac` y `java`).

📄 *Detalle: [`03`](./03-ms-sandbox-ejecucion.md) §1.1 y §4.1*

---

## 5. El hallazgo que reordenó el diseño

Este es el punto que más conviene contar en la defensa, porque cambia qué se está defendiendo.

Buscamos incidentes reales en plataformas que hacen exactamente esto. Encontramos cinco, en dos proyectos —**Judge0**, la plataforma de ejecución de código más usada, y **Ares**, el sandbox de pruebas de la Technische Universität München—. Y:

> **Ninguno de los cinco fue un exploit de kernel. Los cinco fueron bugs de lógica en el código
> de orquestación de la propia plataforma.**

Un symlink que intercepta una escritura. Class files inyectados en un paquete confiable. Un parche que se bypasseó con `chown`.

Eso tiene una consecuencia directa sobre dónde poner el esfuerzo:

| | |
|---|---|
| **Lo que creíamos que era el riesgo** | "El kernel es compartido" |
| **Dónde está el riesgo de verdad** | **Nuestro código**: el que arma el bundle, lo inyecta en el contenedor y lee el reporte |

Y una consecuencia argumentativa que vale la pena enunciar tal cual:

> *"Asumimos el riesgo de un exploit de kernel"* es una frase que suena madura pero es barata.
> *"Asumimos el riesgo de kernel **y auditamos específicamente la clase de bug que tumbó a Judge0**"* es otra cosa.

### La distinción que salió de ahí

Ese hallazgo nos hizo separar dos propiedades que teníamos mezcladas:

**El contenedor protege el host. No protege el veredicto.**

Son dos cosas distintas, y la segunda no la cierra ningún flag de Docker. Ejemplo concreto, que es la versión de un CVE real de Ares aplicada a nosotros: nada impedía que un alumno declarara su clase **en el mismo paquete que los tests del profesor** y sombreara una clase de soporte de la que los tests dependen. Los tests pasan. El contenedor está perfectamente aislado. El veredicto es falso — y el veredicto es la nota.

Se cierra con tres medidas baratas, todas del lado nuestro: validar el paquete que declara el alumno, compilar en directorios separados poniendo los tests primero, y asegurar que el reporte se escriba donde el alumno no pueda tocarlo.

📄 *Detalle: [`03`](./03-ms-sandbox-ejecucion.md) §1.6d · [`hallazgos-investigacion-sandbox.md`](../../hallazgos-investigacion-sandbox.md) §9*

---

## 6. Cómo se ejecuta, y por qué así

### Sin Maven

Sin red, Maven no descarga nada — y aun offline levanta una JVM y resuelve plugins durante segundos. Vamos directo con `javac` y el jar de JUnit, los dos ya dentro de la imagen.

**Y compilamos en dos pasadas, no en una.** Cuesta medio segundo más, y a cambio separa tres culpas distintas:

| Qué falla | Veredicto | De quién es la culpa |
|---|---|---|
| Compilar el código del alumno | `ERROR_COMPILACION` | **Del alumno** |
| Compilar los tests | `SUITE_INVALIDA` | **De T05** — no le consume vida al alumno |
| Correr los tests | `TESTS_FALLIDOS` | Del alumno |

Saber de quién es la culpa vale más que ese medio segundo.

### El veredicto sale del reporte, nunca del código de salida

Si el veredicto saliera del código de salida del proceso, **cualquier alumno aprobaría escribiendo `System.exit(0)`**: el proceso termina informando "todo bien", con cero tests corridos. Lo verificamos y efectivamente aprobaba.

> **Regla: sin reporte válido, con al menos un test corrido, no hay éxito posible.**

De la misma familia hay un caso más sutil que también encontramos probando: cuando la JVM está bien configurada y el alumno agota la memoria, **muere la JVM antes que el contenedor**, así que la marca de "lo mataron por memoria" viene en falso. Si el veredicto se decidiera solo por esa marca, una entrega que reventó por memoria se clasificaría como falla nuestra y no le consumiría vida: intentos infinitos fugando memoria.

### Los tres relojes

Este es el cambio más importante de la última revisión, y salió de estudiar cómo lo resuelven Judge0 y Piston.

El problema medido: con un reloj único, **el camino feliz daba TIMEOUT** — compilar se comía el presupuesto del alumno. Y peor: la misma entrega, en corridas seguidas, variaba entre 1.8 y 5.2 segundos, porque otras ejecuciones competían por el procesador. Eso produce `TIMEOUT` intermitentes sobre código correcto, que es el peor modo de falla posible: no es reproducible y el alumno no puede distinguirlo de un bug suyo.

La solución de la industria no es agrandar el presupuesto ni calibrarlo con percentiles. Es separar los relojes por fase y, sobre todo, **medir el del alumno en tiempo de procesador y no en tiempo de reloj**:

| Reloj | Qué mide | Para qué |
|---|---|---|
| **Del alumno** | **Tiempo de procesador** | Decide el veredicto `TIMEOUT` |
| De compilación | Tiempo de reloj | Detectar un compilador colgado — es costo de plataforma, no se le cobra al alumno |
| Global | Tiempo de reloj, generoso | Red de seguridad contra un proceso que **duerme** en vez de consumir procesador |

> **El tiempo de procesador no cuenta el tiempo en que el sistema operativo se lo dio a otro.**
> Dos corridas idénticas dan el mismo número aunque el servidor esté saturado.
> Los TIMEOUT intermitentes desaparecen **porque desaparece su causa**, no porque se les agregó margen.

📄 *Detalle: [`03`](./03-ms-sandbox-ejecucion.md) §1.4 (todo lo medido), §1.6 (los tropiezos de Java), §4.3 (los relojes)*

---

## 7. Qué medimos, y qué nos desmintió

Corrimos un prototipo descartable contra Docker real, con diez entregas de prueba, seis de ellas hostiles. **Varias mediciones contradijeron lo que habíamos diseñado en papel**, y eso es parte de lo que presentamos:

| Lo que suponíamos | Lo que salió |
|---|---|
| El arranque de la JVM es el costo principal → optimizar con AppCDS | **Falso.** La JVM arranca en 38–82 ms. El tiempo se lo lleva el toolchain: compilar y descubrir tests |
| Un presupuesto de tiempo único alcanza | **Falso.** El camino feliz daba `TIMEOUT` |
| Copiar los archivos al contenedor con `docker cp` | **No funciona** con un filesystem de solo lectura. El bundle terminó entrando por la entrada estándar del contenedor |
| Quedarse sin memoria llega marcado como tal | **Falso** con la JVM bien configurada |
| La ruta de los archivos estaba validada | **No lo estaba.** Dos entregas maliciosas quedaron contenidas **por accidente**, no por diseño |

Los seis casos hostiles —bucle infinito, bomba de procesos, agotar memoria, intento de red, `System.exit(0)`, ruta maliciosa— quedaron contenidos y correctamente clasificados.

> **Lo que vale acá no es el número, es el método.** La suposición era razonable, la medición la desmintió en una tarde, y quedó escrito qué creíamos y qué salió. Vale más la corrección que el acierto.

📄 *Detalle: [`03`](./03-ms-sandbox-ejecucion.md) §1.4 · [`04`](./04-ms-sandbox-worker.md) §16 y §17*

---

## 8. Los patrones de la materia

La premisa: el error clásico de TP es forzar los diez patrones, y un profesor lo nota. Decir *"este no aplica, y este es el motivo"* con fundamento vale más que un Circuit Breaker decorativo.

### Los cuatro con los que lideramos

| Patrón | Cómo aplica acá |
|---|---|
| **Sidecar** ⭐ | El acceso mediado al socket de Docker. **El mejor caso del tema**: resuelve un problema de seguridad real y específico, no uno genérico |
| **Health Check** ⭐ | Con un giro propio: **"saturado" no es "enfermo"**. Un worker con la cola llena está funcionando perfectamente; si reportara estar caído, la carga se corre a los demás y **cae en cascada todo el servicio por estar trabajando a full**. Saturación se responde rechazando con "reintentá en N segundos", nunca declarándose enfermo |
| **Circuit Breaker** ⭐ | No sobre HTTP —casi no llamamos a nadie— sino **sobre el demonio de Docker**. Y con una vuelta: **corta el reclamo de trabajo, no los jobs**. Un job nunca se marca fallado porque se cayó la infraestructura |
| **DDD** | El bounded context más limpio de los doce, y una **capa anticorrupción** sobre JUnit que impide que el formato de reporte de una librería se filtre a la plataforma |

Un detalle del Health Check que nos parece el más interesante del tema: al arrancar, antes de declararse listo, el worker corre **una ejecución canaria** —un job trivial conocido— por el pipeline real completo. Detecta la clase entera de fallas que un chequeo que solo pinguea la base nunca ve: la imagen se construyó mal, todo compila, y nada corre.

### Los que aplican con matices

- **Rate Limit** — el matiz es que el recurso escaso es **CPU física, que no se multiplexa**. Lo que salva no es limitar pedidos por segundo sino **cuántas ejecuciones tiene un alumno en vuelo**: con 6 slots, uno que encola 20 deja a los otros 119 esperando. Es *fairness*, no anti-abuso.
- **EDA** — somos productor y consumidor, y además el broker es nuestra **cola de trabajo**, que es un uso distinto del de notificación y conviene no mezclarlos al explicarlo.
- **SAGA** — somos un **paso** de la coreografía, no un participante con compensación: ejecutar código no deja efectos que deshacer. La compensación de verdad ocurre aguas abajo, revirtiendo acreditaciones.
- **Service Discovery** — con una observación fina: **el worker no se registra**, porque nadie lo llama. Jala trabajo de la cola. El registro sirve para que te encuentren.

### El que no aplica

**BFF.** Un BFF adapta un backend a una pantalla concreta, y el sandbox no tiene cliente de UI: su consumidor es otro microservicio. El BFF del IDE, si existe, es de T05. *Estirar la definición para poder marcar la casilla debilita el argumento en vez de fortalecerlo.*

📄 *Detalle: [`05-ms-sandbox-patrones.md`](./05-ms-sandbox-patrones.md) — los diez, uno por uno*

---

## 9. Lo que evaluamos y descartamos

Según el criterio de la cátedra, *"evaluamos X y lo descartamos porque Y"* vale tanto como la decisión tomada. Esta es la lista corta.

| Alternativa | Por qué se descartó |
|---|---|
| **`SecurityManager` de la JVM** | Java lo deprecó y después lo **deshabilitó permanentemente**; la especificación se revisó para que no se pueda habilitar. No hay reemplazo. **No existe el sandbox dentro de la JVM** — por eso el contenedor no es una opción entre varias, es la única |
| **Partir la ejecución en dos procesos** | No compra frontera de seguridad: mismo contenedor, mismo usuario. La implementación de referencia de esta idea (Ares, de la TUM) **fue vulnerada dos veces** y su propio mantenedor declaró que el diseño no tiene futuro |
| **WebAssembly** | El bloqueante no es la madurez del tooling: es que WASM exige saber en tiempo de compilación qué clases existen, y **JUnit funciona enteramente por reflection**, sobre código que recibimos en tiempo de ejecución. El modelo de aislamiento es más limpio pero es incompatible con nuestro contrato |
| **microVMs (Firecracker)** | Aíslan mejor, pero necesitan virtualización anidada que probablemente no tengamos, y se van del temario |
| **`nsjail` / `bubblewrap`** | Más livianos que Docker, pero no capitalizan la unidad de la materia |
| **Un Job de Kubernetes por ejecución** | El costo de planificación por ejecución lo mata |
| **Pool de contenedores calientes** | Es el patrón de AWS Lambda y la regla que nos habíamos puesto —no reusar entre usuarios— resultó ser la misma que se puso AWS. **Lo descartamos por falta de beneficio medido**: el arranque del contenedor no está en nuestro camino crítico. *"Medimos y no valía la pena"* es una respuesta de ingeniería |
| **Un broker por servicio** | *Database per service* protege el acceso a los datos, no la cantidad de instancias. El broker **es** el bus compartido. El aislamiento equivalente lo dan usuarios y permisos por servicio |
| **Maven, aun offline** | Segundos por ejecución resolviendo plugins, sin ganancia |

Y una que **no** descartamos pero conviene explicar, porque un tribunal que conozca el tema la va a preguntar: **ni Judge0 ni Piston levantan un contenedor por ejecución** — usan un sandbox más liviano *dentro* de Docker, porque a escala de miles de ejecuciones por hora el nuestro no cerraría. A 120 usuarios y 4–6 ejecuciones en paralelo, medimos que sí cierra. **La misma decisión a escala de LeetCode sería equivocada**, y decirlo con esa condición de validez explícita es el punto.

📄 *Detalle: [`hallazgos-investigacion-sandbox.md`](../../hallazgos-investigacion-sandbox.md) — la investigación completa, con fuentes*

---

## 10. Qué falta, y qué hay que acordar

### Pendientes técnicos, en orden de impacto

1. **Implementar los tres relojes** con el del alumno medido en tiempo de procesador. Es el único cambio que toca comportamiento observable, y todavía **no está verificado contra Docker**.
2. **Validar el paquete que declara el código del alumno**. Es lo que protege el veredicto, y es barato.
3. **Verificar que el reporte no sea escribible por el código del alumno.** Hoy no está verificado, y es exactamente el bug que vulneró a Judge0.
4. **Dos casos hostiles más** en la suite de pruebas: enlaces simbólicos y enlaces duros dentro del paquete de entrega.
5. **No escanear el classpath** — darle a JUnit la lista de clases, que ya la tenemos.
6. **Cachear la suite de tests compilada** por versión de desafío. Elimina una de las dos compilaciones, y hace que el *extra* del tema participe del camino crítico de rendimiento en vez de ser un adorno.
7. Suite de entregas maliciosas **corriendo en CI**. Es la mejor evidencia posible para la defensa: no es *"diseñamos un sandbox seguro"*, es *"acá está el ataque y acá está contenido"*.
8. Contrato **OpenAPI** y un stub, para que T05 no dependa de nuestra implementación real.

### Lo que aportamos a una definición abierta de plataforma

**Qué pasa con una entrega si el sandbox no responde** es una decisión que el PRD no cierra —define el equivalente para el evaluador LLM, pero no el nuestro— y que afecta a T03, T05 y T10. Es de nuestro tema, así que llegamos con la regla propuesta en vez de con la pregunta:

| Situación | Veredicto | Consume vida | Consume intento |
|---|---|---|---|
| El sandbox no responde, Docker caído, imagen ausente, reporte ilegible | `ERROR_INTERNO` | **No** | **No** |
| La suite del profesor no compila | `SUITE_INVALIDA` | **No** | No |
| Todo lo demás atribuible al alumno | Ver `03` §5.1 | Sí | Sí |

Sale de la regla de §11 —*es preferible fallar que aprobar de más*— y de su corolario: **el alumno nunca paga por una falla nuestra.** Conviene llevarlo a la sesión de integración como propuesta cerrada.

### Definiciones abiertas con otros grupos

**Con T05:**

| Qué | Postura propuesta |
|---|---|
| ¿El profesor escribe clases de test, o pares entrada/salida? | Plantilla desde pares, al menos para empezar: más seguro y uniforme |
| ¿Cuál es el paquete reservado de los tests? | **Bloqueante.** Sin esto no podemos rechazar una entrega que lo usurpe |
| ¿Qué se muestra de un test oculto que falla? | Solo nombre y resultado. **Nunca el mensaje ni la entrada** |
| ¿Quién filtra los tests ocultos? | **Nosotros**, como defensa en profundidad: si el filtrado estuviera en T05, un bug suyo filtraría la solución |
| ¿El botón "Ejecutar" del IDE pasa por el sandbox? | **Definición crítica de alcance** — cambia el dimensionamiento por completo |

**Con T10** — las vidas son suyas: T03 emite el hecho, T10 decide su efecto:

| Qué | Postura propuesta |
|---|---|
| `ERROR_INTERNO` y `SUITE_INVALIDA` **no consumen vida** | Es la regla de §11 vista desde afuera: el alumno no paga por una falla nuestra ni por una suite mal escrita. Tiene que estar escrito de ambos lados del contrato |
| ¿Qué veredicto sí consume vida? | `TESTS_FALLIDOS`, `ERROR_COMPILACION`, `TIMEOUT`, `LIMITE_MEMORIA` y `SALIDA_ANTICIPADA`. Nosotros clasificamos; **la consecuencia la aplica T10** |

**Con T03:**

| Qué | Por qué importa |
|---|---|
| ¿Una versión publicada de un desafío es **inmutable**? | De esto depende que podamos cachear la suite compilada. Si una versión se puede editar en el lugar, la caché serviría veredictos viejos — **y un veredicto viejo es una nota mal puesta** |

**Con T11**, dueño del contrato de eventos de la plataforma:

| Qué | Por qué importa |
|---|---|
| Alta de `EjecucionFinalizada` en el catálogo, con su envelope | Un evento que no está en ese catálogo no existe para el resto del sistema. Lo consumen T05 y T03 |

📄 *Detalle: [`03`](./03-ms-sandbox-ejecucion.md) §7 (las 17 definiciones abiertas)*

---

## 11. La regla que atraviesa todo el diseño

Si hubiera que quedarse con una sola frase de esta propuesta:

> **Es preferible fallar que aprobar de más.**

Un veredicto de éxito equivocado dispara acreditaciones en tres servicios —monedas, XP, score de IA— y compensarlo cuesta muchísimo más que reintentar la ejecución. En una coreografía de eventos, un evento erróneo se propaga a servicios que ya no controlamos.

De ahí se derivan, y no al revés, tres decisiones que parecen independientes:

- El veredicto sale del reporte y nunca del código de salida.
- Ante la duda, `ERROR_INTERNO` — que **no consume vida ni reintento**, así que el alumno nunca paga por una falla nuestra.
- La validación del paquete del alumno, porque un contenedor perfectamente aislado puede devolver igual un veredicto falso.

---

**Documentos de detalle**

| Documento | Qué contiene |
|---|---|
| [`01-panorama-microservicios-backend.md`](./01-panorama-microservicios-backend.md) | Mapa de los 12 servicios, el stack, y las decisiones transversales del curso |
| [`03-ms-sandbox-ejecucion.md`](./03-ms-sandbox-ejecucion.md) | Aislamiento, lo medido, los tropiezos de Java, máquina de estados, contrato de API completo |
| [`04-ms-sandbox-worker.md`](./04-ms-sandbox-worker.md) | El worker: por qué existe, la cola, el outbox, la DLQ, el janitor, concurrencia, métricas y pruebas |
| [`05-ms-sandbox-patrones.md`](./05-ms-sandbox-patrones.md) | Los patrones de la unidad, uno por uno, aplicados y descartados |
| [`../../hallazgos-investigacion-sandbox.md`](../../hallazgos-investigacion-sandbox.md) | La investigación externa completa, con fuentes citables — **y un glosario del vocabulario técnico** |
