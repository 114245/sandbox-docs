package sandbox.ejecutor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Los siete pasos de la seccion 5, con limpieza garantizada. */
final class Ejecucion implements Motor {

    enum Estado { COMPLETADA, TIMEOUT, ERROR_DAEMON, RECHAZADA }

    /** Los campos y su orden son los de la seccion 3.1; Jackson los serializa en este mismo orden. */
    record Salida(String ejecucionId, Estado resultado, Integer exitCode, boolean oomKilled,
                  long duracionMs, String stdout, String stderr, String reporte,
                  boolean reporteAusente, boolean salidaTruncada) {

        static Salida rechazada(String ejecucionId) {
            return new Salida(ejecucionId, Estado.RECHAZADA, null, false, 0, "", "", null, true, false);
        }

        static Salida errorDaemon(String ejecucionId, long duracionMs) {
            return new Salida(ejecucionId, Estado.ERROR_DAEMON, null, false, duracionMs, "", "", null, true, false);
        }
    }

    private static final SecureRandom AZAR = new SecureRandom();

    private final ClienteDocker cliente;
    private final ExecutorService hilos;
    private final ScheduledExecutorService reloj;
    /** Contenedores vivos de esta instancia, para el apagado ordenado (R11.7). */
    private final Set<String> enVuelo = ConcurrentHashMap.newKeySet();

    Ejecucion(ClienteDocker cliente, ExecutorService hilos, ScheduledExecutorService reloj) {
        this.cliente = cliente;
        this.hilos = hilos;
        this.reloj = reloj;
    }

    Set<String> contenedoresEnVuelo() { return enVuelo; }

    @Override
    public boolean daemonVivo() { return cliente.ping(); }

    @Override
    public void limpiarEnVuelo() {
        for (String contenedor : enVuelo) cliente.borrar(contenedor);
    }

    @Override
    public Salida ejecutar(String ejecucionId, byte[] tar) {
        long comienzo = System.nanoTime();
        // R7.1: 16 bytes de un generador criptografico, en hexadecimal minuscula.
        String nonce = HexFormat.of().formatHex(azar16());

        String contenedor = null;
        try {
            contenedor = cliente.crear(ejecucionId);            // paso 1
            enVuelo.add(contenedor);
            return correr(ejecucionId, contenedor, tar, nonce, comienzo);
        } catch (ErrorDaemon e) {
            Log.info("id=%s resultado=ERROR_DAEMON causa=%s", ejecucionId, e.getMessage());
            return Salida.errorDaemon(ejecucionId, msDesde(comienzo));
        } finally {
            // R5.3: el borrado ocurre siempre, tambien ante excepcion.
            if (contenedor != null) {
                if (!cliente.borrar(contenedor)) {
                    Log.info("id=%s delete fallo; queda para el barrido", ejecucionId);
                }
                enVuelo.remove(contenedor);
            }
        }
    }

    private Salida correr(String ejecucionId, String contenedor, byte[] tar, String nonce, long comienzo) {
        // R5.1: el attach va ANTES del start, o el contenedor puede leer stdin sin nadie del otro lado.
        SocketChannel entrada = cliente.adjuntarStdin(contenedor);   // paso 2
        cliente.iniciar(contenedor);                                  // paso 3
        long arranque = System.nanoTime();                            // R8.1: el reloj arranca aca

        long escritos = escribirEntrada(entrada, nonce, tar, arranque);   // paso 4
        boolean cierreCompleto = escritos >= 0;
        // R8.4: un TIMEOUT es indistinguible de un while(true) y de un half-close mal hecho.
        Log.info("id=%s stdin_bytes=%d cierre_completo=%s", ejecucionId, Math.abs(escritos), cierreCompleto);

        Integer exitCode;
        Estado estado;
        long restante = Constantes.TIMEOUT_EJECUCION_MS - msDesde(arranque);
        try {
            exitCode = esperarConTope(contenedor, Math.max(restante, 0));   // paso 5
            estado = Estado.COMPLETADA;
        } catch (TimeoutException e) {
            // R8.2: kill, wait corto, y la salida parcial se devuelve igual porque sirve para diagnosticar.
            cliente.matar(contenedor);
            try {
                esperarConTope(contenedor, Constantes.TIMEOUT_DAEMON_MS);
            } catch (TimeoutException ignorado) {
                Log.info("id=%s el contenedor no termino tras el kill", ejecucionId);
            }
            exitCode = null;
            estado = Estado.TIMEOUT;
        }

        // R5.10: el paso 5b va tambien en TIMEOUT. Un contenedor matado por el limite de memoria
        // que ademas llego al reloj es un caso real.
        boolean oomKilled = cliente.oomKilled(contenedor);            // paso 5b

        // R5.4: los logs se leen antes del delete.
        var salida = Demultiplexor.demultiplexar(cliente.logs(contenedor));  // paso 6

        // El truncado ocurre antes de extraer el reporte: extraer primero exigiria bufferear el
        // stream sin tope, que es justo lo que R6.7 evita. Si el reporte quedo fuera del millon de
        // bytes conservados, sale reporteAusente=true junto con salidaTruncada=true.
        var extraccion = Reporte.extraer(salida.stdout(), nonce);

        long duracion = msDesde(comienzo);
        Log.info("id=%s resultado=%s exit=%s oom=%s duracion_ms=%d stdout_bytes=%d stderr_bytes=%d truncada=%s reporte_ausente=%s",
                ejecucionId, estado, exitCode, oomKilled, duracion,
                extraccion.stdout().length(), salida.stderr().length(),
                salida.truncada(), extraccion.ausente());

        return new Salida(ejecucionId, estado, exitCode, oomKilled, duracion,
                extraccion.stdout(), salida.stderr(), extraccion.reporte(),
                extraccion.ausente(), salida.truncada());
    }

    /**
     * Paso 4: primero el nonce como primera linea, despues el tar, y se cierra entero (R7.2).
     * El nonce no viaja por variable de entorno ni por archivo (R7.3): /proc/1/environ lo devolveria
     * aunque el entrypoint hiciera unset.
     *
     * R8.5: la escritura lleva su propio tope. Si el contenedor no consume stdin, el buffer del pipe
     * se llena a las pocas decenas de KiB y esto queda bloqueado para siempre; el reloj del paso 5
     * todavia no se esta evaluando, asi que sin el tope el cupo de concurrencia no se libera nunca.
     * El tope es lo que queda del reloj de ejecucion: la spec no define una constante propia, y
     * acotar por el mismo presupuesto deja al paso 5 resolviendo en TIMEOUT sin margen extra.
     *
     * @return bytes escritos; negativo si el cierre no se completo.
     */
    private long escribirEntrada(SocketChannel canal, String nonce, byte[] tar, long arranque) {
        byte[] cabecera = (nonce + "\n").getBytes(StandardCharsets.US_ASCII);
        long total = (long) cabecera.length + tar.length;
        long tope = Math.max(Constantes.TIMEOUT_EJECUCION_MS - msDesde(arranque), 1);
        ScheduledFuture<?> watchdog = reloj.schedule(() -> cerrar(canal), tope, TimeUnit.MILLISECONDS);
        try (canal) {
            OutputStream out = Channels.newOutputStream(canal);
            out.write(cabecera);
            out.write(tar);
            out.flush();
            canal.shutdownOutput();
            return total;
        } catch (IOException e) {
            // Un fallo aca no aborta la ejecucion: el contenedor arranco igual y va a morir por su
            // cuenta o por timeout. Lo que importa es que quede registrado (R8.4, R8.5).
            return -total;
        } finally {
            watchdog.cancel(false);
        }
    }

    private static void cerrar(SocketChannel canal) {
        try {
            canal.close();
        } catch (IOException ignorado) {
            // cerrarlo es justamente lo que queriamos; que ademas falle no cambia nada
        }
    }

    /**
     * Corre el wait en otro hilo para poder distinguir un vencimiento del reloj de ejecucion
     * de un fallo del daemon. La llamada que queda colgada la cierra su propio watchdog.
     */
    private int esperarConTope(String contenedor, long topeMs) throws TimeoutException {
        Future<Integer> tarea = hilos.submit(
                () -> cliente.esperar(contenedor, topeMs + Constantes.TIMEOUT_DAEMON_MS));
        try {
            return tarea.get(topeMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            tarea.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            tarea.cancel(true);
            Thread.currentThread().interrupt();
            throw new ErrorDaemon("espera interrumpida");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ErrorDaemon daemon) throw daemon;
            throw new ErrorDaemon("wait fallo");
        }
    }

    private static byte[] azar16() {
        byte[] b = new byte[16];
        AZAR.nextBytes(b);
        return b;
    }

    private static long msDesde(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000;
    }
}
