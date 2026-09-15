# F-16: perfil sincronizado, invariante de plazos y CI — Plan de implementación

> **Para agentes:** SUB-SKILL REQUERIDO: usar superpowers:subagent-driven-development (recomendado)
> o superpowers:executing-plans para implementar este plan tarea por tarea. Los pasos usan casillas
> (`- [ ]`) para el seguimiento.

**Objetivo:** que el perfil que se ejecuta no pueda divergir de su `.sh`, que la invariante de plazos
la valide un test y que una CI corra la suite adversaria contra Docker real sin poder quedar verde
si esa suite no corrió.

**Arquitectura:** todo vive en los tests del módulo `ejecutor` más un workflow de GitHub Actions. El
único cambio de producción es mover la regex de la clave de perfil de `Servidor` a `Catalogo`. Dos
helpers de test (`SincroniaPerfiles`, `PlazosDeclarados`) concentran la lógica y se prueban sobre
`@TempDir`; los tests "reales" los aplican a los archivos del repo.

**Stack:** Java 21, Maven 3.9, JUnit 5, Jackson (ya en el `pom.xml`), commons-compress (ya en test),
GitHub Actions (`ubuntu-latest`, Temurin 21), Docker del runner.

**Spec:** `docs/disenos/2026-09-14-f16-perfil-y-ci.md`.

## Restricciones globales

- Rama: `fix/f16-perfil-sincronizado-y-ci`. Nada va a `main` hasta validar la rama.
- Commits con conventional commits, **sin** línea `Co-Authored-By` ni atribución a IA.
- Código, identificadores y comentarios en español sin tildes, como el resto de `ms-sandbox`.
- Rutas de los tests relativas al módulo `ejecutor` (`Path.of("..", "perfiles")`), como `BundlesIT`.
- Comandos Maven desde `ms-sandbox/ejecutor/`.
- `MARGEN_CAPA2_S = 2`, `MARGEN_PLATAFORMA_S = 5`.
- Imagen de producción: `sandbox-runner:2.0.0-capa1` ↔ `../imagenes/java21-junit/Dockerfile`.
- `CLAVE_PERFIL`: `^[a-z0-9-]+@[0-9]+$`.
- Los `*.sh` son LF (`.gitattributes`); no convertirlos.
- En la máquina de desarrollo no hay daemon de Docker corriendo: `BundlesIT` y `ProtocoloIT` se
  saltean en local y se validan en CI (Tarea 7).

## Mapa de archivos

| Archivo | Acción | Responsabilidad |
|---|---|---|
| `ms-sandbox/ejecutor/src/main/java/sandbox/ejecutor/Catalogo.java` | Modificar | Dueño de la regla de la clave: `esClaveValida` |
| `ms-sandbox/ejecutor/src/main/java/sandbox/ejecutor/Servidor.java` | Modificar | Usa `Catalogo.esClaveValida` en vez de su regex privada |
| `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/CatalogoTest.java` | Modificar | Test de `esClaveValida` |
| `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/SincroniaPerfiles.java` | Crear | Verificar y regenerar pares JSON/`.sh` de un directorio |
| `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PerfilesSincroniaTest.java` | Crear | Aplica `SincroniaPerfiles` al repo y la prueba sobre `@TempDir` |
| `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PlazosDeclarados.java` | Crear | Extraer plazos de `.sh`, Dockerfile y `capa1.sh`; márgenes |
| `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PlazosInvarianteTest.java` | Crear | Invariante de plazos sobre el repo y reglas de extracción sobre `@TempDir` |
| `ms-sandbox/perfiles/java21-junit.sh` | Modificar | Comentario de la invariante apunta al test |
| `ms-sandbox/perfiles/java21-junit@4.json` | Regenerar | Campo `script` |
| `ms-sandbox/imagenes/README.md` | Modificar | Fórmula de la invariante |
| `ms-sandbox/ejecutor/src/test/resources/perfiles-it/backstop-peor-caso@1.json` | Crear | Perfil de juguete que ignora `SIGTERM` |
| `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/BundlesIT.java` | Modificar | `plataformaPeorCaso` y el test con carrera |
| `ms-sandbox/ci/verificar-reportes-it.sh` | Crear | Falla si los IT con Docker no corrieron |
| `.github/workflows/ms-sandbox.yml` | Crear | CI |

---

### Tarea 1: Regla compartida de la clave de perfil

**Archivos:**
- Modificar: `ms-sandbox/ejecutor/src/main/java/sandbox/ejecutor/Catalogo.java`
- Modificar: `ms-sandbox/ejecutor/src/main/java/sandbox/ejecutor/Servidor.java:42-43,136`
- Test: `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/CatalogoTest.java`

**Interfaces:**
- Consume: nada.
- Produce: `static boolean Catalogo.esClaveValida(String clave)` (visibilidad de paquete). `null`
  devuelve `false`.

- [ ] **Paso 1: escribir el test que falla**

Agregar al final de `CatalogoTest` (antes de la llave de cierre de la clase):

```java
    /** F-16: la regla de la clave vive en Catalogo y la comparten Servidor y SincroniaPerfiles. */
    @Test
    void esClaveValidaAceptaSoloIdEnMinusculaArrobaVersion() {
        assertTrue(Catalogo.esClaveValida("java21-junit@4"));
        assertTrue(Catalogo.esClaveValida("a@0"));
        assertFalse(Catalogo.esClaveValida(null));
        assertFalse(Catalogo.esClaveValida(""));
        assertFalse(Catalogo.esClaveValida("Java21@4"));
        assertFalse(Catalogo.esClaveValida("no-tiene-arroba"));
        assertFalse(Catalogo.esClaveValida("java21-junit@"));
        assertFalse(Catalogo.esClaveValida("java21_junit@4"));
        assertFalse(Catalogo.esClaveValida("java21-junit@4\n"));
    }
```

`a@0` es válida para la regex; que la versión sea mayor que cero lo exige `SincroniaPerfiles`, no
esta regla (así no cambia el comportamiento de `X-Perfil`).

- [ ] **Paso 2: correr y ver que falla**

Run: `mvn -q test -Dtest=CatalogoTest`
Expected: FALLA de compilación: `cannot find symbol ... esClaveValida`.

- [ ] **Paso 3: implementar en `Catalogo`**

Agregar el import `java.util.regex.Pattern` y, después de `private final Map<String, Perfil> perfiles;`:

```java
    /**
     * Formato de la clave de un perfil: {@code <perfilId>@<version>}. Es a la vez el nombre de su
     * archivo sin {@code .json} y el valor exacto del header X-Perfil. Vive aca porque
     * {@link Perfil#clave()} es quien la arma.
     */
    private static final Pattern CLAVE = Pattern.compile("^[a-z0-9-]+@[0-9]+$");

    static boolean esClaveValida(String clave) {
        return clave != null && CLAVE.matcher(clave).matches();
    }
```

- [ ] **Paso 4: usar la regla en `Servidor`**

Borrar las líneas 42-43:

```java
    /** Formato del header X-Perfil: la misma clave con la que se busca en el catalogo. */
    private static final Pattern CLAVE_PERFIL = Pattern.compile("^[a-z0-9-]+@[0-9]+$");
```

Y reemplazar (línea 136):

```java
        if (perfilClave == null || !CLAVE_PERFIL.matcher(perfilClave).matches()) {
```

por:

```java
        if (!Catalogo.esClaveValida(perfilClave)) {
```

El import `java.util.regex.Pattern` de `Servidor` se queda: lo usa `UUID_CANONICO`.

- [ ] **Paso 5: correr y ver que pasa**

Run: `mvn -q test -Dtest=CatalogoTest,ServidorTest`
Expected: PASS. `ServidorTest.sinPerfilValidoEs400` y `perfilDesconocidoEs422` siguen en verde.

- [ ] **Paso 6: comprobar que no queda ninguna regex de clave en `Servidor`**

Run: `grep -n "CLAVE_PERFIL\|a-z0-9-" ejecutor/src/main/java/sandbox/ejecutor/Servidor.java` (desde `ms-sandbox/`)
Expected: sin salida.

- [ ] **Paso 7: commit**

```bash
git add ms-sandbox/ejecutor/src/main/java/sandbox/ejecutor/Catalogo.java \
        ms-sandbox/ejecutor/src/main/java/sandbox/ejecutor/Servidor.java \
        ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/CatalogoTest.java
git commit -m "refactor(ejecutor): mover la regla de la clave de perfil a Catalogo"
```

---

### Tarea 2: Sincronía entre perfiles y scripts

**Archivos:**
- Crear: `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/SincroniaPerfiles.java`
- Crear: `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PerfilesSincroniaTest.java`

**Interfaces:**
- Consume: `Catalogo.esClaveValida(String)` (Tarea 1).
- Produce:
  - `static List<String> SincroniaPerfiles.revisar(Path directorio, boolean regenerar) throws IOException`:
    devuelve los problemas encontrados (vacía = todo bien). Con `regenerar=true`, reescribe los JSON
    cuyo `script` difiere del `.sh` y no los reporta como problema si la reescritura fue válida.
  - `static final String SincroniaPerfiles.COMANDO_REGENERAR`.

- [ ] **Paso 1: escribir los tests**

Crear `PerfilesSincroniaTest.java`:

```java
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
```

- [ ] **Paso 2: correr y ver que falla**

Run: `mvn -q test -Dtest=PerfilesSincroniaTest`
Expected: FALLA de compilación: `cannot find symbol ... SincroniaPerfiles`.

- [ ] **Paso 3: implementar `SincroniaPerfiles`**

Crear `SincroniaPerfiles.java`:

```java
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

    /** El literal del campo script: la clave, los dos puntos y un string JSON con escapes. */
    private static final Pattern LITERAL_SCRIPT =
            Pattern.compile("\"script\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

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
        Matcher m = LITERAL_SCRIPT.matcher(texto);
        if (!m.find()) {
            return json + ": no se encontro el literal \"script\": \"...\" para regenerar";
        }
        int inicio = m.start(1) - 1;   // la comilla de apertura del valor
        int fin = m.end(1) + 1;        // despues de la comilla de cierre
        if (m.find()) {
            return json + ": hay mas de un literal \"script\"; no se regenera a ciegas";
        }

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
}
```

Nota: si el `.sh` no es UTF-8 válido, `new String(esperado, UTF_8)` pierde bytes y la verificación
posterior lo detecta: no se escribe y se reporta el problema.

- [ ] **Paso 4: correr y ver que pasa**

Run: `mvn -q test -Dtest=PerfilesSincroniaTest`
Expected: PASS (14 tests). El test del repo pasa porque los dos pares están sincronizados hoy.

- [ ] **Paso 5: probar la deriva y la regeneración sobre el repo real**

Desde `ms-sandbox/`:

```bash
cp perfiles/java21-junit.sh /tmp/java21-junit.sh.bak
printf '# deriva de prueba\n' >> perfiles/java21-junit.sh
(cd ejecutor && mvn -q test -Dtest=PerfilesSincroniaTest)
```

Expected: FALLA `losPerfilesDelRepoEstanSincronizadosConSusScripts` con un mensaje que nombra
`java21-junit@4.json` y el comando de regeneración.

```bash
(cd ejecutor && mvn -q test -Dtest=PerfilesSincroniaTest -Dperfiles.regenerar=true)
git diff --stat perfiles/
```

Expected: PASS, y `git diff` muestra cambios **sólo** en `java21-junit.sh` y en la línea `"script"`
de `java21-junit@4.json`.

Si el JSON no cambió, Surefire no reenvió la propiedad: agregar en el `maven-surefire-plugin` de
`ejecutor/pom.xml`, dentro de `<configuration>`:

```xml
          <systemPropertyVariables>
            <perfiles.regenerar>${perfiles.regenerar}</perfiles.regenerar>
          </systemPropertyVariables>
```

y la propiedad por defecto en `<properties>`: `<perfiles.regenerar>false</perfiles.regenerar>`.
Repetir el paso.

Restaurar:

```bash
cp /tmp/java21-junit.sh.bak perfiles/java21-junit.sh
(cd ejecutor && mvn -q test -Dtest=PerfilesSincroniaTest -Dperfiles.regenerar=true)
git status --short perfiles/
```

Expected: `git status` sin cambios en `perfiles/`.

- [ ] **Paso 6: commit**

```bash
git add ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/SincroniaPerfiles.java \
        ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PerfilesSincroniaTest.java
# y ms-sandbox/ejecutor/pom.xml solo si hizo falta en el paso 5
git commit -m "test(ejecutor): verificar que cada perfil sea su script byte a byte"
```

---

### Tarea 3: Invariante de plazos

**Archivos:**
- Crear: `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PlazosDeclarados.java`
- Crear: `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PlazosInvarianteTest.java`
- Modificar: `ms-sandbox/perfiles/java21-junit.sh:38-46`
- Regenerar: `ms-sandbox/perfiles/java21-junit@4.json`
- Modificar: `ms-sandbox/imagenes/README.md:176-189`

**Interfaces:**
- Consume: `Constantes.TIMEOUT_EJECUCION_MS`; `SincroniaPerfiles.COMANDO_REGENERAR` (sólo como comando).
- Produce (todo `static`, visibilidad de paquete, en `PlazosDeclarados`):
  - `int MARGEN_CAPA2_S = 2`, `int MARGEN_PLATAFORMA_S = 5`
  - `Path PERFILES`, `Path CAPA1`, `Map<String, Path> DOCKERFILES`
  - `List<Path> perfilesDeProduccion() throws IOException`
  - `String imagenDe(Path perfilJson) throws IOException`
  - `Path scriptDe(Path perfilJson) throws IOException`
  - `Path dockerfileDe(String imagen)`
  - `int presupuestoCapa2(Path script) throws IOException`
  - `int asignacion(Path script, String nombre) throws IOException`
  - `int evalTimeoutDeDockerfile(Path dockerfile) throws IOException`
  - `int evalTimeoutPorDefectoDeCapa1(Path capa1) throws IOException`
  - `int graciaDeCapa1(Path capa1) throws IOException`
  - `long relojEjecutorS()`

  Los errores de extracción se lanzan como `AssertionError` con archivo y líneas: en un test son un
  fallo, no un error.

- [ ] **Paso 1: escribir los tests**

Crear `PlazosInvarianteTest.java`:

```java
package sandbox.ejecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-16 (spec 2026-09-14-f16-perfil-y-ci.md, 4.2): la invariante de plazos que antes "se sostenia a
 * mano". Los valores salen siempre de los archivos reales, via {@link PlazosDeclarados}.
 */
class PlazosInvarianteTest {

    // ------------------------------------------------------------------
    // El repo
    // ------------------------------------------------------------------

    @Test
    void cadaPerfilCabeEnElBackstopDeSuImagen() throws IOException {
        List<Path> perfiles = PlazosDeclarados.perfilesDeProduccion();
        assertFalse(perfiles.isEmpty(), "no hay perfiles en " + PlazosDeclarados.PERFILES.toAbsolutePath());

        for (Path json : perfiles) {
            Path script = PlazosDeclarados.scriptDe(json);
            int presupuesto = PlazosDeclarados.presupuestoCapa2(script);
            Path dockerfile = PlazosDeclarados.dockerfileDe(PlazosDeclarados.imagenDe(json));
            int eval = PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile);

            assertTrue(presupuesto + PlazosDeclarados.MARGEN_CAPA2_S <= eval,
                    json.getFileName() + ": suma de plazos " + presupuesto + " s + margen "
                            + PlazosDeclarados.MARGEN_CAPA2_S + " s no cabe en SANDBOX_EVAL_TIMEOUT_S="
                            + eval + " s de " + dockerfile);
        }
    }

    @Test
    void backstopMasGraciaMasPlataformaCabeEnElRelojDelEjecutor() throws IOException {
        int gracia = PlazosDeclarados.graciaDeCapa1(PlazosDeclarados.CAPA1);
        long reloj = PlazosDeclarados.relojEjecutorS();

        for (Path dockerfile : PlazosDeclarados.DOCKERFILES.values()) {
            int eval = PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile);
            assertTrue(eval + gracia + PlazosDeclarados.MARGEN_PLATAFORMA_S <= reloj,
                    dockerfile + ": backstop " + eval + " s + gracia " + gracia + " s + plataforma "
                            + PlazosDeclarados.MARGEN_PLATAFORMA_S + " s supera el reloj del ejecutor ("
                            + reloj + " s)");
        }
    }

    @Test
    void elValorPorDefectoDeCapa1CoincideConCadaDockerfile() throws IOException {
        int porDefecto = PlazosDeclarados.evalTimeoutPorDefectoDeCapa1(PlazosDeclarados.CAPA1);
        for (Path dockerfile : PlazosDeclarados.DOCKERFILES.values()) {
            assertEquals(porDefecto, PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile),
                    "el default de capa1.sh no coincide con el ENV de " + dockerfile);
        }
    }

    // ------------------------------------------------------------------
    // Las reglas de extraccion, sobre @TempDir
    // ------------------------------------------------------------------

    @Test
    void sumaTodasLasInvocacionesReales(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                A_S=8
                B_S=25
                timeout --signal=KILL "${A_S}s" javac uno
                timeout --signal=KILL "${A_S}s" javac dos
                timeout --signal=KILL "${A_S}s" javac tres
                  timeout --signal=KILL "${B_S}s" \\
                    java -jar x
                """);
        assertEquals(8 * 3 + 25, PlazosDeclarados.presupuestoCapa2(script));
    }

    @Test
    void losComentariosNoCuentan(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                # A_S=99
                #   b) `timeout` de pared, para el que duerme
                A_S=8
                timeout "${A_S}s" javac
                """);
        assertEquals(8, PlazosDeclarados.presupuestoCapa2(script));
    }

    @Test
    void asignacionDuplicadaFalla(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                A_S=25
                timeout "${A_S}s" java
                A_S=5
                """);
        AssertionError e = assertThrows(AssertionError.class, () -> PlazosDeclarados.presupuestoCapa2(script));
        assertTrue(e.getMessage().contains("A_S"), e.getMessage());
    }

    @Test
    void reasignacionNoNumericaTambienCuentaComoDuplicado(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                A_S=25
                export A_S=$((A_S * 2))
                timeout "${A_S}s" java
                """);
        assertThrows(AssertionError.class, () -> PlazosDeclarados.presupuestoCapa2(script));
    }

    @Test
    void timeoutConLiteralFalla(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", """
                A_S=8
                timeout "${A_S}s" javac
                timeout 30s java
                """);
        AssertionError e = assertThrows(AssertionError.class, () -> PlazosDeclarados.presupuestoCapa2(script));
        assertTrue(e.getMessage().contains("timeout 30s"), e.getMessage());
    }

    @Test
    void scriptSinTimeoutFalla(@TempDir Path dir) throws IOException {
        Path script = escribir(dir, "p.sh", "echo nada\n");
        assertThrows(AssertionError.class, () -> PlazosDeclarados.presupuestoCapa2(script));
    }

    @Test
    void envDuplicadoEnDockerfileFalla(@TempDir Path dir) throws IOException {
        Path dockerfile = escribir(dir, "Dockerfile", """
                ENV SANDBOX_EVAL_TIMEOUT_S=45
                ENV SANDBOX_EVAL_TIMEOUT_S=90
                """);
        assertThrows(AssertionError.class, () -> PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile));
    }

    @Test
    void extraeElEnvDeUnaListaMultilinea(@TempDir Path dir) throws IOException {
        Path dockerfile = escribir(dir, "Dockerfile", """
                # SANDBOX_EVAL_TIMEOUT_S es el backstop
                ENV SANDBOX_LIBS=/libs \\
                    SANDBOX_EVAL_TIMEOUT_S=45 \\
                    HOME=/work
                """);
        assertEquals(45, PlazosDeclarados.evalTimeoutDeDockerfile(dockerfile));
    }

    @Test
    void extraeDefaultYGraciaDeCapa1(@TempDir Path dir) throws IOException {
        Path capa1 = escribir(dir, "capa1.sh", """
                EVAL_TIMEOUT_S="${SANDBOX_EVAL_TIMEOUT_S:-45}"
                # El `timeout` es un backstop
                  timeout -k 5s "${EVAL_TIMEOUT_S}s" sh -c "$SCRIPT" </dev/null
                """);
        assertEquals(45, PlazosDeclarados.evalTimeoutPorDefectoDeCapa1(capa1));
        assertEquals(5, PlazosDeclarados.graciaDeCapa1(capa1));
    }

    @Test
    void graciaDuplicadaFalla(@TempDir Path dir) throws IOException {
        Path capa1 = escribir(dir, "capa1.sh", """
                timeout -k 5s "${EVAL_TIMEOUT_S}s" sh -c uno
                timeout -k 1s "${EVAL_TIMEOUT_S}s" sh -c dos
                """);
        assertThrows(AssertionError.class, () -> PlazosDeclarados.graciaDeCapa1(capa1));
    }

    @Test
    void imagenSinDockerfileEnLaTablaFalla() {
        assertThrows(AssertionError.class, () -> PlazosDeclarados.dockerfileDe("sandbox-runner:9.9.9"));
    }

    private static Path escribir(Path dir, String nombre, String contenido) throws IOException {
        Path archivo = dir.resolve(nombre);
        Files.writeString(archivo, contenido, StandardCharsets.UTF_8);
        return archivo;
    }
}
```

- [ ] **Paso 2: correr y ver que falla**

Run: `mvn -q test -Dtest=PlazosInvarianteTest`
Expected: FALLA de compilación: `cannot find symbol ... PlazosDeclarados`.

- [ ] **Paso 3: implementar `PlazosDeclarados`**

Crear `PlazosDeclarados.java`:

```java
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
```

- [ ] **Paso 4: correr y ver que pasa**

Run: `mvn -q test -Dtest=PlazosInvarianteTest`
Expected: PASS (15 tests). Con los valores actuales: `41 + 2 = 43 ≤ 45` y `45 + 5 + 5 = 55 ≤ 60`.

Si `cadaPerfilCabeEnElBackstopDeSuImagen` falla con "invocacion de timeout sin plazo", revisar la
línea que nombra: toda línea activa del `.sh` que contenga la palabra `timeout` cuenta como
invocación. Hoy sólo las líneas 89, 115 y 161 la contienen fuera de comentarios.

- [ ] **Paso 5: probar los criterios de aceptación sobre los archivos reales**

Desde `ms-sandbox/`, cada bloque se revierte con `git checkout` antes del siguiente:

```bash
sed -i 's/^TIMEOUT_TESTS_S=25 /TIMEOUT_TESTS_S=30 /' perfiles/java21-junit.sh
(cd ejecutor && mvn -q test -Dtest=PlazosInvarianteTest#cadaPerfilCabeEnElBackstopDeSuImagen)
git checkout perfiles/java21-junit.sh
```
Expected: FALLA (`suma de plazos 46 s + margen 2 s no cabe en ... 45 s`).

```bash
sed -i 's/SANDBOX_EVAL_TIMEOUT_S=45/SANDBOX_EVAL_TIMEOUT_S=52/' imagenes/java21-junit/Dockerfile
(cd ejecutor && mvn -q test -Dtest=PlazosInvarianteTest)
git checkout imagenes/java21-junit/Dockerfile
```
Expected: FALLAN `backstopMasGraciaMasPlataformaCabeEnElRelojDelEjecutor` (`52 + 5 + 5 = 62 > 60`) y
`elValorPorDefectoDeCapa1CoincideConCadaDockerfile`.

```bash
printf 'TIMEOUT_TESTS_S=5\n' >> perfiles/java21-junit.sh
(cd ejecutor && mvn -q test -Dtest=PlazosInvarianteTest#cadaPerfilCabeEnElBackstopDeSuImagen)
git checkout perfiles/java21-junit.sh
```
Expected: FALLA por "exactamente una linea activa con la asignacion de TIMEOUT_TESTS_S".

- [ ] **Paso 6: actualizar el comentario de la invariante en el `.sh`**

En `ms-sandbox/perfiles/java21-junit.sh`, reemplazar las líneas 41-46:

```sh
# INVARIANTE (nadie la valida, se sostiene a mano): las fases corren en serie
# adentro del backstop de la capa 1, asi que
#     2 * TIMEOUT_COMPILE_S + TIMEOUT_TESTS_S + margen <= SANDBOX_EVAL_TIMEOUT_S (45)
#     8 + 8 + 25 = 41, margen 4 s.
# Si no se cumple, una entrega lenta pero legitima muere por el backstop de la
# capa 1 (TIMEOUT_PARED, 27) antes de que esta capa pueda emitir 42 o 45.
```

por:

```sh
# INVARIANTE (la valida PlazosInvarianteTest, en ejecutor/): las fases corren
# en serie adentro del backstop de la capa 1, asi que la SUMA de los plazos de
# todas las invocaciones de `timeout` de este script, mas 2 s de margen, tiene
# que caber en SANDBOX_EVAL_TIMEOUT_S (45):
#     8 + 8 + 25 = 41, + 2 = 43 <= 45.
# Cada `timeout` usa la forma "${NOMBRE}s" y cada NOMBRE se asigna una sola
# vez: un plazo literal o una reasignacion hacen fallar el test.
# Si no se cumple, una entrega lenta pero legitima muere por el backstop de la
# capa 1 (TIMEOUT_PARED, 27) antes de que esta capa pueda emitir 42 o 45.
```

- [ ] **Paso 7: regenerar el JSON y verificar**

```bash
(cd ejecutor && mvn -q test -Dtest=PerfilesSincroniaTest -Dperfiles.regenerar=true)
(cd ejecutor && mvn -q test -Dtest=PerfilesSincroniaTest,PlazosInvarianteTest)
git diff --stat
```

Expected: PASS. `git diff --stat` muestra `perfiles/java21-junit.sh` y `perfiles/java21-junit@4.json`.

- [ ] **Paso 8: actualizar la fórmula de `imagenes/README.md`**

Reemplazar el bloque de las líneas 176-189 (desde `**Invariante que hoy se sostiene a mano.**`
hasta `...que viajan adentro del guion opaco.`) por:

````markdown
**Invariante validada por `PlazosInvarianteTest`.** Las fases de la capa 2 corren en serie adentro
del backstop de la capa 1, así que su peor caso tiene que caber con margen. El test lee los valores
de los archivos reales (script del perfil, `Dockerfile` de la imagen, `capa1.sh` y `Constantes`):

```
Σ plazos de cada `timeout` del script + MARGEN_CAPA2_S (2 s)       ≤  SANDBOX_EVAL_TIMEOUT_S
        8 + 8 + 25 = 41 s  + 2 s = 43 s                            ≤  45 s
SANDBOX_EVAL_TIMEOUT_S + gracia (-k 5s) + MARGEN_PLATAFORMA_S (5 s) ≤  60 s del ejecutor
        45 + 5 + 5 = 55 s                                          ≤  60 s
```

Si no se cumple, una entrega lenta pero legítima muere por el backstop de la capa 1
(`TIMEOUT_PARED`, 27) antes de que la capa 2 pueda emitir su código preciso (42 o 45), y se pierde
el diagnóstico. `java21-junit@3` la violaba (20 + 20 + 30 = 70 s) y se retiró por `@4`. El ejecutor
en ejecución no la ve (los relojes viajan adentro del guion opaco): la valida el test en el build.
`MARGEN_PLATAFORMA_S` lo mide `BundlesIT#plataformaPeorCaso` en CI.
````

- [ ] **Paso 9: commit**

```bash
git add ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PlazosDeclarados.java \
        ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/PlazosInvarianteTest.java \
        ms-sandbox/perfiles/java21-junit.sh ms-sandbox/perfiles/java21-junit@4.json \
        ms-sandbox/imagenes/README.md
git commit -m "test(ejecutor): validar la invariante de plazos contra los archivos reales"
```

---

### Tarea 4: Medición del peor caso de plataforma

**Archivos:**
- Crear: `ms-sandbox/ejecutor/src/test/resources/perfiles-it/backstop-peor-caso@1.json`
- Modificar: `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/BundlesIT.java`

**Interfaces:**
- Consume: `PlazosDeclarados.evalTimeoutDeDockerfile`, `dockerfileDe`, `graciaDeCapa1`, `CAPA1`,
  `MARGEN_PLATAFORMA_S` (Tarea 3). En `BundlesIT`: `ejecucionFraming`, `tarDeBundle`, `BUNDLES`,
  `campoTexto`, `campoEntero` (ya existen).
- Produce: nada que usen otras tareas.

- [ ] **Paso 1: crear el perfil de juguete**

`ms-sandbox/ejecutor/src/test/resources/perfiles-it/backstop-peor-caso@1.json`:

```json
{
  "perfilId": "backstop-peor-caso",
  "version": 1,
  "imagen": "sandbox-runner:2.0.0-capa1",
  "script": "#!/bin/sh\n#\n# backstop-peor-caso@1 -- guion de juguete de BundlesIT.plataformaPeorCaso (F-16).\n#\n# Ignora SIGTERM y no termina nunca: obliga a la capa 1 a agotar el backstop\n# (SANDBOX_EVAL_TIMEOUT_S) Y la gracia de `timeout -k` hasta el SIGKILL. Duerme\n# en vez de quemar CPU para que no lo corte antes el limite de CPU del perfil.\n# `trap '' TERM` se hereda: los `sleep` hijos tambien ignoran SIGTERM.\ntrap '' TERM\nwhile :; do sleep 1; done\n",
  "reportFormat": "texto-libre",
  "limites": {
    "memoriaMb": 256,
    "cpuS": 10
  }
}
```

Ese directorio lo cargan `BundlesIT` y `ProtocoloIT` enteros; un perfil más no cambia sus tests
(buscan claves concretas). `PerfilesSincroniaTest` no lo recorre.

- [ ] **Paso 2: comprobar que el catálogo de prueba sigue cargando**

Run: `mvn -q test -Dtest=CatalogoTest,PerfilesSincroniaTest`
Expected: PASS. (La carga real de `perfiles-it` ocurre en `BundlesIT.levantar`, que en local se
saltea sin Docker; la validación de `Catalogo` sobre este JSON se ejerce en CI.)

- [ ] **Paso 3: escribir el test**

En `BundlesIT.java`, agregar después de `p4b_elPrimerByteDelTarNoSePierdeEnLaCostura` y antes del
bloque `// 3) Los nueve bundles`:

```java
    // ------------------------------------------------------------------
    // 2b) El peor caso de plataforma (F-16, spec 4.3)
    // ------------------------------------------------------------------

    /**
     * Mide lo que el reloj de 60 s del ejecutor gasta fuera del backstop de la capa 1 en el peor caso:
     * un guion que ignora SIGTERM obliga a agotar backstop + gracia, y el bundle mas grande maximiza
     * transferencia y extraccion. La medicion es desde el test, asi que tambien incluye crear y
     * arrancar el contenedor, que quedan FUERA del reloj real: sobreestima, lo que es seguro para
     * validar la cota {@link PlazosDeclarados#MARGEN_PLATAFORMA_S}.
     *
     * <p>Se aceptan TIMEOUT_PARED (27) y MUERTO_POR_SENAL (31): cuando la gracia de {@code timeout -k}
     * llega al SIGKILL, GNU timeout sale con 137 y no con 124, y capa1.sh clasifica 137 como
     * MUERTO_POR_SENAL. Si la CI muestra 31, esa clasificacion se corrige en el sub-proyecto de
     * capa1.sh, no aca.
     */
    @Test
    @Timeout(120)
    void plataformaPeorCaso() throws IOException {
        byte[] tar = tarDelBundleMasGrande();
        int eval = PlazosDeclarados.evalTimeoutDeDockerfile(
                PlazosDeclarados.dockerfileDe("sandbox-runner:2.0.0-capa1"));
        int gracia = PlazosDeclarados.graciaDeCapa1(PlazosDeclarados.CAPA1);

        long inicio = System.nanoTime();
        var salida = ejecucionFraming.ejecutar(UUID.randomUUID().toString(), tar, "backstop-peor-caso@1");
        long medidoMs = (System.nanoTime() - inicio) / 1_000_000;
        long plataformaMs = medidoMs - (eval + gracia) * 1000L;

        String resultado = salida.reporte() == null ? null : campoTexto(salida.reporte(), "resultado");
        System.out.println("plataforma_peor_caso_ms=" + plataformaMs + " medido_ms=" + medidoMs
                + " duracion_ejecutor_ms=" + salida.duracionMs() + " tar_bytes=" + tar.length
                + " resultado=" + resultado + " exit=" + salida.exitCode());

        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado(),
                "salto el reloj del ejecutor; stderr: " + salida.stderr());
        assertFalse(salida.reporteAusente(), "no vino el sobre; stdout: " + salida.stdout());
        assertTrue(List.of("TIMEOUT_PARED", "MUERTO_POR_SENAL").contains(resultado),
                "resultado inesperado: " + resultado + "; sobre: " + salida.reporte());
        assertEquals(0, campoEntero(salida.reporte(), "procesosSobrevivientes"),
                "sobre: " + salida.reporte());
        assertTrue(plataformaMs <= PlazosDeclarados.MARGEN_PLATAFORMA_S * 1000L,
                "la plataforma gasto " + plataformaMs + " ms fuera del backstop; la cota es "
                        + PlazosDeclarados.MARGEN_PLATAFORMA_S + " s");
    }
```

Y en la sección `// Helpers`, después de `rutaBundle`:

```java
    /** El bundle de pruebas/bundles/ que da el tar mas grande. */
    private static byte[] tarDelBundleMasGrande() throws IOException {
        byte[] mayor = null;
        try (Stream<Path> flujo = Files.list(BUNDLES)) {
            for (Path dir : flujo.filter(Files::isDirectory).sorted().toList()) {
                byte[] tar = tarDeBundle(dir);
                if (mayor == null || tar.length > mayor.length) mayor = tar;
            }
        }
        assertNotNull(mayor, "no hay bundles en " + BUNDLES.toAbsolutePath());
        return mayor;
    }
```

Todos los imports necesarios (`Stream`, `Files`, `List`, `UUID`) ya están en `BundlesIT`.

- [ ] **Paso 4: compilar y verificar el salteo local**

Run: `mvn -q test -Dtest=BundlesIT#plataformaPeorCaso`
Expected: compila y el test queda **salteado** ("no hay daemon de Docker escuchando") en la máquina
local. La ejecución real se valida en CI (Tarea 7).

- [ ] **Paso 5: commit**

```bash
git add ms-sandbox/ejecutor/src/test/resources/perfiles-it/backstop-peor-caso@1.json \
        ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/BundlesIT.java
git commit -m "test(ejecutor): medir el peor caso de plataforma contra la imagen real"
```

---

### Tarea 5: Test con carrera

**Archivos:**
- Modificar: `ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/BundlesIT.java` (`p4c_hostilReporteLoop`)

**Interfaces:** ninguna.

- [ ] **Paso 1: aceptar `exitEval` 0 o 47**

Reemplazar el método:

```java
    /** Igual que hostil-reporte pero en loop -> mismo resultado, tarda mas por el reloj de pared. */
    @Test
    @Timeout(150)
    void p4c_hostilReporteLoop() throws IOException {
        Resultado r = correrBundle("hostil-reporte-loop");
        assertEquals(30, r.exitCode, r.diagnostico());
        assertEquals("VEREDICTO_NO_CONFIABLE", r.resultado, r.diagnostico());
        assertEquals(0, r.exitEval, r.diagnostico());
        assertEquals(6, r.surv, r.diagnostico());
    }
```

por:

```java
    /**
     * Igual que hostil-reporte pero en loop -> mismo resultado, tarda mas por el reloj de pared.
     *
     * <p>exitEval puede ser 0 o 47: la carga hostil reescribe el buzon en loop y compite con la guarda
     * de tests=0 de la capa 2 (codigo 47). Cual gana depende del scheduling. Lo que detecta el ataque
     * no depende de esa carrera: VEREDICTO_NO_CONFIABLE, exit 30 y los 6 sobrevivientes.
     */
    @Test
    @Timeout(150)
    void p4c_hostilReporteLoop() throws IOException {
        Resultado r = correrBundle("hostil-reporte-loop");
        assertEquals(30, r.exitCode, r.diagnostico());
        assertEquals("VEREDICTO_NO_CONFIABLE", r.resultado, r.diagnostico());
        assertTrue(r.exitEval != null && (r.exitEval == 0 || r.exitEval == 47), r.diagnostico());
        assertEquals(6, r.surv, r.diagnostico());
    }
```

- [ ] **Paso 2: compilar**

Run: `mvn -q test-compile`
Expected: BUILD SUCCESS.

- [ ] **Paso 3: commit**

```bash
git add ms-sandbox/ejecutor/src/test/java/sandbox/ejecutor/BundlesIT.java
git commit -m "test(ejecutor): aceptar exitEval 47 en la carrera de hostil-reporte-loop"
```

---

### Tarea 6: CI

**Archivos:**
- Crear: `ms-sandbox/ci/verificar-reportes-it.sh`
- Crear: `.github/workflows/ms-sandbox.yml`

**Interfaces:**
- Consume: los reportes de Surefire en `ms-sandbox/ejecutor/target/surefire-reports/`, con nombres
  `TEST-sandbox.ejecutor.<Clase>.xml`.
- Produce: `sh ms-sandbox/ci/verificar-reportes-it.sh <dir-de-reportes>`; sale con 0 sólo si
  `BundlesIT` y `ProtocoloIT` corrieron con `tests > 0` y ningún reporte tiene skips.

- [ ] **Paso 1: crear el chequeo de reportes**

`ms-sandbox/ci/verificar-reportes-it.sh`:

```sh
#!/bin/sh
#
# verificar-reportes-it.sh -- F-16 (spec 2026-09-14-f16-perfil-y-ci.md, 4.4).
#
# BundlesIT y ProtocoloIT se saltean solos (assumptions) si no hay daemon de
# Docker, y Surefire cuenta eso como skipped, no como fallo. En CI eso dejaria
# la suite adversaria sin correr y el job en verde. Este chequeo lo impide:
#   1. tienen que existir los reportes de BundlesIT y ProtocoloIT, con tests > 0;
#   2. ningun reporte del modulo puede tener skipped distinto de 0.
#
# Vive en la CI y no en los tests para que en local se pueda seguir corriendo
# `mvn verify` sin Docker.
#
# Uso: sh verificar-reportes-it.sh <directorio de surefire-reports>

set -eu

dir=${1:?falta el directorio de reportes}
fallo=0

atributo() {
  # $1 = atributo, $2 = archivo. Lee el atributo del elemento <testsuite>.
  sed -n "s/.*<testsuite[^>]* $1=\"\([0-9]*\)\".*/\1/p" "$2" | head -n 1
}

for it in BundlesIT ProtocoloIT; do
  reporte="$dir/TEST-sandbox.ejecutor.$it.xml"
  if [ ! -f "$reporte" ]; then
    echo "FALLA: no hay reporte de $it ($reporte)"
    fallo=1
    continue
  fi
  tests=$(atributo tests "$reporte")
  if [ -z "$tests" ] || [ "$tests" -eq 0 ]; then
    echo "FALLA: $it no corrio ningun test"
    fallo=1
  fi
done

hay_reportes=0
for reporte in "$dir"/TEST-*.xml; do
  [ -f "$reporte" ] || continue
  hay_reportes=1
  skipped=$(atributo skipped "$reporte")
  if [ "${skipped:-desconocido}" != "0" ]; then
    echo "FALLA: skipped=${skipped:-desconocido} en $reporte"
    grep -o '<skipped[^>]*>' "$reporte" | head -n 5 || true
    fallo=1
  fi
done

if [ "$hay_reportes" -eq 0 ]; then
  echo "FALLA: no hay reportes TEST-*.xml en $dir"
  fallo=1
fi

if [ "$fallo" -eq 0 ]; then
  echo "OK: BundlesIT y ProtocoloIT corrieron contra Docker y no hubo tests salteados"
fi
exit "$fallo"
```

- [ ] **Paso 2: probar el chequeo en local, donde tiene que FALLAR**

Desde `ms-sandbox/ejecutor/` (sin Docker, los IT se saltean):

```bash
mvn -q verify
sh ../ci/verificar-reportes-it.sh target/surefire-reports; echo "rc=$?"
```

Expected: imprime `FALLA: skipped=... en .../TEST-sandbox.ejecutor.BundlesIT.xml` (y lo mismo para
`ProtocoloIT`) y `rc=1`. Esto prueba que el job no puede quedar verde sin Docker.

Si en cambio imprime `FALLA: BundlesIT no corrio ningun test` sin la línea de skipped, revisar el
reporte: con `assumeTrue` en `@BeforeEach`, Surefire registra cada test como skipped y `tests` sigue
contándolos. Cualquiera de los dos mensajes deja `rc=1`, que es lo que importa.

- [ ] **Paso 3: crear el workflow**

`.github/workflows/ms-sandbox.yml`:

```yaml
name: ms-sandbox

on:
  push:
    paths:
      - "ms-sandbox/**"
      - ".gitattributes"
      - ".github/workflows/ms-sandbox.yml"
  pull_request:
    paths:
      - "ms-sandbox/**"
      - ".gitattributes"
      - ".github/workflows/ms-sandbox.yml"

permissions:
  contents: read

jobs:
  worker:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    defaults:
      run:
        working-directory: ms-sandbox/worker
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      # D16IT se saltea sola: necesita un ejecutor levantado. Es el unico skip esperado del repo.
      - name: Tests del worker
        run: mvn -B verify

  ejecutor:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    defaults:
      run:
        working-directory: ms-sandbox
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - name: Imagen real de dos capas (sandbox-runner:2.0.0-capa1)
        run: docker build -f imagenes/java21-junit/Dockerfile -t sandbox-runner:2.0.0-capa1 imagenes
      - name: Fixture de ProtocoloIT (sandbox-runner:1.0.0)
        run: docker build -t sandbox-runner:1.0.0 ejecutor/src/test/fixtures/a6
      - name: Tests del ejecutor, incluida la suite adversaria
        working-directory: ms-sandbox/ejecutor
        run: mvn -B verify
      - name: Los tests con Docker corrieron de verdad
        if: success() || failure()
        run: sh ci/verificar-reportes-it.sh ejecutor/target/surefire-reports
```

- [ ] **Paso 4: validar la sintaxis del YAML**

Run (desde la raíz del repo): `node -e "require('fs').readFileSync('.github/workflows/ms-sandbox.yml','utf8').split('\n').forEach((l,i)=>{if(/\t/.test(l))throw new Error('tab en linea '+(i+1))})" && echo sin-tabs`
Expected: `sin-tabs`. La validación semántica la hace GitHub al correr (Tarea 7).

- [ ] **Paso 5: commit**

```bash
git add ms-sandbox/ci/verificar-reportes-it.sh .github/workflows/ms-sandbox.yml
git commit -m "ci: correr los tests del worker y del ejecutor contra Docker real"
```

---

### Tarea 7: Validación en CI

**Archivos:** ninguno, salvo que la CI muestre un problema.

**Interfaces:** ninguna.

- [ ] **Paso 1: correr toda la suite local**

Desde `ms-sandbox/ejecutor/`: `mvn -B verify`. Desde `ms-sandbox/worker/`: `mvn -B verify`.
Expected: BUILD SUCCESS en los dos (los IT se saltean en local).

- [ ] **Paso 2: publicar la rama**

```bash
git push -u origin fix/f16-perfil-sincronizado-y-ci
```

- [ ] **Paso 3: seguir la corrida**

```bash
gh run list --branch fix/f16-perfil-sincronizado-y-ci --workflow ms-sandbox.yml --limit 1
gh run watch <id> --exit-status
```

Expected: los jobs `worker` y `ejecutor` en verde.

- [ ] **Paso 4: verificar los criterios de aceptación en el log**

```bash
gh run view <id> --log | grep -E "plataforma_peor_caso_ms|OK: BundlesIT|Tests run:.*BundlesIT|Tests run:.*ProtocoloIT"
```

Expected:
- `OK: BundlesIT y ProtocoloIT corrieron contra Docker y no hubo tests salteados`.
- Una línea `plataforma_peor_caso_ms=<n> ... resultado=<TIMEOUT_PARED|MUERTO_POR_SENAL>` con `n ≤ 5000`.
- `Tests run` de `BundlesIT` y `ProtocoloIT` con `Skipped: 0`.

Anotar `n` y el `resultado`. Si es `MUERTO_POR_SENAL`, queda registrado como hallazgo para el
sub-proyecto de `capa1.sh`.

- [ ] **Paso 5: si la CI falla**

- `plataformaPeorCaso` con `n > 5000`: **no** tocar el test. Reportar el valor medido y decidir con
  el usuario si se sube `MARGEN_PLATAFORMA_S` (hay 10 s de holgura) o se bajan plazos.
- `plataformaPeorCaso` con `procesosSobrevivientes > 0` o `VEREDICTO_NO_CONFIABLE`: los `sleep` del
  guion de juguete sobrevivieron al SIGKILL. Reportar antes de cambiar el guion.
- Otro test de `BundlesIT` falla: comparar con la tabla de la §6 del handoff citada en la clase y
  reportar; no ajustar asserts para que pase (criterio ya escrito en la propia clase).

- [ ] **Paso 6: comprobar que la CI no queda verde sin Docker (criterio de la spec)**

La prueba local del Paso 2 de la Tarea 6 ya lo demuestra con el mismo script. No hace falta romper la
CI a propósito. El disparo por `.gitattributes` queda cubierto por los `paths` del workflow; se
comprueba cuando un PR lo toque.

- [ ] **Paso 7: avisar al usuario**

Informar el resultado de la corrida (enlace, `plataforma_peor_caso_ms`, resultado de la capa 1) y
esperar su validación antes de abrir el PR a `main`.
