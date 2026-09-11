package sandbox.ejecutor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * El stdin del contenedor: TRES documentos pegados, sin separadores.
 *
 * <pre>
 *   &lt;nonce&gt;\n              32 hex
 *   &lt;n&gt;\n                  cuantos BYTES mide el guion de la capa 2
 *   &lt;guion de n bytes&gt;     el run.sh del perfil, opaco
 *   &lt;tar&gt;                  el bundle: "lo que quede del stream"
 * </pre>
 *
 * <b>Por que el largo va adelante y no hay marca de fin.</b> El guion lo escribe G5: es texto
 * arbitrario y puede contener cualquier linea, incluida la que eligieramos como separador. Y usar
 * el nonce como delimitador esta prohibido: se lo mostrariamos a la capa 2, que es justamente lo
 * que el nonce existe para evitar. El largo no tiene alfabeto: son n bytes opacos. Es
 * Content-Length.
 *
 * <b>El largo se cuenta en BYTES, no en caracteres</b>: un acento en un comentario del guion mueve
 * el numero. Por eso {@code getBytes(UTF_8).length} y no {@code length()}.
 *
 * R7.3: ni el nonce ni el guion tocan disco ni variable de entorno. /proc/1/environ los devolveria
 * aunque la capa 1 hiciera unset.
 *
 * <b>Por que es un InputStream y no una escritura nuestra.</b> Con docker-java el bucle de
 * escritura de stdin lo maneja la libreria (HijackingHttpRequestExecutor tira de este stream hasta
 * el EOF). Nosotros ya no controlamos el ritmo; lo unico que controlamos es que bytes salen y
 * cuando termino de salir el ultimo. {@link #esperarEntrega} es lo que le devuelve al llamador ese
 * control, y es lo que hace posible R8.5.
 */
final class Entrada extends InputStream {

    private final byte[] datos;
    private final CountDownLatch entregado = new CountDownLatch(1);
    private volatile int servidos;
    private int posicion;

    private Entrada(byte[] datos) {
        this.datos = datos;
    }

    static Entrada armar(String nonce, byte[] guionCapa2, byte[] tar) {
        if (guionCapa2.length > Constantes.MAX_SCRIPT_BYTES) {
            throw new IllegalArgumentException("el guion de la capa 2 supera MAX_SCRIPT_BYTES");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                34 + 12 + guionCapa2.length + tar.length);
        buffer.writeBytes((nonce + "\n").getBytes(StandardCharsets.US_ASCII));
        buffer.writeBytes((guionCapa2.length + "\n").getBytes(StandardCharsets.US_ASCII));
        buffer.writeBytes(guionCapa2);
        buffer.writeBytes(tar);
        return new Entrada(buffer.toByteArray());
    }

    /** Cuantos bytes tiene que consumir el contenedor para que la entrada este completa. */
    int total() { return datos.length; }

    /** Cuantos consumio efectivamente. Es el dato de R8.4: un TIMEOUT se explica distinto si falto stdin. */
    int servidos() { return servidos; }

    /**
     * Bloquea hasta que el ultimo byte salio al cable, o hasta que se vence el tope.
     *
     * R8.5: si el contenedor no consume stdin, el buffer del socket se llena a las pocas decenas de
     * KiB y la escritura de la libreria queda bloqueada para siempre. Ese hilo no se puede
     * interrumpir desde aca -esta trabado en un write, no en un read-, asi que el tope no vive en
     * este stream: vive en el llamador, que al vencerse cierra el canal adjunto entero y la
     * escritura revienta. Sin eso, el cupo de concurrencia no se libera nunca.
     *
     * @return true si se entrego todo.
     */
    boolean esperarEntrega(long topeMs) {
        try {
            return entregado.await(Math.max(topeMs, 0), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public int read() {
        if (posicion >= datos.length) {
            entregado.countDown();
            return -1;
        }
        servidos = ++posicion;
        return datos[posicion - 1] & 0xFF;
    }

    @Override
    public int read(byte[] destino, int desde, int largo) {
        if (posicion >= datos.length) {
            // El llamador ya escribio y flusheo todo lo anterior antes de volver a leer: cuando
            // pide y le decimos EOF, los bytes ya estan en el socket.
            entregado.countDown();
            return -1;
        }
        int n = Math.min(largo, datos.length - posicion);
        System.arraycopy(datos, posicion, destino, desde, n);
        posicion += n;
        servidos = posicion;
        return n;
    }

    @Override
    public int available() {
        return datos.length - posicion;
    }
}
