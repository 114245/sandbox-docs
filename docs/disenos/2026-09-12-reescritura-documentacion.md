# Reescritura de la documentación de `ms-sandbox` a formato final

> **Estado:** diseño, pendiente de revisión.
> **Fecha:** 12 de septiembre de 2026.

## 1. Objetivo

Reemplazar los 13 documentos de `docs/arquitectura/`, que suman unas 7700 líneas, por 8 documentos
finales de alrededor de 3500 líneas en total. Deben contener **sólo decisiones**, estar escritos de
forma directa y dar el razonamiento justo. Además, alinear los READMEs de `ms-sandbox/` con esos
documentos.

**Fuera de alcance:** `historico/` (investigación, intercambios con G5 y fuentes de la cátedra),
`docs/disenos/`, `PRD-Plataforma-Gamificada-TP.md` y `temas_curso.md`. Esos archivos no se
reescriben; sólo se corrigen los enlaces que apunten a documentos renombrados.

## 2. Estructura final de `docs/arquitectura/`

| Archivo | Contenido | Tamaño estimado |
|---|---|---|
| `README.md` | Índice y **tabla única** de decisiones (cerradas y abiertas), preguntas del contrato (A*) y pendientes técnicos (P*) | 200–250 |
| `01-contexto.md` | Qué es el sandbox dentro de la plataforma, los 12 servicios, la frontera con T05, la regla rectora y el glosario | 250–300 |
| `02-arquitectura.md` | Vista general con diagramas: contexto, contenedores (API, cola, worker, ejecutor, imagen), secuencia de una ejecución y topología API/worker | 300–350 |
| `03-aislamiento.md` | Imagen, capas 1 y 2, catálogo de perfiles, límites y relojes, bundle por `stdin`, amenazas y mediciones | 500–600 |
| `04-ejecutor.md` | La spec normativa del ejecutor: contrato HTTP, spec del contenedor, nonce, timeouts, barrido, invariantes I1–I9 y criterios de aceptación | 700–800 |
| `05-worker.md` | Cola, DLQ, concurrencia, veredicto (el mapeo del sobre), verificación de evidencia (D16), outbox, apagado y métricas | 500–600 |
| `06-api.md` | Responsabilidades de la API, endpoints, outbox y eventos, salud, y contrato con T05 bajo el V4 | 350–400 |
| `07-patrones.md` | Patrones de microservicios de la materia aplicados al servicio, para la defensa | 350–400 |

## 3. Plantilla de cada documento

```markdown
# <Título>

> Qué cubre este documento, en una o dos líneas.

## Decisiones
| # | Decisión | Por qué | Descartado |

## <Secciones de contrato o diseño>

## Abierto
| # | Pregunta | Quién la cierra |
```

La tabla de **Decisiones** de cada documento repite las filas del README que le corresponden. El
README es la fuente de verdad del **estado** de cada decisión; el documento lo es del **detalle**.

## 4. Reglas de escritura

1. **Decisiones, no historia.** No se escriben frases del tipo "antes pensábamos", "el hallazgo que
   reordenó el diseño" ni avisos de "en revisión". Si una alternativa descartada importa, va a la
   columna *Descartado* en una línea. La historia queda en `historico/` y en git.
2. **Estado explícito.** Cada componente o sección marca uno de estos estados: **IMPLEMENTADO**
   (existe código y test), **DISEÑADO** (decidido, sin código) o **ABIERTO**. Hoy los documentos no
   separan lo construido de lo diseñado: `04` describe RabbitMQ, Postgres y Spring como si
   existieran, y el módulo `ms-sandbox/worker/` no depende de ninguno de los tres.
3. **El código manda sobre los hechos.** Si un documento y el código se contradicen en un hecho, la
   reescritura sigue al código. Si la contradicción es una **decisión abierta**, se documenta como
   abierta, con los dos valores y lo que hace hoy el código. La reescritura no cierra decisiones.
4. **Los IDs se conservan.** D1–D21, A1–A5, P0–P9, I1–I9 y R*/C* de la spec mantienen su número.
   Nada se renumera, porque `historico/`, los commits y los diseños los citan.
5. **Referencias con título.** Nunca se deja una referencia pelada ("`08` §5", "D16"). Siempre va
   acompañada de un título corto: "D16 (la verificación de evidencia vive en el worker)".
6. **Idioma:** español neutro, siguiendo la convención del repo.

## 5. De dónde sale cada documento

La etiqueta **Mantener** indica que el contenido sigue vigente y se condensa. **Actualizar** indica
que el contenido está desfasado respecto del V4 o del código. **Descartar** indica narrativa,
duplicado u obsoleto.

| Origen | Destino | Tratamiento |
|---|---|---|
| `README.md` §3–4 (decisiones y pendientes) | `README.md` | Mantener |
| `README.md` §5 (qué hay fuera de `docs/arquitectura/`) | `01-contexto.md` | Actualizar a las rutas de `historico/` |
| `00` §1 y §11 (qué construimos, regla rectora) | `01-contexto.md` | Actualizar: infraestructura agnóstica de lenguaje |
| `00` §2 (contrato con T05) | `06-api.md` | Actualizar al V4 (`profileId`) |
| `00` §3 (diagrama) | `02-arquitectura.md` | Mantener el diagrama |
| `00` §4–5 (aislamiento) | `03-aislamiento.md` | Mantener |
| `00` §6 (cómo se ejecuta Java) | — | Descartar: lo reemplaza la capa 2 del perfil |
| `00` §7 (mediciones) | `03-aislamiento.md` | Actualizar con D15 abierta |
| `00` §8–9 (patrones y descartes) | `07-patrones.md` | Mantener |
| `00` §10 (qué falta) | `README.md` | Absorbido por la tabla única |
| `01` §0–4 (stack, 12 servicios, comunicación) | `01-contexto.md` | Actualizar: el sandbox no usa Spring hoy |
| `01` §5–9 (flujos y riesgos del curso) | — | Descartar: no son del sandbox |
| `03` §1.1–1.2, §1.5 (aislamiento, red, `stdin`) | `03-aislamiento.md` | Mantener |
| `03` §1.3–1.4, §1.6, §4 (secuencia, mediciones, tropiezos de Java, recursos) | `03-aislamiento.md` / `04-ejecutor.md` | Actualizar a las dos capas y a D15 |
| `03` §2, §6 (contrato de API pre-V4) | — | Descartar: superado por el V4 |
| `03` §3 (origen de los tests) | `06-api.md` | Actualizar |
| `03` §5 (máquina de estados) | `05-worker.md` | Actualizar a la banda 40–59 y a D16 |
| `03` §7 (definiciones abiertas con T05) | `README.md` | Quedarse sólo con las que siguen siendo del sandbox |
| `04` §1–5, §7–11, §14–17 (cola, DLQ, concurrencia, esquema, métricas) | `05-worker.md` | Actualizar: marcar DISEÑADO |
| `04` §6 (mapeo del sobre) | `05-worker.md` | Mantener: IMPLEMENTADO |
| `04` §10 (topología API/worker) | `02-arquitectura.md` | Mantener |
| `04` §12–13 (cliente del ejecutor, barrido) | `04-ejecutor.md` / `05-worker.md` | Mantener |
| `05` (patrones) | `07-patrones.md` | Mantener; corregir "el worker habla con Docker" |
| `06` §1–3 (contexto, contenedores, secuencia) | `02-arquitectura.md` | Actualizar: redibujar con las dos capas |
| `06` §4 (adentro del contenedor) | `03-aislamiento.md` | Actualizar a las dos capas |
| `06` §5 (máquina de estados) | `05-worker.md` | Actualizar |
| `06` §6–7 (capas internas, decisiones abiertas) | — / `README.md` | Descartar / absorbido |
| `07` y `07-lamina` (la API) | `06-api.md` | Unificar en un archivo (**cierra D14**); descartar las capas internas de Spring |
| `08` (spec del ejecutor) | `04-ejecutor.md` | Mantener casi entero; indexar R*/C* |
| `09` (explicación divulgativa) | `04-ejecutor.md` | Descartar como archivo; el recorrido del tar y el nonce quedan como ejemplos |
| `10` (el sándwich) | `03-aislamiento.md`, `06-api.md`, `01-contexto.md` | Repartir: perfiles y relojes, contrato con G5, glosario |
| `12` (D16) | `05-worker.md` | Mantener casi entero |

## 6. Contradicciones a resolver en la reescritura

| Tema | Qué dicen | Cómo queda |
|---|---|---|
| **D15** (¿1 o 2 CPU por contenedor?) | `08` §4.1: 1 CPU. `03` §1.4a y `04` §11: 2 CPU. El código (`Spec.java`: `withNanoCPUs(1000000000L)`) usa 1 | Sigue **ABIERTA**. `04-ejecutor.md` dice 1 (lo que hace el código). La medición de 8.4 s contra 3.3 s va en `03-aislamiento.md` |
| Spring, RabbitMQ y Postgres en el worker | `04` los describe en detalle; `01` §0 dice Spring Boot para todos. `worker/pom.xml` no tiene Spring, AMQP ni JDBC | `05-worker.md` marca cola, outbox y esquema como **DISEÑADO**. La elección Spring o JDK puro queda abierta para la rodaja de la cola |
| **D20** (¿cómo sale el resultado hacia T05?) | `07` §3.4: el relay publica a RabbitMQ. La respuesta al V4 propone Kafka, el bus de la plataforma | Sigue **ABIERTA** en `06-api.md` |
| Estados Java-específicos | `03` §5 y `06` §5 usan `ERROR_COMPILACION` y `SUITE_INVALIDA` como estados de la capa 1 | Se usan los 8 valores de `capa1.sh` y la banda 40–59 (`05-worker.md`) |
| Versión de la imagen | `12` cita `sandbox-runner:1.0.0`; el compose usa `2.0.0-capa1` | Se usa `2.0.0-capa1` |

## 7. READMEs de `ms-sandbox/`

`ms-sandbox/README.md`, `ejecutor/README.md` e `imagenes/README.md` se revisan contra el código y
contra los documentos nuevos. Los cambios son tres: corregir los hechos desfasados, cambiar las
referencias a documentos viejos por los nuevos (con título) y sacar lo que repitan de la
arquitectura. Sólo cuentan cómo construir, correr y probar. **Lo agregado:** `worker/` no tiene
README propio y se le escribe uno corto con el mismo criterio.

## 8. Ejecución

- **Orden:** `04-ejecutor` → `05-worker` → `03-aislamiento` → `06-api` → `02-arquitectura` →
  `01-contexto` → `07-patrones` → `README` → READMEs de `ms-sandbox/` → borrado de los archivos
  viejos.
  Primero van los documentos normativos, porque los generales los citan.
- **Un commit por documento nuevo.** Mientras dura el trabajo, los archivos viejos conviven con los
  nuevos (los nombres no chocan). Se borran en un commit final con `git rm`, que conserva el
  historial.
- Cada documento lo escribe un único escritor delegado, en secuencia, nunca en paralelo.
- **Sin push.** El push a `main` se hace sólo cuando el usuario lo pida.

## 9. Verificación

1. **Enlaces:** ningún enlace relativo roto en `docs/`, `ms-sandbox/` ni `historico/`. Incluye el de
   `historico/fuentes/TUP_PIV_BE_PROPUESTA_ARQ.md`, que apunta a `01-panorama`.
2. **Cobertura de IDs:** cada D*, A*, P* e I* del README y de `08` aparece en los documentos nuevos.
3. **Hechos contra el código:** constantes de la spec (CPU, memoria, tmpfs, relojes, versión de la
   API de Docker), valores del sobre de `capa1.sh` y estado IMPLEMENTADO de cada pieza, verificado
   contra `ms-sandbox/`.
4. **Reglas de escritura:** sin referencias sin título, sin avisos de revisión y sin narrativa
   histórica.
