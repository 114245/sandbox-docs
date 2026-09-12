package sandbox.worker;

import java.util.Map;

/**
 * El buzon de reportes ya desempaquetado, en memoria. Nunca se extrae a disco: viene de adentro
 * del contenedor donde corrio codigo del alumno, y un nombre de entrada con ../ no puede
 * escaparse a ningun lado si no se escribe nada.
 */
record Buzon(Map<String, byte[]> archivos) {
    static Buzon vacio() { return new Buzon(Map.of()); }

    boolean estaVacio() { return archivos.isEmpty(); }
}
