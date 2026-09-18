package sandbox.ejecutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-16 (spec 2026-09-14-f16-perfil-y-ci.md, 4.1): el campo {@code script} de cada perfil versionado
 * tiene que ser su {@code .sh} byte a byte. El {@code .sh} es la fuente; el JSON se regenera con
 * {@link SincroniaPerfiles#COMANDO_REGENERAR}.
 */
class PerfilesSincroniaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<Path> DIRECTORIOS_DEL_REPO = List.of(
            Path.of("..", "perfiles"),
            Path.of("..", "pruebas", "perfiles-ejemplo"));

    // ------------------------------------------------------------------
    // El repo
    // ------------------------------------------------------------------

    @Test
    void losPerfilesDelRepoEstanSincronizadosConSusScripts() throws IOException {
        boolean regenerar = Boolean.getBoolean("perfiles.regenerar");
        List<String> problemas = new ArrayList<>();
        for (Path directorio : DIRECTORIOS_DEL_REPO) {
            assertTrue(Files.isDirectory(directorio), "no existe " + directorio.toAbsolutePath());
            problemas.addAll(SincroniaPerfiles.revisar(directorio, regenerar));
        }
        assertTrue(problemas.isEmpty(), String.join("\n", problemas));
    }

    // ------------------------------------------------------------------
    // Las reglas, sobre @TempDir
    // ------------------------------------------------------------------

    @Test
    void parSincronizadoNoTieneProblemas(@TempDir Path dir) throws IOException {
        escribirPar(dir, "demo", 1, "#!/bin/sh\necho hola\n");
        assertEquals(List.of(), SincroniaPerfiles.revisar(dir, false));
    }

    @Test
    void jsonDesincronizadoFallaYNombraElComando(@TempDir Path dir) throws IOException {
        escribir(dir, "demo@1.json", perfilJson("demo", 1, "viejo"));
        escribir(dir, "demo.sh", "nuevo\n");

        List<String> problemas = SincroniaPerfiles.revisar(dir, false);

        assertEquals(1, problemas.size(), problemas.toString());
        assertTrue(problemas.get(0).contains("demo@1.json"), problemas.get(0));
        assertTrue(problemas.get(0).contains(SincroniaPerfiles.COMANDO_REGENERAR), problemas.get(0));
    }

    @Test
    void regenerarSincronizaYSoloCambiaElLiteralDeScript(@TempDir Path dir) throws IOException {
        String original = "{\n  \"perfilId\" : \"demo\",\n  \"version\": 1,\n"
                + "  \"imagen\": \"img:1\",\n  \"script\":   \"viejo\",\n"
                + "  \"reportFormat\": \"junit-xml\",\n  \"limites\": { \"memoriaMb\": 512, \"cpuS\": 20 }\n}\n";
        String script = "#!/bin/sh\necho nuevo\n";
        escribir(dir, "demo@1.json", original);
        escribir(dir, "demo.sh", script);

        assertEquals(List.of(), SincroniaPerfiles.revisar(dir, true));

        String esperado = original.replace("\"viejo\"", MAPPER.writeValueAsString(script));
        assertEquals(esperado, leer(dir, "demo@1.json"));
        assertEquals(List.of(), SincroniaPerfiles.revisar(dir, false));
    }

    @Test
    void regenerarPreservaCaracteresEspeciales(@TempDir Path dir) throws IOException {
        String script = "#!/bin/sh\n# comillas \" y barra \\ y tab\tfin\nprintf '%s\\n' \"a—b ñ\"\n";
        escribir(dir, "demo@1.json", perfilJson("demo", 1, "viejo"));
        escribir(dir, "demo.sh", script);

        assertEquals(List.of(), SincroniaPerfiles.revisar(dir, true));

        String scriptEnJson = MAPPER.readTree(leer(dir, "demo@1.json")).get("script").asText();
        assertArrayEquals(script.getBytes(StandardCharsets.UTF_8), scriptEnJson.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void regenerarNoReescribeUnJsonYaSincronizado(@TempDir Path dir) throws IOException {
        escribirPar(dir, "demo", 1, "#!/bin/sh\n");
        Path json = dir.resolve("demo@1.json");
        FileTime antes = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
        Files.setLastModifiedTime(json, antes);

        assertEquals(List.of(), SincroniaPerfiles.revisar(dir, true));

        assertEquals(antes, Files.getLastModifiedTime(json));
    }

    @Test
    void jsonSinScriptFallaSinEscribir(@TempDir Path dir) throws IOException {
        String sinScript = "{\n  \"perfilId\": \"demo\",\n  \"version\": 1\n}\n";
        escribir(dir, "demo@1.json", sinScript);
        escribir(dir, "demo.sh", "#!/bin/sh\n");

        List<String> problemas = SincroniaPerfiles.revisar(dir, true);

        assertEquals(1, problemas.size(), problemas.toString());
        assertEquals(sinScript, leer(dir, "demo@1.json"));
    }

    @Test
    void jsonQueNoParseaFallaSinEscribir(@TempDir Path dir) throws IOException {
        String roto = "{ esto no es json ";
        escribir(dir, "demo@1.json", roto);
        escribir(dir, "demo.sh", "#!/bin/sh\n");

        List<String> problemas = SincroniaPerfiles.revisar(dir, true);

        assertFalse(problemas.isEmpty());
        assertEquals(roto, leer(dir, "demo@1.json"));
    }

    @Test
    void nombreDeArchivoConClaveInvalidaFalla(@TempDir Path dir) throws IOException {
        // Guion bajo y no mayuscula: en Windows "Demo@1.json" y "demo@1.json" son el mismo archivo.
        escribir(dir, "de_mo@1.json", perfilJson("de_mo", 1, "#!/bin/sh\n"));

        List<String> problemas = SincroniaPerfiles.revisar(dir, false);

        assertTrue(problemas.stream().anyMatch(p -> p.contains("de_mo@1.json")), problemas.toString());
    }

    @Test
    void versionCeroEnElNombreFalla(@TempDir Path dir) throws IOException {
        escribirPar(dir, "demo", 0, "#!/bin/sh\n");

        List<String> problemas = SincroniaPerfiles.revisar(dir, false);

        assertTrue(problemas.stream().anyMatch(p -> p.contains("demo@0.json")), problemas.toString());
    }

    @Test
    void perfilIdDistintoDelNombreFalla(@TempDir Path dir) throws IOException {
        escribir(dir, "demo@1.json", perfilJson("otro", 1, "#!/bin/sh\n"));
        escribir(dir, "demo.sh", "#!/bin/sh\n");

        List<String> problemas = SincroniaPerfiles.revisar(dir, false);

        assertTrue(problemas.stream().anyMatch(p -> p.contains("perfilId")), problemas.toString());
    }

    @Test
    void versionDistintaDelNombreFalla(@TempDir Path dir) throws IOException {
        escribir(dir, "demo@5.json", perfilJson("demo", 4, "#!/bin/sh\n"));
        escribir(dir, "demo.sh", "#!/bin/sh\n");

        List<String> problemas = SincroniaPerfiles.revisar(dir, false);

        assertTrue(problemas.stream().anyMatch(p -> p.contains("version")), problemas.toString());
    }

    @Test
    void faltaElScriptFalla(@TempDir Path dir) throws IOException {
        escribir(dir, "demo@1.json", perfilJson("demo", 1, "#!/bin/sh\n"));

        List<String> problemas = SincroniaPerfiles.revisar(dir, true);

        assertTrue(problemas.stream().anyMatch(p -> p.contains("demo.sh")), problemas.toString());
    }

    @Test
    void scriptHuerfanoFalla(@TempDir Path dir) throws IOException {
        escribirPar(dir, "demo", 1, "#!/bin/sh\n");
        escribir(dir, "suelto.sh", "#!/bin/sh\n");

        List<String> problemas = SincroniaPerfiles.revisar(dir, false);

        assertTrue(problemas.stream().anyMatch(p -> p.contains("suelto.sh")), problemas.toString());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void escribirPar(Path dir, String id, int version, String script) throws IOException {
        escribir(dir, id + "@" + version + ".json", perfilJson(id, version, script));
        escribir(dir, id + ".sh", script);
    }

    private static String perfilJson(String id, int version, String script) throws IOException {
        return "{\n"
                + "  \"perfilId\": " + MAPPER.writeValueAsString(id) + ",\n"
                + "  \"version\": " + version + ",\n"
                + "  \"imagen\": \"img:1\",\n"
                + "  \"script\": " + MAPPER.writeValueAsString(script) + ",\n"
                + "  \"reportFormat\": \"junit-xml\",\n"
                + "  \"limites\": {\n    \"memoriaMb\": 512,\n    \"cpuS\": 20\n  }\n"
                + "}\n";
    }

    private static void escribir(Path dir, String nombre, String contenido) throws IOException {
        Files.writeString(dir.resolve(nombre), contenido, StandardCharsets.UTF_8);
    }

    private static String leer(Path dir, String nombre) throws IOException {
        return Files.readString(dir.resolve(nombre), StandardCharsets.UTF_8);
    }
}
