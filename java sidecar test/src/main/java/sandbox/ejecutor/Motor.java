package sandbox.ejecutor;

/**
 * Lo unico que el servidor necesita saber hacer del mundo de Docker.
 * Mantener la frontera aca es lo que deja al servidor sin ninguna referencia al daemon.
 */
interface Motor {

    Ejecucion.Salida ejecutar(String ejecucionId, byte[] tar);

    boolean daemonVivo();

    /** Borra los contenedores que quedaron vivos al apagar (R11.7). */
    void limpiarEnVuelo();
}
