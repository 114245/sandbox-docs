package sandbox.ejecutor;

/**
 * El daemon fallo, no respondio, o respondio algo que no podemos interpretar.
 * R3.2: el mensaje no lleva rutas del host, versiones del daemon ni el cuerpo de la respuesta de Docker.
 */
class ErrorDaemon extends RuntimeException {
    ErrorDaemon(String mensaje) { super(mensaje); }
    ErrorDaemon(String mensaje, Throwable causa) { super(mensaje, causa); }
}
