package sandbox.ejecutor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Daemon de Docker de mentira.
 *
 * Existe para poder afirmar cosas que ningun test contra Docker real deja ver con precision:
 * el orden exacto de las llamadas, los bytes que entraron por stdin, y el comportamiento del
 * cliente ante respuestas chunked, 101 y streams sin enmarcar.
 *
 * <b>Por que TCP y no un socket Unix.</b> Antes hablaba por AF_UNIX porque el cliente a mano solo
 * sabia eso. docker-java resuelve el socket Unix con JNA sobre Linux/macOS, asi que un socket Unix
 * de Java en Windows no le sirve; tcp:// es un transporte de primera clase del daemon real y anda
 * igual en las tres plataformas, que es lo que mantiene la suite verde en Windows nativo.
 */
final class DaemonDePrueba implements AutoCloseable {

    /** Rutas pedidas, en orden. Es lo que permite afirmar R5.1 (attach antes de start) y R5.4. */
    final List<String> llamadas = new CopyOnWriteArrayList<>();
    final Map<String, byte[]> cuerpos = new ConcurrentHashMap<>();
    /** Lo que el ejecutor escribio en el canal adjunto: los tres documentos pegados. */
    volatile byte[] stdinRecibido;
    volatile boolean stdinCerrado;
    /**
     * Corre en el hilo del daemon apenas se cierra el canal adjunto, con los bytes que el ejecutor
     * escribio. Deja armar los logs a partir del nonce sin carrera: cuando corre, /logs todavia no
     * se pidio, porque el attach precede al wait y al logs (R5.1, R5.4).
     */
    volatile Consumer<byte[]> alRecibirStdin;

    /**
     * Los bytes del canal adjunto, esperando a que el daemon termine de leerlos. Leer
     * {@link #stdinRecibido} apenas vuelve ejecutar() es una carrera: el hilo que atiende el attach
     * puede no haber cerrado todavia, y bajo carga esa carrera se pierde y el arreglo llega vacio.
     */
    byte[] esperarStdin() {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!stdinCerrado && System.nanoTime() < limite) Thread.onSpinWait();
        if (!stdinCerrado) throw new IllegalStateException("el daemon no termino de leer el canal adjunto");
        return stdinRecibido;
    }

    // ---- comportamiento configurable
    volatile int exitCode = 0;
    volatile byte[] logs = new byte[0];
    volatile long demoraWaitMs = 0;
    volatile boolean creates201 = true;
    /** State.OOMKilled que devuelve el inspect del paso 5b. */
    volatile boolean oomKilled = false;
    /** Hace fallar el inspect, para probar que el paso 5b no aborta la ejecucion (R5.10). */
    volatile boolean inspectFalla = false;
    /** No leer nada del canal adjunto, como un contenedor que arranca y no consume stdin (R8.5). */
    volatile boolean consumeStdin = true;
    /** ApiVersion y MinAPIVersion que devuelve /version (A40). Ambas null = campo ausente. */
    volatile String apiVersion = "1.43";
    volatile String minApiVersion = "1.24";
    /** Contesta /version con 400 "client version ... is too old", como un Engine con minimo alto. */
    volatile boolean versionRechazaPorVieja = false;

    private final ServerSocket escucha;
    private final ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean vivo = true;

    DaemonDePrueba() throws IOException {
        this.escucha = new ServerSocket();
        this.escucha.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        hilos.submit(this::aceptar);
    }

    String dockerHost() {
        return "tcp://127.0.0.1:" + escucha.getLocalPort();
    }

    private void aceptar() {
        while (vivo) {
            try {
                Socket c = escucha.accept();
                hilos.submit(() -> { try (c) { atender(c); } catch (Exception ignorado) { } });
            } catch (IOException e) {
                return;
            }
        }
    }

    private void atender(Socket socket) throws Exception {
        InputStream in = new BufferedInputStream(socket.getInputStream(), 1);
        OutputStream out = socket.getOutputStream();

        Http.Cabeza cabeza = Http.leerCabeza(in);
        String[] partes = cabeza.linea().split(" ");
        String ruta = partes.length > 1 ? partes[1] : "";
        llamadas.add(ruta);

        if (ruta.contains("/attach")) {
            stdinRecibido = null;
            stdinCerrado = false;
            out.write(("HTTP/1.1 101 UPGRADED\r\n"
                    + "Content-Type: application/vnd.docker.multiplexed-stream\r\n"
                    + "Connection: Upgrade\r\nUpgrade: tcp\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            if (!consumeStdin) {
                // Ni una lectura: el buffer del socket se llena y la escritura del ejecutor se traba.
                while (vivo && !socket.isClosed()) Thread.sleep(50);
                return;
            }
            ByteArrayOutputStream recibido = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[4096];
                int r;
                // Termina cuando el ejecutor cierra el canal adjunto, que es el EOF de stdin del
                // contenedor: con docker-java no hay media-clausura, el cierre es el unico final.
                while ((r = in.read(buffer)) != -1) recibido.write(buffer, 0, r);
            } catch (IOException cierreAbrupto) {
                // Cerrar la conexion entera -que es como docker-java produce el EOF- llega de este
                // lado como un reset, no como un -1 limpio. Para el daemon real es lo mismo: el
                // cliente se fue. Lo que importa es que los bytes que ya llegaron esten completos.
            } finally {
                stdinRecibido = recibido.toByteArray();
                if (alRecibirStdin != null) alRecibirStdin.accept(stdinRecibido);
                // Ultimo, y a proposito: stdinCerrado significa "los bytes llegaron y el hook ya
                // corrio". Es la barrera que espera /logs, asi que no puede levantarse antes.
                stdinCerrado = true;
            }
            return;
        }

        String cl = cabeza.header("content-length");
        if (cl != null && !cl.equals("0")) cuerpos.put(ruta, Http.leerExacto(in, Integer.parseInt(cl)));

        if (ruta.endsWith("/_ping")) {
            texto(out, 200, "OK");
        } else if (ruta.endsWith("/version")) {
            if (versionRechazaPorVieja) {
                json(out, 400, "{\"message\":\"client version " + Constantes.VERSION_API_DOCKER.substring(1)
                        + " is too old, minimum supported API version is " + minApiVersion + "\"}");
            } else {
                StringBuilder cuerpo = new StringBuilder("{\"ApiVersion\":\"").append(apiVersion).append("\"");
                if (minApiVersion != null) cuerpo.append(",\"MinAPIVersion\":\"").append(minApiVersion).append("\"");
                cuerpo.append("}");
                json(out, 200, cuerpo.toString());
            }
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
            // El hook corre en el hilo del attach, no en este. Sin esta barrera, /logs puede
            // servirse con el `logs` vacio por defecto: es la carrera que hacia intermitente a
            // elReporteSeSeparaConElNonceDeLaEjecucion bajo carga.
            if (alRecibirStdin != null) esperarStdin();
            chunked(out, logs);
        } else if (ruta.contains("/containers/json")) {
            json(out, 200, "[]");
        } else if (ruta.endsWith("/json")) {                      // paso 5b: inspect
            if (inspectFalla) json(out, 500, "{\"message\":\"no\"}");
            else json(out, 200, "{\"State\":{\"OOMKilled\":" + oomKilled + ",\"ExitCode\":0}}");
        } else if (partes[0].equals("DELETE")) {
            vacio(out, 204);
        } else {
            json(out, 404, "{\"message\":\"no such thing\"}");
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
    private static void chunked(OutputStream out, byte[] datos) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.docker.multiplexed-stream\r\n"
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
    }
}
