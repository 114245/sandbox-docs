# Contrato de Invocación — Sandbox (Tema 06) ↔ Desafíos Prácticos (Tema 05)

**Estado:** propuesta para discutir en reunión conjunta — no cerrado
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
