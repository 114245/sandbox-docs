package sandbox.worker;

/**
 * Lo que un VerificadorDeEvidencia pudo leer del buzon. 12-d16 seccion 4.
 *
 * OJO con `legible`: false significa "habia algo y no se pudo leer", que NO es lo mismo que
 * `corridas == 0` ("se leyo bien y no corrio nada"). El primero es culpa nuestra y no consume
 * intento; el segundo es del alumno y si lo consume.
 */
record Evidencia(int corridas, int fallidas, boolean legible) {
    static Evidencia ilegible() { return new Evidencia(0, 0, false); }
}
