package sandbox.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Del JSON de la capa 1 al buzon en memoria. */
final class Desempaquetador {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Desempaquetador() {}

    static SobreCapa1 leerSobre(String json) {
        if (json == null || json.isBlank()) throw new ErrorDeSobre("el reporte vino vacio");
        try {
            return MAPPER.readValue(json, SobreCapa1.class);
        } catch (Exception e) {
            throw new ErrorDeSobre("el sobre de la capa 1 no se pudo parsear", e);
        }
    }

    /**
     * base64 -> gunzip -> untar, TODO en memoria. Nunca se escribe a disco: el tar viene de
     * adentro del contenedor y una entrada con ../ no puede escaparse a ningun lado si no se
     * escribe nada.
     */
    static Buzon abrirBuzon(SobreCapa1 sobre) {
        String b64 = sobre.reportesTarGzB64();
        if (b64 == null || b64.isEmpty()) return Buzon.vacio();

        byte[] targz;
        try {
            targz = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new ErrorDeSobre("reportesTarGzB64 no es base64 valido", e);
        }

        Map<String, byte[]> archivos = new LinkedHashMap<>();
        long total = 0;
        try (TarArchiveInputStream tar =
                     new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(targz)))) {
            TarArchiveEntry entrada;
            while ((entrada = tar.getNextTarEntry()) != null) {
                if (entrada.isDirectory()) continue;

                ByteArrayOutputStream contenido = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int leidos;
                while ((leidos = tar.read(buffer)) != -1) {
                    total += leidos;
                    if (total > Constantes.MAX_BUZON_BYTES) {
                        throw new ErrorDeSobre("el buzon supera " + Constantes.MAX_BUZON_BYTES + " bytes");
                    }
                    contenido.write(buffer, 0, leidos);
                }
                archivos.put(entrada.getName(), contenido.toByteArray());
            }
        } catch (ErrorDeSobre e) {
            throw e;
        } catch (Exception e) {
            throw new ErrorDeSobre("el buzon no se pudo desempaquetar", e);
        }
        return new Buzon(archivos);
    }
}
