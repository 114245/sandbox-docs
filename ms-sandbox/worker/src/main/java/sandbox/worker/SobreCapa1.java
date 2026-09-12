package sandbox.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * El sobre interno, el que viaja DENTRO del campo `reporte` de la respuesta del ejecutor.
 *
 * La spec del ejecutor lo trata como opaco a proposito (08 R3.5) y no lo define. La fuente
 * real es ms-sandbox/imagenes/capa1/capa1.sh, funcion emitir().
 *
 * @JsonIgnoreProperties porque el schema puede crecer sin que el worker deje de andar; el
 * dia que el worker NECESITE un campo nuevo, se agrega aca.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SobreCapa1(
        String schema,
        String resultado,
        String detalle,
        /** El codigo de salida de la CAPA 2. La banda 40-59 es de G5. */
        int exitEval,
        int procesosSobrevivientes,
        /** Lo que la capa 2 dejo en $SANDBOX_STATUS. Diagnostico, NO fuente de veredicto. */
        String faseDeclarada,
        String detalleDeclarado,
        Recursos recursos,
        String reportesTarGzB64,
        String stdoutB64,
        String stderrB64,
        boolean truncado) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recursos(long msEval, long cpuEvalMs) {}
}
