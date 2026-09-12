package sandbox.worker;

/**
 * 400, 411 y 413: el ejecutor rechazo el pedido por como estaba armado. Es un bug NUESTRO, no
 * una entrega mala. Cuando exista la cola, esto va derecho a la DLQ: reintentarlo produciria
 * exactamente el mismo rechazo.
 */
final class ErrorDeProgramacion extends RuntimeException {
    ErrorDeProgramacion(String mensaje) { super(mensaje); }
}
