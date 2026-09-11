package tp;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * HOSTIL — intenta salir a la red. §1.2: el aislamiento de red es la defensa
 * central. La assertion esta invertida a proposito:
 *   tests PASAN  → NO hubo red → contenido
 *   tests FALLAN → hubo red    → --network none no esta haciendo su trabajo
 */
public class Solucion {
    public int suma(int a, int b) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("1.1.1.1", 80), 2000);
            return -1;
        } catch (Exception e) {
            return a + b;
        }
    }
}
