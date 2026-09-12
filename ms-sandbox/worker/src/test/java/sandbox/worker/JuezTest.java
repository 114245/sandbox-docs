package sandbox.worker;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JuezTest {

    private static SobreCapa1 capa1(String resultado, int exitEval) {
        return new SobreCapa1("sandbox.capa1/v2", resultado, "", exitEval, 0, "", "",
                              new SobreCapa1.Recursos(0, 0), "", "", "", false);
    }

    private static Optional<Evidencia> evidencia(int corridas, int fallidas) {
        return Optional.of(new Evidencia(corridas, fallidas, true));
    }

    // ---- la regla madre ----

    @Test
    void noHayExitoSinPruebasCorridasLeidasDelXml() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, evidencia(0, 0));

        assertEquals(Veredicto.SALIDA_ANTICIPADA, fallo.veredicto());
        assertTrue(fallo.consumeIntento());
    }

    @Test
    void unaSuiteQueCorrioYPasoEsExitoYNoConsumeIntento() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, evidencia(3, 0));

        assertEquals(Veredicto.EXITO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unaSuiteConFallasEsTestsFallidos() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, evidencia(3, 1));

        assertEquals(Veredicto.TESTS_FALLIDOS, fallo.veredicto());
        assertTrue(fallo.consumeIntento());
    }

    // ---- fail-closed ----

    @Test
    void sinVerificadorParaElFormatoNoHayExitoAunqueLaCapa1DigaOk() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, Optional.empty());

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void conEvidenciaIlegibleNoHayExitoAunqueLaCapa1DigaOk() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, Optional.of(Evidencia.ilegible()));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    // ---- el enum real de la capa 1 ----

    @Test
    void elBackstopDeParedDeLaCapa1EsTimeout() {
        assertEquals(Veredicto.TIMEOUT, Juez.juzgar(capa1("TIMEOUT_PARED", 0), false, Optional.empty()).veredicto());
    }

    @Test
    void losProcesosSobrevivientesDanVeredictoNoConfiable() {
        assertEquals(Veredicto.VEREDICTO_NO_CONFIABLE,
                     Juez.juzgar(capa1("VEREDICTO_NO_CONFIABLE", 0), false, evidencia(3, 0)).veredicto());
    }

    @Test
    void elBuzonVacioDeLaCapa1EsErrorInterno() {
        assertEquals(Veredicto.ERROR_INTERNO, Juez.juzgar(capa1("SIN_REPORTE", 0), false, Optional.empty()).veredicto());
    }

    @Test
    void unSigkillConOomKilledEsLimiteDeMemoria() {
        assertEquals(Veredicto.LIMITE_MEMORIA,
                     Juez.juzgar(capa1("MUERTO_POR_SENAL", 0), true, Optional.empty()).veredicto());
    }

    @Test
    void unSigkillSinOomKilledEsErrorInterno() {
        assertEquals(Veredicto.ERROR_INTERNO,
                     Juez.juzgar(capa1("MUERTO_POR_SENAL", 0), false, Optional.empty()).veredicto());
    }

    @Test
    void unResultadoDesconocidoDeLaCapa1EsErrorInterno() {
        assertEquals(Veredicto.ERROR_INTERNO,
                     Juez.juzgar(capa1("ALGO_QUE_NO_EXISTE", 0), false, evidencia(3, 0)).veredicto());
    }

    // ---- la banda 40-59 ----

    @Test
    void detenidoPorEvaluacionDelegaEnLaBanda() {
        assertEquals(Veredicto.ERROR_COMPILACION,
                     Juez.juzgar(capa1("DETENIDO_POR_EVALUACION", 40), false, Optional.empty()).veredicto());
    }

    // ---- el orden de guardas ----

    @Test
    void unTimeoutConCeroPruebasEsTimeoutYNoSalidaAnticipada() {
        // La guarda de orden de 12-d16 seccion 2: si esto se rompe, una entrega que quemo la
        // CPU se le reporta al alumno como si hubiera salido temprano a proposito.
        Fallo porBackstop = Juez.juzgar(capa1("TIMEOUT_PARED", 0), false, evidencia(0, 0));
        Fallo porRelojCpu = Juez.juzgar(capa1("DETENIDO_POR_EVALUACION", 44), false, evidencia(0, 0));

        assertEquals(Veredicto.TIMEOUT, porBackstop.veredicto());
        assertEquals(Veredicto.TIMEOUT, porRelojCpu.veredicto());
    }

    @Test
    void unResultadoNuloDeLaCapa1EsInternoYNoRevienta() {
        // Jackson deja el campo en null si el sobre de la capa 1 no lo trae, y un switch sobre
        // String null tira NPE antes de llegar al default. El resto del modulo ya tolera
        // sobres no contractuales: este era el unico punto asimetrico.
        Fallo fallo = Juez.juzgar(capa1(null, 0), false, evidencia(3, 0));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void elConteoDeLosXmlLeGanaAlExitEval47DeLaCapa2() {
        // 47 es la capa 2 declarando tests=0 por su cuenta. Es la Opcion B que 12-d16
        // seccion 3 descarta: el vigilado vigilandose. Si el conteo real dice que corrieron
        // pruebas, manda el conteo.
        Fallo fallo = Juez.juzgar(capa1("DETENIDO_POR_EVALUACION", 47), false, evidencia(3, 0));

        assertEquals(Veredicto.EXITO, fallo.veredicto());
    }
}
