package sandbox.ejecutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Servidor HTTP/1.1 sobre socket Unix (R2.1). Unica superficie de red del componente.
 *
 * Se escribe a mano porque com.sun.net.httpserver solo bindea InetSocketAddress. El efecto lateral
 * util es que, al parsear los headers nosotros, el 413 se responde sin leer un solo byte del cuerpo.
 */
final class Servidor implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * UUID canonico, estricto. No es cosmetica: el id se concatena en la URL de create como nombre
     * del contenedor, asi que cualquier laxitud aca es una inyeccion en la peticion al daemon.
     */
    private static final Pattern UUID_CANONICO =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Formato del header X-Perfil: la misma clave con la que se busca en el catalogo. */
    private static final Pattern CLAVE_PERFIL = Pattern.compile("^[a-z0-9-]+@[0-9]+$");

    /** Admitidos = en vuelo + esperando turno. Por encima del tope, 503 inmediato (R9.2). */
    private static final int MAX_ADMITIDOS = Constantes.MAX_CONCURRENTES + Constantes.MAX_COLA;

    private final Path rutaSocket;
    private final ServerSocketChannel escucha;
    private final Motor motor;
    private final Catalogo catalogo;
    private final ExecutorService hilos;

    private final Semaphore turnos = new Semaphore(Constantes.MAX_CONCURRENTES, true);
    private final AtomicInteger admitidos = new AtomicInteger();
    private volatile boolean aceptando = true;
    private final java.util.concurrent.atomic.AtomicBoolean cerrando = new java.util.concurrent.atomic.AtomicBoolean();

    Servidor(String rutaSocket, Motor motor, Catalogo catalogo, ExecutorService hilos) throws IOException {
        this.rutaSocket = Path.of(rutaSocket);
        this.motor = motor;
        this.catalogo = catalogo;
        this.hilos = hilos;

        Files.deleteIfExists(this.rutaSocket);
        if (this.rutaSocket.getParent() != null) Files.createDirectories(this.rutaSocket.getParent());
        this.escucha = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.escucha.bind(UnixDomainSocketAddress.of(this.rutaSocket.toString()));
        permisos0660(this.rutaSocket);
    }

    /** R2.1: 0660. En Windows el sistema de archivos no tiene permisos POSIX; ahi solo se usa para tests. */
    private static void permisos0660(Path ruta) {
        try {
            Files.setPosixFilePermissions(ruta, PosixFilePermissions.fromString("rw-rw----"));
        } catch (IOException | UnsupportedOperationException e) {
            Log.info("no se pudieron fijar permisos 0660 sobre el socket");
        }
    }

    void atender() {
        Log.info("escuchando en %s", rutaSocket);
        while (aceptando) {
            SocketChannel conexion;
            try {
                conexion = escucha.accept();
            } catch (IOException e) {
                if (aceptando) Log.info("accept fallo: %s", e.getMessage());
                return;
            }
            hilos.submit(() -> {
                try (conexion) {
                    responder(conexion);
                } catch (IOException e) {
                    Log.info("conexion cortada: %s", e.getMessage());
                }
            });
        }
    }

    // ---------------------------------------------------------------- ruteo

    private void responder(SocketChannel conexion) throws IOException {
        InputStream in = new BufferedInputStream(Channels.newInputStream(conexion), 8192);
        OutputStream out = Channels.newOutputStream(conexion);

        Http.Cabeza cabeza;
        try {
            cabeza = Http.leerCabeza(in);
        } catch (IOException e) {
            enviar(out, 400, "{\"error\":\"peticion invalida\"}");
            return;
        }
        String[] partes = cabeza.linea().split(" ");
        String metodo = partes.length > 0 ? partes[0] : "";
        String ruta = partes.length > 1 ? partes[1] : "";

        if ("POST".equals(metodo) && "/ejecutar".equals(ruta)) {
            ejecutar(in, out, cabeza);
        } else if ("GET".equals(metodo) && "/salud".equals(ruta)) {
            salud(out);
        } else {
            enviar(out, 404, "{\"error\":\"no existe\"}");
        }
    }

    private void ejecutar(InputStream in, OutputStream out, Http.Cabeza cabeza) throws IOException {
        String id = cabeza.header("x-ejecucion-id");
        if (id == null || !UUID_CANONICO.matcher(id).matches()) {
            enviar(out, 400, "{\"error\":\"X-Ejecucion-Id ausente o invalido\"}");
            return;
        }
        // Paso 2 del handoff (catalogo de perfiles): X-Perfil elige el perfil. Formato invalido o
        // ausente es 400; formato valido pero no presente en el catalogo es 422.
        String perfilClave = cabeza.header("x-perfil");
        if (perfilClave == null || !CLAVE_PERFIL.matcher(perfilClave).matches()) {
            enviar(out, 400, mensaje(id, "X-Perfil ausente o invalido"));
            return;
        }
        if (catalogo.buscar(perfilClave) == null) {
            enviar(out, 422, mensaje(id, "perfil desconocido: " + perfilClave));
            return;
        }
        // R3: no se acepta chunked en la entrada; necesitamos el tamano antes de aceptar bytes.
        String cl = cabeza.header("content-length");
        if (cl == null) {
            enviar(out, 411, mensaje(id, "falta Content-Length"));
            return;
        }
        long largo;
        try {
            largo = Long.parseLong(cl.trim());
        } catch (NumberFormatException e) {
            enviar(out, 400, mensaje(id, "Content-Length invalido"));
            return;
        }
        // A27: se responde y se cierra sin leer un byte del cuerpo.
        if (largo < 0 || largo > Constantes.MAX_BUNDLE_BYTES) {
            enviar(out, 413, mensaje(id, "bundle demasiado grande"));
            return;
        }

        if (!aceptando || !admitir()) {
            // R9.2 y R3.3: saturacion es un resultado, no una falla del servicio.
            enviar(out, 503, json(Ejecucion.Salida.rechazada(id)), "Retry-After: 5");
            return;
        }
        try {
            turnos.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admitidos.decrementAndGet();
            enviar(out, 503, json(Ejecucion.Salida.rechazada(id)), "Retry-After: 5");
            return;
        }
        try {
            byte[] tar = Http.leerExacto(in, (int) largo);
            Ejecucion.Salida salida = motor.ejecutar(id, tar, perfilClave);
            int codigo = salida.resultado() == Ejecucion.Estado.ERROR_DAEMON ? 502 : 200;
            enviar(out, codigo, json(salida));
        } finally {
            turnos.release();
            admitidos.decrementAndGet();
        }
    }

    /** Reserva un cupo si queda lugar. Sin bloquear: la decision de rechazar tiene que ser inmediata. */
    private boolean admitir() {
        while (true) {
            int actual = admitidos.get();
            if (actual >= MAX_ADMITIDOS) return false;
            if (admitidos.compareAndSet(actual, actual + 1)) return true;
        }
    }

    private void salud(OutputStream out) throws IOException {
        int enVuelo = Constantes.MAX_CONCURRENTES - turnos.availablePermits();
        int enCola = Math.max(admitidos.get() - enVuelo, 0);
        boolean vivo = motor.daemonVivo();
        ObjectNode n = MAPPER.createObjectNode();
        n.put("daemon", vivo ? "ok" : "caido");
        n.put("enVuelo", enVuelo);
        n.put("enCola", enCola);
        // R3.3: con la cola llena esto sigue devolviendo 200. Si devolviera 503 el orquestador
        // reiniciaria el ejecutor justo cuando mas se lo necesita.
        enviar(out, vivo ? 200 : 503, escribir(n));
    }

    // ---------------------------------------------------------------- apagado (R11.7)

    @Override
    public void close() {
        if (!cerrando.compareAndSet(false, true)) return;   // el apagado corre una sola vez
        aceptando = false;
        try {
            escucha.close();
        } catch (IOException ignorado) {
            // ya no vamos a aceptar nada mas por este canal
        }
        try {
            // Tomar todos los permisos es esperar a que no quede ninguna ejecucion en vuelo;
            // se devuelven enseguida porque el semaforo no es lo que impide aceptar: eso ya lo
            // hace el canal cerrado.
            boolean vacio = turnos.tryAcquire(Constantes.MAX_CONCURRENTES,
                    Constantes.TIMEOUT_EJECUCION_MS, TimeUnit.MILLISECONDS);
            if (vacio) turnos.release(Constantes.MAX_CONCURRENTES);
            else Log.info("apagado con ejecuciones todavia en vuelo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        motor.limpiarEnVuelo();
        try {
            Files.deleteIfExists(rutaSocket);
        } catch (IOException ignorado) {
            // el socket huerfano se sobreescribe en el proximo arranque
        }
    }

    // ---------------------------------------------------------------- respuestas

    private static String mensaje(String id, String texto) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("ejecucionId", id);
        n.put("error", texto);   // R3.2: mensaje corto, sin rutas del host ni detalles del daemon
        return escribir(n);
    }

    /**
     * El record Salida ya declara los campos de la seccion 3.1 en el orden de la spec, y Jackson
     * serializa los componentes en ese orden. No hace falta armar el objeto a mano.
     */
    static String json(Ejecucion.Salida s) {
        return escribir(s);
    }

    private static String escribir(Object valor) {
        try {
            return MAPPER.writeValueAsString(valor);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void enviar(OutputStream out, int codigo, String cuerpo, String... headersExtra)
            throws IOException {
        byte[] datos = cuerpo.getBytes(StandardCharsets.UTF_8);
        StringBuilder cabeza = new StringBuilder()
                .append("HTTP/1.1 ").append(codigo).append(' ').append(razon(codigo)).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(datos.length).append("\r\n")
                .append("Connection: close\r\n");
        for (String h : headersExtra) cabeza.append(h).append("\r\n");
        cabeza.append("\r\n");
        out.write(cabeza.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(datos);
        out.flush();
    }

    private static String razon(int codigo) {
        return switch (codigo) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 411 -> "Length Required";
            case 413 -> "Payload Too Large";
            case 422 -> "Unprocessable Entity";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }
}
