package sandbox.worker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesempaquetadorTest {

    /** Arma el mismo tar.gz en base64 que produce capa1.sh con `tar -czf - -C $DIR_REPORTES .`. */
    private static String tarGzB64(String... paresNombreContenido) throws Exception {
        ByteArrayOutputStream crudo = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(crudo);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            for (int i = 0; i < paresNombreContenido.length; i += 2) {
                byte[] datos = paresNombreContenido[i + 1].getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entrada = new TarArchiveEntry(paresNombreContenido[i]);
                entrada.setSize(datos.length);
                tar.putArchiveEntry(entrada);
                tar.write(datos);
                tar.closeArchiveEntry();
            }
        }
        return Base64.getEncoder().encodeToString(crudo.toByteArray());
    }

    @Test
    void leeElSobreDeLaCapa1ConTodosSusCampos() {
        String json = """
            {"schema":"sandbox.capa1/v2","resultado":"OK","detalle":"",
             "exitEval":0,"procesosSobrevivientes":0,
             "faseDeclarada":"FIN","detalleDeclarado":"la suite corrio",
             "recursos":{"msEval":4172,"cpuEvalMs":3100},
             "reportesTarGzB64":"","stdoutB64":"","stderrB64":"","truncado":false}
            """;

        SobreCapa1 sobre = Desempaquetador.leerSobre(json);

        assertEquals("sandbox.capa1/v2", sobre.schema());
        assertEquals("OK", sobre.resultado());
        assertEquals(0, sobre.exitEval());
        assertEquals(0, sobre.procesosSobrevivientes());
        assertEquals(4172, sobre.recursos().msEval());
        assertFalse(sobre.truncado());
    }

    @Test
    void unSobreQueEsElLiteralNullTiraErrorDeSobre() {
        // `null` es JSON valido: readValue devuelve null SIN excepcion, asi que el catch no
        // dispara y el null sale a reventar aguas abajo.
        assertThrows(ErrorDeSobre.class, () -> Desempaquetador.leerSobre("null"));
    }

    @Test
    void unSobreQueNoParseaTiraErrorDeSobre() {
        assertThrows(ErrorDeSobre.class, () -> Desempaquetador.leerSobre("{esto no es json"));
    }

    @Test
    void desempaquetaElBuzonConservandoNombresYContenido() throws Exception {
        SobreCapa1 sobre = sobreCon(tarGzB64(
            "./TEST-a.xml", "<testsuite tests=\"1\"/>",
            "./nota.json",  "{\"tests\":1}"));

        Buzon buzon = Desempaquetador.abrirBuzon(sobre);

        assertEquals(2, buzon.archivos().size());
        assertTrue(buzon.archivos().containsKey("./TEST-a.xml"));
        assertEquals("{\"tests\":1}",
                     new String(buzon.archivos().get("./nota.json"), StandardCharsets.UTF_8));
    }

    @Test
    void unBuzonVacioDaUnBuzonVacioYNoUnError() {
        // capa1.sh deja reportesTarGzB64 en "" cuando el directorio esta vacio.
        assertTrue(Desempaquetador.abrirBuzon(sobreCon("")).estaVacio());
    }

    @Test
    void unBase64RotoTiraErrorDeSobre() {
        assertThrows(ErrorDeSobre.class, () -> Desempaquetador.abrirBuzon(sobreCon("no-es-base64!!")));
    }

    @Test
    void unBuzonQueSePasaDelTopeTiraErrorDeSobre() throws Exception {
        // Un gzip chico puede descomprimir a gigabytes. El tope corta antes de llenar memoria.
        String relleno = "x".repeat(Constantes.MAX_BUZON_BYTES + 1);
        SobreCapa1 sobre = sobreCon(tarGzB64("./gordo.xml", relleno));

        assertThrows(ErrorDeSobre.class, () -> Desempaquetador.abrirBuzon(sobre));
    }

    private static SobreCapa1 sobreCon(String reportesTarGzB64) {
        return new SobreCapa1("sandbox.capa1/v2", "OK", "", 0, 0, "", "",
                              new SobreCapa1.Recursos(0, 0), reportesTarGzB64, "", "", false);
    }
}
