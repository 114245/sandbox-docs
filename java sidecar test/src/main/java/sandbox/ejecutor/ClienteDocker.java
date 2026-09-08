package sandbox.ejecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cliente de la API de Docker sobre el socket Unix del daemon.
 *
 * R11.4: no se usa docker-java ni equivalente. Esas librerias ponen, en el mismo proceso que
 * tiene el socket, un objeto con un setter equivalente a withPrivileged(true).
 *
 * Timeouts: SocketChannel no tiene SO_TIMEOUT, asi que cada llamada agenda un watchdog que cierra
 * el canal al vencer; el read bloqueado revienta con AsynchronousCloseException (R8.3).
 */
final class ClienteDocker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIJO = "/" + Constantes.VERSION_API_DOCKER;

    private final UnixDomainSocketAddress socketDaemon;
    private final ScheduledExecutorService reloj;

    ClienteDocker(String rutaSocketDaemon, ScheduledExecutorService reloj) {
        this.socketDaemon = UnixDomainSocketAddress.of(rutaSocketDaemon);
        this.reloj = reloj;
    }

    record Respuesta(int codigo, String contentType, byte[] cuerpo) {}

    // ---------------------------------------------------------------- operaciones de la seccion 5

    /** Paso 1. Devuelve el id del contenedor creado. */
    String crear(String ejecucionId) {
        Respuesta r = pedir("POST", PREFIJO + "/containers/create?name=" + Spec.nombre(ejecucionId),
                Spec.crear(ejecucionId), Constantes.TIMEOUT_DAEMON_MS);
        if (r.codigo() != 201) throw new ErrorDaemon("create respondio " + r.codigo());
        JsonNode id = json(r).get("Id");
        if (id == null || id.asText().isEmpty()) throw new ErrorDaemon("create no devolvio Id");
        return id.asText();
    }

    /**
     * Paso 2, antes de start (R5.1). Devuelve el socket crudo tras el 101 (R5.8).
     * El canal es de una sola direccion: se escribe y se cierra (R5.2). Como nunca leemos de el,
     * los bytes de lectura anticipada de R5.9 no existen para nosotros: no hay stream que preservar.
     */
    SocketChannel adjuntarStdin(String contenedorId) {
        SocketChannel canal = null;
        try {
            canal = SocketChannel.open(StandardProtocolFamily.UNIX);
            canal.connect(socketDaemon);
            ScheduledFuture<?> watchdog = agendarCierre(canal, Constantes.TIMEOUT_DAEMON_MS);
            try {
                OutputStream out = Channels.newOutputStream(canal);
                out.write(("POST " + PREFIJO + "/containers/" + contenedorId + "/attach?stream=1&stdin=1 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Upgrade: tcp\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();

                // Buffer de 1 byte: nada de lectura anticipada, para no comernos bytes del stream crudo.
                InputStream in = new BufferedInputStream(Channels.newInputStream(canal), 1);
                int codigo = Http.codigo(Http.leerCabeza(in).linea());
                if (codigo != 101) throw new ErrorDaemon("attach respondio " + codigo);
            } finally {
                watchdog.cancel(false);
            }
            return canal;
        } catch (ErrorDaemon e) {
            cerrar(canal);
            throw e;
        } catch (IOException e) {
            cerrar(canal);
            throw new ErrorDaemon("attach fallo", e);
        }
    }

    /** Paso 3. */
    void iniciar(String contenedorId) {
        Respuesta r = pedir("POST", PREFIJO + "/containers/" + contenedorId + "/start", null,
                Constantes.TIMEOUT_DAEMON_MS);
        if (r.codigo() != 204 && r.codigo() != 304) throw new ErrorDaemon("start respondio " + r.codigo());
    }

    /** Paso 5. Devuelve el codigo de salida. Es la unica llamada que no usa TIMEOUT_DAEMON_MS. */
    int esperar(String contenedorId, long timeoutMs) {
        Respuesta r = pedir("POST", PREFIJO + "/containers/" + contenedorId + "/wait?condition=not-running",
                null, timeoutMs);
        if (r.codigo() != 200) throw new ErrorDaemon("wait respondio " + r.codigo());
        JsonNode n = json(r).get("StatusCode");
        if (n == null) throw new ErrorDaemon("wait no devolvio StatusCode");
        return n.asInt();
    }

    void matar(String contenedorId) {
        Respuesta r = pedir("POST", PREFIJO + "/containers/" + contenedorId + "/kill", null,
                Constantes.TIMEOUT_DAEMON_MS);
        // 409 = ya no estaba corriendo; es exactamente el estado que buscabamos.
        if (r.codigo() != 204 && r.codigo() != 409) throw new ErrorDaemon("kill respondio " + r.codigo());
    }

    /**
     * Paso 5b. Del inspect se lee UNICAMENTE State.OOMKilled; el resto se ignora.
     *
     * R5.10: si la llamada falla no se aborta la ejecucion. El resultado ya esta, y perderlo por un
     * dato de diagnostico seria peor que devolverlo con oomKilled en false.
     *
     * R3.4: este dato no se puede inferir del exitCode. Con la JVM bien configurada, el que se queda
     * sin memoria es la JVM y no el cgroup, asi que el out-of-memory llega como exitCode 3 con
     * OOMKilled false, no como el 137 que uno esperaria. Los dos caminos existen.
     */
    boolean oomKilled(String contenedorId) {
        try {
            Respuesta r = pedir("GET", PREFIJO + "/containers/" + contenedorId + "/json", null,
                    Constantes.TIMEOUT_DAEMON_MS);
            if (r.codigo() != 200) throw new ErrorDaemon("inspect respondio " + r.codigo());
            return json(r).path("State").path("OOMKilled").asBoolean(false);
        } catch (RuntimeException e) {
            Log.info("inspect fallo (%s); se devuelve oomKilled=false", e.getMessage());
            return false;
        }
    }

    /** Paso 6. Antes del delete (R5.4): borrado el contenedor, los logs no existen mas. */
    byte[] logs(String contenedorId) {
        Respuesta r = pedir("GET", PREFIJO + "/containers/" + contenedorId + "/logs?stdout=1&stderr=1", null,
                Constantes.TIMEOUT_DAEMON_MS);
        if (r.codigo() != 200) throw new ErrorDaemon("logs respondio " + r.codigo());
        // R6.1: raw-stream significa que el contenedor tiene TTY y la salida no viene enmarcada.
        // Es un assert de dos lineas que convierte una corrupcion silenciosa en un error inmediato.
        String tipo = r.contentType() == null ? "" : r.contentType();
        if (!tipo.startsWith("application/vnd.docker.multiplexed-stream")) {
            throw new ErrorDaemon("logs devolvio un content-type inesperado");
        }
        return r.cuerpo();
    }

    /** Paso 7. No lanza: el fallo se registra y se confia en el barrido (R5.3). */
    boolean borrar(String contenedorId) {
        try {
            Respuesta r = pedir("DELETE", PREFIJO + "/containers/" + contenedorId + "?force=1&v=1", null,
                    Constantes.TIMEOUT_DAEMON_MS);
            return r.codigo() == 204 || r.codigo() == 404;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- salud y barrido

    boolean ping() {
        try {
            return pedir("GET", "/_ping", null, Constantes.TIMEOUT_DAEMON_MS).codigo() == 200;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** R10.1: lista los contenedores etiquetados por nosotros. */
    JsonNode listarSandbox() {
        String filtro = java.net.URLEncoder.encode("{\"label\":[\"sandbox=1\"]}", StandardCharsets.UTF_8);
        Respuesta r = pedir("GET", PREFIJO + "/containers/json?all=1&filters=" + filtro, null,
                Constantes.TIMEOUT_DAEMON_MS);
        if (r.codigo() != 200) throw new ErrorDaemon("list respondio " + r.codigo());
        return json(r);
    }

    // ---------------------------------------------------------------- transporte

    private Respuesta pedir(String metodo, String ruta, byte[] cuerpo, long timeoutMs) {
        try (SocketChannel canal = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            canal.connect(socketDaemon);
            ScheduledFuture<?> watchdog = agendarCierre(canal, timeoutMs);
            try {
                StringBuilder pedido = new StringBuilder()
                        .append(metodo).append(' ').append(ruta).append(" HTTP/1.1\r\n")
                        .append("Host: localhost\r\n")          // R5.5
                        .append("Connection: close\r\n");
                if (cuerpo != null) {
                    pedido.append("Content-Type: application/json\r\n")
                          .append("Content-Length: ").append(cuerpo.length).append("\r\n");
                } else {
                    pedido.append("Content-Length: 0\r\n");
                }
                pedido.append("\r\n");

                OutputStream out = Channels.newOutputStream(canal);
                out.write(pedido.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (cuerpo != null) out.write(cuerpo);
                out.flush();

                InputStream in = new BufferedInputStream(Channels.newInputStream(canal), 8192);
                Http.Cabeza cabeza = Http.leerCabeza(in);
                int codigo = Http.codigo(cabeza.linea());
                // R5.6: no seguimos redirecciones. Un 3xx del daemon es una anomalia, no una instruccion.
                if (codigo >= 300 && codigo < 400) throw new ErrorDaemon("el daemon respondio " + codigo);
                byte[] datos = Http.leerCuerpo(in, cabeza, Constantes.MAX_CUERPO_DAEMON_BYTES);
                return new Respuesta(codigo, cabeza.header("content-type"), datos);
            } finally {
                watchdog.cancel(false);
            }
        } catch (IOException e) {
            // R3.2: hacia afuera nunca viaja la ruta del socket ni el cuerpo de la respuesta de Docker.
            throw new ErrorDaemon("fallo la llamada al daemon", e);
        }
    }

    private ScheduledFuture<?> agendarCierre(SocketChannel canal, long timeoutMs) {
        return reloj.schedule(() -> cerrar(canal), timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static void cerrar(SocketChannel canal) {
        try {
            if (canal != null) canal.close();
        } catch (IOException ignorado) {
            // cerrar es lo ultimo que hacemos con el canal; que falle no cambia nada
        }
    }

    private static JsonNode json(Respuesta r) {
        try {
            return MAPPER.readTree(r.cuerpo());
        } catch (IOException e) {
            throw new ErrorDaemon("respuesta del daemon ilegible");
        }
    }
}
