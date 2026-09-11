package sandbox.ejecutor;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cableado del proceso.
 *
 * La ruta del socket propio, la del daemon y la del directorio del catalogo de perfiles son
 * configuracion de despliegue, no de la spec del contenedor: no tocan ningun campo de la seccion
 * 4.1, asi que no violan P1.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String socketEjecutor = env("EJECUTOR_SOCKET", "/run/ejecutor/ejecutor.sock");
        String dockerHost = env("DOCKER_HOST", dockerHostPorDefecto());
        Path directorioPerfiles = Path.of(env("EJECUTOR_PERFILES", "/opt/ejecutor/perfiles"));

        // El catalogo entero se carga al arrancar (Paso 1 del handoff). Un perfil ilegible, o que
        // viole alguno de sus techos, tiene que impedir ARRANCAR y no fallar recien en la primera
        // ejecucion que lo elija.
        Catalogo catalogo = Catalogo.cargar(directorioPerfiles);
        Log.info("catalogo cargado: %d perfil(es) desde %s", catalogo.cantidad(), directorioPerfiles);

        ScheduledExecutorService reloj = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().factory());
        ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();

        Docker docker = Docker.conectar(dockerHost);
        // A40: confirmar la version del daemon antes de abrir el socket del ejecutor o programar
        // el barrido. Un Engine incompatible con R5.0 tiene que tirar el arranque aca, no aparecer
        // recien en la primera ejecucion de un alumno.
        docker.verificarVersion();
        Ejecucion ejecucion = new Ejecucion(docker, catalogo);
        Barrido barrido = new Barrido(docker, ejecucion.contenedoresEnVuelo());
        Servidor servidor = new Servidor(socketEjecutor, ejecucion, catalogo, hilos);

        // R10.1: al arrancar, y despues cada INTERVALO_BARRIDO_MS.
        reloj.scheduleWithFixedDelay(barrido::barrer, 0,
                Constantes.INTERVALO_BARRIDO_MS, TimeUnit.MILLISECONDS);

        // R11.7: dejar de aceptar, esperar a las ejecuciones en vuelo, borrar sus contenedores, salir.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("SIGTERM: apagado ordenado");
            servidor.close();
            reloj.shutdownNow();
            hilos.shutdown();
            try {
                docker.close();
            } catch (Exception ignorado) {
                // el proceso se esta yendo igual
            }
        }));

        servidor.atender();
    }

    /**
     * Windows expone el daemon como named pipe. Que el transporte httpclient5 lo hable es lo que
     * permite correr el ejecutor nativo contra Docker Desktop, en vez de tener que meterlo adentro
     * de un contenedor con el socket montado como hacia falta con el cliente a mano.
     */
    private static String dockerHostPorDefecto() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "npipe:////./pipe/docker_engine"
                : "unix:///var/run/docker.sock";
    }

    private static String env(String nombre, String porDefecto) {
        String v = System.getenv(nombre);
        return (v == null || v.isBlank()) ? porDefecto : v;
    }
}
