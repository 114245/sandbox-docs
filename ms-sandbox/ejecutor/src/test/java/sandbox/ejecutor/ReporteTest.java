package sandbox.ejecutor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Seccion 7: separacion del bloque de reporte. Invariante I5. */
class ReporteTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    private static String bloque(String nonce, String contenido) {
        return "---SANDBOX-" + nonce + "-INICIO---\n" + contenido + "\n---SANDBOX-" + nonce + "-FIN---\n";
    }

    @Test
    void extraeElBloqueYLoSacaDeStdout() {
        String stdout = "compilando\n" + bloque(NONCE, "<testsuite/>") + "listo\n";
        var e = Reporte.extraer(stdout, NONCE);
        assertEquals("<testsuite/>", e.reporte());
        assertEquals("compilando\nlisto\n", e.stdout());
        assertFalse(e.ausente());
    }

    @Test
    void sinMarcadoresElReporteEstaAusente() {
        var e = Reporte.extraer("solo salida del alumno\n", NONCE);
        assertNull(e.reporte());
        assertTrue(e.ausente());
        assertEquals("solo salida del alumno\n", e.stdout());
    }

    /**
     * A21 / I5: el alumno imprime un bloque con un nonce inventado y un reporte falso.
     * El reporte devuelto debe seguir siendo el verdadero, y el falso queda como salida comun.
     */
    @Test
    void a21_unBloqueConNonceAjenoNoFalsificaElReporte() {
        String falso = bloque("deadbeefdeadbeefdeadbeefdeadbeef", "<testsuite name=\"MENTIRA\"/>");
        String stdout = falso + bloque(NONCE, "<testsuite name=\"REAL\"/>");

        var e = Reporte.extraer(stdout, NONCE);
        assertEquals("<testsuite name=\"REAL\"/>", e.reporte());
        assertTrue(e.stdout().contains("MENTIRA"), "el bloque falso es salida comun, no se toca");
        assertFalse(e.stdout().contains("REAL"));
    }

    /** R7.6: con varios marcadores de inicio validos gana el ultimo. */
    @Test
    void r76_conVariosInicioGanaElUltimo() {
        String stdout = "---SANDBOX-" + NONCE + "-INICIO---\nprimero\n"
                      + bloque(NONCE, "segundo");
        assertEquals("segundo", Reporte.extraer(stdout, NONCE).reporte());
    }

    /** R7.5: el marcador de fin tiene que ser posterior al de inicio. */
    @Test
    void r75_finAnteriorAlInicioNoCuenta() {
        String stdout = "---SANDBOX-" + NONCE + "-FIN---\n---SANDBOX-" + NONCE + "-INICIO---\n";
        var e = Reporte.extraer(stdout, NONCE);
        assertNull(e.reporte());
        assertTrue(e.ausente());
    }

    /** Un bloque abierto y nunca cerrado no produce reporte, y stdout queda intacto. */
    @Test
    void bloqueSinCierre() {
        String stdout = "---SANDBOX-" + NONCE + "-INICIO---\na medias";
        var e = Reporte.extraer(stdout, NONCE);
        assertNull(e.reporte());
        assertEquals(stdout, e.stdout());
    }

    @Test
    void reporteVacioEsUnReporte() {
        var e = Reporte.extraer(bloque(NONCE, ""), NONCE);
        assertEquals("", e.reporte());
        assertFalse(e.ausente());
        assertEquals("", e.stdout());
    }

    /** El reporte multilinea se conserva entero, sin recortar mas que los saltos de los marcadores. */
    @Test
    void reporteMultilinea() {
        String xml = "<testsuite>\n  <testcase/>\n</testsuite>";
        var e = Reporte.extraer("antes\n" + bloque(NONCE, xml), NONCE);
        assertEquals(xml, e.reporte());
        assertEquals("antes\n", e.stdout());
    }
}
