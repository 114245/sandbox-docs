package sandbox.worker;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificadorJunitXmlTest {

    private final VerificadorJunitXml verificador = new VerificadorJunitXml();

    private static Buzon buzonCon(String... paresNombreContenido) {
        Map<String, byte[]> archivos = new LinkedHashMap<>();
        for (int i = 0; i < paresNombreContenido.length; i += 2) {
            archivos.put(paresNombreContenido[i],
                         paresNombreContenido[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return new Buzon(archivos);
    }

    private static String testsuite(int tests, int fallas, int errores, int salteados) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
             + "<testsuite name=\"x\" tests=\"" + tests + "\" failures=\"" + fallas
             + "\" errors=\"" + errores + "\" skipped=\"" + salteados + "\"/>";
    }

    @Test
    void sumaLosTestsDeTodosLosXmlDelBuzon() {
        // El caso ok-suma: tres XML, dos con tests="0". Mirar archivo por archivo
        // y rechazar al primero con cero romperia el camino feliz (12-d16 seccion 8.3).
        Buzon buzon = buzonCon(
            "./TEST-a.xml", testsuite(0, 0, 0, 0),
            "./TEST-b.xml", testsuite(3, 0, 0, 0),
            "./TEST-c.xml", testsuite(0, 0, 0, 0));

        Evidencia evidencia = verificador.verificar(buzon);

        assertTrue(evidencia.legible());
        assertEquals(3, evidencia.corridas());
        assertEquals(0, evidencia.fallidas());
    }

    @Test
    void sumaFallasYErroresJuntosComoFallidas() {
        Buzon buzon = buzonCon(
            "./TEST-a.xml", testsuite(5, 2, 1, 0));

        Evidencia evidencia = verificador.verificar(buzon);

        assertEquals(5, evidencia.corridas());
        assertEquals(3, evidencia.fallidas());
    }

    @Test
    void losSalteadosNoCuentanComoCorridos() {
        // Una suite entera con @Disabled declara tests=3 y no ejecuto ninguna unidad de
        // evaluacion. Es la misma familia que el System.exit(0) que origino D6.
        Buzon buzon = buzonCon("./TEST-a.xml", testsuite(3, 0, 0, 3));

        Evidencia evidencia = verificador.verificar(buzon);

        assertTrue(evidencia.legible());
        assertEquals(0, evidencia.corridas());
    }

    @Test
    void ignoraLosArchivosQueNoSonXml() {
        // nota.json trae un campo `tests` ya calculado. Es el contador precalculado que la
        // regla madre prohibe usar como fuente del veredicto: se ignora el archivo entero.
        Buzon buzon = buzonCon(
            "./nota.json", "{\"schema\":\"g5.nota/v1\",\"tests\":99}",
            "./TEST-a.xml", testsuite(2, 0, 0, 0));

        Evidencia evidencia = verificador.verificar(buzon);

        assertEquals(2, evidencia.corridas());
    }

    @Test
    void unXmlRotoDejaLaEvidenciaIlegible() {
        Buzon buzon = buzonCon(
            "./TEST-a.xml", testsuite(3, 0, 0, 0),
            "./TEST-b.xml", "<testsuite tests=\"2\"");   // sin cerrar

        Evidencia evidencia = verificador.verificar(buzon);

        assertFalse(evidencia.legible());
    }

    @Test
    void unBuzonSinNingunXmlNoEsLegible() {
        Buzon buzon = buzonCon("./nota.json", "{\"tests\":7}");

        assertFalse(verificador.verificar(buzon).legible());
    }

    @Test
    void unBuzonVacioNoEsLegible() {
        assertFalse(verificador.verificar(Buzon.vacio()).legible());
    }

    @Test
    void noResuelveEntidadesExternas() {
        // El XML lo escribio un proceso que corrio codigo del alumno. Un DOCTYPE con una
        // entidad externa no puede llegar a abrir un archivo del worker.
        String xxe = "<?xml version=\"1.0\"?>"
                   + "<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                   + "<testsuite name=\"&e;\" tests=\"1\" failures=\"0\" errors=\"0\"/>";
        Buzon buzon = buzonCon("./TEST-a.xml", xxe);

        assertFalse(verificador.verificar(buzon).legible());
    }

    @Test
    void soportaJunitXmlYNadaMas() {
        assertTrue(verificador.soporta("junit-xml"));
        assertFalse(verificador.soporta("pmd-xml"));
    }
}
