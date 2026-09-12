package sandbox.worker;

/**
 * La verificacion fina de evidencia, indexada por el reportFormat de la version de perfil
 * que corrio. La interfaz es textual de 12-d16 seccion 4.
 */
interface VerificadorDeEvidencia {
    boolean soporta(String formato);
    Evidencia verificar(Buzon buzon);
}
