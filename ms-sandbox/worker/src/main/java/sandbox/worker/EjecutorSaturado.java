package sandbox.worker;

/**
 * 503: la cola del ejecutor esta llena. NO hay veredicto y NO es un intento del alumno.
 * Cuando exista la cola, esto se reintenta con backoff; por ahora se propaga.
 */
final class EjecutorSaturado extends RuntimeException {

    private final long segundosDeEspera;

    EjecutorSaturado(long segundosDeEspera) {
        super("el ejecutor esta saturado; reintentar en " + segundosDeEspera + " s");
        this.segundosDeEspera = segundosDeEspera;
    }

    long segundosDeEspera() { return segundosDeEspera; }
}
