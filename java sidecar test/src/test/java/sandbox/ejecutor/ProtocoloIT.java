package sandbox.ejecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A6: el test que valida la seccion 7 contra un daemon de Docker real.
 *
 * La spec dice que si este test falla, la seccion 7 hay que rediseniarla y esto bloquea todo lo
 * demas. El mecanismo del nonce depende de que `read`, en el shell del entrypoint, no consuma mas
 * que la primera linea del descriptor y le deje el resto del stream a tar.
 *
 * Requiere el socket del daemon y la imagen fixture. Sin eso se saltea, para que la suite siga
 * verde donde no hay Docker (por ejemplo, Windows nativo).
 */
class ProtocoloIT {

    private static final Path SOCKET_DAEMON = Path.of("/var/run/docker.sock");

    private ScheduledExecutorService reloj;
    private ExecutorService hilos;
    private Ejecucion ejecucion;

    @BeforeEach
    void levantar() {
        assumeTrue(Files.exists(SOCKET_DAEMON),
                "sin /var/run/docker.sock: correr con scripts/verificar-a6.ps1");
        reloj = Executors.newScheduledThreadPool(2);
        hilos = Executors.newVirtualThreadPerTaskExecutor();
        ejecucion = new Ejecucion(new ClienteDocker(SOCKET_DAEMON.toString(), reloj), hilos, reloj);
    }

    @AfterEach
    void bajar() {
        if (reloj != null) reloj.shutdownNow();
        if (hilos != null) hilos.shutdownNow();
    }

    /**
     * A6. Se corre la spec de produccion sin tocar un solo campo; lo unico distinto es que
     * sandbox-runner:1.0.0 apunta al fixture de busybox en vez de al runner con la JVM.
     */
    @Test
    @Timeout(120)
    void a6_elNonceViajaPorStdinYElTarLlegaEntero() {
        List<String> entradas = List.of("Solucion.java", "pom.xml", "src/test/SolucionTest.java");
        byte[] tar = tarConEntradas(entradas);

        var salida = ejecucion.ejecutar(UUID.randomUUID().toString(), tar);

        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado(),
                "stderr del contenedor: " + salida.stderr());
        assertEquals(0, salida.exitCode(), "stderr del contenedor: " + salida.stderr());

        // 1) `read` leyo la primera linea entera y nada mas: el eco trae el nonce completo.
        var eco = java.util.regex.Pattern.compile("FIN ([0-9a-f]{32})").matcher(salida.stdout());
        assertTrue(eco.find(), "el entrypoint no imprimio 'FIN <nonce>'; stdout: " + salida.stdout());
        String nonce = eco.group(1);

        // 2) A tar le quedo el resto del stream intacto: lista las tres entradas.
        assertFalse(salida.reporteAusente(), "el bloque de reporte no aparecio");
        for (String entrada : entradas) {
            assertTrue(salida.reporte().contains(entrada),
                    "falta " + entrada + " en el listado: " + salida.reporte());
        }

        // 3) El bloque salio de stdout, y el nonce que el ejecutor uso para extraerlo es el mismo
        //    que el entrypoint leyo de stdin.
        assertFalse(salida.stdout().contains("---SANDBOX-" + nonce + "-INICIO---"));
        assertFalse(salida.stdout().contains(entradas.get(0)));
    }

    /** Que el contenedor termine borrado, por la via normal (I6). */
    @Test
    @Timeout(120)
    void i6_noQuedanContenedoresDeEstaEjecucion() {
        ejecucion.ejecutar(UUID.randomUUID().toString(), tarConEntradas(List.of("a.txt")));
        assertTrue(ejecucion.contenedoresEnVuelo().isEmpty());
    }

    /** Tar ustar armado a mano: el ejecutor no lo interpreta, asi que no hace falta una libreria. */
    private static byte[] tarConEntradas(List<String> nombres) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (String nombre : nombres) {
            byte[] contenido = ("contenido de " + nombre + "\n").getBytes(StandardCharsets.UTF_8);
            salida.writeBytes(encabezado(nombre, contenido.length));
            salida.writeBytes(contenido);
            salida.writeBytes(new byte[(512 - contenido.length % 512) % 512]);
        }
        salida.writeBytes(new byte[1024]);   // dos bloques en cero cierran el archivo
        return salida.toByteArray();
    }

    private static byte[] encabezado(String nombre, int largo) {
        byte[] h = new byte[512];
        poner(h, 0, nombre);
        poner(h, 100, "000644 ");
        poner(h, 108, "001750 ");
        poner(h, 116, "001750 ");
        poner(h, 124, String.format("%011o ", largo));
        poner(h, 136, String.format("%011o ", 0));
        for (int i = 148; i < 156; i++) h[i] = ' ';   // el checksum se calcula con este campo en blanco
        h[156] = '0';
        poner(h, 257, "ustar");
        h[263] = '0'; h[264] = '0';

        int suma = 0;
        for (byte b : h) suma += b & 0xFF;
        poner(h, 148, String.format("%06o", suma));
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void poner(byte[] destino, int desde, String valor) {
        byte[] b = valor.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, destino, desde, b.length);
    }
}
