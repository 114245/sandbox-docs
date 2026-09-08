# Contrato de Invocación — Sandbox (Tema 06) ↔ Desafíos Prácticos (Tema 05)

> # ⚠ DOCUMENTO OBSOLETO — no usar como contrato
>
> **Superado el 7 de septiembre de 2026** por el V4 del Grupo 5 y la respuesta que le mandamos.
> Se conserva por trazabilidad: es el primer borrador del contrato, y sirve para entender de
> dónde venimos. **No refleja lo acordado.**
>
> **Qué se cayó de acá:**
>
> | Esto de abajo | Por qué ya no vale |
> |---|---|
> | `language: "python"`, `language_version: "3.11"` | El sandbox dejó de saber de lenguajes. Lo define el **perfil**, y el primero es Java 21 |
> | `source_code` como string único | Ahora viaja un **tar** con un árbol de archivos entero |
> | `test_cases[]` con `stdin` / `expected_stdout` | No comparamos stdout. Los tests son **archivos** que corre la capa de evaluación de T05 |
> | `entrypoint: "solucion.py"` | El entrypoint es **nuestro** y no es negociable; lo que T05 aporta es el script de evaluación (capa 2) |
> | `limits` en el request | Se mudan al **perfil**, con presupuestos separados de compilación y evaluación |
>
> **Qué sobrevive** —y por eso este documento no se borra—:
>
> - **El modelo asincrónico** y su fundamento (la latencia de levantar un contenedor + los picos de
>   ~120 usuarios simultáneos en cierres de curso). Se confirmó, y hoy es la **D20**: `202 Accepted`
>   más el resultado por el bus de eventos.
> - **La distinción entre error de infraestructura y falla del alumno**, y que el primero **no
>   consume intento**. Sigue vigente, ahora la calcula T05 con lo que le damos nosotros.
>
> **Qué leer en su lugar:**
>
> - [`otros/Respuesta_G8_a_Propuesta_V4.md`](./Respuesta_G8_a_Propuesta_V4.md) — el contrato del
>   contenedor completo (§2) y el modelo asincrónico (§6). Es lo que se le mandó a G5.
> - [`docs/arquitectura/11-impacto-v4-g5.md`](../docs/arquitectura/11-impacto-v4-g5.md) §7.4 — el
>   análisis de por qué este documento queda obsoleto.

---

**Estado:** ~~propuesta para discutir en reunión conjunta — no cerrado~~ **OBSOLETO** (ver arriba)
**Tecnología del sandbox:** Docker (aislamiento por contenedor efímero)

---

## Por qué el contrato es asincrónico

Con Docker, cada ejecución levanta un contenedor a partir de una imagen con el runtime del lenguaje ya instalado. Levantar, ejecutar y destruir un contenedor tiene latencia real — no es instantáneo. Sumado a que el sistema debe soportar picos de concurrencia en cierres de curso (~120 usuarios simultáneos), el modelo **no puede ser una llamada síncrona que espera la respuesta**. Por eso proponemos: se encola la ejecución, se confirma la recepción al instante, y el resultado se consulta o se notifica después.

---

## 1. Enviar código a ejecución

`POST /executions`

```json
{
  "execution_id": "uuid-generado-por-tema05",
  "language": "python",
  "language_version": "3.11",
  "source_code": "def suma(a, b):\n    return a + b",
  "entrypoint": "solucion.py",
  "test_cases": [
    {
      "id": "tc_01",
      "stdin": "3 4\n",
      "expected_stdout": "7\n",
      "timeout_ms": 3000
    }
  ],
  "limits": {
    "memory_mb": 256,
    "cpu_cores": 1,
    "timeout_ms": 5000,
    "network_disabled": true
  }
}
```

**Respuesta inmediata (solo confirma recepción, no el resultado):**

```json
{
  "execution_id": "uuid",
  "status": "queued",
  "queue_position": 3
}
```

---

## 2. Consultar resultado

`GET /executions/{execution_id}`

```json
{
  "execution_id": "uuid",
  "status": "completed",
  "results": [
    {
      "test_id": "tc_01",
      "passed": true,
      "actual_stdout": "7\n",
      "actual_stderr": "",
      "execution_time_ms": 45,
      "memory_used_mb": 18
    }
  ],
  "compile_output": null,
  "total_execution_time_ms": 120,
  "container_status": "destroyed"
}
```

### Estados posibles (`status`)

| Estado | Significado |
|---|---|
| `queued` | Esperando lugar en la cola |
| `running` | Contenedor levantado, ejecutando |
| `completed` | Terminó — ver `results` |
| `compile_error` | No compiló (lenguajes compilados) |
| `runtime_error` | Crasheó en ejecución |
| `timeout` | Superó el tiempo límite (falla de test, no de infraestructura) |
| `memory_exceeded` | Superó el límite de memoria |
| `infra_error` | Problema del sandbox — **no es responsabilidad del alumno** |

---

## 3. Alternativa: webhook en lugar de polling

Si a Tema 05 le resulta más práctico no estar consultando, pueden entregarnos una URL de callback y nosotros notificamos al terminar:

```json
{
  "callback_url": "https://tema05.internal/executions/callback"
}
```

Se propone como **opción**, no como reemplazo obligatorio del polling — a definir según cómo maneje Tema 05 su lado.

---

## División de responsabilidades (para dejar explícito)

El sandbox **ejecuta y devuelve hechos crudos** (salida, tiempo, estado). No conoce desafíos, cursos ni alumnos, y no decide si un resultado es pedagógicamente correcto ni qué efecto tiene sobre XP o vidas — eso es dominio de Tema 05 (y de ahí en más, Tema 03/10).

---

## Preguntas para Tema 05

1. **¿Qué lenguajes entran en el MVP?** Necesitamos la lista cerrada para preparar y mantener las imágenes Docker correspondientes con anticipación.
2. **¿Cómo se evalúa "correcto"?** ¿Comparación exacta de `stdout`, o con tolerancia (espacios, orden, formato numérico)?
   - **Propuesta nuestra:** el sandbox devuelve `stdout`/`stderr` crudos sin comparar; la lógica de corrección la aplica Tema 05. Así el sandbox se mantiene como servicio reutilizable sin lógica de negocio.
3. **¿Polling o webhook?** Para que cada equipo diseñe su lado en consecuencia.
4. **¿Qué pasa con código que no compila?** ¿Es un test fallido más o un estado distinto, mostrado diferente al alumno?
5. **Volumen esperado:** ¿cuántos test cases en promedio por desafío? Necesario para dimensionar timeouts totales y cola de ejecución.
