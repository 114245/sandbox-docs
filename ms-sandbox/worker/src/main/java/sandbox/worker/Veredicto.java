package sandbox.worker;

/** Los estados terminales. 03 seccion 5.1, "los estados terminales". */
enum Veredicto {
    EXITO,
    TESTS_FALLIDOS,
    ERROR_COMPILACION,
    TIMEOUT,
    LIMITE_MEMORIA,
    SALIDA_ANTICIPADA,
    SUITE_INVALIDA,
    VEREDICTO_NO_CONFIABLE,
    ERROR_INTERNO
}
