package tp;

/**
 * HOSTIL — duerme sin quemar CPU. El reloj de CPU del alumno NUNCA se agota:
 * lo tiene que frenar el backstop de pared (§1.3 paso 6).
 */
public class Solucion {
    public int suma(int a, int b) {
        try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) { }
        return 0;
    }
}
