package sandbox.ejecutor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Demultiplexado de la salida enmarcada de Docker (seccion 6). */
final class Demultiplexor {

    record Salida(String stdout, String stderr, boolean truncada) {}

    private Demultiplexor() {}

    /**
     * Separa los dos streams de un cuerpo enmarcado.
     *
     * @throws ErrorDaemon si el stream esta corrupto o declara un frame fuera de rango.
     */
    static Salida demultiplexar(byte[] datos) {
        Acumulador out = new Acumulador();
        Acumulador err = new Acumulador();

        int i = 0;
        while (i < datos.length) {
            // R6.5: un encabezado incompleto al final del stream es corrupcion, no fin normal.
            if (datos.length - i < 8) throw new ErrorDaemon("frame truncado en el encabezado");

            int tipo = datos[i] & 0xFF;
            // R6.4: adivinar stdout es como se corrompe un resultado en silencio.
            if (tipo != 1 && tipo != 2) throw new ErrorDaemon("tipo de stream invalido: " + tipo);

            // R6.2: el largo es un uint32; se valida ANTES de reservar nada.
            long largo = ((long) (datos[i + 4] & 0xFF) << 24)
                       | ((long) (datos[i + 5] & 0xFF) << 16)
                       | ((long) (datos[i + 6] & 0xFF) << 8)
                       |  (long) (datos[i + 7] & 0xFF);
            if (largo > Constantes.MAX_FRAME_BYTES) throw new ErrorDaemon("frame de " + largo + " bytes");

            i += 8;
            if (datos.length - i < largo) throw new ErrorDaemon("frame truncado en el payload");

            // R6.3: largo 0 es valido; el bucle avanza igual porque i ya sumo el encabezado.
            (tipo == 1 ? out : err).escribir(datos, i, (int) largo);
            i += (int) largo;
        }

        // R6.6: recien aca, con el stream entero concatenado, se decodifica. Nunca por linea ni por frame.
        return new Salida(out.texto(), err.texto(), out.truncada || err.truncada);
    }

    /** R6.7: conserva el principio y descarta el resto. El reporte y los errores de compilacion estan al principio. */
    private static final class Acumulador {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncada = false;

        void escribir(byte[] datos, int desde, int largo) {
            int espacio = Constantes.MAX_SALIDA_BYTES - buffer.size();
            if (largo > espacio) {
                truncada = true;
                largo = Math.max(espacio, 0);
            }
            if (largo > 0) buffer.write(datos, desde, largo);
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
