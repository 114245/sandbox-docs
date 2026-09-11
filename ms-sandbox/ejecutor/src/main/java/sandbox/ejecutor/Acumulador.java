package sandbox.ejecutor;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Junta la salida enmarcada del contenedor (seccion 6).
 *
 * Reemplaza al Demultiplexor: el desenmarcado ahora lo hace docker-java, que entrega
 * {@link Frame} ya tipados. Lo que NO hace docker-java, y sigue siendo nuestro, es el tope de
 * memoria (R6.7), la decodificacion del stream entero de una sola vez (R6.6) y la guarda contra un
 * stream que no viene enmarcado (R6.1/R6.4).
 */
final class Acumulador {

    record Salida(String stdout, String stderr, boolean truncada) {}

    private final Buffer out = new Buffer();
    private final Buffer err = new Buffer();

    /**
     * R6.1 y R6.4: docker-java decide el tipo por el primer byte del frame y devuelve RAW cuando no
     * es 0, 1 ni 2. Eso pasa exactamente cuando el contenedor tenia TTY y la salida no viene
     * enmarcada. Adivinar stdout ahi es como se corrompe un resultado en silencio, asi que es un
     * error del daemon, no un caso a tolerar. STDIN tampoco tiene sentido en una respuesta.
     *
     * El payload se copia acto seguido a proposito: docker-java reusa el mismo array entre frames
     * cuando el frame llena el buffer, asi que guardarse la referencia corromperia la salida.
     */
    void agregar(Frame frame) {
        StreamType tipo = frame.getStreamType();
        if (tipo != StreamType.STDOUT && tipo != StreamType.STDERR) {
            throw new ErrorDaemon("tipo de stream invalido: " + tipo);
        }
        byte[] payload = frame.getPayload();
        (tipo == StreamType.STDOUT ? out : err).escribir(payload, payload.length);
    }

    Salida salida() {
        // R6.6: recien aca, con el stream entero concatenado, se decodifica. Nunca por linea ni por frame.
        return new Salida(out.texto(), err.texto(), out.truncada || err.truncada);
    }

    /** R6.7: conserva el principio y descarta el resto. El reporte y los errores estan al principio. */
    private static final class Buffer {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncada = false;

        void escribir(byte[] datos, int largo) {
            int espacio = Constantes.MAX_SALIDA_BYTES - buffer.size();
            if (largo > espacio) {
                truncada = true;
                largo = Math.max(espacio, 0);
            }
            if (largo > 0) buffer.write(datos, 0, largo);
        }

        String texto() {
            // El corte por bytes puede partir un caracter UTF-8: se reemplaza en vez de fallar.
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try {
                return d.decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalStateException(e); // inalcanzable con REPLACE
            }
        }
    }
}
