package sandbox.ejecutor;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/** Los siete pasos de la seccion 5, con limpieza garantizada. */
final class Ejecucion implements Motor {

    enum Estado { COMPLETADA, TIMEOUT, ERROR_DAEMON, RECHAZADA }

    /**
     * Los campos y su orden son los de la seccion 3.1, mas perfilId/perfilVersion/perfilHash del
     * catalogo de perfiles AL FINAL (Paso 2 del handoff): asi el test que fija el orden de los diez
     * campos originales se extiende en vez de reescribirse. Jackson serializa los componentes del
     * record en este mismo orden.
     */
    record Salida(String ejecucionId, Estado resultado, Integer exitCode, boolean oomKilled,
                  long duracionMs, String stdout, String stderr, String reporte,
                  boolean reporteAusente, boolean salidaTruncada,
                  String perfilId, Integer perfilVersion, String perfilHash) {

        /** Rechazada por saturacion, ANTES de elegir contenedor: el perfil todavia no importa. */
        static Salida rechazada(String ejecucionId) {
            return new Salida(ejecucionId, Estado.RECHAZADA, null, false, 0, "", "", null, true, false,
                    null, null, null);
        }

        /** El daemon fallo. El perfil pudo haberse resuelto o no; se informa vacio en los dos casos. */
        static Salida errorDaemon(String ejecucionId, long duracionMs) {
            return new Salida(ejecucionId, Estado.ERROR_DAEMON, null, false, duracionMs, "", "", null, true, false,
                    null, null, null);
        }
    }

    private static final SecureRandom AZAR = new SecureRandom();

    private final Docker docker;
    /**
     * El catalogo de perfiles (Paso 1 de la Opcion 1). {@code Servidor} ya valido que la clave del
     * header X-Perfil exista aca antes de admitir el request; esta busqueda no deberia fallar
     * nunca, pero si lo hace es un error de programacion, no del daemon ni del alumno.
     */
    private final Catalogo catalogo;
    /** Contenedores vivos de esta instancia, para el apagado ordenado (R11.7). */
    private final Set<String> enVuelo = ConcurrentHashMap.newKeySet();

    Ejecucion(Docker docker, Catalogo catalogo) {
        this.docker = docker;
        this.catalogo = catalogo;
    }

    Set<String> contenedoresEnVuelo() { return enVuelo; }

    @Override
    public boolean daemonVivo() { return docker.ping(); }

    @Override
    public void limpiarEnVuelo() {
        for (String contenedor : enVuelo) docker.borrar(contenedor);
    }

    @Override
    public Salida ejecutar(String ejecucionId, byte[] tar, String perfilClave) {
        long comienzo = System.nanoTime();
        // R7.1: 16 bytes de un generador criptografico, en hexadecimal minuscula.
        String nonce = HexFormat.of().formatHex(azar16());

        Catalogo.Perfil perfil = catalogo.buscar(perfilClave);
        if (perfil == null) {
            throw new IllegalStateException("perfil no encontrado en el catalogo: " + perfilClave);
        }

        String contenedor = null;
        try {
            contenedor = docker.crear(ejecucionId, perfil);     // paso 1
            enVuelo.add(contenedor);
            return correr(ejecucionId, contenedor, tar, nonce, comienzo, perfil);
        } catch (ErrorDaemon e) {
            Log.info("id=%s resultado=ERROR_DAEMON causa=%s", ejecucionId, e.getMessage());
            return Salida.errorDaemon(ejecucionId, msDesde(comienzo));
        } finally {
            // R5.3: el borrado ocurre siempre, tambien ante excepcion.
            if (contenedor != null) {
                if (!docker.borrar(contenedor)) {
                    Log.info("id=%s delete fallo; queda para el barrido", ejecucionId);
                }
                enVuelo.remove(contenedor);
            }
        }
    }

    private Salida correr(String ejecucionId, String contenedor, byte[] tar, String nonce, long comienzo,
                           Catalogo.Perfil perfil) {
        Entrada entrada = Entrada.armar(nonce, perfil.script(), tar);

        // R5.1: el attach va ANTES del start, o el contenedor puede leer stdin sin nadie del otro lado.
        Docker.Adjunto adjunto = docker.adjuntar(contenedor, entrada);   // paso 2
        long arranque;
        boolean entregaCompleta;
        try {
            docker.iniciar(contenedor);                                   // paso 3
            arranque = System.nanoTime();                                 // R8.1: el reloj arranca aca

            // Paso 4. La escritura la maneja la libreria; lo nuestro es el tope (R8.5) y el cierre.
            entregaCompleta = entrada.esperarEntrega(
                    Constantes.TIMEOUT_EJECUCION_MS - msDesde(arranque));
        } finally {
            // R7.2: el canal se cierra entero, siempre. Con StdinOnce, cerrarlo ES el EOF de stdin
            // del contenedor; sin esto la capa 1 espera el fin del bundle hasta el reloj de pared.
            adjunto.close();
        }
        // R8.4: un TIMEOUT es indistinguible de un while(true) si no se sabe si stdin llego entero.
        Log.info("id=%s stdin_bytes=%d/%d entrega_completa=%s",
                ejecucionId, entrada.servidos(), entrada.total(), entregaCompleta);

        Integer exitCode;
        Estado estado;
        long restante = Constantes.TIMEOUT_EJECUCION_MS - msDesde(arranque);
        try {
            exitCode = docker.esperar(contenedor, Math.max(restante, 0));   // paso 5
            estado = Estado.COMPLETADA;
        } catch (TimeoutException e) {
            // R8.2: kill, wait corto, y la salida parcial se devuelve igual porque sirve para diagnosticar.
            docker.matar(contenedor);
            try {
                docker.esperar(contenedor, Constantes.TIMEOUT_DAEMON_MS);
            } catch (TimeoutException ignorado) {
                Log.info("id=%s el contenedor no termino tras el kill", ejecucionId);
            }
            exitCode = null;
            estado = Estado.TIMEOUT;
        }

        // R5.10: el paso 5b va tambien en TIMEOUT. Un contenedor matado por el limite de memoria
        // que ademas llego al reloj es un caso real.
        boolean oomKilled = docker.oomKilled(contenedor);            // paso 5b

        // R5.4: los logs se leen antes del delete.
        var salida = docker.logs(contenedor);                        // paso 6

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
                extraccion.ausente(), salida.truncada(),
                perfil.perfilId(), perfil.version(), perfil.scriptHash());
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
