package sandbox.ejecutor;

/**
 * Separacion del bloque de reporte (seccion 7).
 *
 * El codigo del alumno escribe en el mismo stdout por donde viaja el reporte. Sin un separador que
 * el alumno no pueda producir, un println con el formato del reporte falsifica el resultado.
 * El separador es el nonce, que solo conocen el ejecutor y el entrypoint.
 */
final class Reporte {
    private Reporte() {}

    /** El stdout ya sin el bloque, y el contenido del reporte (null si no aparecio). */
    record Extraccion(String stdout, String reporte) {
        boolean ausente() { return reporte == null; }
    }

    static Extraccion extraer(String stdout, String nonce) {
        String marcaInicio = "---SANDBOX-" + nonce + "-INICIO---";
        String marcaFin = "---SANDBOX-" + nonce + "-FIN---";

        // R7.6: si el marcador de inicio aparece mas de una vez, se usa la ultima ocurrencia.
        int inicio = stdout.lastIndexOf(marcaInicio);
        if (inicio < 0) return new Extraccion(stdout, null);

        // R7.5: la primera aparicion del marcador de fin POSTERIOR al de inicio.
        int fin = stdout.indexOf(marcaFin, inicio + marcaInicio.length());
        if (fin < 0) return new Extraccion(stdout, null);

        String contenido = recortarSaltos(stdout.substring(inicio + marcaInicio.length(), fin));

        // Se remueve desde el primer byte del marcador de inicio hasta el ultimo del de fin,
        // mas el salto de linea inmediatamente posterior si existe.
        int corte = fin + marcaFin.length();
        if (corte < stdout.length() && stdout.charAt(corte) == '\r') corte++;
        if (corte < stdout.length() && stdout.charAt(corte) == '\n') corte++;

        return new Extraccion(stdout.substring(0, inicio) + stdout.substring(corte), contenido);
    }

    /** Quita el salto que sigue al marcador de inicio y el que precede al de fin, no mas. */
    private static String recortarSaltos(String s) {
        if (s.startsWith("\r\n")) s = s.substring(2);
        else if (s.startsWith("\n")) s = s.substring(1);
        if (s.endsWith("\r\n")) s = s.substring(0, s.length() - 2);
        else if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
