package sandbox.ejecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A40: el arranque tiene que fallar si el daemon no soporta v1.43, en vez de descubrirlo en la
 * primera ejecucion de un alumno (ver README, seccion Pendientes, item 4).
 */
class DockerVersionTest {

    private DaemonDePrueba daemon;
    private Docker docker;

    @AfterEach
    void bajar() throws Exception {
        if (docker != null) docker.close();
        if (daemon != null) daemon.close();
    }

    // ---------------------------------------------------------------- la comparacion pura

    @Test
    void a40_bordesInclusivosSonCompatibles() {
        assertTrue(Docker.versionCompatible("1.43", "1.43", "1.43"));
        assertTrue(Docker.versionCompatible("1.24", "1.51", "1.43"));
    }

    @Test
    void a40_apiVersionPorDebajoDeLaRequeridaEsIncompatible() {
        assertFalse(Docker.versionCompatible("1.24", "1.40", "1.43"));
    }

    @Test
    void a40_minApiVersionPorEncimaDeLaRequeridaEsIncompatible() {
        assertFalse(Docker.versionCompatible("1.44", "1.51", "1.43"));
    }

    /** La trampa numerica-vs-string: "1.9" ordena DESPUES de "1.43" como texto, pero es MENOR. */
    @Test
    void a40_comparaNumericoYNoComoTexto() {
        assertTrue(Docker.versionCompatible("1.9", "1.51", "1.43"));
        assertFalse(Docker.versionCompatible("1.9", "1.20", "1.43"));
    }

    @Test
    void a40_minApiVersionAusenteSeToleraSiElApiVersionAlcanza() {
        assertTrue(Docker.versionCompatible(null, "1.51", "1.43"));
    }

    @Test
    void a40_minApiVersionAusenteNoSalvaUnApiVersionBajo() {
        assertFalse(Docker.versionCompatible(null, "1.30", "1.43"));
    }

    @Test
    void a40_entradaMalformadaEsIncompatible() {
        assertFalse(Docker.versionCompatible("uno.cuarentaytres", "1.51", "1.43"));
        assertFalse(Docker.versionCompatible("1.24", "no-parseable", "1.43"));
        assertFalse(Docker.versionCompatible("1.24", "1", "1.43"));
        assertFalse(Docker.versionCompatible("1.24", "", "1.43"));
    }

    // ---------------------------------------------------------------- contra el daemon de mentira

    @Test
    void a40_daemonCompatiblePasaLaVerificacion() throws Exception {
        daemon = new DaemonDePrueba();
        daemon.apiVersion = "1.51";
        daemon.minApiVersion = "1.24";
        docker = Docker.conectar(daemon.dockerHost());

        assertDoesNotThrow(docker::verificarVersion);
    }

    @Test
    void a40_daemonConMinimoPorEncimaFallaConMensajeClaro() throws Exception {
        daemon = new DaemonDePrueba();
        daemon.apiVersion = "1.51";
        daemon.minApiVersion = "1.44";
        docker = Docker.conectar(daemon.dockerHost());

        IllegalStateException e = assertThrows(IllegalStateException.class, docker::verificarVersion);
        assertTrue(e.getMessage().contains("1.44"), e.getMessage());
        assertTrue(e.getMessage().contains(Constantes.VERSION_API_DOCKER), e.getMessage());
    }

    /** Un Engine con minimo por encima de v1.43 puede contestar el propio /v1.43/version con 400. */
    @Test
    void a40_rechazo400PorVersionViejaEsIncompatibilidadNoInalcanzable() throws Exception {
        daemon = new DaemonDePrueba();
        daemon.versionRechazaPorVieja = true;
        daemon.minApiVersion = "1.44";
        docker = Docker.conectar(daemon.dockerHost());

        IllegalStateException e = assertThrows(IllegalStateException.class, docker::verificarVersion);
        // Clasificado como rechazo de version, no como daemon inalcanzable, y con el cuerpo del
        // daemon adentro: es lo unico que dice de que lado del rango quedo v1.43.
        assertTrue(e.getMessage().contains("rechazo la API"), e.getMessage());
        assertTrue(e.getMessage().contains("too old"), e.getMessage());
        assertFalse(e.getMessage().contains("contactar"), e.getMessage());
    }

    @Test
    void a40_daemonInalcanzableFallaComoTal() throws Exception {
        // Un puerto local cerrado: nadie escucha, la conexion se rechaza enseguida.
        int puertoLibre;
        try (ServerSocket s = new ServerSocket(0)) {
            puertoLibre = s.getLocalPort();
        }
        docker = Docker.conectar("tcp://127.0.0.1:" + puertoLibre);

        IllegalStateException e = assertThrows(IllegalStateException.class, docker::verificarVersion);
        assertTrue(e.getMessage().contains("contactar"), e.getMessage());
    }
}
