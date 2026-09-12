package sandbox.worker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BandaDeEvaluacionTest {

    @Test
    void mapeaLaTablaDeG5QueImplementaElPerfilJava21Junit() {
        assertEquals(Veredicto.ERROR_COMPILACION, BandaDeEvaluacion.mapear(40).veredicto());
        assertEquals(Veredicto.SUITE_INVALIDA,    BandaDeEvaluacion.mapear(41).veredicto());
        assertEquals(Veredicto.ERROR_INTERNO,     BandaDeEvaluacion.mapear(42).veredicto());
        assertEquals(Veredicto.LIMITE_MEMORIA,    BandaDeEvaluacion.mapear(43).veredicto());
        assertEquals(Veredicto.TIMEOUT,           BandaDeEvaluacion.mapear(44).veredicto());
        assertEquals(Veredicto.TIMEOUT,           BandaDeEvaluacion.mapear(45).veredicto());
        assertEquals(Veredicto.ERROR_INTERNO,     BandaDeEvaluacion.mapear(46).veredicto());
        assertEquals(Veredicto.SALIDA_ANTICIPADA, BandaDeEvaluacion.mapear(47).veredicto());
    }

    @Test
    void laSuiteRotaNoLeConsumeIntentoAlAlumno() {
        // 41 es "no compila la suite de pruebas": la suite es de la catedra.
        assertFalse(BandaDeEvaluacion.mapear(41).consumeIntento());
    }

    @Test
    void elRelojDePlataformaNoLeConsumeIntentoAlAlumno() {
        // 42 es javac pasandose del presupuesto de compilacion, que es NUESTRO.
        assertFalse(BandaDeEvaluacion.mapear(42).consumeIntento());
    }

    @Test
    void unCodigoSinAsignarDeLaBandaEsErrorInternoYNoConsumeIntento() {
        // Fail-closed: un codigo que no sabemos leer no puede aprobar ni castigar al alumno.
        for (int codigo = 48; codigo <= 59; codigo++) {
            Fallo fallo = BandaDeEvaluacion.mapear(codigo);
            assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto(), "codigo " + codigo);
            assertFalse(fallo.consumeIntento(), "codigo " + codigo);
        }
    }

    @Test
    void unCodigoFueraDeLaBandaEsErrorInterno() {
        assertEquals(Veredicto.ERROR_INTERNO, BandaDeEvaluacion.mapear(39).veredicto());
        assertEquals(Veredicto.ERROR_INTERNO, BandaDeEvaluacion.mapear(60).veredicto());
    }
}
