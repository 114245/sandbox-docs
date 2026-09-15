package sandbox.ejecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-16 (spec 4.2 y 4.3): extrae los plazos declarados en los archivos reales (script del perfil,
 * Dockerfile de la imagen, capa1.sh) y guarda los margenes con nombre. Lo usan
 * {@code PlazosInvarianteTest} y {@code BundlesIT#plataformaPeorCaso}.
 *
 * <p>Regla de extraccion: cada valor tiene que aparecer en EXACTAMENTE una linea activa (no
 * comentario). Cero o mas de una es un fallo: una reasignacion posterior podria ser el valor efectivo.
 */
final class PlazosDeclarados {

    /** Lo que la capa 2 hace fuera de sus timeout: find, sed, leer el XML, escribir la nota. */
    static final int MARGEN_CAPA2_S = 2;

    /**
     * Lo que el reloj del ejecutor cubre fuera del backstop: transferir nonce, script y tar, extraer,
     * emitir el sobre y esperar la salida. No incluye crear ni arrancar el contenedor (el reloj
     * arranca despues de docker.iniciar). Cota elegida; la mide BundlesIT#plataformaPeorCaso en CI.
     */
    static final int MARGEN_PLATAFORMA_S = 5;

    static final Path PERFILES = Path.of("..", "perfiles");
    static final Path CAPA1 = Path.of("..", "imagenes", "capa1", "capa1.sh");

    /** imagen del perfil -> Dockerfile que la construye. Agregar una imagen obliga a agregar su fila. */
    static final Map<String, Path> DOCKERFILES = Map.of(
            "sandbox-runner:2.0.0-capa1", Path.of("..", "imagenes", "java21-junit", "Dockerfile"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern PALABRA_TIMEOUT = Pattern.compile("\\btimeout\\b");

    /** timeout, opciones sueltas (--signal=KILL, -v) y el plazo como "${NOMBRE}s". */
    private static final Pattern PLAZO_DE_TIMEOUT = Pattern.compile(
            "\\btimeout(?:\\s+--?[A-Za-z][A-Za-z-]*(?:=\\S+)?)*\\s+\"\\$\\{([A-Z_][A-Z0-9_]*)\\}s\"");

    private PlazosDeclarados() {}

    private record Linea(int numero, String texto) {}

    // ------------------------------------------------------------------
    // Perfiles e imagenes
    // ------------------------------------------------------------------

    static List<Path> perfilesDeProduccion() throws IOException {
        try (var flujo = Files.list(PERFILES)) {
            return flujo.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    static String imagenDe(Path perfilJson) throws IOException {
        String imagen = leerJson(perfilJson).path("imagen").asText(null);
        if (imagen == null) throw new AssertionError(perfilJson + ": falta imagen");
        return imagen;
    }

    static Path scriptDe(Path perfilJson) throws IOException {
        String id = leerJson(perfilJson).path("perfilId").asText(null);
        if (id == null) throw new AssertionError(perfilJson + ": falta perfilId");
        Path script = perfilJson.getParent().resolve(id + ".sh");
        if (!Files.isRegularFile(script)) throw new AssertionError(perfilJson + ": falta " + script);
        return script;
    }

    static Path dockerfileDe(String imagen) {
        Path dockerfile = DOCKERFILES.get(imagen);
        if (dockerfile == null) {
            throw new AssertionError("la imagen " + imagen + " no tiene fila en PlazosDeclarados.DOCKERFILES");
        }
        return dockerfile;
    }

    // ------------------------------------------------------------------
    // Capa 2
    // ------------------------------------------------------------------

    /** Suma de los plazos de TODAS las invocaciones activas de timeout del script (fases en serie). */
    static int presupuestoCapa2(Path script) throws IOException {
        int total = 0;
        int invocaciones = 0;
        for (Linea linea : lineasActivas(script)) {
            if (!PALABRA_TIMEOUT.matcher(linea.texto()).find()) continue;
            Matcher m = PLAZO_DE_TIMEOUT.matcher(linea.texto());
            if (!m.find()) {
                throw new AssertionError(script + " linea " + linea.numero()
                        + ": invocacion de timeout sin plazo con la forma \"${NOMBRE}s\": " + linea.texto().strip());
            }
            total += asignacion(script, m.group(1));
            invocaciones++;
        }
        if (invocaciones == 0) {
            throw new AssertionError(script + ": no hay ninguna invocacion activa de timeout");
        }
        return total;
    }

    /** NOMBRE=<entero>, asignado en exactamente una linea activa (con o sin export/readonly). */
    static int asignacion(Path script, String nombre) throws IOException {
        String q = Pattern.quote(nombre);
        return unico(script,
                Pattern.compile("^\\s*(?:export\\s+|readonly\\s+)?" + q + "="),
                Pattern.compile("^\\s*(?:export\\s+|readonly\\s+)?" + q + "=(\\d+)(?:\\s|$)"),
                "la asignacion de " + nombre);
    }

    // ------------------------------------------------------------------
    // Imagen y capa 1
    // ------------------------------------------------------------------

    static int evalTimeoutDeDockerfile(Path dockerfile) throws IOException {
        return unico(dockerfile,
                Pattern.compile("\\bSANDBOX_EVAL_TIMEOUT_S="),
                Pattern.compile("\\bSANDBOX_EVAL_TIMEOUT_S=(\\d+)\\b"),
                "SANDBOX_EVAL_TIMEOUT_S");
    }

    static int evalTimeoutPorDefectoDeCapa1(Path capa1) throws IOException {
        return unico(capa1,
                Pattern.compile("SANDBOX_EVAL_TIMEOUT_S:-"),
                Pattern.compile("\\$\\{SANDBOX_EVAL_TIMEOUT_S:-(\\d+)\\}"),
                "el valor por defecto de SANDBOX_EVAL_TIMEOUT_S");
    }

    static int graciaDeCapa1(Path capa1) throws IOException {
        return unico(capa1,
                Pattern.compile("\\btimeout\\b.*\\s-k\\b"),
                Pattern.compile("\\s-k\\s+(\\d+)s\\b"),
                "la gracia de timeout -k");
    }

    static long relojEjecutorS() {
        return Constantes.TIMEOUT_EJECUCION_MS / 1000;
    }

    // ------------------------------------------------------------------
    // Extraccion
    // ------------------------------------------------------------------

    private static int unico(Path archivo, Pattern detector, Pattern valor, String que) throws IOException {
        List<Linea> encontradas = new ArrayList<>();
        for (Linea linea : lineasActivas(archivo)) {
            if (detector.matcher(linea.texto()).find()) encontradas.add(linea);
        }
        if (encontradas.size() != 1) {
            throw new AssertionError(archivo + ": se esperaba exactamente una linea activa con " + que
                    + " y hay " + encontradas.size() + ": " + encontradas);
        }
        Linea linea = encontradas.get(0);
        Matcher m = valor.matcher(linea.texto());
        if (!m.find()) {
            throw new AssertionError(archivo + " linea " + linea.numero() + ": " + que
                    + " no tiene un entero reconocible: " + linea.texto().strip());
        }
        return Integer.parseInt(m.group(1));
    }

    /** Lineas que no son comentario: las que, sin espacios iniciales, no empiezan con '#'. */
    private static List<Linea> lineasActivas(Path archivo) throws IOException {
        List<String> todas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
        List<Linea> activas = new ArrayList<>();
        for (int i = 0; i < todas.size(); i++) {
            String texto = todas.get(i);
            if (!texto.stripLeading().startsWith("#")) activas.add(new Linea(i + 1, texto));
        }
        return activas;
    }

    private static JsonNode leerJson(Path archivo) throws IOException {
        return MAPPER.readTree(Files.readString(archivo, StandardCharsets.UTF_8));
    }
}
