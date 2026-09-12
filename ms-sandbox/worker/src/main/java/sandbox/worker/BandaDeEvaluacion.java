package sandbox.worker;

/**
 * La banda 40-59: los codigos con los que la CAPA 2 se frena a proposito.
 *
 * ============================ EL ARCHIVO QUE TOCA A3 ============================
 * Esta tabla es PROVISIONAL. D19 ("bandas de codigos de salida") le reserva la banda 40-59 a
 * G5, y A3 -- "que etiquetas de fase y que codigos 40-59 definen" -- todavia no fue
 * contestada. Lo que hay aca es la tabla que implementa NUESTRO perfil de referencia,
 * ms-sandbox/perfiles/java21-junit.sh, que escribimos nosotros.
 *
 * Cuando llegue la tabla de A3, se cambia ESTE archivo y nada mas. Por eso el juez delega en
 * vez de tener el switch adentro, y por eso la tabla es global y no por perfil: D19 propone
 * una sola banda para toda la plataforma.
 * ===============================================================================
 */
final class BandaDeEvaluacion {

    private static final int MINIMO = 40;
    private static final int MAXIMO = 59;

    private BandaDeEvaluacion() {}

    static boolean contiene(int exitEval) {
        return exitEval >= MINIMO && exitEval <= MAXIMO;
    }

    static Fallo mapear(int exitEval) {
        return switch (exitEval) {
            case 40 -> Fallo.delAlumno(Veredicto.ERROR_COMPILACION);   // no compila la solucion
            case 41 -> new Fallo(Veredicto.SUITE_INVALIDA, false);     // la suite es de la catedra
            case 42 -> Fallo.interno();                                // reloj de plataforma de javac
            case 43 -> Fallo.delAlumno(Veredicto.LIMITE_MEMORIA);      // la JVM se quedo sin memoria
            case 44 -> Fallo.delAlumno(Veredicto.TIMEOUT);             // reloj de CPU del alumno
            case 45 -> Fallo.delAlumno(Veredicto.TIMEOUT);             // backstop de pared de la suite
            case 46 -> Fallo.interno();                                // la JVM no escribio reporte
            case 47 -> Fallo.delAlumno(Veredicto.SALIDA_ANTICIPADA);   // reporte con tests=0
            // Un codigo de la banda que todavia no tiene significado no puede aprobar ni
            // castigar: fail-closed hasta que A3 lo defina.
            default -> Fallo.interno();
        };
    }
}
