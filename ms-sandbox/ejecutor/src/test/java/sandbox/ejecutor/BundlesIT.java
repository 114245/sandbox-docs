package sandbox.ejecutor;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Paso 4 del handoff (Opcion 1): la validacion contra Docker real que {@code ProtocoloIT} no cubre.
 *
 * <p>{@code ProtocoloIT} corre contra {@code sandbox-runner:1.0.0}, que es un fixture de busybox
 * re-etiquetado -- nunca toco la imagen de produccion. Esta clase corre contra
 * {@code sandbox-runner:2.0.0-capa1}, la imagen real de dos capas, con el catalogo de perfiles de
 * produccion en {@code perfiles/} (el perfil {@code java21-junit@4}) para los nueve bundles, y con
 * un perfil de juguete propio ({@code framing-real@1}, en {@code src/test/resources/perfiles-it})
 * para el framing y la prueba de integridad byte a byte, que no necesitan una JVM adentro.
 *
 * <p><b>Corre nativo en Windows</b>, igual que {@code ProtocoloIT}: si no hay daemon escuchando el
 * test se saltea via {@code assumeTrue}.
 *
 * <p><b>Lento a proposito.</b> Tres de los nueve bundles agotan el reloj de pared completo de 60 s
 * ({@code hostil-cpu}, {@code hostil-sleep}, {@code hostil-reporte-loop}), y los seis restantes
 * compilan y corren una JVM real. Correr la clase entera puede superar el limite de una sola
 * invocacion de build; en la sesion que escribio esto se corrio en tandas con
 * {@code mvn -Dtest=BundlesIT#metodo}.
 */
class BundlesIT {

    private static final Path BUNDLES = Path.of("..", "pruebas", "bundles");

    private Docker docker;
    /** Catalogo de produccion: el mismo directorio {@code perfiles/} que usaria el daemon real. */
    private Ejecucion ejecucionProduccion;
    /** Catalogo de prueba: solo el perfil de juguete {@code framing-real@1} de este archivo. */
    private Ejecucion ejecucionFraming;

    @BeforeEach
    void levantar() throws IOException, URISyntaxException {
        Docker candidato;
        try {
            candidato = Docker.conectar(dockerHost());
            assumeTrue(candidato.ping(), "no hay daemon de Docker escuchando; el test se saltea");
        } catch (RuntimeException e) {
            org.junit.jupiter.api.Assumptions.abort("no se pudo abrir el transporte a Docker: " + e);
            return;
        }
        docker = candidato;

        Catalogo catalogoProduccion = Catalogo.cargar(Path.of("..", "perfiles"));
        Catalogo catalogoFraming = Catalogo.cargar(directorioPerfilesDePrueba());
        ejecucionProduccion = new Ejecucion(docker, catalogoProduccion);
        ejecucionFraming = new Ejecucion(docker, catalogoFraming);
    }

    private static Path directorioPerfilesDePrueba() throws URISyntaxException {
        return Path.of(BundlesIT.class.getResource("/perfiles-it").toURI());
    }

    @AfterEach
    void bajar() throws Exception {
        if (docker != null) docker.close();
    }

    private static String dockerHost() {
        String env = System.getenv("DOCKER_HOST");
        if (env != null && !env.isBlank()) return env;
        return System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "npipe:////./pipe/docker_engine"
                : "unix:///var/run/docker.sock";
    }

    // ------------------------------------------------------------------
    // 1) El framing, contra la imagen real (no el fixture de busybox)
    // ------------------------------------------------------------------

    /**
     * Prueba que los tres documentos -- nonce, guion, tar -- llegan enteros a
     * {@code sandbox-runner:2.0.0-capa1}. Usa el bundle {@code ok-suma} pero con el perfil de
     * juguete {@code framing-real@1}: no hace falta compilar nada para probar el framing, y separar
     * esto de los nueve bundles de mas abajo aisla "did the bytes arrive" de "did the evaluation
     * behave correctly".
     */
    @Test
    @Timeout(150)
    void p4a_elFramingLlegaEnteroALaImagenReal() throws IOException {
        byte[] tar = tarDeBundle(rutaBundle("ok-suma"));

        var salida = ejecucionFraming.ejecutar(UUID.randomUUID().toString(), tar, "framing-real@1");

        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado(), "stderr: " + salida.stderr());
        assertEquals(0, salida.exitCode(), "exit code inesperado; stderr: " + salida.stderr());
        assertFalse(salida.reporteAusente(), "no vino el sobre; stdout: " + salida.stdout());

        String reporte = salida.reporte();
        assertEquals("OK", campoTexto(reporte, "resultado"), "sobre: " + reporte);

        Map<String, String> buzon = listarBuzon(decodificarBuzon(reporte));
        String listado = buzon.get("listado.txt");
        assertNotNull(listado, "el perfil de juguete no dejo listado.txt en el buzon: " + buzon.keySet());
        String listadoNormalizado = listado.replace('\\', '/');
        for (String esperado : List.of("src/tp/Solucion.java", "test/tp/SolucionTest.java")) {
            assertTrue(listadoNormalizado.contains(esperado),
                    "falta " + esperado + " en el listado real de $SANDBOX_IN: " + listado);
        }
    }

    // ------------------------------------------------------------------
    // 2) El sobre-consumo, byte a byte
    // ------------------------------------------------------------------

    /**
     * ProtocoloIT#a6 ya prueba el caso general -- el tar completo llega con el tamanio correcto --
     * pero eso lo confirma un fixture que hace su propio conteo de bytes. Este test es mas
     * especifico: arma un tar de un solo archivo cuyo PRIMER byte del archivo entero (offset 0,
     * que en un header ustar es el primer caracter del campo {@code name}) y cuyo primer byte de
     * CONTENIDO son valores conocidos, y verifica los dos por separado despues de que la imagen
     * real los recibio. Si algo en la costura guion-tar (dd, Entrada.armar, docker-java) se comiera
     * un solo byte, el nombre del archivo llegaria corrido o el tar dejaria directamente de
     * parsear -- capa1 lo rechazaria como BUNDLE_INVALIDO (exit 22) antes de que este perfil
     * corriera.
     */
    @Test
    @Timeout(150)
    void p4b_elPrimerByteDelTarNoSePierdeEnLaCostura() throws IOException {
        char primerCaracterDelTar = 'Z';
        char primerCaracterDelContenido = 'M';
        String nombreArchivo = primerCaracterDelTar + "-marca-de-byte-cero.txt";
        byte[] contenido = (primerCaracterDelContenido + "-primer-byte-del-contenido\n")
                .getBytes(StandardCharsets.UTF_8);

        byte[] tar = tarDeUnArchivo(nombreArchivo, contenido);
        assertEquals((byte) primerCaracterDelTar, tar[0],
                "el helper de test no arranco el tar con el caracter esperado en offset 0");

        var salida = ejecucionFraming.ejecutar(UUID.randomUUID().toString(), tar, "framing-real@1");

        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado(), "stderr: " + salida.stderr());
        assertEquals(0, salida.exitCode(),
                "exit code inesperado (¿se comio un byte y el tar quedo BUNDLE_INVALIDO=22?); stderr: "
                        + salida.stderr());
        assertFalse(salida.reporteAusente(), "no vino el sobre; stdout: " + salida.stdout());
        assertEquals("OK", campoTexto(salida.reporte(), "resultado"), "sobre: " + salida.reporte());

        Map<String, String> buzon = listarBuzon(decodificarBuzon(salida.reporte()));

        String primerArchivo = buzon.get("primer-archivo.txt");
        assertNotNull(primerArchivo, "falta primer-archivo.txt en el buzon: " + buzon.keySet());
        assertTrue(primerArchivo.replace('\\', '/').endsWith("/" + nombreArchivo),
                "el nombre del primer archivo del tar llego alterado -- se perdio o se corrio un byte "
                        + "en la costura entre el guion y el tar: " + primerArchivo);

        String primerByteHex = buzon.get("primer-byte.txt");
        assertEquals(String.format("%02x", (int) primerCaracterDelContenido), primerByteHex,
                "el primer byte del CONTENIDO del archivo no coincide: " + primerByteHex);
    }

    // ------------------------------------------------------------------
    // 3) Los nueve bundles, contra la tabla de la §6 del handoff
    // ------------------------------------------------------------------
    //
    // La tabla de la §6 se midio con probar-capa1.sh, es decir SIN el ejecutor en el medio. Estos
    // tests la usan como hipotesis a confirmar, no como oraculo: si la medicion real diverge, el
    // assert documenta la divergencia en vez de forzarla para que pase.
    //
    // hostil-paquete y hostil-red no tenian linea de base a traves del ejecutor (solo habian
    // corrido por run.sh): sus asserts estan marcados como primera medicion, no como confirmacion.

    /** Linea de base: exit=0, resultado=OK, exitEval=0, surv=0. Sin sorpresas. */
    @Test
    @Timeout(150)
    void p4c_okSuma() throws IOException {
        Resultado r = correrBundle("ok-suma");
        assertEquals(0, r.exitCode, r.diagnostico());
        assertEquals("OK", r.resultado, r.diagnostico());
        assertEquals(0, r.exitEval, r.diagnostico());
        assertEquals(0, r.surv, r.diagnostico());
    }

    /** Capa 2 corta con System.exit(0) apenas arranca -> banda 40-59, 47 (salida anticipada). */
    @Test
    @Timeout(150)
    void p4c_hostilExit0() throws IOException {
        Resultado r = correrBundle("hostil-exit0");
        assertEquals(47, r.exitCode, r.diagnostico());
        assertEquals("DETENIDO_POR_EVALUACION", r.resultado, r.diagnostico());
        assertEquals(47, r.exitEval, r.diagnostico());
        assertEquals(0, r.surv, r.diagnostico());
    }

    /** Agota el reloj de CPU del alumno (ulimit -t) -> 44. Consume CPU real: no es instantaneo. */
    @Test
    @Timeout(150)
    void p4c_hostilCpu() throws IOException {
        Resultado r = correrBundle("hostil-cpu");
        assertEquals(44, r.exitCode, r.diagnostico());
        assertEquals("DETENIDO_POR_EVALUACION", r.resultado, r.diagnostico());
        assertEquals(44, r.exitEval, r.diagnostico());
        assertEquals(0, r.surv, r.diagnostico());
    }

    /** ExitOnOutOfMemoryError de la JVM -> 43. */
    @Test
    @Timeout(150)
    void p4c_hostilMemoria() throws IOException {
        Resultado r = correrBundle("hostil-memoria");
        assertEquals(43, r.exitCode, r.diagnostico());
        assertEquals("DETENIDO_POR_EVALUACION", r.resultado, r.diagnostico());
        assertEquals(43, r.exitEval, r.diagnostico());
        assertEquals(0, r.surv, r.diagnostico());
    }

    /** Duerme en vez de quemar CPU -> backstop de pared de la capa 2, 45. Tarda los 30s completos. */
    @Test
    @Timeout(150)
    void p4c_hostilSleep() throws IOException {
        Resultado r = correrBundle("hostil-sleep");
        assertEquals(45, r.exitCode, r.diagnostico());
        assertEquals("DETENIDO_POR_EVALUACION", r.resultado, r.diagnostico());
        assertEquals(45, r.exitEval, r.diagnostico());
        assertEquals(0, r.surv, r.diagnostico());
    }

    /** Deja un proceso vivo reescribiendo el reporte -> capa 1 detecta y pisa con 30, surv=6. */
    @Test
    @Timeout(150)
    void p4c_hostilReporte() throws IOException {
        Resultado r = correrBundle("hostil-reporte");
        assertEquals(30, r.exitCode, r.diagnostico());
        assertEquals("VEREDICTO_NO_CONFIABLE", r.resultado, r.diagnostico());
        assertEquals(0, r.exitEval, r.diagnostico());
        assertEquals(6, r.surv, r.diagnostico());
    }

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

    /**
     * SIN linea de base previa a traves del ejecutor (handoff §7, "cabos sueltos"): solo se habia
     * corrido con {@code run.sh}. El CVE-2024-23682-style shadowing de {@code tp.Ayuda} depende de
     * que los tests queden PRIMERO en el classpath de ejecucion, que es lo que
     * {@code java21-junit.sh} hace a proposito. La tabla del handoff dice OK; esta es la primera
     * confirmacion real a traves del ejecutor.
     */
    @Test
    @Timeout(150)
    void p4c_hostilPaquete() throws IOException {
        Resultado r = correrBundle("hostil-paquete");
        assertEquals(0, r.exitCode, r.diagnostico());
        assertEquals("OK", r.resultado, r.diagnostico());
        assertEquals(0, r.exitEval, r.diagnostico());
        assertEquals(0, r.surv, r.diagnostico());
    }

    /**
     * SIN linea de base previa a traves del ejecutor (mismo cabo suelto que hostil-paquete). Prueba
     * el aislamiento de red ({@code --network none}) con la assertion invertida del bundle: los
     * tests PASAN si la conexion fallo. Esta es la primera confirmacion real a traves del ejecutor.
     */
    @Test
    @Timeout(150)
    void p4c_hostilRed() throws IOException {
        Resultado r = correrBundle("hostil-red");
        assertEquals(0, r.exitCode, r.diagnostico());
        assertEquals("OK", r.resultado, r.diagnostico());
        assertEquals(0, r.exitEval, r.diagnostico());
        assertEquals(0, r.surv, r.diagnostico());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Resultado(String bundle, Integer exitCode, String resultado, Integer exitEval,
                              int surv, String reporteCrudo, String stderr) {
        String diagnostico() {
            return bundle + ": exit=" + exitCode + " resultado=" + resultado + " exitEval=" + exitEval
                    + " surv=" + surv + " stderr=" + stderr + " sobre=" + reporteCrudo;
        }
    }

    private Resultado correrBundle(String nombreBundle) throws IOException {
        byte[] tar = tarDeBundle(rutaBundle(nombreBundle));
        var salida = ejecucionProduccion.ejecutar(UUID.randomUUID().toString(), tar, "java21-junit@4");

        assertNotEquals(Ejecucion.Estado.TIMEOUT, salida.resultado(),
                nombreBundle + ": salto el backstop de 60s del ejecutor; stderr: " + salida.stderr());
        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado(),
                nombreBundle + ": stderr: " + salida.stderr());
        assertFalse(salida.reporteAusente(),
                nombreBundle + ": no vino el sobre; stdout: " + salida.stdout());

        String reporte = salida.reporte();
        String resultado = campoTexto(reporte, "resultado");
        Integer exitEval = campoEntero(reporte, "exitEval");
        Integer surv = campoEntero(reporte, "procesosSobrevivientes");

        return new Resultado(nombreBundle, salida.exitCode(), resultado, exitEval,
                surv == null ? -1 : surv, reporte, salida.stderr());
    }

    private static Path rutaBundle(String nombre) {
        Path ruta = BUNDLES.resolve(nombre);
        assertTrue(Files.isDirectory(ruta), "no existe el bundle: " + ruta.toAbsolutePath());
        return ruta;
    }

    /** Empaqueta src/ y test/ del bundle en un tar, igual que {@code tar -cf ... -C dir src test}. */
    private static byte[] tarDeBundle(Path bundleDir) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream taos = new TarArchiveOutputStream(bytes)) {
            for (String sub : List.of("src", "test")) {
                Path raiz = bundleDir.resolve(sub);
                if (!Files.isDirectory(raiz)) continue;
                try (Stream<Path> flujo = Files.walk(raiz)) {
                    List<Path> archivos = flujo.filter(Files::isRegularFile).sorted().toList();
                    for (Path archivo : archivos) {
                        String nombreEnTar = bundleDir.relativize(archivo).toString().replace('\\', '/');
                        byte[] contenido = Files.readAllBytes(archivo);
                        TarArchiveEntry entrada = new TarArchiveEntry(nombreEnTar);
                        entrada.setSize(contenido.length);
                        taos.putArchiveEntry(entrada);
                        taos.write(contenido);
                        taos.closeArchiveEntry();
                    }
                }
            }
        }
        return bytes.toByteArray();
    }

    /** Tar de una sola entrada, para el test de integridad byte a byte. */
    private static byte[] tarDeUnArchivo(String nombre, byte[] contenido) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream taos = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entrada = new TarArchiveEntry(nombre);
            entrada.setSize(contenido.length);
            taos.putArchiveEntry(entrada);
            taos.write(contenido);
            taos.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    /** El buzon viaja como tar.gz en base64 dentro del sobre JSON, campo reportesTarGzB64. */
    private static byte[] decodificarBuzon(String reporte) {
        String b64 = campoTexto(reporte, "reportesTarGzB64");
        assertNotNull(b64, "el sobre no trae reportesTarGzB64: " + reporte);
        assertFalse(b64.isEmpty(), "el buzon vino vacio; sobre: " + reporte);
        return Base64.getDecoder().decode(b64);
    }

    private static Map<String, String> listarBuzon(byte[] tarGz) throws IOException {
        Map<String, String> archivos = new LinkedHashMap<>();
        try (TarArchiveInputStream tar =
                     new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(tarGz)))) {
            TarArchiveEntry entrada;
            while ((entrada = tar.getNextTarEntry()) != null) {
                if (entrada.isDirectory()) continue;
                // capa1.sh empaqueta el buzon con `tar -czf - -C "$DIR_REPORTES" .`: los nombres
                // salen con el prefijo "./" de ese ".". Se lo saca para que el buzon se indexe por
                // nombre de archivo llano, igual que lo escribe el guion.
                String nombre = entrada.getName();
                if (nombre.startsWith("./")) nombre = nombre.substring(2);
                archivos.put(nombre, new String(tar.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return archivos;
    }

    /** El sobre es JSON de una sola linea sin comillas escapadas en los valores que nos interesan. */
    private static String campoTexto(String reporte, String clave) {
        Matcher m = Pattern.compile("\"" + clave + "\":\"([^\"]*)\"").matcher(reporte);
        return m.find() ? m.group(1) : null;
    }

    private static Integer campoEntero(String reporte, String clave) {
        Matcher m = Pattern.compile("\"" + clave + "\":(-?[0-9]+)").matcher(reporte);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }
}
