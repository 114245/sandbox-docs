package sandbox.worker;

/**
 * Un veredicto y si le cuesta un intento al alumno. Van juntos a proposito: el par es lo que
 * se le devuelve a T05, y separarlos deja que alguien mande un ERROR_INTERNO que consume vida.
 */
record Fallo(Veredicto veredicto, boolean consumeIntento) {

    /** Nuestro problema, no del alumno. Nunca consume intento (12-d16 seccion 5). */
    static Fallo interno() { return new Fallo(Veredicto.ERROR_INTERNO, false); }

    static Fallo delAlumno(Veredicto veredicto) { return new Fallo(veredicto, true); }
}
