package sandbox.worker;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Directorio -> tar SIN comprimir, que es lo que el ejecutor espera en el cuerpo del POST
 * (08 seccion 3.1, Content-Type: application/octet-stream).
 *
 * PROVISIONAL en un punto: no valida las rutas del bundle. Esa validacion es RUTA_INVALIDA,
 * que 07 seccion 4 marca como inexistente ("no existe hoy y hace falta") y ubica del lado de
 * la API, no del worker. Cuando exista, este empaquetador recibe rutas ya validadas.
 */
final class Empaquetador {

    private Empaquetador() {}

    static byte[] desdeDirectorio(Path raiz) {
        ByteArrayOutputStream crudo = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(crudo)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            List<Path> archivos;
            try (Stream<Path> recorrido = Files.walk(raiz)) {
                archivos = recorrido.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path archivo : archivos) {
                // Separador POSIX siempre: el tar lo abre la capa 1 adentro de un contenedor
                // Linux, aunque el worker corra en Windows.
                String nombre = raiz.relativize(archivo).toString().replace('\\', '/');
                byte[] datos = Files.readAllBytes(archivo);
                TarArchiveEntry entrada = new TarArchiveEntry(nombre);
                entrada.setSize(datos.length);
                tar.putArchiveEntry(entrada);
                tar.write(datos);
                tar.closeArchiveEntry();
            }
        } catch (Exception e) {
            // La causa va como causa y no concatenada al mensaje: concatenarla pierde el
            // stack trace, que es justo lo que hace falta para un bug nuestro.
            throw new ErrorDeProgramacion("no se pudo empaquetar el bundle de " + raiz, e);
        }
        return crudo.toByteArray();
    }
}
