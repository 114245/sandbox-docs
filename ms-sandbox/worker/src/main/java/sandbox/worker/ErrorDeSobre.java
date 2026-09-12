package sandbox.worker;

/** El sobre de la capa 1 no se pudo leer o el buzon no se pudo abrir. Siempre ERROR_INTERNO. */
final class ErrorDeSobre extends RuntimeException {
    ErrorDeSobre(String mensaje) { super(mensaje); }
    ErrorDeSobre(String mensaje, Throwable causa) { super(mensaje, causa); }
}
