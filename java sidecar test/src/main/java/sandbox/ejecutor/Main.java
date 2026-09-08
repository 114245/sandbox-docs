package sandbox.ejecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cableado del proceso.
 *
 * Las dos rutas de socket son configuracion de despliegue, no de la spec del contenedor: no tocan
 * ningun campo de la seccion 4.1, asi que no violan P1.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String socketEjecutor = env("EJECUTOR_SOCKET", "/run/ejecutor/ejecutor.sock");
        String socketDaemon = env("DOCKER_SOCKET", "/var/run/docker.sock");

        ScheduledExecutorService reloj = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().factory());
        ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();

        ClienteDocker cliente = new ClienteDocker(socketDaemon, reloj);
        Ejecucion ejecucion = new Ejecucion(cliente, hilos, reloj);
        Barrido barrido = new Barrido(cliente, ejecucion.contenedoresEnVuelo());
        Servidor servidor = new Servidor(socketEjecutor, ejecucion, hilos);

        // R10.1: al arrancar, y despues cada INTERVALO_BARRIDO_MS.
        reloj.scheduleWithFixedDelay(barrido::barrer, 0,
                Constantes.INTERVALO_BARRIDO_MS, TimeUnit.MILLISECONDS);

        // R11.7: dejar de aceptar, esperar a las ejecuciones en vuelo, borrar sus contenedores, salir.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("SIGTERM: apagado ordenado");
            servidor.close();
            reloj.shutdownNow();
            hilos.shutdown();
        }));

        servidor.atender();
    }

    private static String env(String nombre, String porDefecto) {
        String v = System.getenv(nombre);
        return (v == null || v.isBlank()) ? porDefecto : v;
    }
}
