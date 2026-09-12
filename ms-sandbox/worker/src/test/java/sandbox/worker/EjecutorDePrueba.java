package sandbox.worker;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Un ejecutor de mentira sobre un socket Unix. Escrito a mano, como DaemonDePrueba del
 * ejecutor: el proyecto no usa librerias de mocking.
 */
final class EjecutorDePrueba implements AutoCloseable {

    private final ServerSocketChannel escucha;
    private final Thread hilo;
    private final String respuestaCruda;
    private final long demoraMs;
    private volatile byte[] tarRecibido;
    private volatile String cabezaRecibida;

    private EjecutorDePrueba(Path ruta, String respuestaCruda, long demoraMs) throws Exception {
        this.respuestaCruda = respuestaCruda;
        this.demoraMs = demoraMs;
        this.escucha = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.escucha.bind(UnixDomainSocketAddress.of(ruta.toString()));
        this.hilo = Thread.ofVirtual().start(this::atender);
    }

    static EjecutorDePrueba queResponde(Path ruta, String respuestaCruda) throws Exception {
        return new EjecutorDePrueba(ruta, respuestaCruda, 0);
    }

    static EjecutorDePrueba queNuncaResponde(Path ruta) throws Exception {
        return new EjecutorDePrueba(ruta, "", Long.MAX_VALUE);
    }

    /** Respuesta 200 con un sobre minimo cuyo campo `reporte` es lo que se le pase. */
    static String respuesta200(String reporteJson) {
        String cuerpo = """
            {"ejecucionId":"3f2b","resultado":"COMPLETADA","exitCode":0,"oomKilled":false,\
            "duracionMs":4172,"stdout":"","stderr":"","reporte":%s,"reporteAusente":false,\
            "salidaTruncada":false,"perfilId":"java21-junit","perfilVersion":4,"perfilHash":"9f86"}\
            """.formatted(reporteJson);
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + cuerpo.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + cuerpo;
    }

    /** Respuesta 200 con el cuerpo TAL CUAL: para probar cuerpos que no son un sobre. */
    static String respuesta200Cruda(String cuerpo) {
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + cuerpo.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + cuerpo;
    }

    static String respuestaSinCuerpo(int codigo, String razon, String headersExtra) {
        return "HTTP/1.1 " + codigo + " " + razon + "\r\n" + headersExtra + "Content-Length: 0\r\n\r\n";
    }

    byte[] tarRecibido()      { return tarRecibido; }
    String cabezaRecibida()   { return cabezaRecibida; }

    private void atender() {
        try (SocketChannel conexion = escucha.accept()) {
            InputStream in = Channels.newInputStream(conexion);
            Http.Cabeza cabeza = Http.leerCabeza(in);
            cabezaRecibida = cabeza.linea() + "|" + cabeza.header("x-perfil")
                           + "|" + cabeza.header("x-ejecucion-id");
            int largo = Integer.parseInt(cabeza.header("content-length"));
            tarRecibido = Http.leerExacto(in, largo);

            if (demoraMs > 0) Thread.sleep(demoraMs);

            OutputStream out = Channels.newOutputStream(conexion);
            out.write(respuestaCruda.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            // El socket cerrado por el cliente es parte de las pruebas: no es un fallo.
        }
    }

    @Override
    public void close() throws Exception {
        hilo.interrupt();
        escucha.close();
    }
}
