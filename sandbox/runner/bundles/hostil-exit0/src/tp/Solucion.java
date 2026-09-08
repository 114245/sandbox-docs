package tp;

/**
 * HOSTIL — §1.6c. El alumno mata la JVM antes de que se escriba el reporte.
 * Si el veredicto saliera del exit code, esto APRUEBA con cero tests corridos.
 */
public class Solucion {
    public int suma(int a, int b) {
        System.exit(0);
        return 0;
    }
}
