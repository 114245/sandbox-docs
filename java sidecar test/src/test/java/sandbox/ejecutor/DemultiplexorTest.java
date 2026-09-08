package sandbox.ejecutor;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** A8 a A12: el demuxer contra streams sinteticos. */
class DemultiplexorTest {

    private static byte[] frame(int tipo, byte[] payload) {
        byte[] f = new byte[8 + payload.length];
        f[0] = (byte) tipo;
        int n = payload.length;
        f[4] = (byte) (n >>> 24); f[5] = (byte) (n >>> 16); f[6] = (byte) (n >>> 8); f[7] = (byte) n;
        System.arraycopy(payload, 0, f, 8, payload.length);
        return f;
    }

    private static byte[] concatenar(byte[]... partes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : partes) o.writeBytes(p);
        return o.toByteArray();
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void separaLosDosStreams() {
        var s = Demultiplexor.demultiplexar(concatenar(
                frame(1, utf8("hola ")),
                frame(2, utf8("error")),
                frame(1, utf8("mundo"))));
        assertEquals("hola mundo", s.stdout());
        assertEquals("error", s.stderr());
        assertFalse(s.truncada());
    }

    /** A8: muchos frames chicos; el texto reconstruido debe ser exacto. */
    @Test
    void a8_muchosFramesChicos() {
        StringBuilder esperado = new StringBuilder();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int i = 0; i < 5000; i++) {
            String trozo = "linea-" + i + "\n";
            esperado.append(trozo);
            stream.writeBytes(frame(1, utf8(trozo)));
        }
        assertEquals(esperado.toString(), Demultiplexor.demultiplexar(stream.toByteArray()).stdout());
    }

    /** A9: un frame que declara 4 GiB debe abortar sin reservar memoria. */
    @Test
    void a9_frameDe4GiBAborta() {
        byte[] encabezado = new byte[] {1, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        ErrorDaemon e = assertThrows(ErrorDaemon.class, () -> Demultiplexor.demultiplexar(encabezado));
        assertTrue(e.getMessage().contains("4294967295"), "debe reportar el largo declarado: " + e.getMessage());
    }

    /** A10: un frame de largo 0 en el medio se procesa sin trabarse. */
    @Test
    void a10_frameDeLargoCero() {
        var s = Demultiplexor.demultiplexar(concatenar(
                frame(1, utf8("a")),
                frame(1, new byte[0]),
                frame(1, utf8("b"))));
        assertEquals("ab", s.stdout());
    }

    /** A11: tipo 7 es error fatal, no se asume stdout. */
    @Test
    void a11_tipoDesconocidoEsFatal() {
        byte[] datos = concatenar(frame(1, utf8("a")), frame(7, utf8("veneno")));
        assertThrows(ErrorDaemon.class, () -> Demultiplexor.demultiplexar(datos));
    }

    /** A12: una linea que cruza dos frames no debe tener cortes espurios. */
    @Test
    void a12_lineaQueCruzaDosFrames() {
        var s = Demultiplexor.demultiplexar(concatenar(
                frame(1, utf8("una linea par")),
                frame(1, utf8("tida al medio\n"))));
        assertEquals("una linea partida al medio\n", s.stdout());
    }

    /** R6.7: se conserva el principio y se marca truncada. */
    @Test
    void truncaConservandoElPrincipio() {
        byte[] relleno = new byte[Constantes.MAX_FRAME_BYTES];
        java.util.Arrays.fill(relleno, (byte) 'x');
        var s = Demultiplexor.demultiplexar(concatenar(
                frame(1, utf8("PRINCIPIO")),
                frame(1, relleno),
                frame(1, utf8("FINAL"))));
        assertTrue(s.truncada());
        assertEquals(Constantes.MAX_SALIDA_BYTES, s.stdout().length());
        assertTrue(s.stdout().startsWith("PRINCIPIO"));
        assertFalse(s.stdout().contains("FINAL"));
    }

    /** Un encabezado incompleto al final es corrupcion, no fin de stream. */
    @Test
    void encabezadoIncompletoEsError() {
        assertThrows(ErrorDaemon.class, () -> Demultiplexor.demultiplexar(new byte[] {1, 0, 0, 0, 0}));
    }

    /** El payload declarado debe estar completo. */
    @Test
    void payloadIncompletoEsError() {
        byte[] f = frame(1, utf8("hola"));
        byte[] cortado = java.util.Arrays.copyOf(f, f.length - 1);
        assertThrows(ErrorDaemon.class, () -> Demultiplexor.demultiplexar(cortado));
    }

    @Test
    void streamVacio() {
        var s = Demultiplexor.demultiplexar(new byte[0]);
        assertEquals("", s.stdout());
        assertEquals("", s.stderr());
        assertFalse(s.truncada());
    }
}
