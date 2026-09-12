package sandbox.worker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * El PASO 1 del mapeo, fila por fila, sin socket y sin Docker: el Sobre se construye a mano.
 *
 * Lo que estos tests sostienen no es la tabla sino la regla madre: el UNICO EXITO que puede
 * salir de Nucleo es el que delega en Juez sobre evidencia leida de los XML. Todo lo demas,
 * incluido lo que no entendemos, es ERROR_INTERNO y no consume intento.
 */
class NucleoTest {

    private final Nucleo nucleo = new Nucleo();

    // ---- el nivel del ejecutor ----

    @Test
    void unErrorDelDaemonEsInternoYNoConsumeIntento() {
        Fallo fallo = nucleo.evaluar(sobre("ERROR_DAEMON", false, true, null));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void elTimeoutDelEjecutorEsInternoYNoTimeoutDelAlumno() {
        // El reloj de 60 s del ejecutor es de respaldo: solo se dispara si fallaron los relojes
        // de adentro del contenedor. Que se dispare es problema nuestro, no una entrega lenta.
        Fallo fallo = nucleo.evaluar(sobre("TIMEOUT", false, true, null));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertNotEquals(Veredicto.TIMEOUT, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unaCorridaMuertaPorMemoriaEsLimiteMemoriaYConsumeIntento() {
        Fallo fallo = nucleo.evaluar(sobre("COMPLETADA", true, true, null));

        assertEquals(Veredicto.LIMITE_MEMORIA, fallo.veredicto());
        assertTrue(fallo.consumeIntento());
    }

    @Test
    void elOomSeMiraAntesQueElReporteAusente() {
        // El orden de la tabla del diseno seccion 5: oomKilled gana, y es el unico veredicto
        // del alumno que este nivel puede emitir.
        Fallo fallo = nucleo.evaluar(sobre("COMPLETADA", true, true, null));

        assertEquals(Veredicto.LIMITE_MEMORIA, fallo.veredicto());
    }

    @Test
    void unReporteAusenteEsInternoYNoConsumeIntento() {
        Fallo fallo = nucleo.evaluar(sobre("COMPLETADA", false, true, null));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unaCorridaRechazadaNoPuedeAprobar() {
        // El cliente normalmente corta antes con EjecutorSaturado (503). Si igual llegara, lo
        // que no puede es aprobar.
        Fallo fallo = nucleo.evaluar(sobre("RECHAZADA", false, true, null));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unResultadoDesconocidoEsInterno() {
        Fallo fallo = nucleo.evaluar(sobre("ALGO_QUE_NO_EXISTE", false, true, null));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unResultadoNuloEsInternoYNoRevienta() {
        // Jackson deja el campo en null si el ejecutor no lo mando.
        Fallo fallo = nucleo.evaluar(sobre(null, false, true, null));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unSobreNuloEsInternoYNoRevienta() {
        Fallo fallo = nucleo.evaluar(null);

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    // ---- la delegacion en el juez ----

    @Test
    void unaSuiteQueCorrioYPasoEsExitoYNoConsumeIntento() {
        Fallo fallo = nucleo.evaluar(sobreConXml(testsuite(3, 0)));

        assertEquals(Veredicto.EXITO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unaEntregaQueNoCorrioNingunTestEsSalidaAnticipada() {
        // El System.exit(0) del alumno: XML con tests="0" y exit code 0.
        Fallo fallo = nucleo.evaluar(sobreConXml(testsuite(0, 0)));

        assertNotEquals(Veredicto.EXITO, fallo.veredicto());
        assertEquals(Veredicto.SALIDA_ANTICIPADA, fallo.veredicto());
        assertTrue(fallo.consumeIntento());
    }

    @Test
    void unaSuiteConFallasEsTestsFallidos() {
        Fallo fallo = nucleo.evaluar(sobreConXml(testsuite(3, 1)));

        assertEquals(Veredicto.TESTS_FALLIDOS, fallo.veredicto());
        assertTrue(fallo.consumeIntento());
    }

    // ---- fail-closed ----

    @Test
    void unFormatoSinVerificadorNoPuedeAprobar() {
        Nucleo sinVerificador = new Nucleo(Verificadores.porDefecto(), "pmd-xml");

        Fallo fallo = sinVerificador.evaluar(sobreConXml(testsuite(3, 0)));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unReporteQueNoParseaEsInternoYNoConsumeIntento() {
        Fallo fallo = nucleo.evaluar(sobre("COMPLETADA", false, false, "esto no es json"));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unReporteQueEsElLiteralNullEsInternoYNoRevienta() {
        Fallo fallo = nucleo.evaluar(sobre("COMPLETADA", false, false, "null"));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unBuzonQueNoSeDesempaquetaEsInternoYNoConsumeIntento() {
        Fallo fallo = nucleo.evaluar(sobre("COMPLETADA", false, false, reporte("OK", 0, "no-es-base64!!")));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void ningunCaminoDelNivelDelEjecutorPuedeLlegarAExito() {
        // La propiedad entera de este archivo en un solo test: Nucleo no fabrica EXITO. El
        // unico EXITO sale de Juez sobre evidencia leida de los XML.
        for (String resultado : new String[] {
                "ERROR_DAEMON", "TIMEOUT", "RECHAZADA", "COMPLETADA", "LO_QUE_SEA", null}) {
            assertNotEquals(Veredicto.EXITO, nucleo.evaluar(sobre(resultado, false, true, null)).veredicto(),
                            "aprobo sin evidencia con resultado " + resultado);
            assertNotEquals(Veredicto.EXITO, nucleo.evaluar(sobre(resultado, true, true, null)).veredicto(),
                            "aprobo sin evidencia con resultado " + resultado + " y oomKilled");
        }
    }

    // ---- armado de sobres a mano ----

    private static Sobre sobre(String resultado, boolean oomKilled, boolean reporteAusente, String reporte) {
        return new Sobre("3f2b", resultado, 0, oomKilled, 4172, "", "", reporte,
                         reporteAusente, false, "java21-junit", 4, "9f86");
    }

    private static Sobre sobreConXml(String xml) {
        return sobre("COMPLETADA", false, false, reporte("OK", 0, tarGzB64("./TEST-a.xml", xml)));
    }

    private static String testsuite(int tests, int fallas) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
             + "<testsuite name=\"x\" tests=\"" + tests + "\" failures=\"" + fallas
             + "\" errors=\"0\" skipped=\"0\"/>";
    }

    private static String reporte(String resultado, int exitEval, String tarGzB64) {
        return """
            {"schema":"sandbox.capa1/v2","resultado":"%s","detalle":"","exitEval":%d,\
            "procesosSobrevivientes":0,"faseDeclarada":"FIN","detalleDeclarado":"",\
            "recursos":{"msEval":10,"cpuEvalMs":5},"reportesTarGzB64":"%s",\
            "stdoutB64":"","stderrB64":"","truncado":false}\
            """.formatted(resultado, exitEval, tarGzB64);
    }

    private static String tarGzB64(String nombre, String contenido) {
        ByteArrayOutputStream crudo = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(crudo);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            byte[] datos = contenido.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entrada = new TarArchiveEntry(nombre);
            entrada.setSize(datos.length);
            tar.putArchiveEntry(entrada);
            tar.write(datos);
            tar.closeArchiveEntry();
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo armar el buzon de prueba", e);
        }
        return Base64.getEncoder().encodeToString(crudo.toByteArray());
    }
}
