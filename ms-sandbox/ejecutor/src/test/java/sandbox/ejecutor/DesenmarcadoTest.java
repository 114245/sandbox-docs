package sandbox.ejecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A8-A12 (seccion 13.3): caja negra contra la cadena real docker-java -&gt; Acumulador -&gt;
 * Docker.logs.
 *
 * Desde C11 el desenmarcado lo hace docker-java ({@code FramedInputStreamConsumer}), no nosotros.
 * Estos tests alimentan a {@link DaemonDePrueba} con bytes de frame armados a mano -algunos
 * validos, otros corruptos a proposito- via {@code daemon.logs}, y verifican lo que la cadena
 * entera hace al llamar {@link Docker#logs}, no lo que {@link Acumulador} hace solo (eso ya lo
 * cubre {@code AcumuladorTest}).
 *
 * Las hipotesis sobre el comportamiento de la libreria salieron de leer
 * {@code FramedInputStreamConsumer.java} (docker-java-core 3.4.1, fuente descompilada del jar de
 * sources). Cada test dice, en su javadoc, si confirma o refuta la hipotesis de la que partio.
 */
class DesenmarcadoTest {

    private DaemonDePrueba daemon;
    private Docker docker;

    @BeforeEach
    void levantar() throws Exception {
        daemon = new DaemonDePrueba();
        docker = Docker.conectar(daemon.dockerHost());
    }

    @AfterEach
    void bajar() throws Exception {
        docker.close();
        daemon.close();
    }

    /**
     * Encabezado crudo de 8 bytes (tipo + 3 bytes de relleno + largo uint32 big-endian), con un
     * largo declarado que puede no coincidir con lo que realmente sigue. A diferencia de
     * {@link DaemonDePrueba#frame}, este helper no escribe payload: sirve para armar encabezados
     * truncados o con un largo mentiroso.
     */
    private static byte[] cabecera(int tipo, long largoDeclarado) {
        byte[] h = new byte[8];
        h[0] = (byte) tipo;
        h[4] = (byte) (largoDeclarado >>> 24);
        h[5] = (byte) (largoDeclarado >>> 16);
        h[6] = (byte) (largoDeclarado >>> 8);
        h[7] = (byte) largoDeclarado;
        return h;
    }

    private static byte[] concatenar(byte[]... partes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : partes) o.writeBytes(p);
        return o.toByteArray();
    }

    /** A8: salida armada para llegar en muchos frames chicos; el texto reconstruido es exacto. */
    @Test
    @Timeout(30)
    void a8_muchosFramesChicosSeReconstruyenExactos() {
        StringBuilder esperado = new StringBuilder();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int i = 0; i < 200; i++) {
            String pedazo = "L" + i + ";";
            esperado.append(pedazo);
            stream.writeBytes(DaemonDePrueba.frame(1, pedazo));
        }
        daemon.logs = stream.toByteArray();

        var salida = docker.logs("cualquiera");

        assertEquals(esperado.toString(), salida.stdout());
        assertFalse(salida.truncada());
    }

    /**
     * A9: un frame que declara 4 GiB (largo {@code 0xFFFFFFFF}).
     *
     * <b>La hipotesis de partida era otra, y la refuto un experimento, no una lectura del
     * fuente.</b> Leyendo {@code FramedInputStreamConsumer} se esperaba
     * {@code IndexOutOfBoundsException} directo, porque el uint32 del largo se acumula en un
     * {@code int} a fuerza de corrimientos de bits ({@code 0xFFFFFFFF} da {@code -1}) y el bucle
     * hace {@code body.read(buffer, 0, Math.min(1024, bytesToRead))} con {@code bytesToRead}
     * negativo. Probado contra {@code DaemonDePrueba} eso NO pasa siempre: el {@code -1} baja hasta
     * {@code SessionInputBufferImpl.read} (httpclient5, debajo de {@code ChunkedInputStream}), y
     * ahi el resultado depende de si ese objeto tiene algun byte del stream ya bufferizado en ese
     * instante:
     * <ul>
     *   <li>si SI hay bytes bufferizados (el caso realista: el stream sigue despues del frame
     *       corrupto, como cualquier log que continua), intenta un
     *       {@code System.arraycopy(..., length=-1)} y tira
     *       {@code ArrayIndexOutOfBoundsException} -&gt; {@code onError} -&gt; {@code ErrorDaemon}.
     *       Es el caso que este test fija, agregando algo de stream despues del encabezado
     *       mentiroso para que ese buffer nunca este vacio en ese instante.</li>
     *   <li>si NO hay nada mas bufferizado (el frame corrupto es literalmente el final del
     *       stream), la lectura da {@code -1} de EOF antes de llegar al arraycopy, y
     *       {@code FramedInputStreamConsumer} hace {@code return} liso y llano: sin excepcion, sin
     *       dato. Es indistinguible de R6.5 (ver {@code r65_...SoloEncabezadoSinNadaDespues} mas
     *       abajo) y NO es lo que este test mide.</li>
     * </ul>
     * Lo que las dos ramas comparten, y es lo unico que R6.2 puede seguir prometiendo con la
     * libreria puesta: en NINGUNA se reserva memoria proporcional a los 4 GiB declarados (el
     * buffer sigue siendo el fijo de 1024 bytes) y el desenlace es practicamente instantaneo. Que
     * termine en error o en silencio ya no es una garantia nuestra, es un accidente de si el
     * transporte tenia algo bufferizado en ese momento exacto.
     */
    @Test
    @Timeout(10)
    void a9_declaradoDeCuatroGibNoReservaMemoriaYSeResuelveDeInmediato() {
        // Encabezado con largo mentiroso + algo de stream despues, para que la lectura corrupta
        // encuentre bytes ya bufferizados del lado del cliente (ver el javadoc: sin esto cae en la
        // rama silenciosa de R6.5, no en la de error).
        daemon.logs = concatenar(cabecera(1, 0xFFFFFFFFL), "AAAAAAAAAA".getBytes(StandardCharsets.UTF_8));

        long inicio = System.nanoTime();
        ErrorDaemon e = assertThrows(ErrorDaemon.class, () -> docker.logs("cualquiera"));
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertTrue(ms < 2000, "el desenlace tiene que ser practicamente instantaneo, tardo " + ms + " ms");
        assertTrue(e.getMessage().contains("ArrayIndexOutOfBoundsException"), e.getMessage());
    }

    /**
     * R6.5 reescrita, caso limite de A9: si el encabezado con largo mentiroso es literalmente lo
     * ultimo del stream (nada bufferizado despues), el mismo largo de 4 GiB NO da error: la lectura
     * corrupta encuentra el fin del stream (EOF) antes que datos, y
     * {@code FramedInputStreamConsumer} corta en silencio, igual que cualquier otro truncamiento.
     * Confirma que el resultado de A9 depende del contenido posterior, no del largo declarado en si.
     */
    @Test
    @Timeout(10)
    void r65_elMismoLargoDeCuatroGibSinNadaDespuesTerminaEnSilencio() {
        daemon.logs = cabecera(1, 0xFFFFFFFFL);   // nada mas despues del encabezado

        var salida = docker.logs("cualquiera");

        assertEquals("", salida.stdout());
        assertFalse(salida.truncada());
    }

    /** A10: un frame de largo 0 en el medio no traba el bucle. */
    @Test
    @Timeout(30)
    void a10_frameDeLargoCeroEnElMedioNoTrabaElBucle() {
        daemon.logs = concatenar(
                DaemonDePrueba.frame(1, "antes\n"),
                cabecera(1, 0),
                DaemonDePrueba.frame(1, "despues\n"));

        var salida = docker.logs("cualquiera");

        assertEquals("antes\ndespues\n", salida.stdout());
    }

    /**
     * A11: tipo de stream invalido (aca 7, que docker-java clasifica como {@code RAW}) es error
     * fatal; no se asume stdout.
     */
    @Test
    @Timeout(30)
    void a11_tipoDeStreamInvalidoEsErrorFatal() {
        daemon.logs = new byte[] {7};   // ni encabezado completo hace falta: RAW se detecta con el primer byte

        ErrorDaemon e = assertThrows(ErrorDaemon.class, () -> docker.logs("cualquiera"));
        assertTrue(e.getMessage().contains("RAW"), e.getMessage());
    }

    /** A11 (variante): tipo STDIN (0) tampoco tiene sentido en una respuesta y tambien es fatal. */
    @Test
    @Timeout(30)
    void a11_tipoStdinTampocoTieneSentidoEnUnaRespuesta() {
        daemon.logs = DaemonDePrueba.frame(0, "no deberia estar aca");

        ErrorDaemon e = assertThrows(ErrorDaemon.class, () -> docker.logs("cualquiera"));
        assertTrue(e.getMessage().contains("STDIN"), e.getMessage());
    }

    /** A12: una linea que cruza dos frames se reconstruye sin cortes espurios. */
    @Test
    @Timeout(30)
    void a12_unaLineaQueCruzaDosFramesSeReconstruyeSinCortes() {
        daemon.logs = concatenar(
                DaemonDePrueba.frame(1, "ho"),
                DaemonDePrueba.frame(1, "la mundo\nsegunda linea\n"));

        var salida = docker.logs("cualquiera");

        assertEquals("hola mundo\nsegunda linea\n", salida.stdout());
    }

    /**
     * R6.2 reescrita: un largo declarado por debajo de 2^31 -aunque supere holgadamente
     * {@code MAX_SALIDA_BYTES}- NO aborta la lectura. Lo unico que la detiene es el tope propio de
     * {@link Acumulador} (R6.7): el payload llega en trozos de 1024 bytes (el buffer fijo de la
     * libreria) y el buffer de {@code Acumulador} corta al llegar al tope, marcando
     * {@code truncada}.
     */
    @Test
    @Timeout(30)
    void r62_unLargoBajoDosElevadoATreintaYUnoNoAbortaYElTopePropioLoTrunca() {
        String grande = "y".repeat(Constantes.MAX_SALIDA_BYTES + 5000);
        daemon.logs = DaemonDePrueba.frame(1, grande);

        var salida = docker.logs("cualquiera");

        assertTrue(salida.truncada());
        assertEquals(Constantes.MAX_SALIDA_BYTES, salida.stdout().length());
    }

    /**
     * R6.5 reescrita: la conexion HTTP termina de forma perfectamente valida (el cuerpo chunked se
     * cierra con {@code "0\r\n\r\n"}), pero el CONTENIDO de docker dentro de ese cuerpo se corta a
     * la mitad de un encabezado.
     *
     * <b>Confirmado.</b> {@code FramedInputStreamConsumer} no distingue esto de un fin de stream
     * normal: cada vez que {@code body.read()} devuelve {@code -1} a mitad de encabezado, el
     * metodo hace {@code return} liso y llano, sin pasar por {@code onError}. La lectura se
     * completa con lo que llego, sin marcar {@code ERROR_DAEMON}. Es el caso realista de "el stream
     * se corto": no hace falta un cierre abortivo de socket para producirlo, alcanza con que el
     * cuerpo HTTP sea mas corto que el frame que promete.
     */
    @Test
    @Timeout(30)
    void r65_unEncabezadoTruncadoTerminaLaLecturaEnSilencioSinError() {
        byte[] frameCompleto = DaemonDePrueba.frame(1, "primero\n");
        byte[] encabezadoAMedias = new byte[] {1, 0, 0, 0, 0, 0};   // le faltan los 2 ultimos bytes del largo
        daemon.logs = concatenar(frameCompleto, encabezadoAMedias);

        var salida = docker.logs("cualquiera");

        assertEquals("primero\n", salida.stdout(), "el frame completo antes del corte se conserva");
        assertFalse(salida.truncada(), "esto no es el tope de R6.7: el stream se corto solo, nadie lo recorto");
    }

    /**
     * R6.5 reescrita, variante con payload truncado: el encabezado declara un largo que el cuerpo
     * no llega a cumplir. Misma conclusion que el caso anterior: se conserva lo que llego, sin
     * error.
     */
    @Test
    @Timeout(30)
    void r65_unPayloadTruncadoTerminaLaLecturaEnSilencioSinError() {
        byte[] encabezado = cabecera(1, 20);   // declara 20 bytes...
        byte[] payloadCorto = "solo diez.".getBytes(StandardCharsets.UTF_8);   // ...pero llegan 10
        daemon.logs = concatenar(encabezado, payloadCorto);

        var salida = docker.logs("cualquiera");

        assertEquals("solo diez.", salida.stdout());
        assertFalse(salida.truncada());
    }
}
