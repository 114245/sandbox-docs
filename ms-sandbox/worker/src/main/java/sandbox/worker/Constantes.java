package sandbox.worker;

/** Los numeros del worker, en un solo lugar. */
final class Constantes {
    private Constantes() {}

    /**
     * Tope del cliente contra el ejecutor. DEBE ser estrictamente mayor que el
     * TIMEOUT_EJECUCION_MS del ejecutor (60 s): 04 seccion 12, la escalera de relojes.
     * Si fueran iguales, un timeout del ejecutor y uno del cliente serian indistinguibles.
     */
    static final long TIMEOUT_CLIENTE_MS = 75_000;

    /**
     * Tope de bytes DESCOMPRIMIDOS del buzon. Provisional: 12-d16 seccion 9 deja el numero
     * definitivo abierto ("hay que elegirlo y medirlo, y va en 08 seccion 4 como constante").
     *
     * Existe igual porque el buzon llega como tar.gz de adentro del contenedor: sin tope, un
     * gzip de pocos KB puede descomprimir a gigabytes. El sobre entero viaja por stdout, que el
     * ejecutor ya recorta a 1 MiB (MAX_SALIDA_BYTES), asi que 8 MiB descomprimidos es holgado
     * para reportes reales y muy lejos de cualquier cosa peligrosa.
     */
    static final int MAX_BUZON_BYTES = 8_388_608;   // 8 MiB

    /** Donde escucha el ejecutor: 08 R2.1. */
    static final String RUTA_SOCKET = "/run/ejecutor/ejecutor.sock";
}
