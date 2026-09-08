package sandbox.ejecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/** Seccion 5 completa contra un daemon de mentira: orden de llamadas, stdin, logs y limpieza. */
class EjecucionTest {

    private static final String NONCE_REGEX = "[0-9a-f]{32}";

    private Path directorio;
    private DaemonDePrueba daemon;
    private ClienteDocker cliente;
    private Ejecucion ejecucion;
    private ExecutorService hilos;
    private ScheduledExecutorService reloj;

    @BeforeEach
    void levantar() throws Exception {
        directorio = Files.createTempDirectory("dk");
        daemon = new DaemonDePrueba(directorio.resolve("d.sock"));
        reloj = Executors.newScheduledThreadPool(2);
        hilos = Executors.newVirtualThreadPerTaskExecutor();
        cliente = new ClienteDocker(daemon.rutaSocket(), reloj);
        ejecucion = new Ejecucion(cliente, hilos, reloj);
    }

    @AfterEach
    void bajar() throws Exception {
        daemon.close();
        hilos.shutdownNow();
        reloj.shutdownNow();
        try (var f = Files.walk(directorio)) {
            f.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
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
        // El nonce lo elige el ejecutor, asi que el "entrypoint" de mentira no puede saberlo de antemano.
        // Se resuelve en dos etapas: primero corremos para capturar el nonce que entro por stdin.
        daemon.logs = concatenar(
                DaemonDePrueba.frame(1, "compilando\n"),
                DaemonDePrueba.frame(2, "un aviso\n"));

        String ejecucionId = id();
        var salida = ejecucion.ejecutar(ejecucionId, "TAR".getBytes(StandardCharsets.UTF_8));

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
        int borrado = indiceDe("?force=1");
        assertTrue(attach < start, "el attach tiene que ir antes del start");
        assertTrue(logs < borrado, "los logs se leen antes del delete");
        assertTrue(indiceDe("/wait") < logs);
    }

    /** R7.1 y R7.2: el nonce es la primera linea de stdin, y despues van los bytes del tar. */
    @Test
    @Timeout(30)
    void r72_stdinLlevaNonceYDespuesElTar() {
        byte[] tar = "contenido del tar, opaco".getBytes(StandardCharsets.UTF_8);
        ejecucion.ejecutar(id(), tar);

        byte[] recibido = daemon.stdinRecibido;
        assertNotNull(recibido, "no llego nada por el socket adjunto");
        String texto = new String(recibido, StandardCharsets.UTF_8);
        int salto = texto.indexOf('\n');
        assertTrue(salto > 0, "el nonce tiene que terminar en salto de linea");

        String nonce = texto.substring(0, salto);
        assertTrue(nonce.matches(NONCE_REGEX), "nonce de 32 hex minusculas, era: " + nonce.length() + " chars");
        assertArrayEquals(tar, java.util.Arrays.copyOfRange(recibido, salto + 1, recibido.length),
                "despues del nonce van los bytes del tar, sin tocar");
        assertTrue(daemon.stdinCerrado, "el socket adjunto se cierra entero");
    }

    /** I7: el ejecutor no desempaqueta ni interpreta el tar; lo reenvia byte a byte. */
    @Test
    @Timeout(30)
    void i7_elTarViajaOpaco() {
        byte[] basura = new byte[1024];
        new java.util.Random(7).nextBytes(basura);
        ejecucion.ejecutar(id(), basura);

        byte[] recibido = daemon.stdinRecibido;
        byte[] cuerpo = java.util.Arrays.copyOfRange(recibido, 33, recibido.length);   // 32 hex + \n
        assertArrayEquals(basura, cuerpo);
    }

    /** El nonce cambia en cada ejecucion (R7.1). */
    @Test
    @Timeout(30)
    void elNonceEsDistintoCadaVez() {
        ejecucion.ejecutar(id(), new byte[0]);
        String primero = new String(daemon.stdinRecibido, StandardCharsets.UTF_8).substring(0, 32);
        ejecucion.ejecutar(id(), new byte[0]);
        String segundo = new String(daemon.stdinRecibido, StandardCharsets.UTF_8).substring(0, 32);
        assertNotEquals(primero, segundo);
    }

    /** El bloque de reporte se extrae con el nonce real de la ejecucion y sale de stdout. */
    @Test
    @Timeout(30)
    void elReporteSeSeparaConElNonceDeLaEjecucion() throws Exception {
        // Primera pasada solo para conocer el nonce que genera el ejecutor no sirve: cambia siempre.
        // En cambio se le pide al daemon que arme los logs en el momento, usando lo que entro por stdin.
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

    /** R6.1: si logs vuelve como raw-stream el contenedor tenia TTY; eso es ERROR_DAEMON. */
    @Test
    @Timeout(30)
    void r61_rawStreamEsErrorDaemon() {
        daemon.contentTypeLogs = "application/vnd.docker.raw-stream";
        var salida = ejecucion.ejecutar(id(), new byte[0]);
        assertEquals(Ejecucion.Estado.ERROR_DAEMON, salida.resultado());
        assertNull(salida.exitCode());
    }

    /** Seccion 3.2: COMPLETADA con exitCode != 0 es normal, no un error. */
    @Test
    @Timeout(30)
    void exitCodeDistintoDeCeroSigueSiendoCompletada() {
        daemon.exitCode = 1;
        daemon.logs = DaemonDePrueba.frame(2, "error: cannot find symbol\n");
        var salida = ejecucion.ejecutar(id(), new byte[0]);
        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado());
        assertEquals(1, salida.exitCode());
        assertTrue(salida.stderr().contains("cannot find symbol"));
    }

    /** R5.3: un fallo del daemon devuelve ERROR_DAEMON y no deja el contenedor sin borrar. */
    @Test
    @Timeout(30)
    void createQueFallaEsErrorDaemon() {
        daemon.creates201 = false;
        var salida = ejecucion.ejecutar(id(), new byte[0]);
        assertEquals(Ejecucion.Estado.ERROR_DAEMON, salida.resultado());
        assertTrue(ejecucion.contenedoresEnVuelo().isEmpty());
    }

    /** R5.3: el delete corre siempre, y al terminar no queda nada anotado como en vuelo. */
    @Test
    @Timeout(30)
    void siempreSeBorraElContenedor() {
        ejecucion.ejecutar(id(), new byte[0]);
        assertTrue(daemon.llamadas.stream().anyMatch(l -> l.contains("?force=1&v=1")),
                "el delete tiene que ir con force y v");
        assertTrue(ejecucion.contenedoresEnVuelo().isEmpty());
    }

    /**
     * R8.2: vencido el reloj de ejecucion se hace kill, se vuelve a esperar, se leen igual los logs
     * parciales y se borra. El resultado es TIMEOUT con exitCode null.
     */
    @Test
    @Timeout(120)
    void r82_timeoutMataYDevuelveLaSalidaParcial() {
        daemon.demoraWaitMs = Constantes.TIMEOUT_EJECUCION_MS * 3;
        daemon.logs = DaemonDePrueba.frame(1, "arranque y me colgue\n");

        var salida = ejecucion.ejecutar(id(), new byte[0]);

        assertEquals(Ejecucion.Estado.TIMEOUT, salida.resultado());
        assertNull(salida.exitCode());
        assertEquals("arranque y me colgue\n", salida.stdout(), "la salida parcial se devuelve igual");
        assertTrue(daemon.llamadas.stream().anyMatch(l -> l.contains("/kill")));
        assertTrue(daemon.llamadas.stream().anyMatch(l -> l.contains("?force=1")));
        assertTrue(salida.duracionMs() >= Constantes.TIMEOUT_EJECUCION_MS);
    }

    /** R5.0: la version de la API va explicita en el path, nunca el default del daemon. */
    @Test
    @Timeout(30)
    void r50_todasLasRutasLlevanLaVersion() {
        ejecucion.ejecutar(id(), new byte[0]);
        for (String llamada : daemon.llamadas) {
            assertTrue(llamada.startsWith("/" + Constantes.VERSION_API_DOCKER + "/"),
                    "ruta sin version: " + llamada);
        }
    }

    /** A1 en vivo: el cuerpo que recibe el daemon es exactamente el golden. */
    @Test
    @Timeout(30)
    void elCuerpoDeCreateEsElGolden() throws Exception {
        String ejecucionId = id();
        ejecucion.ejecutar(ejecucionId, new byte[0]);

        String ruta = daemon.llamadas.stream().filter(l -> l.contains("/create")).findFirst().orElseThrow();
        assertTrue(ruta.endsWith("?name=sandbox-" + ejecucionId));

        byte[] enviado = daemon.cuerpos.get(ruta);
        assertArrayEquals(Spec.crear(ejecucionId), enviado);
    }

    /** R5.7: la respuesta de logs viene chunked y el cliente la reconstruye entera. */
    @Test
    @Timeout(30)
    void r57_logsChunkedSeReconstruyeEntero() {
        String largo = "x".repeat(5000);
        daemon.logs = DaemonDePrueba.frame(1, largo);
        var salida = ejecucion.ejecutar(id(), new byte[0]);
        assertEquals(largo, salida.stdout());
    }

    @Test
    void pingContraElDaemon() {
        assertTrue(cliente.ping());
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

        var salida = ejecucion.ejecutar(id(), new byte[0]);

        assertTrue(salida.oomKilled());
        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado());
        assertEquals(0, salida.exitCode());
        // R5.10: el inspect va despues del wait y antes del delete.
        assertTrue(indiceDe("/wait") < indiceDe("/contenedor-de-prueba/json"));
        assertTrue(indiceDe("/contenedor-de-prueba/json") < indiceDe("?force=1"));
    }

    /** A31 (C2): si el inspect falla, se devuelve oomKilled=false y el resto del resultado intacto. */
    @Test
    @Timeout(30)
    void a31_elInspectQueFallaNoAbortaLaEjecucion() {
        daemon.inspectFalla = true;
        daemon.exitCode = 7;
        daemon.logs = DaemonDePrueba.frame(1, "salida normal\n");

        var salida = ejecucion.ejecutar(id(), new byte[0]);

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

        var salida = ejecucion.ejecutar(id(), new byte[0]);

        assertEquals(Ejecucion.Estado.TIMEOUT, salida.resultado());
        assertTrue(salida.oomKilled(), "un contenedor matado por memoria que ademas llego al reloj es un caso real");
    }

    /**
     * A33 (C3): un contenedor que arranca y no consume stdin. Sin el tope de R8.5 la escritura del
     * paso 4 queda bloqueada para siempre y el cupo de concurrencia no se libera nunca; el bug es
     * invisible porque la suite queda verde y el ejecutor se traba en produccion.
     */
    @Test
    @Timeout(180)
    void a33_laEscrituraQueNoAvanzaNoRetieneElCupo() {
        daemon.consumeStdin = false;
        byte[] bundleGrande = new byte[Constantes.MAX_BUNDLE_BYTES];   // muy por encima del buffer del pipe

        long comienzo = System.nanoTime();
        var salida = ejecucion.ejecutar(id(), bundleGrande);
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
     * que acaba de recibir: es lo que hace el entrypoint de la imagen en produccion.
     */
    private Ejecucion.Salida ejecutarConReporte(String contenido, String... ruidoPrevio) throws Exception {
        DaemonDePrueba d = daemon;
        Thread armador = new Thread(() -> {
            while (d.stdinRecibido == null) Thread.onSpinWait();
            String nonce = new String(d.stdinRecibido, StandardCharsets.UTF_8).substring(0, 32);
            StringBuilder salida = new StringBuilder("antes\n");
            for (String r : ruidoPrevio) salida.append(r);
            salida.append("---SANDBOX-").append(nonce).append("-INICIO---\n")
                  .append(contenido).append('\n')
                  .append("---SANDBOX-").append(nonce).append("-FIN---\n")
                  .append("despues\n");
            d.logs = DaemonDePrueba.frame(1, salida.toString());
        });
        armador.setDaemon(true);
        armador.start();

        daemon.demoraWaitMs = 300;   // le da tiempo al armador antes de que se pidan los logs
        Ejecucion.Salida salida = ejecucion.ejecutar(id(), new byte[0]);
        armador.join(1000);
        return salida;
    }
}
