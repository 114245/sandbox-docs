package tp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class SolucionTest {
    @Test void sumaDosPositivos() { assertEquals(Ayuda.esperado(3, 4), new Solucion().suma(3, 4)); }
    @Test void sumaConNegativos() { assertEquals(Ayuda.esperado(-1, 3), new Solucion().suma(-1, 3)); }
    @Test void casoBorde()        { assertEquals(Ayuda.esperado(0, 0), new Solucion().suma(0, 0)); }
}
