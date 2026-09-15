# Auditoría integral de SANDBOX

**Fecha:** 2026-09-12  
**Alcance:** repositorio completo `sandbox-docs`, implementación ejecutable de `ms-sandbox`, imágenes, perfiles, pruebas, documentación, dependencias y comportamiento dinámico.  
**Método:** revisión estática, trazado de flujo y fronteras de confianza, compilación, ejecución de pruebas oficiales, creación de cargas adversarias controladas, inspección de imágenes y contraste de dependencias con bases públicas de vulnerabilidades.

## 1. Conclusión ejecutiva

El proyecto tiene una arquitectura de aislamiento del host sensata y bastante defensiva: contenedores sin red, sin capacidades Linux, sin privilegios, sin montajes del host, con raíz de solo lectura, límites de memoria/CPU/PID/archivos, `no-new-privileges`, usuario no root, tiempo máximo y limpieza de procesos y contenedores. En las pruebas efectuadas no se logró escribir en el host, acceder al socket Docker, habilitar red externa, montar namespaces ni descubrir el nonce de control.

Sin embargo, el sistema no cumple todavía su propiedad de seguridad más importante: **el resultado de la evaluación no es confiable frente a código estudiantil malicioso**. Se reprodujo una falsificación completa del veredicto: el código no confiable escribió un XML JUnit falso en el directorio de reportes y terminó la JVM con `Runtime.halt(0)`. El evaluador aceptó tres pruebas inventadas, cero fallos, fase `FIN`, salida `OK` y código 0. El nonce protege el sobre exterior, pero no autentica el origen del reporte interior.

También se confirmó que las pruebas supuestamente ocultas son legibles por la solución, que existe un descriptor heredado que permite saltarse el límite interno de salida y que el artefacto Java empaquetado no inicia por firmas inválidas incluidas en el fat JAR. Por lo tanto, la evaluación general es:

| Dimensión | Evaluación | Resultado |
|---|---:|---|
| Aislamiento frente al host | Fuerte, con riesgo residual de kernel/runtime compartido | 8/10 |
| Integridad del veredicto y métricas | Quebrada | 2/10 |
| Confidencialidad de pruebas | Quebrada | 1/10 |
| Disponibilidad y tolerancia a entradas hostiles | Parcial | 5/10 |
| Cadena de suministro | Débil | 3/10 |
| Operabilidad del artefacto entregable | Bloqueada por el fat JAR | 3/10 |

Estas cifras son una escala cualitativa propia de esta auditoría, no puntuaciones CVSS.

## 2. Alcance real del repositorio

El repositorio mezcla especificación, decisiones de arquitectura, material de referencia y una implementación parcial:

- `ms-sandbox/ejecutor`: servicio Java 21 que recibe una ejecución por socket Unix, crea y controla el contenedor mediante Docker y recupera el sobre de resultados.
- `ms-sandbox/imagenes`: Dockerfile de la imagen de evaluación y scripts de capa 1.
- `ms-sandbox/perfiles`: catálogos JSON y scripts de capa 2 para cada modalidad.
- `ms-sandbox/pruebas`: pruebas unitarias, integración y bundles hostiles.
- `docs` y documentos raíz: contratos, decisiones, threat model y propuestas.
- API HTTP pública, worker, base de datos, outbox y RabbitMQ: **diseño, no implementación disponible en este repositorio**.

Los PDF son material exportado o complementario. Cuando existe una fuente Markdown equivalente se tomó ésta como fuente inspeccionable y vigente. La propia documentación de arquitectura indica que varias piezas anteriores quedaron parcialmente obsoletas después de G5 V4 y que la especificación 08 prevalece para el protocolo actual.

No es posible afirmar que el producto completo esté listo porque el repositorio sólo permite validar el ejecutor, la imagen y los perfiles. Persistencia, autorización del usuario final, idempotencia de negocio, entrega de jobs, reintentos y publicación de resultados siguen siendo propiedades documentadas, no verificadas en código.

## 3. Arquitectura y flujo de trabajo

### 3.1 Flujo implementado

1. El servicio Java abre un socket Unix y expone `POST /ejecutar` y `GET /salud` mediante un parser HTTP propio.
2. Valida sintácticamente identificador, perfil y tamaño declarado; aplica una cola de 16 y un límite de 8 ejecuciones concurrentes.
3. Resuelve el perfil cargado al inicio y genera un nonce criptográfico por ejecución.
4. Construye una especificación Docker con:
   - red deshabilitada;
   - root filesystem de solo lectura;
   - `tmpfs` en `/work` de 64 MiB con `noexec,nosuid,nodev`;
   - usuario 1000;
   - memoria y swap iguales;
   - una CPU, límite de PIDs y `ulimit` para CPU, archivos, procesos y tamaño de archivo;
   - todas las capabilities eliminadas;
   - `no-new-privileges`;
   - sin binds, devices, puertos ni política de reinicio.
5. Crea el contenedor, adjunta stdin/stdout, lo inicia y envía un frame: nonce, tamaño del script, script de capa 2 y tar del bundle.
6. Capa 1 valida y extrae el tar, exporta los directorios, invoca capa 2 y captura sus salidas.
7. Capa 2 compila solución y pruebas por separado, ejecuta JUnit, genera XML, nota y metadatos.
8. Capa 1 mata procesos supervivientes, empaqueta reportes y emite un sobre delimitado por el nonce.
9. El ejecutor espera, mata por timeout si corresponde, inspecciona OOM, lee logs, extrae el sobre y elimina el contenedor en un `finally`.

### 3.2 Flujo diseñado pero no comprobable

La documentación agrega un worker que consumiría una cola, construiría el bundle, llamaría al ejecutor, validaría `reportFormat`, persistiría el resultado y publicaría un evento mediante outbox. Ninguna de esas piezas está implementada aquí. En particular, no se pudo comprobar:

- autenticación y autorización de API;
- protección contra replay a nivel de negocio;
- idempotencia y deduplicación de trabajos;
- integridad transaccional de resultados/outbox;
- gestión de secretos;
- validación segura de la descompresión en el worker;
- límites globales multiusuario y rate limiting.

## 4. Modelo de amenazas y fronteras de confianza

| Actor o activo | Confianza esperada | Observación |
|---|---|---|
| Código de estudiante | Ninguna | Puede ejecutar Java arbitrario dentro del contenedor. |
| Tests y capa 2 | Confiables por diseño | En la implementación comparten UID, filesystem y JVM con el adversario. |
| Capa 1 de la imagen | Confiable | Controla framing y sobre, pero entrega la autoría del reporte a capa 2. |
| Ejecutor | Altamente confiable | Tiene acceso al socket Docker; una toma de control equivale prácticamente a control del daemon/host. |
| Cliente del socket Unix | Confiable parcialmente | Puede solicitar ejecuciones; el parser ofrece vectores de agotamiento antes de la admisión. |
| Docker daemon/runtime/kernel | TCB | No existe aislamiento de kernel fuerte como VM/gVisor/Kata. |
| Perfil e imagen | Configuración confiable | Se referencian por tags mutables y la validación admite valores peligrosos. |
| Worker futuro | Confiable | Debe desconfiar completamente de reportes, logs y tarballs provenientes del contenedor. |

La frontera `estudiante → host` se comportó bien en estas pruebas. La frontera `estudiante → veredicto` está rota. Son propiedades diferentes: evitar un escape de contenedor no implica que la nota sea auténtica.

## 5. Hallazgos de seguridad

### F-01 — Crítico — Falsificación completa de reportes y nota

**Estado:** confirmado dinámicamente.  
**Impacto:** pérdida total de integridad del veredicto, cantidad de tests, fallos, detalle y potencialmente nota.  
**Causa:** `/work/reports` es escribible por el mismo UID y proceso que ejecuta la solución no confiable. La capa 2 considera válido cualquier XML que aparezca allí y sólo comprueba que el total de tests sea mayor que cero. Los reportes no tienen autenticación ni una frontera de proceso/usuario que garantice su autoría.

Prueba usada: `auditoria/fixtures/forja-sincrona`. La solución escribió un XML JUnit válido con tres tests exitosos y ejecutó `Runtime.halt(0)`. Resultado observado:

- código del contenedor: 0;
- OOM: falso;
- resultado de capa 1: `OK`;
- código de evaluación: 0;
- procesos supervivientes: 0;
- fase: `FIN`;
- detalle: la suite corrió 3 tests;
- XML: 3 tests, 0 fallos.

El nonce exterior no resuelve este problema. Evita que stdout arbitrario suplante fácilmente el delimitador final, pero el contenido que se firma implícitamente con ese sobre ya fue construido a partir de archivos controlados por el atacante.

**Remediación prioritaria:**

1. No ejecutar solución y orquestador confiable en la misma JVM, UID ni namespace de escritura.
2. Ejecutar el código estudiante como usuario subordinado sin escritura sobre tests, reportes, runner ni librerías.
3. Mantener los reportes en un directorio sólo escribible por el runner confiable; éste debe derivar las métricas desde eventos/resultados obtenidos directamente, no aceptar XML preexistente.
4. Preferir dos contenedores o una VM/microVM: runner confiable fuera del sandbox del estudiante, comunicándose por un protocolo mínimo.
5. Firmar/MAC el resultado sólo después de que un componente externo y confiable lo haya validado. Una firma creada dentro del mismo entorno comprometible no agrega seguridad.
6. Añadir esta carga a CI como prueba negativa obligatoria.

### F-02 — Alto — Exposición total de pruebas ocultas

**Estado:** confirmado dinámicamente.  
**Impacto:** el estudiante puede leer casos, respuestas, secretos accidentales y adaptar el comportamiento para aprobar sólo los tests conocidos.  
**Causa:** el código fuente de pruebas viaja en el mismo bundle y queda legible en `/work/in/test` para el mismo UID.

La carga `auditoria/fixtures/lectura-tests` imprimió íntegramente `tp/SolucionTest.java`, incluida la cadena centinela `CADENA_SECRETA_DE_PRUEBA_8f41c2`, y aun así finalizó `OK`.

Ocultar nombres o filtrar mensajes de error no corrige la exposición directa. Los tests secretos deben residir fuera del filesystem legible por el estudiante. Si se requiere ejecutar ambos en una JVM, Java moderno no ofrece una barrera robusta equivalente al antiguo `SecurityManager`; se necesita separación de procesos/contenedores y un protocolo de evaluación que no revele el test.

### F-03 — Alto — Descriptor heredado permite saltar el límite interno de salida

**Estado:** confirmado dinámicamente.  
**Impacto:** agotamiento de logs/memoria, pérdida del sobre, diagnóstico incorrecto y denegación de servicio.  
**Causa:** capa 1 guarda stdout original en FD 3 (`exec 3>&1`) y lo hereda el proceso no confiable. La carga puede escribir en `/proc/self/fd/3`, evitando la captura y el límite de 65.536 bytes de capa 2.

La carga `auditoria/fixtures/fuga-fd3` escribió aproximadamente 1,25 MiB directamente. El sobre interno siguió informando `truncado:false`. El ejecutor Java tiene un segundo límite de 1 MiB, por lo que termina fallando de forma conservadora al perder el marcador; aun así el atacante puede borrar el diagnóstico real y consumir recursos por fuera del límite previsto.

**Remediación:** cerrar todos los FDs no requeridos antes de ejecutar código no confiable (`3>&-` como mínimo), lanzar mediante un wrapper que construya una tabla explícita de descriptores, limitar stdout en la frontera Docker y probar `/proc/self/fd/*` en CI.

### F-04 — Alto — Slowloris y agotamiento previo a la admisión en socket Unix

**Estado:** confirmado por análisis de código; la ruta HTTP completa quedó bloqueada por F-06.  
**Impacto:** un proceso local con acceso al UDS puede agotar threads/conexiones y falsificar los indicadores de ocupación o bloquear capacidad sin ejecutar código útil.  
**Causa:** se crea un virtual thread por conexión aceptada sin deadline de lectura; hay 8 KiB por línea, pero no límite de cantidad/tamaño total de headers. Los semáforos se adquieren después de leer headers. Tras la admisión, ocho clientes pueden retener los ocho permisos enviando lentamente el body y dieciséis más ocupar la cola.

La protección `0660` del socket es valiosa, pero si el ajuste de permisos falla el servicio sólo registra el error; no verifica ni falla cerrado.

**Remediación:** deadline de header y body, límite de headers y bytes totales, límite de conexiones preadmisión, adquisición temprana de un cupo barato, lectura exacta con tasa mínima y verificación estricta de propietario/grupo/modo del UDS.

### F-05 — Alto — Validación insegura de perfiles

**Estado:** confirmado estáticamente.  
**Impacto:** un error de configuración puede desactivar límites o iniciar un catálogo ambiguo considerado sano.  
**Detalles:**

- sólo se validan máximos; cero y negativos pueden pasar para memoria, CPU y versiones;
- `Memory=0` en Docker significa sin límite efectivo;
- no se exige correspondencia entre nombre `<id>@<version>.json` y contenido;
- no se valida `profileId` cargado con las mismas reglas del request;
- `reportFormat` nulo o vacío puede aceptarse;
- duplicados pisan entradas anteriores silenciosamente;
- catálogo vacío puede iniciar y responder salud;
- no se verifica al arranque que la imagen exista ni que el tag resuelva al digest esperado.

**Remediación:** schema cerrado con mínimos y máximos, tipos y enums; rechazo de duplicados; catálogo no vacío; correspondencia archivo/contenido; digest obligatorio; preflight de imagen; tests de propiedades para números límite.

### F-06 — Alto — El fat JAR compilado no es ejecutable

**Estado:** confirmado dinámicamente.  
**Impacto:** el servicio documentado no puede arrancar desde su artefacto de entrega; impide salud, pruebas end-to-end y despliegue normal.  
**Evidencia:** `mvn -DskipTests package` finaliza correctamente, pero `java -jar target/ejecutor.jar` termina inmediatamente con:

```text
SecurityException: Invalid signature file digest for Manifest main attributes
```

El JAR sombreado conserva firmas `META-INF/BC2048KE.SF` y `.DSA` de Bouncy Castle después de modificar el contenido. Es un fallo determinista de empaquetado, no una limitación del entorno de auditoría.

**Remediación:** filtrar `META-INF/*.SF`, `*.DSA` y `*.RSA` en Maven Shade, generar un artefacto reproducible y añadir un smoke test que ejecute el JAR y consulte `/salud`.

### F-07 — Alto — Dependencias antiguas con vulnerabilidades conocidas

**Estado:** confirmado contra OSV y fuentes de proyecto al 2026-09-12.  
**Impacto:** depende de la ruta vulnerable; varias bibliotecas se usan sólo indirectamente, por lo que presencia no equivale a explotabilidad. El riesgo aumenta porque el ejecutor procesa respuestas del daemon y datos de configuración en un proceso con acceso al socket Docker.

Versiones destacadas:

| Componente | Versión | Observación |
|---|---:|---|
| Jackson databind/core | 2.17.2 | Múltiples advisories posteriores, incluidos de severidad alta; actualizar a una rama corregida y verificar compatibilidad. |
| docker-java | 3.4.1 | La línea publicada avanzó; 3.5 ya actualizó múltiples dependencias y existe 3.7.1. |
| Commons IO | 2.13.0 | Afectado por CVE-2024-47554; corregido desde 2.14.0. |
| Commons Lang | 3.12.0 | Afectado por CVE-2025-48924; corregido desde 3.18.0. |
| Guava | 19.0 | Muy antigua; OSV reporta CVE-2018-10237, CVE-2020-8908 y CVE-2023-2976. |
| Bouncy Castle | 1.76 | Diversos advisories posteriores, incluido uno crítico para determinadas funciones; además rompe el fat JAR por sus firmas. |
| HttpClient 5 / HttpCore 5 | 5.0.3 / 5.0.2 | Advisories de 2026 con correcciones en versiones recientes. |
| Commons Compress (test) | 1.21 | Apache confirma CVE-2024-25710 y CVE-2024-26308 para este rango; actualizar al menos a 1.26. |

La resolución exacta debe automatizarse con SBOM y escaneo en CI. No se debe interpretar la lista como prueba de explotación remota de cada CVE en este servicio.

### F-08 — Alto — Imagen base no reproducible y componentes vulnerables innecesarios

**Estado:** confirmado con inspección local de Docker Scout.  
**Impacto:** deriva de builds y exposición a vulnerabilidades del sistema base.  
**Detalles:**

- `FROM eclipse-temurin:21-jdk` no está fijado por digest;
- paquetes `apt` son flotantes;
- JUnit se descarga por TLS sin checksum, con un TODO explícito;
- el perfil refiere una imagen por tag mutable, no digest;
- la imagen resultante mide aproximadamente 235 MB y contiene unas 210 aplicaciones/paquetes detectados;
- Scout encontró 1 vulnerabilidad crítica y 5 altas en Go 1.26.5 dentro de `/usr/bin/pebble`; la corrección indicada es Go 1.26.6.

`pebble` no forma parte del flujo normal y la red externa está deshabilitada, por lo que la explotabilidad directa parece baja; precisamente por eso conviene quitarlo. Usar una imagen mínima y reconstruida reduce superficie de ataque.

### F-09 — Alto — Reportes sin límite estructural y posible bomba de descompresión

**Estado:** confirmado por diseño/código.  
**Impacto:** agotamiento de CPU, memoria o disco del worker futuro.  
**Causa:** capa 1 empaqueta todo `reports` sin un número/tamaño/tipo explícito de archivos. El `tmpfs` de 64 MiB actúa como límite bruto, pero un tar.gz altamente compresible puede expandirse mucho al ser procesado por el consumidor. El tope de stdout del ejecutor suele hacerlo fallar cerrado, aunque no reemplaza la validación segura en el worker.

**Remediación:** allowlist de rutas y tipos, máximo de entradas, bytes comprimidos y descomprimidos, ratio de compresión, profundidad y longitud de nombre; streaming con contador; nunca materializar el archivo completo antes de validar; rechazar enlaces y especiales también al descomprimir el resultado.

### F-10 — Medio/alto — Validación tar incompleta y frágil

**Estado:** confirmado dinámicamente.  
**Resultados:** enlaces simbólicos, hardlinks, traversal `..` y rutas absolutas fueron rechazazados con código 22 `BUNDLE_INVALIDO`. Un FIFO fue aceptado por capa 1 y luego terminó como código 40 `DETENIDO_POR_EVALUACION`.

La validación sólo rechaza symlinks/hardlinks; no aplica una allowlist de archivos regulares y directorios. Además interpreta la salida humana de `tar -tvf` tomando el último fragmento separado por espacios, lo que es frágil ante nombres con espacios y extensiones PAX.

La versión GNU tar encontrada fue `1.35+dfsg-4ubuntu0.4`, posterior al paquete corregido por Ubuntu para CVE-2025-45582, por lo que ese CVE concreto no quedó presente en la imagen probada.

**Remediación:** parser estructurado de archivo o extracción en dos pasos con API segura; aceptar exclusivamente archivos regulares/directorios bajo prefijos exactos; rechazar dispositivos, FIFO, sockets, enlaces, atributos extendidos inesperados y nombres no canónicos.

### F-11 — Medio/alto — Errores de Docker pueden cerrar la conexión sin respuesta controlada

**Estado:** confirmado estáticamente.  
**Impacto:** respuestas ambiguas, reintentos incorrectos y menor tolerancia a fallos.  
**Causa:** varias llamadas `.exec()` de adjuntar, esperar e inspeccionar logs quedan fuera de las conversiones específicas de `ErrorDaemon`. Una excepción runtime puede salir de `Ejecucion`; el task del servidor sólo captura `IOException`, por lo que la conexión puede cerrarse sin un 502/JSON estable.

El parser de multiplexado de docker-java tiene además una regresión reconocida en los tests: ciertos frames truncados/malformados pueden aceptarse silenciosamente y no se valida el content type. `salidaTruncada` representa el límite local, no prueba integridad del stream.

**Remediación:** frontera única de excepciones, respuesta estable por fase, checksums/longitud del sobre, parser de frames estricto o versión corregida y fault injection para desconexión del daemon en cada transición.

### F-12 — Medio — Salud superficial y canario no implementado

**Estado:** confirmado estáticamente y por ejecución.  
**Impacto:** el servicio puede declararse sano sin ser capaz de evaluar nada.  
**Causa:** `/salud` comprueba ping de Docker y semáforos, pero no catálogo no vacío, existencia/digest de imágenes ni ejecución canario.

Antes de construir las imágenes, 13 pruebas de integración fallaron por `No such image`, una condición que el health check conceptual no detectaría. La documentación propone canario, pero el código no lo implementa.

### F-13 — Medio — Identificador repetido permite reejecución y colisiones

**Estado:** confirmado por diseño.  
**Impacto:** trabajo duplicado, colisión de nombre de contenedor en concurrencia y resultados ambiguos.  
**Causa:** el ejecutor no almacena idempotencia; dos requests con el mismo `X-Ejecucion-Id` pueden volver a ejecutar. El worker futuro podría resolverlo, pero no existe en este repositorio.

### F-14 — Medio — Superficie privilegiada del ejecutor

**Estado:** riesgo arquitectónico.  
**Impacto:** comprometer el proceso Java otorga capacidad de crear contenedores mediante Docker; el socket del daemon es una frontera equivalente a privilegio administrativo.

Aunque la especificación producida es restrictiva, la seguridad depende de que ninguna entrada pueda alterar mounts, image, entrypoint, capabilities o flags. Hoy esos campos son fijos, lo cual es una fortaleza. Conviene aislar el ejecutor en un host/VM dedicado, usar un proxy de API Docker con allowlist estricta o un runtime de sandbox dedicado, y nunca compartir el daemon con cargas sensibles.

### F-15 — Medio — Cierre y limpieza con posibles carreras

**Estado:** confirmado por análisis.  
**Impacto:** ejecuciones huérfanas o errores durante shutdown bajo carga.  
**Causa:** el servidor espera un máximo de 60 segundos y la limpieza/cierre del cliente Docker puede competir con tareas aún en ejecución. Existe un recolector de huérfanos valioso, pero no elimina por sí solo todas las carreras.

### F-16 — Medio — Controles manualmente duplicados y deriva de configuración

**Estado:** confirmado.  
**Impacto:** perfiles y scripts pueden divergir silenciosamente.  
**Causa:** el script de capa 2 se copia dentro de JSON, constantes y formatos están acoplados manualmente, no hay pipeline de generación/verificación ni CI visible en el repositorio.

## 6. Pruebas dinámicas realizadas

Todas las cargas fueron ejecutadas contra imágenes locales y únicamente dentro del alcance autorizado. No se intentó afectar servicios externos ni persistir fuera del workspace.

### 6.1 Suite oficial y build

| Ejecución | Resultado | Interpretación |
|---|---|---|
| `mvn test` sin imágenes locales | 122 tests; 13 fallos | 109 pasaron; 13 fallaron por precondición `No such image`. |
| Build imagen productiva | Exitoso | Se construyó `sandbox-runner:2.0.0-capa1`. |
| `BundlesIT` + `ProtocoloIT` tras imagen productiva | Bundles 10/11; Protocolo 2/4 | Las dos de protocolo restantes requerían fixture A6; único fallo real de bundles fue una expectativa de código de salida en carrera. |
| Script oficial `verificar-a6.ps1` | 4/4 | Construyó el fixture y pasó todo el protocolo A6. |
| Unitarias | 107/107 | Pasaron. |
| Comportamiento total observado | 121/122 | El único desacuerdo fue `hostil-reporte-loop`: detectó correctamente procesos supervivientes y declaró `VEREDICTO_NO_CONFIABLE`, pero produjo 47 en vez del 0 esperado por el assert. |
| `mvn -DskipTests package` | Build exitoso | El JAR resultante no inicia; véase F-06. |

No se debe presentar esto como una única corrida verde: son ejecuciones separadas y condicionadas por las imágenes disponibles.

### 6.2 Cargas hostiles existentes

| Caso | Resultado observado |
|---|---|
| suma válida | Código 0, `OK`. |
| `exit(0)` malicioso | Código 47; detectado. |
| consumo de CPU | Código 44; detenido. |
| consumo de memoria | Código 43/OOM; detenido. |
| sleep | Código 45/timeout; detenido. |
| proceso superviviente / loop | Resultado no confiable; detectado. |
| shadowing de clases de prueba | Contenido por el orden de classpath: tests primero. |
| intento de reventar salida | Contenido por los límites normales, excepto el bypass FD 3 descubierto. |

### 6.3 Cargas adicionales de esta auditoría

| Prueba | Objetivo | Resultado |
|---|---|---|
| `forja-sincrona` | Falsificar XML y terminar antes del runner | **Éxito del ataque:** evaluación falsa aceptada como `OK`. |
| `lectura-tests` | Leer test oculto | **Éxito del ataque:** fuente y centinela expuestos. |
| `fuga-fd3` | Evadir límite de stdout | **Éxito del ataque:** ~1,25 MiB enviados por FD heredado. |
| `escape-host` | Escribir `/etc`, alterar `/libs`, usar red, socket Docker, mount namespace y leer nonce | **Ataques bloqueados:** bitmask 0, sin escape ni secreto observado. |
| tar con symlink | Escapar extracción | Rechazado, código 22. |
| tar con hardlink | Escapar extracción | Rechazado, código 22. |
| tar con `../` | Path traversal | Rechazado, código 22. |
| tar absoluto | Escribir ruta absoluta | Rechazado, código 22. |
| tar con FIFO | Introducir archivo especial | Aceptado inicialmente; terminó código 40, clasificación incorrecta. |

### 6.4 Intentos de cruce de aislamiento

No se encontró un escape del contenedor en el conjunto ensayado:

- escritura en rootfs bloqueada;
- librerías de sólo lectura;
- red externa bloqueada;
- socket Docker no montado;
- `unshare`/montaje bloqueado;
- sin capabilities aprovechables;
- sin nonce visible en entorno de PID 1;
- sin bind mounts ni devices;
- límites de CPU, memoria, PID y tiempo actuaron.

Esto no constituye una prueba matemática de ausencia de escape. El contenedor comparte kernel Linux y runtime `runc`; una vulnerabilidad futura del kernel/runtime o una mala configuración del daemon sigue dentro del riesgo residual. El host observado usó Docker Engine 29.7.2, `runc` 1.4.3, seccomp incorporado y cgroup namespaces; no se observó user namespace, gVisor, Kata ni microVM.

## 7. Fortalezas

1. Especificación Docker por allowlist y campos fijos; la entrada del usuario no controla imagen, entrypoint, mounts ni privilegios.
2. Defensa en profundidad: red, capacidades, usuario, filesystem, tmpfs y múltiples límites de recursos.
3. Memoria y swap iguales, reduciendo desplazamiento del problema al host.
4. Timeout externo y detección OOM independientes de lo que declare el código.
5. Limpieza en `finally` y recolector de contenedores huérfanos etiquetados.
6. Nonce generado con `SecureRandom` para separar logs arbitrarios del sobre final.
7. Límite de tamaño de request, salida y script en varias capas.
8. Separación de compilación de solución/tests y precedencia de clases de pruebas, que contiene shadowing simple.
9. Pruebas hostiles ya incluidas para CPU, memoria, timeout, procesos supervivientes y protocolo truncado.
10. El tar bloquea los vectores clásicos de symlink, hardlink, absoluto y traversal en los casos probados.
11. Documentación explícita de invariantes y de varias deudas; existe conciencia de que un reporte producido por código no confiable necesita una frontera más fuerte, aunque la implementación actual no la logra.

## 8. Debilidades funcionales y operativas

- El artefacto principal no arranca después de empaquetar.
- No existe despliegue completo de API/worker/mensajería/persistencia.
- Las imágenes son precondiciones externas no verificadas al arrancar.
- Los tests de integración no construyen siempre todos los fixtures automáticamente.
- Una aserción depende de una carrera de códigos de salida y provoca falso rojo aun cuando la anomalía se detecta.
- `/tmp` queda de sólo lectura; bibliotecas Java legítimas que esperan temporales podrían fallar sin un tmpfs específico.
- Health no refleja capacidad real de evaluar.
- Los mensajes de fallo mezclan error del estudiante, fallo de infraestructura y veredicto no confiable en algunos caminos.
- No hay trazabilidad de SBOM, digest, firma, provenance ni build reproducible.
- No hay CI visible que ejecute build del JAR, imágenes, pruebas adversarias y escaneo.

## 9. Tolerancia a fallos

### Bien resuelto

- timeout y OOM se determinan desde fuera del proceso evaluado;
- contenedor se intenta eliminar incluso ante fallos;
- existe limpieza de huérfanos;
- salida ausente o sobre inválido tiende a fallar cerrado;
- semáforos separan concurrencia activa y cola;
- el código diferencia varias fases y diagnósticos.

### Insuficiente

- falta un estado transaccional/idempotente de ejecución;
- excepciones runtime del cliente Docker no están normalizadas;
- no hay timeouts de transporte HTTP;
- health no cubre dependencias operativas reales;
- el framing no incluye longitud/hash autenticado de cada segmento;
- parser de demultiplexado puede aceptar truncamientos;
- no hay chaos/fault injection sistemático entre create/attach/start/stdin/wait/inspect/log/remove;
- shutdown puede competir con ejecuciones vivas;
- el veredicto puede aparentar éxito ante compromiso lógico del runner.

## 10. Plan de remediación priorizado

### P0 — Antes de usar para calificaciones reales

1. Rediseñar la frontera de confianza para que el estudiante no pueda leer tests ni escribir reportes.
2. Separar runner confiable y solución por UID/proceso/container/VM; validar y producir la nota fuera del dominio no confiable.
3. Cerrar FD 3 y todos los descriptores heredados.
4. Corregir Maven Shade y añadir smoke test del JAR.
5. Hacer que profiles requieran límites positivos, imagen por digest y catálogo válido/no vacío.

### P1 — Endurecimiento inmediato

1. Deadlines, cupos preadmisión y límites totales del parser HTTP/UDS.
2. Allowlist estricta de tar y rechazo de todos los tipos especiales.
3. Validación streaming de reportes con máximos comprimido/descomprimido.
4. Actualizar dependencias y base; eliminar `pebble` y paquetes innecesarios.
5. Fijar base, JUnit y artefactos por digest/checksum; generar SBOM y firmar imágenes.
6. Health profundo: catálogo, imagen/digest y canario periódico.
7. Normalizar todas las excepciones Docker a respuestas estables y probar cada fallo de fase.

### P2 — Madurez de plataforma

1. Host o VM dedicado para el daemon de evaluación; considerar gVisor, Kata o microVM para reducir el riesgo de kernel compartido.
2. Proxy de Docker con API allowlist y política externa que rechace specs fuera del perfil.
3. Idempotencia persistente, cuotas por usuario/tenant y control de replay.
4. Observabilidad inmutable: correlación, razón de kill, OOM, truncamiento de transporte, digest de imagen/perfil y anomalías.
5. CI obligatoria con pruebas negativas: falsificación de XML, lectura de tests, FDs, tar especial, slowloris, daemon caído, salida truncada y carrera de shutdown.
6. Generar JSON de perfil desde una fuente única para evitar copias manuales del script.

## 11. Criterios de aceptación sugeridos

El sistema no debería aprobar una revisión de seguridad hasta demostrar automáticamente que:

- una solución no puede leer bytes de tests secretos;
- no puede crear, reemplazar, truncar ni borrar ningún artefacto usado para calcular la nota;
- `Runtime.halt`, `System.exit`, threads, subprocesses y manipulación dinámica de classpath no producen un falso aprobado;
- sólo quedan abiertos stdin/stdout/stderr esperados en el proceso no confiable;
- todos los tipos tar fuera de archivo regular/directorio son rechazados antes de extraer;
- el JAR producido inicia desde cero y pasa un canario real;
- imagen y perfil se registran por digest/hash inmutable;
- health pasa únicamente si se puede crear, ejecutar y eliminar un sandbox canario;
- cada transición Docker puede fallar y aun así devuelve un error estable y limpia recursos;
- un cliente lento no puede ocupar capacidad indefinidamente;
- reportes comprimidos se procesan bajo límites explícitos de entrada, expansión y cantidad de archivos;
- la suite de cargas adversarias corre en cada cambio de runner, imagen o dependencia.

## 12. Referencias externas consultadas

- Apache Commons Compress, vulnerabilidades publicadas: https://commons.apache.org/proper/commons-compress/security.html
- Releases de docker-java: https://github.com/docker-java/docker-java/releases
- Ubuntu CVE-2025-45582 para GNU tar: https://ubuntu.com/security/CVE-2025-45582
- OSV, Jackson databind GHSA-j3rv-43j4-c7qm: https://osv.dev/vulnerability/GHSA-j3rv-43j4-c7qm
- OSV, Commons IO GHSA-78wr-2p64-hpwj: https://osv.dev/vulnerability/GHSA-78wr-2p64-hpwj
- OSV, Commons Lang GHSA-j288-q9x7-2f5v: https://osv.dev/vulnerability/GHSA-j288-q9x7-2f5v
- Go vulnerability database, GO-2026-5026: https://pkg.go.dev/vuln/GO-2026-5026

## 13. Limitaciones de la auditoría

- Se auditó el código y entorno presentes, no API/worker/DB/cola inexistentes.
- No se realizó fuzzing prolongado, análisis formal, pentest del kernel ni explotación de CVEs del runtime.
- No se probó bajo carga distribuida real ni en un host de producción.
- La ausencia de escape en estas cargas no demuestra ausencia universal de escape.
- El inventario CVE es una fotografía a la fecha indicada; requiere actualización continua y análisis de alcanzabilidad por función.
- El servicio HTTP completo no pudo arrancar por el defecto reproducible del fat JAR; F-04 se concluye del flujo de código y debe verificarse dinámicamente después de corregir F-06.

## 14. Dictamen

**No apto todavía para calificación adversaria real.** El proyecto contiene una base de contención de recursos razonable y defendió correctamente el host frente a los intentos directos realizados. Pero el objetivo de un sandbox de evaluación no es sólo proteger el host: también debe garantizar que la nota provenga de tests confidenciales y de un runner confiable. Ambas propiedades se rompen con cargas simples, reproducibles y sin escapar del contenedor.

La prioridad no es agregar más filtros a stdout ni más validaciones al XML existente. La corrección estructural es mover tests, observación y cálculo del veredicto fuera del dominio escribible/legible por el estudiante. Una vez reparada esa frontera, el resto de los controles actuales constituye una buena base sobre la cual endurecer disponibilidad, supply chain y operación.
