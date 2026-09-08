package sandbox.ejecutor;

/**
 * Log de una linea por evento a stderr (R11.6).
 * Nunca recibe el bundle, la salida del alumno ni el nonce: eso se controla en los sitios de llamada.
 */
final class Log {
    private Log() {}

    static void info(String formato, Object... args) {
        System.err.println("[ejecutor] " + String.format(formato, args));
    }
}
