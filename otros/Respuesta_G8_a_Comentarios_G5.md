# Respuesta del Grupo 8 a los comentarios del Grupo 5

Esta respuesta refleja el estado actual del ejecutor, la capa 1 y el núcleo del worker. Para evitar ambigüedades, cada punto distingue entre lo que ya está implementado y probado, el contrato que proponemos para la integración y lo que todavía falta desarrollar.

## Estado general de la implementación

- **Implementado y probado:** ejecutor Java 21, catálogo local de perfiles, imagen con capa 1, script de referencia `java21-junit`, cliente del ejecutor y núcleo del worker que transforma el sobre en un veredicto.
- **Implementado y probado para D16:** el worker sólo puede producir `EXITO` a partir de evidencia JUnit XML legible, con al menos una prueba ejecutada y sin fallas.
- **Pendiente:** API pública, persistencia, consumo de cola, publicación en Kafka, evento de finalización, `GET` por ejecución, registro de perfiles y smoke test de publicación.

## 1. Procesos que sobreviven a la evaluación

**Respuesta directa:** coincidimos en que la detección no debe descartar los resultados disponibles. La capa 1 mata los procesos sobrevivientes, empaqueta el buzón de reportes y emite el sobre igualmente. La ejecución queda marcada como no confiable para que no pueda terminar en éxito.

- **Implementado y probado:** si queda algún proceso vivo, la capa 1 informa `VEREDICTO_NO_CONFIABLE`, incluye `procesosSobrevivientes`, conserva `reportesTarGzB64`, `stdoutB64` y `stderrB64`, y termina con código `30`. El worker traduce ese resultado a `VEREDICTO_NO_CONFIABLE` y actualmente no consume intento. Los bundles hostiles `hostil-reporte` y `hostil-reporte-loop` verifican esta conducta.
- **Decisión/contrato propuesto:** mantenemos la semántica solicitada por G5, recibir los resultados y marcar el intento como sospechoso, pero usamos el código `30` en lugar de `21`. Ambos pertenecen a nuestra banda `20-31`; `30` ya está implementado, documentado y probado específicamente para este caso. El dato estable para integrar no debe ser sólo el número, sino el par `resultado: VEREDICTO_NO_CONFIABLE` y `procesosSobrevivientes > 0`.
- **Pendiente:** exponer esa marca y los reportes mediante la API o el evento público. Esos canales todavía no existen.

## 2. Validación del tar antes de extraerlo

**Respuesta directa:** la frase "rechacen ustedes antes de armarlo" fue imprecisa. G5 no debería tener que armar ni validar el tar. Según el contrato actual, G5 entrega la lista de archivos y G8 valida sus rutas antes de que nuestro worker construya el tar.

- **Implementado:** la capa 1 aplica una segunda defensa sobre el tar ya serializado. Lo guarda sin extraerlo, inspecciona sus entradas y rechaza rutas absolutas, nombres con `..`, enlaces simbólicos y enlaces duros. Sólo después intenta extraerlo en `/work/in`. Ante un tar inválido emite `BUNDLE_INVALIDO` con código `22`.
- **Decisión/contrato propuesto:** G8 realiza ambos controles. La API valida cada ruta de `files[]` antes de aceptar el pedido y el worker arma el tar. Luego, la capa 1 vuelve a validar ese tar antes de extraerlo. G5 sólo debe enviarnos las rutas y contenidos acordados.
- **Pendiente:** la validación temprana en la API no está implementada. El `Empaquetador` actual del worker sólo arma tars desde un directorio local usado por las pruebas y documenta esta limitación. También falta ejecutar como aceptación específica la matriz de tars hostiles con rutas absolutas, `..`, enlaces simbólicos y enlaces duros.

## 3. Archivos `$SANDBOX_STATUS/fase` y `$SANDBOX_STATUS/detalle`

**Respuesta directa:** son dos archivos de texto plano, opcionales, sin extensión. No son parámetros del proceso ni forman parte del buzón de reportes.

El script de evaluación puede escribir, por ejemplo:

```sh
printf '%s' 'COMPILACION' > "$SANDBOX_STATUS/fase"
printf '%s' 'no compila la solución del alumno' > "$SANDBOX_STATUS/detalle"
```

- **Implementado y probado:** la capa 1 lee ambos archivos al terminar la capa 2 y copia su contenido en los campos `faseDeclarada` y `detalleDeclarado` del sobre `sandbox.capa1/v2`. Limita `fase` a 128 bytes y `detalle` a 512 bytes, elimina caracteres de control y escapa el contenido para no romper el JSON. El worker ya recibe esos campos como diagnóstico, pero no los usa para decidir el veredicto.
- **Decisión/contrato propuesto:** usarlos únicamente para mensajes comprensibles, como `COMPILACION`, `PRUEBAS` o `ANALISIS`. El código de salida sigue siendo el canal resistente ante una muerte abrupta y el reporte sigue siendo la fuente del veredicto.
- **Pendiente:** definir cómo se proyectarán estos campos en la respuesta pública y en `EXECUTION_COMPLETED`.

## 4. Punto 2.2: destino de `stdout` y `stderr`

**Respuesta directa:** se guardan por separado en el directorio temporal, no en el buzón de reportes. `stdout` va a `/work/tmp/fase.out` y `stderr` a `/work/tmp/fase.err`.

- **Implementado y probado:** la capa 1 redirige ambos streams antes de ejecutar el script del perfil. Al cerrar la evaluación los incorpora por separado como `stdoutB64` y `stderrB64` dentro del sobre. Cada stream se limita a 65.536 bytes y el campo `truncado` indica si alguno superó ese límite. Por lo tanto, `stderr` ya se conserva y viaja junto con el resultado.
- **Decisión/contrato propuesto:** mantener `stdout` y `stderr` separados hasta el contrato público. Mezclarlos perdería información útil para distinguir errores de compilación, salida normal y diagnósticos de herramientas.
- **Pendiente:** decodificar y exponer ambos campos en la API o el evento final. El núcleo actual del worker los recibe, pero todavía no existe esa salida pública.

## 5. Punto 4.3: tiempos de compilación y ejecución

**Respuesta directa:** no necesitamos una señal por `stdout`. El script del perfil conoce exactamente cuándo empieza y termina cada comando, por lo que puede medir cada fase alrededor de `javac`, el análisis o la ejecución de pruebas.

- **Implementado y probado:** la capa 1 informa tiempos agregados de toda la capa 2 en `recursos.msEval` y `recursos.cpuEvalMs`. El perfil de producción aplica relojes separados para compilación y pruebas, pero no publica todavía la duración observada de cada fase.
- **Decisión/contrato propuesto:** si G5 necesita tiempos por fase, el script debe escribir un artefacto estructurado, por ejemplo `tiempos.json`, dentro de `$SANDBOX_REPORTS`. Ya existe un perfil experimental fuera del catálogo de producción que demuestra esta medición. Usar `stdout` como señal de cambio de fase sería más frágil y mezclaría protocolo con salida diagnóstica.
- **Pendiente:** acordar el esquema de ese artefacto y llevar la medición al perfil de producción. Hoy no prometemos tiempos por fase en el evento ni en el `GET`.

## 6. Punto 4.6: `reportFormat`, nombre del reporte y códigos de salida

**Respuesta directa:** hoy la única opción soportada es `junit-xml`. El worker no depende de un nombre único de archivo: recorre todos los archivos `.xml` del buzón y suma la evidencia de todas las suites.

- **Implementado y probado:** `java21-junit@4` declara `reportFormat: "junit-xml"`; el verificador suma `tests - skipped` y `failures + errors` en todos los XML. Un XML ilegible, un buzón sin XML o un formato sin verificador cierran de forma segura con `ERROR_INTERNO`, nunca con `EXITO`.
- **Límite actual:** aunque `reportFormat` está declarado en el perfil del ejecutor, el núcleo de producción del worker todavía lo tiene fijado mediante `FORMATO_DE_EVIDENCIA = "junit-xml"`. El ejecutor devuelve `perfilId`, `perfilVersion` y `perfilHash`, pero todavía no transporta `reportFormat` al worker. Por eso no afirmamos soporte dinámico de formatos.
- **Decisión/contrato propuesto:** pueden usar un nombre estándar para facilitar diagnóstico, pero la corrección no dependerá de ese nombre mientras los reportes JUnit terminen en `.xml`. G5 define qué códigos y significados necesita su script; G8 mantiene y valida la traducción de esos códigos a veredictos de plataforma.
- **Pendiente:** transportar el formato efectivo desde la versión del perfil y agregar verificadores para cualquier formato adicional. La tabla de la banda `40-59` está hoy en código, no en una base de datos, y es provisional hasta acordarla con G5.

## 7. Punto 4.7: validación del perfil

**Respuesta directa:** el smoke test de publicación debería ser asíncrono respecto del alta del perfil y quedar bajo responsabilidad de G8. El perfil no debería poder activarse hasta que la validación termine correctamente.

- **Implementado y probado:** existen pruebas del catálogo al arranque del ejecutor y una integración ejecutor-worker para D16. Estas pruebas verifican el perfil de referencia y el comportamiento `fail-closed`, pero no constituyen un flujo de registro de perfiles.
- **Decisión/contrato propuesto:** al registrar una versión, G8 ejecutaría al menos un bundle esperado como exitoso y otro esperado como fallido. Ambos deben producir veredictos distintos y coherentes. Mientras se ejecuta esa validación, el perfil permanece sin activar.
- **Necesitamos de G5:** el script de la capa 2, el `reportFormat` y fixtures mínimos con su resultado esperado para cada perfil.
- **Pendiente:** implementar el registro, sus estados y el smoke test. Hoy no hay endpoint ni proceso asíncrono para esto.

## 8. Punto 6: integración asíncrona

**Respuesta directa:** aceptamos los cuatro ajustes: devolver el identificador en el `202`, no recibir `intentoNro`, usar `EXECUTION_COMPLETED` y mapear el `GET` por el mismo `executionId`.

- **Decisión/contrato propuesto:** la respuesta de aceptación devuelve el identificador generado, por ejemplo `{"executionId":"..."}`. Ese mismo valor correlaciona la ejecución interna, el evento `EXECUTION_COMPLETED` y `GET /ejecuciones/{executionId}`. Para G8 cada solicitud es una ejecución independiente; la relación con entrega, alumno o número de intento queda del lado de G5. Kafka se reserva para publicar el resultado hacia la plataforma; la cola interna de trabajo es una preocupación separada.
- **Implementado y probado parcialmente:** el ejecutor ya exige un identificador UUID en `X-Ejecucion-Id`, lo usa para correlación y lo devuelve en su respuesta interna. Esto no equivale al contrato público anterior.
- **Pendiente:** el endpoint que responde `202 Accepted`, Kafka, el evento `EXECUTION_COMPLETED`, la persistencia y el `GET /ejecuciones/{executionId}`. Ninguna de esas piezas está implementada actualmente.

## 9. Punto 7.4: incorporación de nuevos códigos

**Respuesta directa:** hoy no es solamente agregar un código a una base de datos. La tabla efectiva está implementada en el worker y cada código tiene semántica de negocio, incluido el veredicto resultante y si consume intento.

- **Implementado y probado:** el worker contiene una tabla provisional para `40-47`; los valores no definidos dentro de `40-59` producen `ERROR_INTERNO` y no pueden aprobar una entrega.
- **Decisión/contrato propuesto:** G5 define la necesidad y el significado del código; G8 incorpora su traducción y sus pruebas. Cuando exista el registro de perfiles, podremos persistir metadatos descriptivos, pero un cambio que altere veredictos o consumo de intento seguirá requiriendo validación de comportamiento.
- **Pendiente:** decidir qué parte de la tabla será configuración versionada y qué parte seguirá siendo lógica del worker. No existe hoy una base de datos de códigos.

## 10. Punto 7.7: códigos iniciales del perfil

**Respuesta directa:** de acuerdo, para el contrato inicial entre grupos podemos fijar únicamente `0` y ampliar la tabla cuando G5 entregue las necesidades de cada perfil.

`0` significa que la capa 2 terminó y dejó evidencia para evaluar. No significa que el alumno aprobó: el worker determina `EXITO` o `TESTS_FALLIDOS` leyendo el reporte.

- **Estado actual:** el perfil de referencia de G8 usa provisionalmente `40-47` para distinguir compilación fallida, suite inválida, límites y ausencia de evidencia. Esos códigos permiten probar la cadena actual, pero no los presentamos como una tabla definitiva acordada con G5.
- **Decisión/contrato propuesto:** `0` queda estable con la semántica anterior. Los códigos adicionales se acuerdan antes de integrar cada perfil y se incorporan dentro de la banda `40-59`.
- **Pendiente:** reemplazar o confirmar la tabla provisional cuando G5 envíe la lista definitiva.

## Datos necesarios para la integración inicial

Para integrar el primer perfil necesitamos de G5:

1. El script de evaluación de la capa 2.
2. El `reportFormat`, inicialmente `junit-xml`.
3. Un bundle que deba completar correctamente y otro que deba producir un fallo conocido.
4. La lista de códigos adicionales a `0`, cuando la tengan definida.

Con eso podemos cerrar el contrato del perfil sin depender todavía de la API pública, Kafka o el registro dinámico de perfiles.
