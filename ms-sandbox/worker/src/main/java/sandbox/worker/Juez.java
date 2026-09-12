package sandbox.worker;

import java.util.Optional;

/**
 * El mapeo de veredictos. Funcion pura: no sabe de sockets, ni de tar, ni de formatos.
 *
 * OJO con el orden del switch: ES la guarda de orden de 12-d16 seccion 2. Los timeouts se
 * resuelven por `resultado` y por la banda, asi que llegan antes de que la rama OK pueda mirar
 * `corridas == 0`. Si alguien aplana esto a una cadena de ifs sobre `corridas`, la propiedad se
 * pierde en silencio y una entrega que quemo la CPU pasa a reportarse como salida anticipada.
 */
final class Juez {

    private Juez() {}

    static Fallo juzgar(SobreCapa1 capa1, boolean oomKilled, Optional<Evidencia> evidencia) {
        return switch (capa1.resultado()) {
            case "OK"                      -> juzgarCorridaCompleta(evidencia);
            case "DETENIDO_POR_EVALUACION" -> juzgarFrenoDeLaCapa2(capa1, evidencia);
            case "TIMEOUT_PARED"           -> Fallo.delAlumno(Veredicto.TIMEOUT);
            case "VEREDICTO_NO_CONFIABLE"  -> new Fallo(Veredicto.VEREDICTO_NO_CONFIABLE, false);
            case "MUERTO_POR_SENAL"        -> oomKilled
                                                  ? Fallo.delAlumno(Veredicto.LIMITE_MEMORIA)
                                                  : Fallo.interno();
            case "SIN_REPORTE",
                 "BUNDLE_INVALIDO",
                 "EVALUACION_ANOMALA"      -> Fallo.interno();
            // Un resultado que no conocemos es un despliegue desalineado, no una entrega mala.
            default                        -> Fallo.interno();
        };
    }

    /**
     * La capa 2 llego al final. Aca y solo aca se mira la evidencia.
     *
     * El Optional vacio es el fail-closed de 12-d16 seccion 5: sin verificador para el formato
     * declarado, no hay EXITO posible. Que sea un Optional y no una Evidencia con corridas=0 es
     * lo que evita que el camino natural del codigo sea el peor.
     */
    private static Fallo juzgarCorridaCompleta(Optional<Evidencia> evidencia) {
        if (evidencia.isEmpty() || !evidencia.get().legible()) return Fallo.interno();

        Evidencia e = evidencia.get();
        if (e.corridas() == 0) return Fallo.delAlumno(Veredicto.SALIDA_ANTICIPADA);
        if (e.fallidas() > 0)  return Fallo.delAlumno(Veredicto.TESTS_FALLIDOS);
        return new Fallo(Veredicto.EXITO, false);
    }

    /**
     * La capa 2 se freno a proposito con un codigo de su banda.
     *
     * Excepcion deliberada al mapeo de la banda: si la capa 2 declaro 47 ("reporte con
     * tests=0") pero el conteo real de los XML dice que SI corrieron pruebas, manda el conteo.
     * El 47 es la capa 2 hablando de si misma, y 12-d16 seccion 3 descarta esa fuente al
     * rechazar la Opcion B: pedirle al vigilado que se vigile.
     */
    private static Fallo juzgarFrenoDeLaCapa2(SobreCapa1 capa1, Optional<Evidencia> evidencia) {
        if (capa1.exitEval() == 47
                && evidencia.isPresent()
                && evidencia.get().legible()
                && evidencia.get().corridas() > 0) {
            return juzgarCorridaCompleta(evidencia);
        }
        if (!BandaDeEvaluacion.contiene(capa1.exitEval())) return Fallo.interno();
        return BandaDeEvaluacion.mapear(capa1.exitEval());
    }
}
