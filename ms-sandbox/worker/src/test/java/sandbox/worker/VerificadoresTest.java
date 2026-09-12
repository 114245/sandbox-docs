package sandbox.worker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificadoresTest {

    private final Verificadores verificadores = Verificadores.porDefecto();

    @Test
    void encuentraElVerificadorDeJunitXml() {
        assertTrue(verificadores.para("junit-xml").isPresent());
    }

    @Test
    void unFormatoDesconocidoNoDevuelveVerificador() {
        // El vacio es el punto entero del fail-closed: no devuelve un verificador que no
        // encuentra nada, no devuelve verificador. 12-d16 seccion 5.
        assertTrue(verificadores.para("pmd-xml").isEmpty());
    }

    @Test
    void unFormatoNuloOVacioNoDevuelveVerificador() {
        assertTrue(verificadores.para(null).isEmpty());
        assertTrue(verificadores.para("").isEmpty());
    }
}
