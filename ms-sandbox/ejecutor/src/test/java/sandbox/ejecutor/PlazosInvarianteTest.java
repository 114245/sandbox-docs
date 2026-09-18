package sandbox.ejecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-16 (spec 2026-09-14-f16-perfil-y-ci.md, 4.2): la invariante de plazos que antes "se sostenia a
 * mano". Los valores salen siempre de los archivos reales, via {@link PlazosDeclarados}.
 */
class PlazosInvarianteTest {

    // ------------------------------------------------------------------
    // El repo
    // ------------------------------------------------------------------

    @Test
    void cadaPerfilCabeEnElBackstopDeSuImagen() throws IOException {
        List<Path> perfiles = PlazosDeclarados.perfilesDeProduccion();
        assertFalse(perfiles.isEmpty(), "no hay perfiles en " + PlazosDeclarados.PERFILES.toAbsolutePath());

        for (Path json : perfiles) {
            Path script = PlazosDeclarados.scriptDe(json);
            int presupuesto = PlazosDeclarados.presupuestoCapa2(script);
            Path dockerfile = PlazosDeclarados.dockerfileDe(PlazosDeclarados.imagenDe(json));
            int eval = PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile);

            assertTrue(presupuesto + PlazosDeclarados.MARGEN_CAPA2_S <= eval,
                    json.getFileName() + ": suma de plazos " + presupuesto + " s + margen "
                            + PlazosDeclarados.MARGEN_CAPA2_S + " s no cabe en SANDBOX_EVAL_TIMEOUT_S="
                            + eval + " s de " + dockerfile);
        }
    }

    @Test
    void backstopMasGraciaMasPlataformaCabeEnElRelojDelEjecutor() throws IOException {
        int gracia = PlazosDeclarados.graciaDeCapa1(PlazosDeclarados.CAPA1);
        long reloj = PlazosDeclarados.relojEjecutorS();

        for (Path dockerfile : PlazosDeclarados.DOCKERFILES.values()) {
            int eval = PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile);
            assertTrue(eval + gracia + PlazosDeclarados.MARGEN_PLATAFORMA_S <= reloj,
                    dockerfile + ": backstop " + eval + " s + gracia " + gracia + " s + plataforma "
                            + PlazosDeclarados.MARGEN_PLATAFORMA_S + " s supera el reloj del ejecutor ("
                            + reloj + " s)");
        }
    }

    @Test
    void elValorPorDefectoDeCapa1CoincideConCadaDockerfile() throws IOException {
        int porDefecto = PlazosDeclarados.evalTimeoutPorDefectoDeCapa1(PlazosDeclarados.CAPA1);
        for (Path dockerfile : PlazosDeclarados.DOCKERFILES.values()) {
            assertEquals(porDefecto, PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile),
                    "el default de capa1.sh no coincide con el ENV de " + dockerfile);
        }
    }

    // ------------------------------------------------------------------
    // Las reglas de extraccion, sobre @TempDir
    // ------------------------------------------------------------------

    @Test
    void sumaTodasLasInvocacionesReales(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                A_S=8
                B_S=25
                timeout --signal=KILL "${A_S}s" javac uno
                timeout --signal=KILL "${A_S}s" javac dos
                timeout --signal=KILL "${A_S}s" javac tres
                  timeout --signal=KILL "${B_S}s" \\
                    java -jar x
                """);
        assertEquals(8 * 3 + 25, PlazosDeclarados.presupuestoCapa2(script));
    }

    @Test
    void losComentariosNoCuentan(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                # A_S=99
                #   b) `timeout` de pared, para el que duerme
                A_S=8
                timeout "${A_S}s" javac
                """);
        assertEquals(8, PlazosDeclarados.presupuestoCapa2(script));
    }

    @Test
    void asignacionDuplicadaFalla(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                A_S=25
                timeout "${A_S}s" java
                A_S=5
                """);
        AssertionError e = assertThrows(AssertionError.class, () -> PlazosDeclarados.presupuestoCapa2(script));
        assertTrue(e.getMessage().contains("A_S"), e.getMessage());
    }

    @Test
    void reasignacionNoNumericaTambienCuentaComoDuplicado(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                A_S=25
                export A_S=$((A_S * 2))
                timeout "${A_S}s" java
                """);
        assertThrows(AssertionError.class, () -> PlazosDeclarados.presupuestoCapa2(script));
    }

    @Test
    void timeoutConLiteralFalla(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                A_S=8
                timeout "${A_S}s" javac
                timeout 30s java
                """);
        AssertionError e = assertThrows(AssertionError.class, () -> PlazosDeclarados.presupuestoCapa2(script));
        assertTrue(e.getMessage().contains("timeout 30s"), e.getMessage());
    }

    @Test
    void scriptSinTimeoutFalla(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", "echo nada\n");
        assertThrows(AssertionError.class, () -> PlazosDeclarados.presupuestoCapa2(script));
    }

    @Test
    void envDuplicadoEnDockerfileFalla(@TempDir Path dir) throws IOException {
        Path dockerfile = escribir(dir, "Dockerfile", """
                ENV SANDBOX_EVAL_TIMEOUT_S=45
                ENV SANDBOX_EVAL_TIMEOUT_S=90
                """);
        assertThrows(AssertionError.class, () -> PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile));
    }

    @Test
    void extraeElEnvDeUnaListaMultilinea(@TempDir Path dir) throws IOException {
        Path dockerfile = escribir(dir, "Dockerfile", """
                # SANDBOX_EVAL_TIMEOUT_S es el backstop
                ENV SANDBOX_LIBS=/libs \\
                    SANDBOX_EVAL_TIMEOUT_S=45 \\
                    HOME=/work
                """);
        assertEquals(45, PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile));
    }

    @Test
    void extraeDefaultYGraciaDeCapa1(@TempDir Path dir) throws IOException {
        Path capa1 = escribir(dir, "capa1.sh", """
                EVAL_TIMEOUT_S="${SANDBOX_EVAL_TIMEOUT_S:-45}"
                # El `timeout` es un backstop
                  timeout -k 5s "${EVAL_TIMEOUT_S}s" sh -c "$SCRIPT" </dev/null
                """);
        assertEquals(45, PlazosDeclarados.evalTimeoutPorDefectoDeCapa1(capa1));
        assertEquals(5, PlazosDeclarados.graciaDeCapa1(capa1));
    }

    @Test
    void graciaDuplicadaFalla(@TempDir Path dir) throws IOException {
        Path capa1 = escribir(dir, "capa1.sh", """
                timeout -k 5s "${EVAL_TIMEOUT_S}s" sh -c uno
                timeout -k 1s "${EVAL_TIMEOUT_S}s" sh -c dos
                """);
        assertThrows(AssertionError.class, () -> PlazosDeclarados.graciaDeCapa1(capa1));
    }

    @Test
    void imagenSinDockerfileEnLaTablaFalla() {
        assertThrows(AssertionError.class, () -> PlazosDeclarados.dockerfileDe("sandbox-runner:9.9.9"));
    }

    private static Path escribir(Path dir, String nombre, String contenido) throws IOException {
        Path archivo = dir.resolve(nombre);
        Files.writeString(archivo, contenido, StandardCharsets.UTF_8);
        return archivo;
    }
}
