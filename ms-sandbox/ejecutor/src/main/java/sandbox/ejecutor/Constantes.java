package sandbox.ejecutor;

/** Valores de la spec, seccion 4.3. Constantes de compilacion: nunca parametros de request (P1). */
final class Constantes {
    private Constantes() {}

    static final int  MAX_BUNDLE_BYTES     = 2_097_152;   // 2 MiB
    /** Reloj de pared, desde start. Es la red de ultima instancia, no el mecanismo. */
    static final long TIMEOUT_EJECUCION_MS = 60_000;
    static final long TIMEOUT_DAEMON_MS    = 5_000;
    static final int  MAX_SALIDA_BYTES     = 1_048_576;   // 1 MiB por stream
    static final int  MAX_CONCURRENTES     = 8;
    static final int  MAX_COLA             = 16;
    static final long INTERVALO_BARRIDO_MS = 300_000;     // 5 min
    static final long EDAD_HUERFANO_MS     = 600_000;     // 10 min
    static final String VERSION_API_DOCKER = "v1.43";

    /**
     * Techo duro de memoria de un perfil del catalogo (Paso 1). Ningun perfil puede declarar mas:
     * es la validacion que hace fallar el ARRANQUE, no una ejecucion individual.
     */
    static final int MEMORIA_MAX_MB = 1024;

    /**
     * Techo duro de cpuS de un perfil del catalogo. Coincide a proposito con el limite que ya
     * impone R4.1 (cpuS*2 <= reloj de pared en segundos): con TIMEOUT_EJECUCION_MS en 60 s, ningun
     * perfil valido puede pasar de 30 igual, asi que este techo nunca queda mas laxo que R4.1.
     */
    static final int CPU_MAX_S = 30;

    /**
     * ENTRYPOINT de la capa 1. La imagen ya lo declara; se fuerza igual desde la spec para que un
     * rebuild de la imagen no pueda cambiar quien corre primero adentro del contenedor.
     */
    static final String ENTRYPOINT = "/opt/sandbox/capa1.sh";

    /**
     * Tope del segundo documento de stdin (el guion de la capa 2). Es el mismo valor que la imagen
     * publica como SANDBOX_MAX_SCRIPT_BYTES: si el ejecutor mandara mas, la capa 1 lo rechazaria.
     */
    static final int MAX_SCRIPT_BYTES = 262_144;

    /** Tope de una respuesta del daemon leida entera en memoria (seccion 4.3). */
    static final int MAX_CUERPO_DAEMON_BYTES = 16_777_216;   // 16 MiB
}
