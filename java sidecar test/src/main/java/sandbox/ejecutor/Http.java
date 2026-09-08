package sandbox.ejecutor;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Lo minimo de HTTP/1.1 que hace falta para hablar con el daemon y para atender al worker.
 * Se escribe a mano porque java.net.http.HttpClient no habla sockets Unix (JDK-8377806)
 * y com.sun.net.httpserver solo bindea InetSocketAddress.
 */
final class Http {
    private Http() {}

    /** Encabezados de un mensaje: primera linea mas headers con la clave en minusculas. */
    record Cabeza(String linea, Map<String, String> headers) {
        String header(String nombre) { return headers.get(nombre.toLowerCase()); }
    }

    static Cabeza leerCabeza(InputStream in) throws IOException {
        String primera = leerLinea(in);
        if (primera.isEmpty()) throw new EOFException("mensaje vacio");
        Map<String, String> headers = new HashMap<>();
        String linea;
        while (!(linea = leerLinea(in)).isEmpty()) {
            int dosPuntos = linea.indexOf(':');
            if (dosPuntos < 0) throw new IOException("header invalido");
            headers.put(linea.substring(0, dosPuntos).trim().toLowerCase(),
                        linea.substring(dosPuntos + 1).trim());
        }
        return new Cabeza(primera, headers);
    }

    /** Lee una linea terminada en CRLF. Tope de 8 KiB para que un peer malicioso no nos haga crecer. */
    private static String leerLinea(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] datos = buffer.toByteArray();
                int largo = (datos.length > 0 && datos[datos.length - 1] == '\r') ? datos.length - 1 : datos.length;
                return new String(datos, 0, largo, StandardCharsets.ISO_8859_1);
            }
            if (buffer.size() >= 8192) throw new IOException("linea demasiado larga");
            buffer.write(b);
        }
        if (buffer.size() == 0) return "";
        throw new EOFException("linea sin terminar");
    }

    /** Codigo de estado de una linea "HTTP/1.1 200 OK". */
    static int codigo(String lineaDeEstado) throws IOException {
        String[] partes = lineaDeEstado.split(" ", 3);
        if (partes.length < 2) throw new IOException("linea de estado invalida");
        try {
            return Integer.parseInt(partes[1]);
        } catch (NumberFormatException e) {
            throw new IOException("codigo de estado invalido");
        }
    }

    /**
     * Cuerpo de una respuesta: chunked (R5.7, lo usa logs), Content-Length, o hasta EOF.
     * El tope evita que un daemon anomalo nos haga crecer sin limite.
     */
    static byte[] leerCuerpo(InputStream in, Cabeza cabeza, int tope) throws IOException {
        String te = cabeza.header("transfer-encoding");
        if (te != null && te.toLowerCase().contains("chunked")) return leerChunked(in, tope);

        String cl = cabeza.header("content-length");
        if (cl != null) {
            int n;
            try {
                n = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("content-length invalido");
            }
            if (n < 0 || n > tope) throw new IOException("cuerpo de " + cl + " bytes");
            return leerExacto(in, n);
        }
        return leerHastaEof(in, tope);
    }

    static byte[] leerExacto(InputStream in, int n) throws IOException {
        byte[] datos = new byte[n];
        int leidos = 0;
        while (leidos < n) {
            int r = in.read(datos, leidos, n - leidos);
            if (r < 0) throw new EOFException("cuerpo incompleto");
            leidos += r;
        }
        return datos;
    }

    private static byte[] leerHastaEof(InputStream in, int tope) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int r;
        while ((r = in.read(buffer)) != -1) {
            if (salida.size() + r > tope) throw new IOException("cuerpo por encima del tope");
            salida.write(buffer, 0, r);
        }
        return salida.toByteArray();
    }

    private static byte[] leerChunked(InputStream in, int tope) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        while (true) {
            String linea = leerLinea(in);
            int extension = linea.indexOf(';');
            if (extension >= 0) linea = linea.substring(0, extension);
            int largo;
            try {
                largo = Integer.parseInt(linea.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("chunk invalido");
            }
            if (largo < 0 || salida.size() + largo > tope) throw new IOException("cuerpo por encima del tope");
            if (largo == 0) {
                while (!leerLinea(in).isEmpty()) { /* trailers */ }
                return salida.toByteArray();
            }
            salida.writeBytes(leerExacto(in, largo));
            leerLinea(in); // CRLF de cierre del chunk
        }
    }
}
