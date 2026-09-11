package sandbox.ejecutor;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seccion 6, lo que quedo de nuestro lado despues de docker-java.
 *
 * El desenmarcado (leer el encabezado de 8 bytes, el uint32 del largo, reensamblar frames partidos
 * entre chunks) ahora lo hace la libreria, y sus tests se fueron con el Demultiplexor. Lo que sigue
 * siendo nuestro y se prueba aca: el tope de memoria, la decodificacion del stream entero y la
 * guarda contra una salida sin enmarcar.
 */
class AcumuladorTest {

    private static Frame frame(StreamType tipo, String texto) {
        return new Frame(tipo, texto.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void separaLosDosStreams() {
        Acumulador a = new Acumulador();
        a.agregar(frame(StreamType.STDOUT, "compilando\n"));
        a.agregar(frame(StreamType.STDERR, "aviso\n"));
        a.agregar(frame(StreamType.STDOUT, "listo\n"));

        var s = a.salida();
        assertEquals("compilando\nlisto\n", s.stdout());
        assertEquals("aviso\n", s.stderr());
        assertFalse(s.truncada());
    }

    /**
     * R6.1 y R6.4: docker-java devuelve RAW cuando el primer byte del frame no es 0, 1 ni 2, que es
     * exactamente lo que pasa si el contenedor tenia TTY y la salida no viene enmarcada. Adivinar
     * stdout ahi es como se corrompe un resultado en silencio.
     */
    @Test
    void unFrameSinEnmarcarEsErrorDelDaemon() {
        Acumulador a = new Acumulador();
        assertThrows(ErrorDaemon.class, () -> a.agregar(frame(StreamType.RAW, "salida cruda")));
        assertThrows(ErrorDaemon.class, () -> a.agregar(frame(StreamType.STDIN, "no tiene sentido")));
    }

    /** R6.6: la decodificacion es sobre el stream entero, no por frame. */
    @Test
    void unCaracterUtf8PartidoEntreDosFramesSeReconstruye() {
        byte[] enie = "ñ".getBytes(StandardCharsets.UTF_8);   // dos bytes
        Acumulador a = new Acumulador();
        a.agregar(new Frame(StreamType.STDOUT, new byte[] {enie[0]}));
        a.agregar(new Frame(StreamType.STDOUT, new byte[] {enie[1]}));
        assertEquals("ñ", a.salida().stdout());
    }

    /** R6.7: pasado el tope se conserva el principio y se marca truncada. */
    @Test
    void elTopeConservaElPrincipioYMarcaTruncada() {
        Acumulador a = new Acumulador();
        a.agregar(frame(StreamType.STDOUT, "PRINCIPIO"));
        a.agregar(frame(StreamType.STDOUT, "x".repeat(Constantes.MAX_SALIDA_BYTES)));

        var s = a.salida();
        assertTrue(s.truncada());
        assertEquals(Constantes.MAX_SALIDA_BYTES, s.stdout().length());
        assertTrue(s.stdout().startsWith("PRINCIPIO"),
                "R6.7 conserva el principio: ahi estan el reporte y los errores de compilacion");
    }

    /** El truncado de un stream no contamina al otro. */
    @Test
    void elTopeEsPorStream() {
        Acumulador a = new Acumulador();
        a.agregar(frame(StreamType.STDOUT, "x".repeat(Constantes.MAX_SALIDA_BYTES + 10)));
        a.agregar(frame(StreamType.STDERR, "corto\n"));

        var s = a.salida();
        assertTrue(s.truncada());
        assertEquals("corto\n", s.stderr());
    }

    /** Un frame de largo cero es valido y no rompe nada. */
    @Test
    void frameVacio() {
        Acumulador a = new Acumulador();
        a.agregar(new Frame(StreamType.STDOUT, new byte[0]));
        var s = a.salida();
        assertEquals("", s.stdout());
        assertFalse(s.truncada());
    }

    /** Sin frames, la salida es vacia y no nula. */
    @Test
    void sinFrames() {
        var s = new Acumulador().salida();
        assertEquals("", s.stdout());
        assertEquals("", s.stderr());
        assertFalse(s.truncada());
    }
}
