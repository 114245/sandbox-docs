package sandbox.ejecutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A35 y A37 (C7): la clase de tests del catalogo que la spec (13.7-13.8) marca como el hueco mas
 * grande que dejo C7. Hasta este archivo, las cinco validaciones de R4.5 y el calculo de
 * {@code perfilHash} de R3.6 estaban afirmadas por el documento y sostenidas solo por lectura del
 * codigo.
 *
 * <h2>Donde se afirma el WHEN de A35</h2>
 * {@link Main#main} llama a {@link Catalogo#cargar} en la linea 26, ANTES de conectar a Docker
 * ({@code Docker.conectar}, linea 32), antes de construir {@link Servidor} (linea 35) y, sobre
 * todo, antes de {@code servidor.atender()} (linea 54) -- el metodo que recien ahi empieza a
 * aceptar conexiones. {@code Catalogo} no expone otro seam de proceso completo (no hay forma de
 * levantar {@code Main} en un test sin arrancar Docker de verdad), asi que estos tests ejercitan
 * directamente {@link Catalogo#cargar}, que es el punto exacto de la secuencia de arranque en el
 * que la spec pone la falla: si {@code cargar} tira, la excepcion se propaga fuera de
 * {@code main} y el proceso nunca llega a {@code atender()} -- ninguna request pudo haber tocado
 * jamas un perfil rechazado, porque el socket nunca se abrio.
 *
 * <h2>Dos divergencias spec-vs-codigo confirmadas por estos tests (no se toco produccion)</h2>
 * <ul>
 *   <li><b>R4.1 es inalcanzable con las constantes actuales.</b> Ver
 *   {@link #a35_r4_1EsInalcanzableHoyConLasConstantesActuales()}.</li>
 *   <li><b>Un campo {@code hash} declarado hace fallar la carga entera, no se ignora.</b> Ver
 *   {@link #a37_campoHashDeclaradoHaceFallarLaCargaPorPropiedadDesconocida()}.</li>
 * </ul>
 */
class CatalogoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // Helpers de fixture: construyen perfiles validos en @TempDir, sin tocar
    // src/test/resources: cada test arma exactamente el perfil que necesita.
    // ------------------------------------------------------------------

    /** Un mapa de perfil valido y completo, modificable por cada test antes de escribirlo. */
    private static Map<String, Object> perfilValido() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("perfilId", "test-perfil");
        m.put("version", 1);
        m.put("imagen", "sandbox-runner:1.0.0");
        m.put("script", "#!/bin/sh\necho hola\n");
        m.put("reportFormat", "junit-xml");
        Map<String, Object> limites = new LinkedHashMap<>();
        limites.put("memoriaMb", 512);
        limites.put("cpuS", 10);
        m.put("limites", limites);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> limitesDe(Map<String, Object> perfil) {
        return (Map<String, Object>) perfil.get("limites");
    }

    private static void escribir(Path dir, String nombreArchivo, Map<String, Object> datos) throws IOException {
        MAPPER.writeValue(dir.resolve(nombreArchivo).toFile(), datos);
    }

    private static void escribirCrudo(Path dir, String nombreArchivo, String contenido) throws IOException {
        Files.writeString(dir.resolve(nombreArchivo), contenido, StandardCharsets.UTF_8);
    }

    /** Guion de exactamente {@code bytes} bytes UTF-8: 'a' es 1 byte, asi que cuenta cae exacto. */
    private static String scriptDeBytes(int bytes) {
        return "a".repeat(bytes);
    }

    private static String claveDe(Map<String, Object> perfil) {
        return perfil.get("perfilId") + "@" + perfil.get("version");
    }

    // ==================================================================
    // A35 (C7, R4.5) -- cada causa de rechazo, una por una, al ARRANCAR.
    // ==================================================================

    /** R4.5: JSON invalido hace fallar cargar(), no una ejecucion. */
    @Test
    void a35_jsonInvalidoFallaElArranque(@TempDir Path dir) throws IOException {
        escribirCrudo(dir, "roto@1.json", "{ esto no es json valido ");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("JSON invalido"), "mensaje inesperado: " + ex.getMessage());
    }

    /** R4.5: memoriaMb > MEMORIA_MAX_MB. Boundary: el techo exacto se acepta, uno mas se rechaza. */
    @Test
    void a35_memoriaMbSobreElTechoFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        limitesDe(perfil).put("memoriaMb", Constantes.MEMORIA_MAX_MB + 1);
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("memoriaMb") && ex.getMessage().contains("supera el techo"),
                "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_memoriaMbEnElTechoExactoSeAcepta(@TempDir Path dir) throws Exception {
        Map<String, Object> perfil = perfilValido();
        limitesDe(perfil).put("memoriaMb", Constantes.MEMORIA_MAX_MB);
        escribir(dir, claveDe(perfil) + ".json", perfil);

        Catalogo catalogo = Catalogo.cargar(dir);
        assertEquals(1, catalogo.cantidad());
        assertEquals(Constantes.MEMORIA_MAX_MB, catalogo.buscar(claveDe(perfil)).memoriaMb());
    }

    /** R4.5: cpuS > CPU_MAX_S. Boundary: el techo exacto se acepta, uno mas se rechaza. */
    @Test
    void a35_cpuSSobreElTechoFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        limitesDe(perfil).put("cpuS", Constantes.CPU_MAX_S + 1);
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        // "supera el techo" y no solo "cpuS": con cpuS = CPU_MAX_S + 1 tambien se viola R4.1, cuyo
        // mensaje nombra cpuS. Sin esto, borrar la validacion del techo dejaria el test en verde.
        assertTrue(ex.getMessage().contains("supera el techo"), "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_cpuSEnElTechoExactoSeAcepta(@TempDir Path dir) throws Exception {
        Map<String, Object> perfil = perfilValido();
        limitesDe(perfil).put("cpuS", Constantes.CPU_MAX_S);
        escribir(dir, claveDe(perfil) + ".json", perfil);

        Catalogo catalogo = Catalogo.cargar(dir);
        assertEquals(1, catalogo.cantidad());
        assertEquals(Constantes.CPU_MAX_S, catalogo.buscar(claveDe(perfil)).cpuS());
    }

    /**
     * R4.5 / R4.1: "violacion de R4.1" es una de las cinco causas que la spec (tabla de §4.4)
     * afirma que hace fallar el arranque. Pero con las constantes actuales el branch de R4.1 en
     * {@code Catalogo.cargarUno} ({@code cpuS >= reloj || cpuS * 2 > reloj}, reloj =
     * {@code TIMEOUT_EJECUCION_MS / 1000} = 60) es INALCANZABLE: el check anterior en el mismo
     * metodo, {@code cpuS > CPU_MAX_S} (30), ya rechaza cualquier perfil con cpuS > 30 ANTES de
     * llegar al de R4.1, y para todo cpuS <= 30, {@code cpuS * 2 <= 60} siempre. No hay ningun
     * valor de cpuS que dispare la excepcion de R4.1 sin haber disparado antes la de CPU_MAX_S.
     *
     * Esto NO es un bug de Catalogo: es exactamente lo que el comentario de Constantes.CPU_MAX_S
     * ya documenta ("coincide a proposito con el limite que ya impone R4.1"). Pero significa que
     * la quinta causa de la tabla de R4.5 hoy es teorica, sostenida solo por esta relacion entre
     * constantes, nunca ejercitada como excepcion propia. Este test PIN-ea el hecho actual (la
     * misma relacion que ya afirmaba {@code SpecTest#a32_ulimitCpuYSuRelacionConElRelojDePared})
     * y documenta por que: no se toco produccion, no se agrego ningun seam nuevo.
     */
    @Test
    void a35_r4_1EsInalcanzableHoyConLasConstantesActuales() {
        long relojSegundos = Constantes.TIMEOUT_EJECUCION_MS / 1000;
        assertTrue(Constantes.CPU_MAX_S * 2 <= relojSegundos,
                "si esto deja de cumplirse, el branch de R4.1 en Catalogo.cargarUno se vuelve "
                        + "alcanzable y hace falta un test que efectivamente dispare esa excepcion, "
                        + "no solo esta relacion entre constantes");
    }

    /** R4.5: script mayor a MAX_SCRIPT_BYTES. Boundary: el tope exacto se acepta, uno mas se rechaza. */
    @Test
    void a35_guionSobreMaxScriptBytesFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        perfil.put("script", scriptDeBytes(Constantes.MAX_SCRIPT_BYTES + 1));
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("MAX_SCRIPT_BYTES"), "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_guionEnElTopeExactoSeAcepta(@TempDir Path dir) throws Exception {
        Map<String, Object> perfil = perfilValido();
        perfil.put("script", scriptDeBytes(Constantes.MAX_SCRIPT_BYTES));
        escribir(dir, claveDe(perfil) + ".json", perfil);

        Catalogo catalogo = Catalogo.cargar(dir);
        assertEquals(1, catalogo.cantidad());
        assertEquals(Constantes.MAX_SCRIPT_BYTES, catalogo.buscar(claveDe(perfil)).script().length);
    }

    /** R4.5, "un catalogo a medias": un solo archivo invalido hace fallar TODO el arranque. */
    @Test
    void a35_unArchivoInvalidoEntreValidosFallaTodoElCatalogo(@TempDir Path dir) throws IOException {
        Map<String, Object> valido1 = perfilValido();
        valido1.put("perfilId", "valido-uno");
        escribir(dir, claveDe(valido1) + ".json", valido1);

        Map<String, Object> valido2 = perfilValido();
        valido2.put("perfilId", "valido-dos");
        escribir(dir, claveDe(valido2) + ".json", valido2);

        Map<String, Object> invalido = perfilValido();
        invalido.put("perfilId", "invalido");
        limitesDe(invalido).put("memoriaMb", Constantes.MEMORIA_MAX_MB + 1);
        escribir(dir, claveDe(invalido) + ".json", invalido);

        assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir),
                "un solo perfil invalido tiene que tirar abajo el catalogo entero, no cargar parcial");
    }

    /** R4.3: solo se leen archivos *.json del directorio; el resto se ignora, invalido o no. */
    @Test
    void a35_soloArchivosJsonSeCargan(@TempDir Path dir) throws Exception {
        Map<String, Object> valido = perfilValido();
        escribir(dir, claveDe(valido) + ".json", valido);
        escribirCrudo(dir, "notas.txt", "esto ni siquiera es json, pero no termina en .json");
        escribirCrudo(dir, "README", "tampoco esto");

        Catalogo catalogo = Catalogo.cargar(dir);
        assertEquals(1, catalogo.cantidad(), "los archivos que no terminan en .json no deberian cargarse ni intentarse parsear");
    }

    // ------------------------------------------------------------------
    // Validaciones de estructura de cargarUno que R4.5 tambien exige, cubiertas barato.
    // ------------------------------------------------------------------

    @Test
    void a35_faltaPerfilIdFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        perfil.remove("perfilId");
        escribir(dir, "sin-perfilId@1.json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("perfilId"), "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_faltaVersionFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        perfil.remove("version");
        escribir(dir, "sin-version.json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("version"), "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_faltaImagenFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        perfil.remove("imagen");
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("imagen"), "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_faltaScriptFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        perfil.remove("script");
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("script"), "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_faltanLimitesFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        perfil.remove("limites");
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("limites"), "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_faltaMemoriaMbDentroDeLimitesFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        limitesDe(perfil).remove("memoriaMb");
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("limites"), "mensaje inesperado: " + ex.getMessage());
    }

    @Test
    void a35_faltaCpuSDentroDeLimitesFallaElArranque(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        limitesDe(perfil).remove("cpuS");
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("limites"), "mensaje inesperado: " + ex.getMessage());
    }

    // ==================================================================
    // A37 (C7, R3.6) -- perfilHash es SHA-256 del guion, calculado al cargar.
    // ==================================================================

    /**
     * R3.6: perfilHash tiene que ser el SHA-256 de los bytes EXACTOS del guion (UTF-8), calculado
     * al cargar. El guion incluye una 'ñ' a proposito: en UTF-8 ocupa 2 bytes pero 1 solo char de
     * Java, asi que si Catalogo hasheara por longitud de String en vez de por bytes, este test lo
     * detectaria. El hash esperado se calcula ACA, independientemente de Catalogo, con el mismo
     * algoritmo (MessageDigest sobre los bytes UTF-8) para no reusar la implementacion bajo prueba.
     */
    @Test
    void a37_perfilHashEsSha256DeLosBytesUtf8DelGuion(@TempDir Path dir) throws Exception {
        String script = "#!/bin/sh\n# comentario con una eñe\necho 'ñandú'\n";
        byte[] bytesEsperados = script.getBytes(StandardCharsets.UTF_8);
        assertNotEquals(script.length(), bytesEsperados.length,
                "el guion de prueba no sirve: tiene que haber al menos un char cuyos bytes UTF-8 no sean 1:1");

        String hashEsperado = sha256Hex(bytesEsperados);

        Map<String, Object> perfil = perfilValido();
        perfil.put("script", script);
        escribir(dir, claveDe(perfil) + ".json", perfil);

        Catalogo catalogo = Catalogo.cargar(dir);
        Catalogo.Perfil cargado = catalogo.buscar(claveDe(perfil));
        assertNotNull(cargado);
        assertEquals(64, cargado.scriptHash().length(), "SHA-256 en hex tiene que ser 64 caracteres");
        assertEquals(hashEsperado, cargado.scriptHash());
    }

    /**
     * R3.6: "un campo hash declarado en el JSON del perfil NO debe leerse". La spec (§4.4, R3.6)
     * es ambigua sobre COMO se cumple eso: podria significar "se ignora silenciosamente" o
     * "el deserializador ni siquiera lo conoce, y un campo extra hace fallar la carga". Con el
     * {@code ObjectMapper} default que usa {@code Catalogo.cargar} (sin
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} deshabilitado) y {@code PerfilJson} siendo un record SIN
     * un componente {@code hash}, la propiedad declarada es DESCONOCIDA para Jackson: la carga
     * entera falla como "JSON invalido", generalizada por R4.5. Este test confirma
     * EMPIRICAMENTE ese comportamiento (no se asumio, se ejecuto). Cualquiera de las dos lecturas
     * de la spec queda satisfecha igual: un {@code hash} declarado JAMAS termina siendo el
     * {@code perfilHash} de la respuesta, porque en este codigo ni siquiera llega a cargar. No se
     * toco produccion para resolver la ambiguedad: se documenta el hecho actual.
     */
    @Test
    void a37_campoHashDeclaradoHaceFallarLaCargaPorPropiedadDesconocida(@TempDir Path dir) throws IOException {
        Map<String, Object> perfil = perfilValido();
        perfil.put("hash", "0000000000000000000000000000000000000000000000000000000000000000");
        escribir(dir, claveDe(perfil) + ".json", perfil);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Catalogo.cargar(dir));
        assertTrue(ex.getMessage().contains("JSON invalido"), "mensaje inesperado: " + ex.getMessage());
    }

    private static String sha256Hex(byte[] datos) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
