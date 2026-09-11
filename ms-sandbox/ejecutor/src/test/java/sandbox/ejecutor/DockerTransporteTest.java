package sandbox.ejecutor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R5.13: el transporte de produccion es unix:// o npipe:// unicamente. tcp:// (y cualquier otra
 * cosa) queda afuera porque el cierre del canal adjunto es abortivo y sobre TCP eso trunca stdin
 * (ver el javadoc de {@link Docker#validarTransporte} para la medicion).
 */
class DockerTransporteTest {

    @Test
    void a41_unixSeAcepta() {
        assertDoesNotThrow(() -> Docker.validarTransporte("unix:///var/run/docker.sock"));
    }

    @Test
    void a41_npipeSeAcepta() {
        assertDoesNotThrow(() -> Docker.validarTransporte("npipe:////./pipe/docker_engine"));
    }

    @Test
    void a41_tcpSeRechaza() {
        assertMensajeConLaCausa(assertThrows(IllegalStateException.class,
                () -> Docker.validarTransporte("tcp://127.0.0.1:2375")));
    }

    @Test
    void a41_httpSeRechaza() {
        assertMensajeConLaCausa(assertThrows(IllegalStateException.class,
                () -> Docker.validarTransporte("http://127.0.0.1:2375")));
    }

    @Test
    void a41_httpsSeRechaza() {
        assertMensajeConLaCausa(assertThrows(IllegalStateException.class,
                () -> Docker.validarTransporte("https://127.0.0.1:2376")));
    }

    @Test
    void a41_vacioSeRechaza() {
        assertMensajeConLaCausa(assertThrows(IllegalStateException.class,
                () -> Docker.validarTransporte("")));
    }

    @Test
    void a41_nullSeRechaza() {
        assertMensajeConLaCausa(assertThrows(IllegalStateException.class,
                () -> Docker.validarTransporte(null)));
    }

    @Test
    void a41_basuraSeRechaza() {
        assertMensajeConLaCausa(assertThrows(IllegalStateException.class,
                () -> Docker.validarTransporte("esto-no-es-una-url")));
    }

    /** El chequeo no puede depender de mayusculas/minusculas: "TCP://" tiene que caer igual. */
    @Test
    void a41_variantesDeMayusculasSeRechazan() {
        assertMensajeConLaCausa(assertThrows(IllegalStateException.class,
                () -> Docker.validarTransporte("TCP://127.0.0.1:2375")));
        assertMensajeConLaCausa(assertThrows(IllegalStateException.class,
                () -> Docker.validarTransporte("Tcp://127.0.0.1:2375")));
    }

    private static void assertMensajeConLaCausa(IllegalStateException e) {
        assertTrue(e.getMessage().contains("DOCKER_HOST"), e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("abortivo")
                        || e.getMessage().toLowerCase().contains("reset"),
                "el mensaje tiene que explicar la causa (cierre abortivo / reset): " + e.getMessage());
    }
}
