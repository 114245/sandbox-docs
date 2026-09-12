package sandbox.worker;

/** El ejecutor fallo, no contesto, o contesto algo que no se pudo leer. Siempre ERROR_INTERNO. */
class ErrorDeEjecutor extends RuntimeException {
    ErrorDeEjecutor(String mensaje) { super(mensaje); }
    ErrorDeEjecutor(String mensaje, Throwable causa) { super(mensaje, causa); }
}
