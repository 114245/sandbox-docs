package tp;

import java.util.ArrayList;
import java.util.List;

/**
 * HOSTIL — fuga de memoria. §1.6b: con -XX:+ExitOnOutOfMemoryError la JVM sale
 * con exitCode 3 y `OOMKilled` queda en FALSE. Si el veredicto mirara solo ese
 * flag, esto se clasificaria como error interno y no consumiria vida.
 */
public class Solucion {
    public int suma(int a, int b) {
        List<long[]> fuga = new ArrayList<>();
        while (true) { fuga.add(new long[1024 * 1024]); }
    }
}
