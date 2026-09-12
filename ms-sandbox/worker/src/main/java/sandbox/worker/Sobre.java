package sandbox.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * La respuesta del ejecutor: 08 seccion 3.1, "la respuesta". Trece campos, y el orden importa
 * del lado del ejecutor (R3.7), no del nuestro.
 *
 * `reporte` es opaco para el ejecutor (R3.5) y es donde viaja el SobreCapa1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record Sobre(
        String ejecucionId,
        String resultado,
        Integer exitCode,
        boolean oomKilled,
        long duracionMs,
        String stdout,
        String stderr,
        String reporte,
        boolean reporteAusente,
        boolean salidaTruncada,
        String perfilId,
        Integer perfilVersion,
        String perfilHash) {}
