package sandbox.ejecutor;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/** Cliente minimo para hablarle al ejecutor desde los tests. Hace de worker. */
final class ClienteHttpDePrueba {

    record Respuesta(int codigo, java.util.Map<String, String> headers, String cuerpo) {}

    private final String rutaSocket;

    ClienteHttpDePrueba(String rutaSocket) { this.rutaSocket = rutaSocket; }

    Respuesta enviar(String cabecerasCrudas, byte[] cuerpo) throws IOException {
        try (SocketChannel canal = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            canal.connect(UnixDomainSocketAddress.of(rutaSocket));
            OutputStream out = Channels.newOutputStream(canal);
            out.write(cabecerasCrudas.getBytes(StandardCharsets.ISO_8859_1));
            if (cuerpo != null) out.write(cuerpo);
            out.flush();

            InputStream in = new BufferedInputStream(Channels.newInputStream(canal), 8192);
            Http.Cabeza cabeza = Http.leerCabeza(in);
            byte[] datos = Http.leerCuerpo(in, cabeza, 8 * 1024 * 1024);
            return new Respuesta(Http.codigo(cabeza.linea()), cabeza.headers(),
                    new String(datos, StandardCharsets.UTF_8));
        }
    }

    Respuesta ejecutar(String ejecucionId, byte[] tar) throws IOException {
        return enviar("POST /ejecutar HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "X-Ejecucion-Id: " + ejecucionId + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + tar.length + "\r\n\r\n", tar);
    }

    Respuesta salud() throws IOException {
        return enviar("GET /salud HTTP/1.1\r\nHost: localhost\r\n\r\n", null);
    }
}
