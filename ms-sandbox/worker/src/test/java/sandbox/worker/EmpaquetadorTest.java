package sandbox.worker;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EmpaquetadorTest {

    @Test
    void empaquetaConRutasRelativasYSeparadorPosix(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/com"));
        Files.writeString(dir.resolve("src/com/A.java"), "class A {}");
        Files.createDirectories(dir.resolve("test"));
        Files.writeString(dir.resolve("test/ATest.java"), "class ATest {}");

        List<String> nombres = nombresDe(Empaquetador.desdeDirectorio(dir));

        // Separador POSIX siempre: el tar lo abre la capa 1 adentro de un contenedor Linux,
        // aunque el worker corra en Windows.
        assertTrue(nombres.contains("src/com/A.java"), nombres.toString());
        assertTrue(nombres.contains("test/ATest.java"), nombres.toString());
        assertTrue(nombres.stream().noneMatch(n -> n.contains("\\")));
    }

    @Test
    void conservaElContenidoDeCadaArchivo(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("run.sh"), "#!/bin/sh\necho hola\n");

        byte[] tar = Empaquetador.desdeDirectorio(dir);

        assertEquals("#!/bin/sh\necho hola\n", contenidoDe(tar, "run.sh"));
    }

    private static List<String> nombresDe(byte[] tar) throws Exception {
        List<String> nombres = new ArrayList<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) if (!e.isDirectory()) nombres.add(e.getName());
        }
        return nombres;
    }

    private static String contenidoDe(byte[] tar, String nombre) throws Exception {
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                if (e.getName().equals(nombre)) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
