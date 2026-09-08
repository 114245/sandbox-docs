package sandbox.ejecutor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Daemon de Docker de mentira sobre un socket Unix.
 *
 * Existe para poder afirmar cosas que ningun test contra Docker real deja ver con precision:
 * el orden exacto de las llamadas, los bytes que entraron por stdin, y el comportamiento del
 * cliente ante respuestas chunked, 101 y content-types equivocados.
 */
final class DaemonDePrueba implements AutoCloseable {

    /** Rutas pedidas, en orden. Es lo que permite afirmar R5.1 (attach antes de start) y R5.4. */
    final List<String> llamadas = new CopyOnWriteArrayList<>();
    final Map<String, byte[]> cuerpos = new ConcurrentHashMap<>();
    /** Lo que el ejecutor escribio en el socket adjunto: nonce + tar. */
    volatile byte[] stdinRecibido;
    volatile boolean stdinCerrado;

    // ---- comportamiento configurable
    volatile int exitCode = 0;
    volatile byte[] logs = new byte[0];
    volatile String contentTypeLogs = "application/vnd.docker.multiplexed-stream";
    volatile long demoraWaitMs = 0;
    volatile boolean creates201 = true;
    /** State.OOMKilled que devuelve el inspect del paso 5b. */
    volatile boolean oomKilled = false;
    /** Hace fallar el inspect, para probar que el paso 5b no aborta la ejecucion (R5.10). */
    volatile boolean inspectFalla = false;
    /** No leer nada del socket adjunto, como un contenedor que arranca y no consume stdin (R8.5). */
    volatile boolean consumeStdin = true;

    private final Path ruta;
    private final ServerSocketChannel escucha;
    private final ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean vivo = true;

    DaemonDePrueba(Path ruta) throws IOException {
        this.ruta = ruta;
        Files.deleteIfExists(ruta);
        this.escucha = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.escucha.bind(UnixDomainSocketAddress.of(ruta.toString()));
        hilos.submit(this::aceptar);
    }

    String rutaSocket() { return ruta.toString(); }

    private void aceptar() {
        while (vivo) {
            try {
                SocketChannel c = escucha.accept();
                hilos.submit(() -> { try (c) { atender(c); } catch (Exception ignorado) { } });
            } catch (IOException e) {
                return;
            }
        }
    }

    private void atender(SocketChannel c) throws Exception {
        InputStream in = new BufferedInputStream(Channels.newInputStream(c), 1);
        OutputStream out = Channels.newOutputStream(c);

        Http.Cabeza cabeza = Http.leerCabeza(in);
        String[] partes = cabeza.linea().split(" ");
        String ruta = partes.length > 1 ? partes[1] : "";
        llamadas.add(ruta);

        if (ruta.contains("/attach")) {
            out.write(("HTTP/1.1 101 UPGRADED\r\n"
                    + "Content-Type: application/vnd.docker.multiplexed-stream\r\n"
                    + "Connection: Upgrade\r\nUpgrade: tcp\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            if (!consumeStdin) {
                // Ni una lectura: el buffer del socket se llena y la escritura del ejecutor se traba.
                while (vivo) Thread.sleep(50);
                return;
            }
            ByteArrayOutputStream recibido = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int r;
            while ((r = in.read(buffer)) != -1) recibido.write(buffer, 0, r);
            stdinRecibido = recibido.toByteArray();
            stdinCerrado = true;
            return;
        }

        String cl = cabeza.header("content-length");
        if (cl != null && !cl.equals("0")) cuerpos.put(ruta, Http.leerExacto(in, Integer.parseInt(cl)));

        if (ruta.equals("/_ping")) {
            texto(out, 200, "OK");
        } else if (ruta.contains("/containers/create")) {
            if (creates201) json(out, 201, "{\"Id\":\"contenedor-de-prueba\",\"Warnings\":[]}");
            else json(out, 500, "{\"message\":\"no\"}");
        } else if (ruta.contains("/start")) {
            vacio(out, 204);
        } else if (ruta.contains("/wait")) {
            if (demoraWaitMs > 0) Thread.sleep(demoraWaitMs);
            json(out, 200, "{\"StatusCode\":" + exitCode + "}");
        } else if (ruta.contains("/kill")) {
            demoraWaitMs = 0;   // matarlo hace que el siguiente wait vuelva enseguida
            vacio(out, 204);
        } else if (ruta.contains("/logs")) {
            chunked(out, contentTypeLogs, logs);
        } else if (ruta.contains("/containers/json")) {
            json(out, 200, "[]");
        } else if (ruta.endsWith("/json")) {                      // paso 5b: inspect
            if (inspectFalla) json(out, 500, "{\"message\":\"no\"}");
            else json(out, 200, "{\"State\":{\"OOMKilled\":" + oomKilled + ",\"ExitCode\":0}}");
        } else if (partes[0].equals("DELETE")) {
            vacio(out, 204);
        } else {
            vacio(out, 404);
        }
        out.flush();
    }

    private static void vacio(OutputStream out, int codigo) throws IOException {
        out.write(("HTTP/1.1 " + codigo + " X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void json(OutputStream out, int codigo, String cuerpo) throws IOException {
        cuerpo(out, codigo, "application/json", cuerpo.getBytes(StandardCharsets.UTF_8));
    }

    private static void texto(OutputStream out, int codigo, String cuerpo) throws IOException {
        cuerpo(out, codigo, "text/plain", cuerpo.getBytes(StandardCharsets.UTF_8));
    }

    private static void cuerpo(OutputStream out, int codigo, String tipo, byte[] datos) throws IOException {
        out.write(("HTTP/1.1 " + codigo + " X\r\nContent-Type: " + tipo + "\r\nContent-Length: "
                + datos.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(datos);
    }

    /** logs viaja chunked, como en el daemon real: es lo que ejercita R5.7. */
    private static void chunked(OutputStream out, String tipo, byte[] datos) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + tipo + "\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        int trozo = 7;   // trozos chicos y desalineados con los frames, a proposito
        for (int i = 0; i < datos.length; i += trozo) {
            int n = Math.min(trozo, datos.length - i);
            out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(datos, i, n);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }

    static byte[] frame(int tipo, String texto) {
        byte[] p = texto.getBytes(StandardCharsets.UTF_8);
        byte[] f = new byte[8 + p.length];
        f[0] = (byte) tipo;
        f[4] = (byte) (p.length >>> 24); f[5] = (byte) (p.length >>> 16);
        f[6] = (byte) (p.length >>> 8);  f[7] = (byte) p.length;
        System.arraycopy(p, 0, f, 8, p.length);
        return f;
    }

    @Override
    public void close() throws IOException {
        vivo = false;
        escucha.close();
        hilos.shutdownNow();
        Files.deleteIfExists(ruta);
    }
}
