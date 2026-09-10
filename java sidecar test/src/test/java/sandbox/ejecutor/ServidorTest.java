package sandbox.ejecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Seccion 3 y seccion 9: contrato HTTP, saturacion y limites de entrada. */
class ServidorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** El catalogo de esta suite: un solo perfil, la misma clave que manda ClienteHttpDePrueba por defecto. */
    private static final Catalogo CATALOGO = Catalogo.deUnSolo(Catalogo.Perfil.armar(
            "perfil-prueba", 1, "sandbox-runner:prueba",
            "#!/bin/sh\necho prueba\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "junit-xml", 512, 20));

    /** Motor de mentira: no toca Docker, y puede quedarse trabado a pedido para llenar la cola. */
    private static final class MotorDePrueba implements Motor {
        final CountDownLatch soltar = new CountDownLatch(1);
        final AtomicInteger enEjecucion = new AtomicInteger();
        volatile boolean bloquear = false;
        volatile boolean daemonVivo = true;
        volatile boolean limpiado = false;

        @Override
        public Ejecucion.Salida ejecutar(String id, byte[] tar, String perfilClave) {
            enEjecucion.incrementAndGet();
            try {
                if (bloquear) soltar.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                enEjecucion.decrementAndGet();
            }
            return new Ejecucion.Salida(id, Ejecucion.Estado.COMPLETADA, 0, false, 12,
                    "salida", "", "<testsuite/>", false, false,
                    "perfil-prueba", 1, "hash-de-prueba");
        }

        @Override public boolean daemonVivo() { return daemonVivo; }
        @Override public void limpiarEnVuelo() { limpiado = true; }
    }

    private Path directorio;
    private String rutaSocket;
    private Servidor servidor;
    private ExecutorService hilos;
    private MotorDePrueba motor;
    private ClienteHttpDePrueba cliente;

    @BeforeEach
    void levantar() throws IOException {
        directorio = Files.createTempDirectory("ej");
        rutaSocket = directorio.resolve("e.sock").toString();
        motor = new MotorDePrueba();
        hilos = Executors.newVirtualThreadPerTaskExecutor();
        servidor = new Servidor(rutaSocket, motor, CATALOGO, hilos);
        hilos.submit(servidor::atender);
        cliente = new ClienteHttpDePrueba(rutaSocket);
    }

    @AfterEach
    void bajar() throws IOException {
        motor.soltar.countDown();
        servidor.close();
        hilos.shutdownNow();
        try (var f = Files.walk(directorio)) {
            f.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static String id() { return UUID.randomUUID().toString(); }

    @Test
    void caminoFelizDevuelveElContratoDeLaSeccion3() throws Exception {
        var r = cliente.ejecutar(id(), "tar de mentira".getBytes());
        assertEquals(200, r.codigo());
        JsonNode n = MAPPER.readTree(r.cuerpo());
        assertEquals("COMPLETADA", n.get("resultado").asText());
        assertEquals(0, n.get("exitCode").asInt());
        assertEquals("salida", n.get("stdout").asText());
        assertEquals("<testsuite/>", n.get("reporte").asText());
        assertFalse(n.get("oomKilled").asBoolean());
        assertFalse(n.get("reporteAusente").asBoolean());
        assertFalse(n.get("salidaTruncada").asBoolean());
        assertTrue(n.has("duracionMs"));
    }

    /**
     * La serializacion de la respuesta se apoya en que Jackson emite los componentes del record
     * en orden de declaracion. Este test fija ese contrato: los diez campos de la seccion 3.1,
     * en ese orden, con los nulos presentes en vez de omitidos, EXTENDIDO (Paso 2 del handoff) con
     * perfilId/perfilVersion/perfilHash del catalogo de perfiles al final.
     */
    @Test
    void laRespuestaTieneLosCamposDeLaSeccion3EnOrden() {
        var timeout = new Ejecucion.Salida("una-id", Ejecucion.Estado.TIMEOUT, null, true, 60001,
                "parcial", "", null, true, true, null, null, null);
        assertEquals("{\"ejecucionId\":\"una-id\",\"resultado\":\"TIMEOUT\",\"exitCode\":null,"
                + "\"oomKilled\":true,\"duracionMs\":60001,\"stdout\":\"parcial\",\"stderr\":\"\","
                + "\"reporte\":null,\"reporteAusente\":true,\"salidaTruncada\":true,"
                + "\"perfilId\":null,\"perfilVersion\":null,\"perfilHash\":null}", Servidor.json(timeout));
    }

    @Test
    void errorDaemonEs502() throws Exception {
        Servidor otro = null;
        try {
            Motor caido = new Motor() {
                @Override public Ejecucion.Salida ejecutar(String id, byte[] tar, String perfilClave) {
                    return Ejecucion.Salida.errorDaemon(id, 3);
                }
                @Override public boolean daemonVivo() { return false; }
                @Override public void limpiarEnVuelo() {}
            };
            String ruta = directorio.resolve("d.sock").toString();
            otro = new Servidor(ruta, caido, CATALOGO, hilos);
            hilos.submit(otro::atender);

            var r = new ClienteHttpDePrueba(ruta).ejecutar(id(), new byte[0]);
            assertEquals(502, r.codigo());
            assertEquals("ERROR_DAEMON", MAPPER.readTree(r.cuerpo()).get("resultado").asText());
        } finally {
            if (otro != null) otro.close();
        }
    }

    /** Seccion 3.3: falta el id, o no es un UUID. */
    @Test
    void sinIdValidoEs400() throws Exception {
        var sinHeader = cliente.enviar("POST /ejecutar HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n", null);
        assertEquals(400, sinHeader.codigo());

        var malFormado = cliente.ejecutar("no-soy-un-uuid", new byte[0]);
        assertEquals(400, malFormado.codigo());
    }

    /** El id se concatena en la URL de create: un id con barras seria una inyeccion. */
    @Test
    void idConCaracteresDeRutaEs400() throws Exception {
        assertEquals(400, cliente.ejecutar("../../containers/otro", new byte[0]).codigo());
    }

    /** Paso 2 del handoff (catalogo de perfiles): X-Perfil ausente o con formato invalido es 400. */
    @Test
    void sinPerfilValidoEs400() throws Exception {
        var sinHeader = cliente.enviar("POST /ejecutar HTTP/1.1\r\nHost: localhost\r\n"
                + "X-Ejecucion-Id: " + id() + "\r\nContent-Length: 0\r\n\r\n", null);
        assertEquals(400, sinHeader.codigo());

        var malFormado = cliente.enviar("POST /ejecutar HTTP/1.1\r\nHost: localhost\r\n"
                + "X-Ejecucion-Id: " + id() + "\r\nX-Perfil: no-tiene-arroba\r\n"
                + "Content-Length: 0\r\n\r\n", null);
        assertEquals(400, malFormado.codigo());
    }

    /** Paso 2 del handoff: header bien formado pero ausente del catalogo es 422. */
    @Test
    void perfilDesconocidoEs422() throws Exception {
        var r = cliente.ejecutar(id(), new byte[0], "no-existe@99");
        assertEquals(422, r.codigo());
        assertEquals(0, motor.enEjecucion.get());
    }

    /** Seccion 3.1: no se acepta entrada sin Content-Length. */
    @Test
    void sinContentLengthEs411() throws Exception {
        var r = cliente.enviar("POST /ejecutar HTTP/1.1\r\nHost: localhost\r\n"
                + "X-Ejecucion-Id: " + id() + "\r\n"
                + "X-Perfil: " + ClienteHttpDePrueba.PERFIL_POR_DEFECTO + "\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n", null);
        assertEquals(411, r.codigo());
    }

    /**
     * A27: bundle de MAX_BUNDLE_BYTES + 1 responde 413 sin haber leido el cuerpo.
     * El test declara el largo y NO manda un solo byte: si el servidor intentara leerlo, se colgaria.
     */
    @Test
    @Timeout(10)
    void a27_bundleDemasiadoGrandeEs413SinLeerElCuerpo() throws Exception {
        var r = cliente.enviar("POST /ejecutar HTTP/1.1\r\nHost: localhost\r\n"
                + "X-Ejecucion-Id: " + id() + "\r\n"
                + "X-Perfil: " + ClienteHttpDePrueba.PERFIL_POR_DEFECTO + "\r\n"
                + "Content-Length: " + (Constantes.MAX_BUNDLE_BYTES + 1) + "\r\n\r\n", null);
        assertEquals(413, r.codigo());
        assertEquals(0, motor.enEjecucion.get());
    }

    @Test
    void unBundleJustoEnElLimiteSeAcepta() throws Exception {
        var r = cliente.ejecutar(id(), new byte[Constantes.MAX_BUNDLE_BYTES]);
        assertEquals(200, r.codigo());
    }

    /**
     * A26: MAX_CONCURRENTES + MAX_COLA + 1 pedidos simultaneos, exactamente uno recibe 503.
     * Se espera a que /salud muestre la cola llena antes de mandar el ultimo, para que el test
     * afirme el limite y no una carrera.
     */
    @Test
    @Timeout(60)
    void a26_conLaColaLlenaExactamenteUnoRecibe503() throws Exception {
        motor.bloquear = true;
        int admitidos = Constantes.MAX_CONCURRENTES + Constantes.MAX_COLA;

        List<Future<Integer>> enVuelo = new ArrayList<>();
        for (int i = 0; i < admitidos; i++) {
            enVuelo.add(hilos.submit(() -> cliente.ejecutar(id(), new byte[8]).codigo()));
        }
        esperarACola(Constantes.MAX_CONCURRENTES, Constantes.MAX_COLA);

        var rechazado = cliente.ejecutar(id(), new byte[8]);
        assertEquals(503, rechazado.codigo());
        assertEquals("5", rechazado.headers().get("retry-after"));
        assertEquals("RECHAZADA", MAPPER.readTree(rechazado.cuerpo()).get("resultado").asText());

        motor.soltar.countDown();
        for (Future<Integer> f : enVuelo) assertEquals(200, f.get(30, TimeUnit.SECONDS));
    }

    /** R3.3: saturacion no es enfermedad. Con la cola llena, /salud sigue en 200. */
    @Test
    @Timeout(60)
    void r33_conLaColaLlenaSaludSigueEn200() throws Exception {
        motor.bloquear = true;
        for (int i = 0; i < Constantes.MAX_CONCURRENTES + Constantes.MAX_COLA; i++) {
            hilos.submit(() -> cliente.ejecutar(id(), new byte[8]).codigo());
        }
        esperarACola(Constantes.MAX_CONCURRENTES, Constantes.MAX_COLA);

        var r = cliente.salud();
        assertEquals(200, r.codigo());
        JsonNode n = MAPPER.readTree(r.cuerpo());
        assertEquals("ok", n.get("daemon").asText());
        assertEquals(Constantes.MAX_CONCURRENTES, n.get("enVuelo").asInt());
        assertEquals(Constantes.MAX_COLA, n.get("enCola").asInt());
    }

    /** Seccion 3.4: si el daemon no responde el ping, /salud es 503. */
    @Test
    void saludEs503SiElDaemonNoResponde() throws Exception {
        motor.daemonVivo = false;
        var r = cliente.salud();
        assertEquals(503, r.codigo());
        assertEquals("caido", MAPPER.readTree(r.cuerpo()).get("daemon").asText());
    }

    @Test
    void saludEnReposo() throws Exception {
        JsonNode n = MAPPER.readTree(cliente.salud().cuerpo());
        assertEquals(0, n.get("enVuelo").asInt());
        assertEquals(0, n.get("enCola").asInt());
    }

    @Test
    void otraRutaEs404() throws Exception {
        assertEquals(404, cliente.enviar("GET /otra HTTP/1.1\r\nHost: localhost\r\n\r\n", null).codigo());
    }

    /** R11.7: al cerrar deja de aceptar y limpia los contenedores que quedaron. */
    @Test
    @Timeout(30)
    void apagadoOrdenado() throws Exception {
        servidor.close();
        assertTrue(motor.limpiado);
        assertThrows(IOException.class, () -> cliente.salud());
    }

    private void esperarACola(int enVuelo, int enCola) throws Exception {
        long limite = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < limite) {
            JsonNode n = MAPPER.readTree(cliente.salud().cuerpo());
            if (n.get("enVuelo").asInt() == enVuelo && n.get("enCola").asInt() == enCola) return;
            Thread.sleep(20);
        }
        fail("la cola nunca llego a " + enVuelo + "/" + enCola);
    }
}
