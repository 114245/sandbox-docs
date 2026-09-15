package sandbox.ejecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-16 (spec 4.1): verifica, y opcionalmente regenera, los pares {@code <id>@<version>.json} /
 * {@code <id>.sh} de un directorio de perfiles. El {@code .sh} es la fuente.
 *
 * <p>Helper de test sin estado. No reemplaza la validacion de arranque de {@link Catalogo} (F-05):
 * cubre los perfiles versionados en el repo.
 */
final class SincroniaPerfiles {

    static final String COMANDO_REGENERAR =
            "mvn test -Dtest=PerfilesSincroniaTest -Dperfiles.regenerar=true";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern NOMBRE_JSON = Pattern.compile("^(.*)@([0-9]+)\\.json$");

    /**
     * La clave del campo script, los dos puntos y la comilla de apertura del valor. El resto del
     * valor (hasta la comilla de cierre) se escanea a mano en {@link #hallarLiteralScript}: una
     * regex recursiva tipo {@code (?:[^"\\]|\\.)*} desborda la pila de Java con guiones de varios
     * KB (cada caracter agrega un frame), y los guiones reales del repo superan ese tamano.
     */
    private static final Pattern CLAVE_SCRIPT = Pattern.compile("\"script\"\\s*:\\s*\"");

    private SincroniaPerfiles() {}

    static List<String> revisar(Path directorio, boolean regenerar) throws IOException {
        List<Path> jsons;
        List<Path> scripts;
        try (var flujo = Files.list(directorio)) {
            List<Path> archivos = flujo.filter(Files::isRegularFile).sorted().toList();
            jsons = archivos.stream().filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            scripts = archivos.stream().filter(p -> p.getFileName().toString().endsWith(".sh")).toList();
        }

        List<String> problemas = new ArrayList<>();
        Set<String> idsUsados = new HashSet<>();

        for (Path json : jsons) {
            String nombre = json.getFileName().toString();
            String clave = nombre.substring(0, nombre.length() - ".json".length());
            Matcher partes = NOMBRE_JSON.matcher(nombre);
            if (!Catalogo.esClaveValida(clave) || !partes.matches()) {
                problemas.add(json + ": el nombre no es <perfilId>@<version>.json con una clave valida");
                continue;
            }
            String id = partes.group(1);
            long version = Long.parseLong(partes.group(2));
            if (version <= 0) {
                problemas.add(json + ": la version del nombre tiene que ser mayor que cero");
                continue;
            }
            idsUsados.add(id);

            String texto = Files.readString(json, StandardCharsets.UTF_8);
            JsonNode arbol;
            try {
                arbol = MAPPER.readTree(texto);
            } catch (IOException e) {
                problemas.add(json + ": no parsea como JSON: " + e.getMessage());
                continue;
            }
            if (arbol == null || !arbol.isObject()) {
                problemas.add(json + ": no es un objeto JSON");
                continue;
            }
            if (!id.equals(arbol.path("perfilId").asText(null))) {
                problemas.add(json + ": perfilId=" + arbol.path("perfilId") + " no coincide con el nombre (" + id + ")");
                continue;
            }
            JsonNode versionJson = arbol.path("version");
            if (!versionJson.isIntegralNumber() || versionJson.asLong() != version) {
                problemas.add(json + ": version=" + versionJson + " no coincide con el nombre (" + version + ")");
                continue;
            }

            Path script = directorio.resolve(id + ".sh");
            if (!Files.isRegularFile(script)) {
                problemas.add(json + ": falta " + script.getFileName() + " en el mismo directorio");
                continue;
            }
            JsonNode scriptJson = arbol.get("script");
            if (scriptJson == null || !scriptJson.isTextual()) {
                problemas.add(json + ": falta el campo script o no es un string");
                continue;
            }

            byte[] esperado = Files.readAllBytes(script);
            if (Arrays.equals(esperado, scriptJson.asText().getBytes(StandardCharsets.UTF_8))) {
                continue;
            }
            if (!regenerar) {
                problemas.add(json + ": el campo script no es igual a " + script.getFileName()
                        + ". Regenerar desde ms-sandbox/ejecutor con: " + COMANDO_REGENERAR);
                continue;
            }
            String problema = regenerarScript(json, texto, arbol, esperado);
            if (problema != null) problemas.add(problema);
        }

        for (Path script : scripts) {
            String nombre = script.getFileName().toString();
            String id = nombre.substring(0, nombre.length() - ".sh".length());
            if (!idsUsados.contains(id)) {
                problemas.add(script + ": script huerfano, ningun " + id + "@<version>.json lo usa");
            }
        }
        return problemas;
    }

    /** Reescribe solo el literal de script. Devuelve un problema, o null si escribio bien. */
    private static String regenerarScript(Path json, String texto, JsonNode antes, byte[] esperado)
            throws IOException {
        int[] span = hallarLiteralScript(texto, 0);
        if (span == null) {
            return json + ": no se encontro el literal \"script\": \"...\" para regenerar";
        }
        if (hallarLiteralScript(texto, span[1]) != null) {
            return json + ": hay mas de un literal \"script\"; no se regenera a ciegas";
        }
        int inicio = span[0];   // la comilla de apertura del valor
        int fin = span[1];      // despues de la comilla de cierre

        String valor = new String(esperado, StandardCharsets.UTF_8);
        String nuevo = texto.substring(0, inicio) + MAPPER.writeValueAsString(valor) + texto.substring(fin);

        JsonNode despues;
        try {
            despues = MAPPER.readTree(nuevo);
        } catch (IOException e) {
            return json + ": la regeneracion produjo JSON invalido; no se escribio";
        }
        byte[] obtenido = despues.path("script").asText("").getBytes(StandardCharsets.UTF_8);
        ObjectNode restoAntes = ((ObjectNode) antes.deepCopy()).without("script");
        ObjectNode restoDespues = ((ObjectNode) despues.deepCopy()).without("script");
        if (!Arrays.equals(esperado, obtenido) || !restoAntes.equals(restoDespues)) {
            return json + ": la regeneracion no dejo el script igual al .sh o altero otros campos; no se escribio";
        }
        Files.writeString(json, nuevo, StandardCharsets.UTF_8);
        return null;
    }

    /**
     * Busca, a partir de {@code desde}, el literal {@code "script": "..."} y devuelve
     * {@code [inicio, fin)}: la comilla de apertura del valor y la posicion justo despues de la
     * comilla de cierre. {@code null} si no hay otro. El valor se escanea caracter a caracter
     * (sin regex) para no desbordar la pila con guiones largos.
     */
    private static int[] hallarLiteralScript(String texto, int desde) {
        Matcher clave = CLAVE_SCRIPT.matcher(texto);
        if (!clave.find(desde)) {
            return null;
        }
        int inicio = clave.end() - 1; // la comilla de apertura del valor
        int i = clave.end();
        while (i < texto.length()) {
            char c = texto.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return new int[] {inicio, i + 1};
            }
            i++;
        }
        return null; // el valor nunca cerro
    }
}
