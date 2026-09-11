package sandbox.ejecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A6: el test que valida la seccion 7 contra un daemon de Docker real.
 *
 * La spec dice que si este test falla, la seccion 7 hay que rediseniarla y esto bloquea todo lo
 * demas. Con el framing de tres documentos son cuatro hipotesis, no una: que `read` no consuma mas
 * que su linea (dos veces), que `dd iflag=fullblock` lea exactamente los N bytes del guion sin
 * comerse nada del tar, y que el tar llegue entero detras.
 *
 * <b>Corre nativo en Windows.</b> Antes hacia falta meter la suite adentro de un contenedor con el
 * socket montado, porque Java no podia hablarle al named pipe de Docker Desktop. El transporte
 * httpclient5 de docker-java si lo habla: {@link Docker#conectar} resuelve npipe o unix segun la
 * plataforma. Si no hay daemon, el test se saltea.
 */
class ProtocoloIT {

    /**
     * El guion de la capa 2 del fixture. Lleva un acento a proposito: si el largo se contara en
     * caracteres en vez de bytes, el `dd` del fixture leeria uno de menos y se comeria el primer
     * byte del tar. Es el bug que este test tiene que ver.
     *
     * Vive en src/test/resources/perfiles-it/test-busybox@1.json, byte a byte igual a esta
     * constante -se comparan mas abajo, en levantar()-, y es la TRAMPA que el handoff pedia
     * resolver: la imagen de este perfil de prueba sigue apuntando a sandbox-runner:1.0.0 -el
     * fixture de busybox-, que era la imagen fija del ejecutor antes de que pasara a salir del
     * catalogo de perfiles.
     */
    private static final byte[] GUION =
            "#!/bin/sh\n# guion de juguete de la capa 2: compilación\necho hola\n"
                    .getBytes(StandardCharsets.UTF_8);

    private Docker docker;
    private Ejecucion ejecucion;

    @BeforeEach
    void levantar() throws IOException, URISyntaxException {
        Docker candidato;
        try {
            candidato = Docker.conectar(dockerHost());
            assumeTrue(candidato.ping(), "no hay daemon de Docker escuchando; el test se saltea");
        } catch (RuntimeException e) {
            org.junit.jupiter.api.Assumptions.abort("no se pudo abrir el transporte a Docker: " + e);
            return;
        }
        docker = candidato;
        Catalogo catalogo = Catalogo.cargar(directorioPerfilesDePrueba());
        Catalogo.Perfil perfil = catalogo.buscar("test-busybox@1");
        assertArrayEquals(GUION, perfil.script(),
                "el perfil de prueba de test resources se desincronizo de GUION");
        ejecucion = new Ejecucion(docker, catalogo);
    }

    private static Path directorioPerfilesDePrueba() throws URISyntaxException {
        return Path.of(ProtocoloIT.class.getResource("/perfiles-it").toURI());
    }

    @AfterEach
    void bajar() throws Exception {
        if (docker != null) docker.close();
    }

    private static String dockerHost() {
        String env = System.getenv("DOCKER_HOST");
        if (env != null && !env.isBlank()) return env;
        return System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "npipe:////./pipe/docker_engine"
                : "unix:///var/run/docker.sock";
    }

    /**
     * A6. Se corre la spec de produccion sin tocar un solo campo; lo unico distinto es que
     * {@code sandbox-runner:1.0.0} apunta al fixture de busybox en vez de al runner con la JVM.
     */
    @Test
    @Timeout(180)
    void a6_losTresDocumentosLleganEnterosYEnOrden() {
        List<String> entradas = List.of("Solucion.java", "pom.xml", "src/test/SolucionTest.java");
        byte[] tar = tarConEntradas(entradas);

        var salida = ejecucion.ejecutar(UUID.randomUUID().toString(), tar, "test-busybox@1");

        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado(),
                "stderr del contenedor: " + salida.stderr());
        assertEquals(0, salida.exitCode(), "stderr del contenedor: " + salida.stderr());

        // 1) `read` leyo la primera linea entera y nada mas: el eco trae el nonce completo.
        var eco = Pattern.compile("FIN ([0-9a-f]{32})").matcher(salida.stdout());
        assertTrue(eco.find(), "la capa 1 no imprimio 'FIN <nonce>'; stdout: " + salida.stdout());
        String nonce = eco.group(1);

        // 2) `read` de la segunda linea tampoco se paso, y `dd` leyo exactamente N bytes.
        assertTrue(salida.stdout().contains("GUION " + GUION.length + " de " + GUION.length),
                "el guion no llego completo o llego de mas; stdout: " + salida.stdout());

        assertFalse(salida.reporteAusente(), "el bloque de reporte no aparecio");
        String reporte = salida.reporte();

        // 3) El guion viajo byte a byte, con el acento intacto.
        assertTrue(reporte.contains("compilación"),
                "el guion llego corrupto: el largo se conto en caracteres, no en bytes");

        // 4) Al tar le quedo el resto del stream, entero y sin decapitar.
        assertTrue(reporte.contains("TAR " + tar.length),
                "el tar llego con otro tamanio; alguien se comio bytes del stream: " + reporte);
        for (String entrada : entradas) {
            assertTrue(reporte.contains(entrada), "falta " + entrada + " en el listado: " + reporte);
        }

        // 5) El bloque salio de stdout, y el nonce que el ejecutor uso para extraerlo es el mismo
        //    que la capa 1 leyo de stdin.
        assertFalse(salida.stdout().contains("---SANDBOX-" + nonce + "-INICIO---"));
        assertFalse(salida.stdout().contains(entradas.get(0)));
    }

    /**
     * El cierre del canal adjunto llega como EOF. Es lo que docker-java NO hace solo: su escritor
     * vacia el stream y deja la conexion abierta. El fixture hace `cat > archivo`, que sin EOF no
     * vuelve nunca; si esto termina en COMPLETADA y no en TIMEOUT, el EOF llego.
     */
    @Test
    @Timeout(180)
    void elCierreDelCanalAdjuntoEsElEofDelContenedor() {
        var salida = ejecucion.ejecutar(UUID.randomUUID().toString(), tarConEntradas(List.of("a.txt")), "test-busybox@1");
        assertEquals(Ejecucion.Estado.COMPLETADA, salida.resultado(),
                "sin EOF la capa 1 se cuelga en el cat del bundle hasta el reloj de pared");
    }

    /** A40: el daemon real de este entorno tiene que pasar la verificacion de arranque. */
    @Test
    @Timeout(30)
    void a40_elDaemonRealPasaLaVerificacionDeVersion() {
        assertDoesNotThrow(docker::verificarVersion);
    }

    /** Que el contenedor termine borrado, por la via normal (I6). */
    @Test
    @Timeout(180)
    void i6_noQuedanContenedoresDeEstaEjecucion() {
        ejecucion.ejecutar(UUID.randomUUID().toString(), tarConEntradas(List.of("a.txt")), "test-busybox@1");
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
