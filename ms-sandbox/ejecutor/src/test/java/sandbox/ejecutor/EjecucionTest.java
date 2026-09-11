package sandbox.ejecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Seccion 5 completa contra un daemon de mentira: orden de llamadas, stdin, logs y limpieza. */
class EjecucionTest {

    private static final String NONCE_REGEX = "[0-9a-f]{32}";
    /** El guion de la capa 2. Contenido irrelevante; lo que importa es su largo en BYTES. */
    private static final byte[] GUION =
            "#!/bin/sh\necho capa 2 con acento: ñ\n".getBytes(StandardCharsets.UTF_8);
    /** nonce (32) + \n + largo del guion + \n */
    private static final int CABECERA = 32 + 1 + String.valueOf(GUION.length).length() + 1;

    /** El perfil de prueba de esta suite: mismo guion de siempre, envuelto en el catalogo. */
    private static final Catalogo.Perfil PERFIL = Catalogo.Perfil.armar(
            "java21-junit", 3, "sandbox-runner:2.0.0-capa1", GUION, "junit-xml", 512, 20);
    private static final String PERFIL_CLAVE = PERFIL.clave();
    private static final Catalogo CATALOGO = Catalogo.deUnSolo(PERFIL);

    private DaemonDePrueba daemon;
    private Docker docker;
    private Ejecucion ejecucion;

    @BeforeEach
    void levantar() throws Exception {
        daemon = new DaemonDePrueba();
        docker = Docker.conectar(daemon.dockerHost());
        ejecucion = new Ejecucion(docker, CATALOGO);
    }

    @AfterEach
    void bajar() throws Exception {
        docker.close();
        daemon.close();
    }

    private static byte[] concatenar(byte[]... partes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : partes) o.writeBytes(p);
        return o.toByteArray();
    }

    private static String id() { return UUID.randomUUID().toString(); }

    /** El camino feliz: los siete pasos, en orden, con el reporte separado del stdout. */
    @Test
    @Timeout(30)
    void caminoFeliz() {
        daemon.logs = concatenar(
                DaemonDePrueba.frame(1, "compilando\n"),
                DaemonDePrueba.frame(2, "un aviso\n"));

        String ejecucionId = id();
        var salida = ejecucion.ejecutar(ejecucionId, "TAR".getBytes(StandardCharsets.UTF_8), PERFIL_CLAVE);

        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado());
        assertEquals(0, salida.exitCode());
        assertEquals("compilando\n", salida.stdout());
        assertEquals("un aviso\n", salida.stderr());
        assertTrue(salida.reporteAusente());
        assertNull(salida.reporte());
        assertFalse(salida.salidaTruncada());

        // R5.1 y R5.4: attach antes de start, logs antes de delete.
        int attach = indiceDe("/attach");
        int start = indiceDe("/start");
        int logs = indiceDe("/logs");
        int borrado = indiceDe("force=true");
        assertTrue(attach < start, "el attach tiene que ir antes del start");
        assertTrue(logs < borrado, "los logs se leen antes del delete");
        assertTrue(indiceDe("/wait") < logs);
    }

    /**
     * R7.1, R7.2 y el framing de tres documentos: nonce, largo del guion, guion, y el tar como
     * "lo que quede del stream".
     */
    @Test
    @Timeout(30)
    void framingDeTresDocumentos() {
        byte[] tar = "contenido del tar, opaco".getBytes(StandardCharsets.UTF_8);
        ejecucion.ejecutar(id(), tar, PERFIL_CLAVE);

        byte[] recibido = daemon.esperarStdin();
        assertNotNull(recibido, "no llego nada por el canal adjunto");
        String texto = new String(recibido, StandardCharsets.ISO_8859_1);

        int salto1 = texto.indexOf('\n');
        assertTrue(salto1 > 0, "el nonce tiene que terminar en salto de linea");
        String nonce = texto.substring(0, salto1);
        assertTrue(nonce.matches(NONCE_REGEX), "nonce de 32 hex minusculas, era: " + nonce);

        int salto2 = texto.indexOf('\n', salto1 + 1);
        String largo = texto.substring(salto1 + 1, salto2);
        // Bytes, no caracteres: el guion tiene una ñ, asi que length() daria uno menos.
        assertEquals(String.valueOf(GUION.length), largo,
                "el largo se cuenta en bytes; con caracteres el guion llegaria cortado");
        assertNotEquals(String.valueOf("#!/bin/sh\necho capa 2 con acento: ñ\n".length()), largo);

        int desdeGuion = salto2 + 1;
        assertArrayEquals(GUION, Arrays.copyOfRange(recibido, desdeGuion, desdeGuion + GUION.length),
                "el guion de la capa 2 viaja tal cual, opaco");
        assertArrayEquals(tar, Arrays.copyOfRange(recibido, desdeGuion + GUION.length, recibido.length),
                "despues del guion van los bytes del tar, sin tocar");
        assertTrue(daemon.stdinCerrado, "el canal adjunto se cierra entero: es el EOF del contenedor");
    }

    /** I7: el ejecutor no desempaqueta ni interpreta el tar; lo reenvia byte a byte. */
    @Test
    @Timeout(30)
    void i7_elTarViajaOpaco() {
        byte[] basura = new byte[1024];
        new java.util.Random(7).nextBytes(basura);
        ejecucion.ejecutar(id(), basura, PERFIL_CLAVE);

        byte[] recibido = daemon.esperarStdin();
        byte[] cuerpo = Arrays.copyOfRange(recibido, CABECERA + GUION.length, recibido.length);
        assertArrayEquals(basura, cuerpo);
    }

    /** El nonce cambia en cada ejecucion (R7.1). */
    @Test
    @Timeout(30)
    void elNonceEsDistintoCadaVez() {
        ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        String primero = new String(daemon.esperarStdin(), StandardCharsets.UTF_8).substring(0, 32);
        ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        String segundo = new String(daemon.esperarStdin(), StandardCharsets.UTF_8).substring(0, 32);
        assertNotEquals(primero, segundo);
    }

    /** R7.3: el nonce no viaja como variable de entorno ni aparece en la spec del contenedor. */
    @Test
    @Timeout(30)
    void r73_elNonceNoTocaLaSpecDelContenedor() {
        ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        String nonce = new String(daemon.esperarStdin(), StandardCharsets.UTF_8).substring(0, 32);

        String rutaCreate = daemon.llamadas.stream().filter(l -> l.contains("/create")).findFirst().orElseThrow();
        String create = new String(daemon.cuerpos.get(rutaCreate), StandardCharsets.UTF_8);
        assertFalse(create.contains(nonce), "el nonce se filtro al cuerpo de create");
        assertFalse(rutaCreate.contains(nonce), "el nonce se filtro a la query de create");
    }

    /** El bloque de reporte se extrae con el nonce real de la ejecucion y sale de stdout. */
    @Test
    @Timeout(30)
    void elReporteSeSeparaConElNonceDeLaEjecucion() throws Exception {
        var salida = ejecutarConReporte("<testsuite name=\"REAL\"/>");

        assertEquals("<testsuite name=\"REAL\"/>", salida.reporte());
        assertFalse(salida.reporteAusente());
        assertEquals("antes\ndespues\n", salida.stdout());
    }

    /** A21 / I5: un bloque con nonce inventado por el alumno no reemplaza al verdadero. */
    @Test
    @Timeout(30)
    void a21_elAlumnoNoPuedeFalsificarElReporte() throws Exception {
        var salida = ejecutarConReporte("<testsuite name=\"REAL\"/>",
                "---SANDBOX-deadbeefdeadbeefdeadbeefdeadbeef-INICIO---\n"
                        + "<testsuite name=\"MENTIRA\"/>\n"
                        + "---SANDBOX-deadbeefdeadbeefdeadbeefdeadbeef-FIN---\n");

        assertEquals("<testsuite name=\"REAL\"/>", salida.reporte());
        assertTrue(salida.stdout().contains("MENTIRA"));
    }

    /**
     * R6.1 y R6.4: una salida que no viene enmarcada es un contenedor con TTY. docker-java la
     * entrega como frames RAW, y adivinar que eso es stdout es como se corrompe un resultado en
     * silencio: es ERROR_DAEMON.
     */
    @Test
    @Timeout(30)
    void r61_salidaSinEnmarcarEsErrorDaemon() {
        daemon.logs = "salida cruda, sin encabezado de 8 bytes\n".getBytes(StandardCharsets.UTF_8);
        var salida = ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        assertEquals(Ejecucion.Estado.ERROR_DAEMON, salida.resultado());
        assertNull(salida.exitCode());
    }

    /** Seccion 3.2: COMPLETADA con exitCode != 0 es normal, no un error. */
    @Test
    @Timeout(30)
    void exitCodeDistintoDeCeroSigueSiendoCompletada() {
        daemon.exitCode = 1;
        daemon.logs = DaemonDePrueba.frame(2, "error: cannot find symbol\n");
        var salida = ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado());
        assertEquals(1, salida.exitCode());
        assertTrue(salida.stderr().contains("cannot find symbol"));
    }

    /** R5.3: un fallo del daemon devuelve ERROR_DAEMON y no deja el contenedor sin borrar. */
    @Test
    @Timeout(30)
    void createQueFallaEsErrorDaemon() {
        daemon.creates201 = false;
        var salida = ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        assertEquals(Ejecucion.Estado.ERROR_DAEMON, salida.resultado());
        assertTrue(ejecucion.contenedoresEnVuelo().isEmpty());
    }

    /** R5.3: el delete corre siempre, y al terminar no queda nada anotado como en vuelo. */
    @Test
    @Timeout(30)
    void siempreSeBorraElContenedor() {
        ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        assertTrue(daemon.llamadas.stream().anyMatch(l -> l.contains("v=true") && l.contains("force=true")),
                "el delete tiene que ir con force y v");
        assertTrue(ejecucion.contenedoresEnVuelo().isEmpty());
    }

    /**
     * R8.2: vencido el reloj de ejecucion se hace kill, se vuelve a esperar, se leen igual los logs
     * parciales y se borra. El resultado es TIMEOUT con exitCode null.
     */
    @Test
    @Timeout(180)
    void r82_timeoutMataYDevuelveLaSalidaParcial() {
        daemon.demoraWaitMs = Constantes.TIMEOUT_EJECUCION_MS * 3;
        daemon.logs = DaemonDePrueba.frame(1, "arranque y me colgue\n");

        var salida = ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);

        assertEquals(Ejecucion.Estado.TIMEOUT, salida.resultado());
        assertNull(salida.exitCode());
        assertEquals("arranque y me colgue\n", salida.stdout(), "la salida parcial se devuelve igual");
        assertTrue(daemon.llamadas.stream().anyMatch(l -> l.contains("/kill")));
        assertTrue(daemon.llamadas.stream().anyMatch(l -> l.contains("force=true")));
        assertTrue(salida.duracionMs() >= Constantes.TIMEOUT_EJECUCION_MS);
    }

    /** R5.0: la version de la API va explicita en el path, nunca el default del daemon. */
    @Test
    @Timeout(30)
    void r50_todasLasRutasLlevanLaVersion() {
        ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        assertFalse(daemon.llamadas.isEmpty());
        for (String llamada : daemon.llamadas) {
            assertTrue(llamada.startsWith("/" + Constantes.VERSION_API_DOCKER + "/"),
                    "ruta sin version: " + llamada);
        }
    }

    /**
     * A1 en vivo: el cuerpo que recibe el daemon es exactamente el golden.
     *
     * Es el test que sostiene la mitigacion de haber pasado a docker-java. SpecTest compara el
     * golden contra lo que serializa Spec; este compara lo que serializa Spec contra lo que salio
     * de verdad por el socket. Juntos cierran I1: la spec del cable es la spec del archivo.
     */
    @Test
    @Timeout(30)
    void elCuerpoDeCreateEsElGolden() {
        String ejecucionId = id();
        ejecucion.ejecutar(ejecucionId, new byte[0], PERFIL_CLAVE);

        String ruta = daemon.llamadas.stream().filter(l -> l.contains("/create")).findFirst().orElseThrow();
        assertTrue(ruta.endsWith("?name=sandbox-" + ejecucionId), "ruta de create: " + ruta);

        assertArrayEquals(Golden.bytes(ejecucionId, PERFIL), daemon.cuerpos.get(ruta));
    }

    /** R5.7: la respuesta de logs viene chunked y el cliente la reconstruye entera. */
    @Test
    @Timeout(30)
    void r57_logsChunkedSeReconstruyeEntero() {
        String largo = "x".repeat(5000);
        daemon.logs = DaemonDePrueba.frame(1, largo);
        var salida = ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
        assertEquals(largo, salida.stdout());
    }

    @Test
    void pingContraElDaemon() {
        assertTrue(docker.ping());
    }

    // ------------------------------------------------------------------ seccion 13.6

    /** A31 (C2): el oomKilled sale del inspect del paso 5b. */
    @Test
    @Timeout(30)
    void a31_oomKilledVieneDelInspect() {
        daemon.oomKilled = true;
        // R3.4: con la JVM bien configurada el OOM llega como exitCode 3 y OOMKilled false, o al
        // reves. Por eso el dato no se infiere del exitCode: aca el exit es 0 y el oom es true.
        daemon.exitCode = 0;

        var salida = ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);

        assertTrue(salida.oomKilled());
        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado());
        assertEquals(0, salida.exitCode());
        // R5.10: el inspect va despues del wait y antes del delete.
        assertTrue(indiceDe("/wait") < indiceDe("/contenedor-de-prueba/json"));
        assertTrue(indiceDe("/contenedor-de-prueba/json") < indiceDe("force=true"));
    }

    /** A31 (C2): si el inspect falla, se devuelve oomKilled=false y el resto del resultado intacto. */
    @Test
    @Timeout(30)
    void a31_elInspectQueFallaNoAbortaLaEjecucion() {
        daemon.inspectFalla = true;
        daemon.exitCode = 7;
        daemon.logs = DaemonDePrueba.frame(1, "salida normal\n");

        var salida = ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);

        assertFalse(salida.oomKilled());
        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado(), "un dato de diagnostico no puede tirar el resultado");
        assertEquals(7, salida.exitCode());
        assertEquals("salida normal\n", salida.stdout());
    }

    /** R5.10: en TIMEOUT el paso 5b se ejecuta igual. */
    @Test
    @Timeout(180)
    void r510_elInspectTambienCorreEnTimeout() {
        daemon.demoraWaitMs = Constantes.TIMEOUT_EJECUCION_MS * 2;
        daemon.oomKilled = true;

        var salida = ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);

        assertEquals(Ejecucion.Estado.TIMEOUT, salida.resultado());
        assertTrue(salida.oomKilled(), "un contenedor matado por memoria que ademas llego al reloj es un caso real");
    }

    /**
     * A33 (C3): un contenedor que arranca y no consume stdin.
     *
     * Con docker-java el bucle de escritura lo maneja la libreria, en un hilo suyo, y queda trabado
     * en un write que no se puede interrumpir. El tope de R8.5 vive entonces en el llamador: al
     * vencerse cierra el canal adjunto entero y la escritura revienta. Sin eso el cupo de
     * concurrencia no se libera nunca y el bug es invisible, porque la suite queda verde y el que se
     * traba es el ejecutor en produccion.
     */
    @Test
    @Timeout(240)
    void a33_laEscrituraQueNoAvanzaNoRetieneElCupo() {
        daemon.consumeStdin = false;
        byte[] bundleGrande = new byte[Constantes.MAX_BUNDLE_BYTES];   // muy por encima del buffer del socket

        long comienzo = System.nanoTime();
        var salida = ejecucion.ejecutar(id(), bundleGrande, PERFIL_CLAVE);
        long duracionMs = (System.nanoTime() - comienzo) / 1_000_000;

        assertEquals(Ejecucion.Estado.TIMEOUT, salida.resultado());
        assertNull(salida.exitCode());
        // Lo que importa: la llamada RETORNA. Sin R8.5 nunca lo haria.
        assertTrue(duracionMs < Constantes.TIMEOUT_EJECUCION_MS * 2,
                "la escritura no se abandono a tiempo: " + duracionMs + " ms");
        // Y el contenedor queda borrado, asi que el cupo se libera limpio.
        assertTrue(ejecucion.contenedoresEnVuelo().isEmpty());
    }

    // ------------------------------------------------------------------ apoyo

    private int indiceDe(String fragmento) {
        for (int i = 0; i < daemon.llamadas.size(); i++) {
            if (daemon.llamadas.get(i).contains(fragmento)) return i;
        }
        return fail("nunca se llamo a " + fragmento);
    }

    /**
     * Corre una ejecucion en la que el daemon arma los logs con el nonce real, leyendolo del stdin
     * que acaba de recibir: es lo que hace la capa 1 en produccion.
     */
    private Ejecucion.Salida ejecutarConReporte(String contenido, String... ruidoPrevio) throws Exception {
        DaemonDePrueba d = daemon;
        // El hook corre en el hilo del daemon al cerrarse el canal adjunto, o sea antes del /logs.
        // Antes esto era un hilo aparte girando sobre stdinRecibido, mas un demoraWaitMs de 300 ms en
        // el wait para darle ventaja; bajo la suite completa esa ventaja no siempre alcanzaba y el
        // reporte llegaba nulo. El orden de las llamadas ya da el sincronismo: no hace falta apostar.
        d.alRecibirStdin = stdin -> {
            String nonce = new String(stdin, StandardCharsets.UTF_8).substring(0, 32);
            StringBuilder texto = new StringBuilder("antes\n");
            for (String r : ruidoPrevio) texto.append(r);
            texto.append("---SANDBOX-").append(nonce).append("-INICIO---\n")
                 .append(contenido).append('\n')
                 .append("---SANDBOX-").append(nonce).append("-FIN---\n")
                 .append("despues\n");
            d.logs = DaemonDePrueba.frame(1, texto.toString());
        };
        return ejecucion.ejecutar(id(), new byte[0], PERFIL_CLAVE);
    }
}
