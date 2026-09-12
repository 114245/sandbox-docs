package sandbox.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** POST /ejecutar por socket Unix. 08 seccion 2 y 3.1. */
final class ClienteEjecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESPUESTA_BYTES = 16_777_216;   // 16 MiB

    private final Path rutaSocket;
    private final long topeMs;

    ClienteEjecutor(Path rutaSocket) {
        this(rutaSocket, Constantes.TIMEOUT_CLIENTE_MS);
    }

    /** El tope explicito existe para los tests: produccion siempre usa TIMEOUT_CLIENTE_MS. */
    ClienteEjecutor(Path rutaSocket, long topeMs) {
        this.rutaSocket = rutaSocket;
        this.topeMs = topeMs;
    }

    Sobre ejecutar(String ejecucionId, String perfil, byte[] tar) {
        try (SocketChannel canal = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            canal.connect(UnixDomainSocketAddress.of(rutaSocket.toString()));
            escribirPedido(Channels.newOutputStream(canal), ejecucionId, perfil, tar);
            return leerConTope(canal);
        } catch (EjecutorSaturado | ErrorDeProgramacion | ErrorDeEjecutor e) {
            throw e;
        } catch (Exception e) {
            throw new ErrorDeEjecutor("no se pudo hablar con el ejecutor en " + rutaSocket, e);
        }
    }

    private void escribirPedido(OutputStream salida, String ejecucionId, String perfil, byte[] tar)
            throws Exception {
        String cabeza = "POST /ejecutar HTTP/1.1\r\n"
                      + "Host: localhost\r\n"                       // R5.5
                      + "Content-Type: application/octet-stream\r\n"
                      + "X-Ejecucion-Id: " + ejecucionId + "\r\n"
                      + "X-Perfil: " + perfil + "\r\n"
                      + "Content-Length: " + tar.length + "\r\n"    // sin chunked: 08 seccion 3.1
                      + "\r\n";
        salida.write(cabeza.getBytes(StandardCharsets.ISO_8859_1));
        salida.write(tar);
        salida.flush();
    }

    /**
     * Un SocketChannel bloqueante NO tiene SO_TIMEOUT y Channels.newInputStream se cuelga para
     * siempre. La lectura va en un hilo virtual y, si vence el tope, se cierra el canal desde
     * aca: el lector despierta con AsynchronousCloseException.
     *
     * El executor se apaga con shutdownNow() en el finally y NO con try-with-resources: close()
     * de un executor de tareas virtuales ESPERA a que terminen, y la tarea que queremos cortar
     * es justamente la que esta colgada.
     */
    private Sobre leerConTope(SocketChannel canal) {
        InputStream entrada = Channels.newInputStream(canal);
        ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Sobre> tarea = hilos.submit(() -> leerRespuesta(entrada));
            try {
                return tarea.get(topeMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                cerrarCallado(canal);
                throw new ErrorDeEjecutor("el ejecutor no respondio en " + topeMs + " ms", e);
            } catch (InterruptedException e) {
                // Tragarse la interrupcion deja al hilo sin la senal de apagado. Va a importar
                // cuando el worker corra bajo un consumidor de cola con apagado ordenado.
                Thread.currentThread().interrupt();
                throw new ErrorDeEjecutor("la espera de la respuesta fue interrumpida", e);
            } catch (Exception e) {
                Throwable causa = e.getCause() != null ? e.getCause() : e;
                if (causa instanceof EjecutorSaturado s) throw s;
                if (causa instanceof ErrorDeProgramacion p) throw p;
                if (causa instanceof ErrorDeEjecutor d) throw d;
                throw new ErrorDeEjecutor("fallo la lectura de la respuesta", causa);
            }
        } finally {
            hilos.shutdownNow();
        }
    }

    private Sobre leerRespuesta(InputStream entrada) throws Exception {
        Http.Cabeza cabeza = Http.leerCabeza(entrada);
        int codigo = Http.codigo(cabeza.linea());
        byte[] cuerpo = Http.leerCuerpo(entrada, cabeza, MAX_RESPUESTA_BYTES);

        switch (codigo) {
            case 200 -> { /* sigue abajo */ }
            case 503 -> throw new EjecutorSaturado(segundosDe(cabeza.header("retry-after")));
            case 400, 411, 413 -> throw new ErrorDeProgramacion(
                    "el ejecutor rechazo el pedido con " + codigo + ": es un bug del worker");
            // 422 es un perfil que no esta en el catalogo: despliegue desalineado, no bug del
            // pedido. 502 es ERROR_DAEMON. Los dos son ERROR_INTERNO.
            default -> throw new ErrorDeEjecutor("el ejecutor respondio " + codigo);
        }

        Sobre sobre;
        try {
            sobre = MAPPER.readValue(cuerpo, Sobre.class);
        } catch (Exception e) {
            throw new ErrorDeEjecutor("la respuesta del ejecutor no se pudo parsear", e);
        }
        // Un cuerpo 200 cuyo contenido sea el literal `null` parsea sin excepcion y devuelve
        // null. Devolverlo seria propagar un NPE hasta el veredicto.
        if (sobre == null) throw new ErrorDeEjecutor("el ejecutor respondio 200 con un sobre nulo");
        return sobre;
    }

    private static long segundosDe(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) return 1;
        try {
            return Math.max(1, Long.parseLong(retryAfter.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void cerrarCallado(SocketChannel canal) {
        try {
            canal.close();
        } catch (Exception e) {
            // Cerrar para despertar al lector; que falle el cierre no cambia el resultado.
        }
    }
}
