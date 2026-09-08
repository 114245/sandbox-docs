package sandbox.ejecutor;

/** Valores de la spec, seccion 4.3. Constantes de compilacion: nunca parametros de request (P1). */
final class Constantes {
    private Constantes() {}

    static final int  MAX_BUNDLE_BYTES     = 2_097_152;   // 2 MiB
    /** Reloj de pared, desde start. Es la red de ultima instancia, no el mecanismo. */
    static final long TIMEOUT_EJECUCION_MS = 60_000;
    /**
     * Ulimit `cpu` del contenedor: techo de CPU de todas las fases juntas.
     * R4.1 exige que sea holgadamente menor que TIMEOUT_EJECUCION_MS en segundos; si se acercaran,
     * el reloj de pared dispararia siempre primero y este limite quedaria decorativo.
     */
    static final int  TIMEOUT_CPU_SEGUNDOS = 20;
    static final long TIMEOUT_DAEMON_MS    = 5_000;
    static final int  MAX_SALIDA_BYTES     = 1_048_576;   // 1 MiB por stream
    static final int  MAX_FRAME_BYTES      = 1_048_576;   // 1 MiB por frame
    static final int  MAX_CONCURRENTES     = 8;
    static final int  MAX_COLA             = 16;
    static final long INTERVALO_BARRIDO_MS = 300_000;     // 5 min
    static final long EDAD_HUERFANO_MS     = 600_000;     // 10 min
    static final String VERSION_API_DOCKER = "v1.43";

    /** Tag fijo de la imagen del runner. Cambiarlo exige recompilar y redesplegar: es el costo aceptado de P1. */
    static final String TAG_IMAGEN = "1.0.0";
    static final String IMAGEN     = "sandbox-runner:" + TAG_IMAGEN;

    /** Tope de una respuesta del daemon leida entera en memoria (seccion 4.3). */
    static final int MAX_CUERPO_DAEMON_BYTES = 16_777_216;   // 16 MiB
}
