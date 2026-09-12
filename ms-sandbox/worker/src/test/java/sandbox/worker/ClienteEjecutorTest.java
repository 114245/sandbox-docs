package sandbox.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClienteEjecutorTest {

    private static final byte[] TAR = "tar-de-mentira".getBytes(StandardCharsets.UTF_8);
    private static final String ID  = "3f2b0000-0000-4000-8000-000000000000";

    private static Path socketEn(Path dir) {
        return dir.resolve("e.sock");
    }

    @Test
    void mandaLosTresHeadersYElTarEnElCuerpo(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ejecutor =
                     EjecutorDePrueba.queResponde(socket, EjecutorDePrueba.respuesta200("null"))) {

            new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR);

            assertEquals("POST /ejecutar HTTP/1.1|java21-junit@4|" + ID, ejecutor.cabezaRecibida());
            assertArrayEquals(TAR, ejecutor.tarRecibido());
        }
    }

    @Test
    void devuelveElSobreConSusTreceCampos(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado =
                     EjecutorDePrueba.queResponde(socket, EjecutorDePrueba.respuesta200("\"{}\""))) {

            Sobre sobre = new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR);

            assertEquals("COMPLETADA", sobre.resultado());
            assertEquals(0, sobre.exitCode());
            assertFalse(sobre.oomKilled());
            assertFalse(sobre.reporteAusente());
            assertEquals("java21-junit", sobre.perfilId());
            assertEquals(4, sobre.perfilVersion());
        }
    }

    @Test
    void un503EsEjecutorSaturadoYLlevaElRetryAfter(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queResponde(
                socket, EjecutorDePrueba.respuestaSinCuerpo(503, "Service Unavailable", "Retry-After: 7\r\n"))) {

            EjecutorSaturado e = assertThrows(EjecutorSaturado.class,
                    () -> new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR));

            assertEquals(7, e.segundosDeEspera());
        }
    }

    @Test
    void un502EsErrorDeEjecutor(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queResponde(
                socket, EjecutorDePrueba.respuestaSinCuerpo(502, "Bad Gateway", ""))) {

            assertThrows(ErrorDeEjecutor.class,
                    () -> new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR));
        }
    }

    @Test
    void un400EsErrorDeProgramacionPorqueElBugEsNuestro(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queResponde(
                socket, EjecutorDePrueba.respuestaSinCuerpo(400, "Bad Request", ""))) {

            assertThrows(ErrorDeProgramacion.class,
                    () -> new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR));
        }
    }

    @Test
    void unSocketQueNoExisteEsErrorDeEjecutor(@TempDir Path dir) {
        assertThrows(ErrorDeEjecutor.class,
                () -> new ClienteEjecutor(dir.resolve("no-esta.sock")).ejecutar(ID, "x@1", TAR));
    }

    @Test
    void siElEjecutorNoRespondeAntesDelTopeEsErrorDeEjecutor(@TempDir Path dir) throws Exception {
        // No se espera 75 s: se construye el cliente con un tope corto. Lo que se prueba es que
        // el tope EXISTE y despierta al lector, no su valor de produccion.
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queNuncaResponde(socket)) {

            ClienteEjecutor cliente = new ClienteEjecutor(socket, 300);

            assertThrows(ErrorDeEjecutor.class, () -> cliente.ejecutar(ID, "java21-junit@4", TAR));
        }
    }

    @Test
    void un200QueNoParseaEsErrorDeEjecutor(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queResponde(
                socket, EjecutorDePrueba.respuesta200Cruda("{esto no es json"))) {

            assertThrows(ErrorDeEjecutor.class,
                    () -> new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR));
        }
    }

    @Test
    void un200CuyoCuerpoEsElLiteralNullEsErrorDeEjecutor(@TempDir Path dir) throws Exception {
        // `null` parsea BIEN y devuelve un Sobre null: sin la guarda, el null se propaga hasta
        // el veredicto y revienta lejos de aca.
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queResponde(
                socket, EjecutorDePrueba.respuesta200Cruda("null"))) {

            assertThrows(ErrorDeEjecutor.class,
                    () -> new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR));
        }
    }

    @Test
    void elTopeDeProduccionEsMayorQueElDelEjecutor() {
        // 04 seccion 12, la escalera de relojes: 60 s ejecutor < 75 s cliente. Nunca iguales.
        assertTrue(Constantes.TIMEOUT_CLIENTE_MS > 60_000);
    }
}
