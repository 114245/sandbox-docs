package tp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lo que escribe el PROFESOR (T05). */
class SolucionTest {

    @Test
    @DisplayName("sumaDosPositivos")
    void sumaDosPositivos() {
        assertEquals(7, new Solucion().suma(3, 4));
    }

    @Test
    @DisplayName("sumaConNegativos")
    void sumaConNegativos() {
        assertEquals(2, new Solucion().suma(-1, 3));
    }

    @Test
    @DisplayName("casoBorde")
    void casoBorde() {
        assertEquals(0, new Solucion().suma(0, 0));
    }
}
