package sandbox.worker;

import java.util.Optional;

/**
 * La costura entre el sobre del ejecutor y el veredicto: el PASO 1 del mapeo (16-d16 diseno
 * seccion 5) y la traduccion de las excepciones del cliente (seccion 4).
 *
 * Antes esta cadena vivia SOLO adentro de D16IT, o sea en un test: `sobre.resultado()` no se
 * leia nunca en produccion y ninguna de las cuatro excepciones tenia veredicto. Aca esta, del
 * lado que corresponde.
 *
 * No hace I/O de red: recibe el Sobre ya traido por ClienteEjecutor. Lo unico que "abre" es el
 * reporte que ya vino adentro del sobre, en memoria.
 *
 * REGLA MADRE: este archivo NO puede producir EXITO por su cuenta. El unico EXITO posible sale
 * de Juez.juzgar sobre evidencia leida de los XML. Cualquier duda de este nivel es
 * ERROR_INTERNO, que no consume intento (12-d16 seccion 5, fail-closed).
 */
final class Nucleo {

    /**
     * PROVISIONAL: el formato de evidencia deberia salir del catalogo de perfiles, o sea de la
     * version de perfil que realmente corrio. El worker todavia no tiene catalogo de perfiles y
     * el diseno seccion 9 ("que formatos ademas de junit-xml") lo deja explicitamente para la
     * rodaja siguiente, atado a A2 y A4. Hasta entonces hay un solo formato y se nombra aca.
     */
    static final String FORMATO_DE_EVIDENCIA = "junit-xml";

    private final Verificadores verificadores;
    private final String formato;

    Nucleo() {
        this(Verificadores.porDefecto(), FORMATO_DE_EVIDENCIA);
    }

    /**
     * El formato explicito existe para las pruebas del fail-closed: produccion siempre usa
     * FORMATO_DE_EVIDENCIA. Inyectarlo solo puede QUITAR verificador (y por lo tanto quitar
     * EXITO), nunca agregar uno que apruebe de mas: el registro sigue siendo el de produccion.
     */
    Nucleo(Verificadores verificadores, String formato) {
        this.verificadores = verificadores;
        this.formato = formato;
    }

    /**
     * PASO 1, el nivel del ejecutor. RECHAZADA no figura en la tabla del diseno porque el
     * ejecutor la devuelve como 503 y ClienteEjecutor corta antes con EjecutorSaturado; si aun
     * asi llegara hasta aca, lo que NO puede es aprobar.
     *
     * El TIMEOUT del ejecutor NO es el TIMEOUT del alumno: es el reloj de respaldo de 60 s, que
     * solo se dispara si fallaron los relojes de adentro del contenedor. Que se dispare es un
     * problema nuestro, asi que ERROR_INTERNO y no TIMEOUT (diseno seccion 5).
     */
    Fallo evaluar(Sobre sobre) {
        if (sobre == null) return Fallo.interno();
        try {
            return switch (sobre.resultado()) {
                case "COMPLETADA"  -> evaluarCompletada(sobre);
                case "ERROR_DAEMON" -> Fallo.interno();
                case "TIMEOUT"      -> Fallo.interno();
                case "RECHAZADA"    -> Fallo.interno();
                // Un resultado desconocido es un despliegue desalineado, no una entrega mala.
                // Jackson deja el campo en null si falta: tampoco puede aprobar.
                case null, default  -> Fallo.interno();
            };
        } catch (RuntimeException e) {
            // ErrorDeSobre y cualquier fallo del desempaquetado o de la verificacion. El
            // problema es nuestro, no del alumno: ERROR_INTERNO y no consume intento.
            return Fallo.interno();
        }
    }

    /**
     * El ejecutor llego al final. El orden de las guardas es el de la tabla del diseno y no se
     * negocia: oomKilled antes que reporteAusente, y las dos antes de mirar el reporte.
     */
    private Fallo evaluarCompletada(Sobre sobre) {
        if (sobre.oomKilled())     return Fallo.delAlumno(Veredicto.LIMITE_MEMORIA);
        if (sobre.reporteAusente()) return Fallo.interno();

        SobreCapa1 capa1 = Desempaquetador.leerSobre(sobre.reporte());
        Buzon      buzon = Desempaquetador.abrirBuzon(capa1);

        // Optional a proposito: un formato sin verificador no devuelve un verificador que no
        // encuentra nada, no devuelve verificador. El juez recibe el vacio y no puede aprobar.
        Optional<Evidencia> evidencia = verificadores.para(formato).map(v -> v.verificar(buzon));

        return Juez.juzgar(capa1, sobre.oomKilled(), evidencia);
    }

    /**
     * La traduccion de las excepciones del cliente (diseno seccion 4). EjecutorSaturado y
     * ErrorDeProgramacion NO producen veredicto y por eso NO estan aca: el primero se reintenta
     * cuando exista la cola, el segundo va a la DLQ. Se propagan.
     */
    Fallo evaluarPedido(String ejecucionId, String perfil, byte[] tar, ClienteEjecutor cliente) {
        Sobre sobre;
        try {
            sobre = cliente.ejecutar(ejecucionId, perfil, tar);
        } catch (ErrorDeEjecutor e) {
            // 502, 422, socket caido o timeout del cliente: ERROR_INTERNO, no consume intento.
            return Fallo.interno();
        }
        return evaluar(sobre);
    }
}
