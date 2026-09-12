package sandbox.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LA PRUEBA QUE CIERRA D16.
 *
 * Son los pasos 4 y 5 de la validacion de 12-d16 seccion 8.4, que quedaron sin correr con una
 * razon explicita: "no existen todavia ni los perfiles ni el worker". Los perfiles ya existen;
 * con esta rodaja existe el worker.
 *
 * Necesita el ejecutor levantado contra Docker real. Sin socket, se saltea.
 */
class D16IT {

    private static final String PERFIL  = "java21-junit@4";
    private static final Path   BUNDLES = Path.of("..", "pruebas", "bundles");

    private static Path socket() {
        String desdeElEntorno = System.getenv("SANDBOX_EJECUTOR_SOCKET");
        return Path.of(desdeElEntorno != null ? desdeElEntorno : Constantes.RUTA_SOCKET);
    }

    private static Fallo veredictoDe(String bundle) {
        byte[] tar = Empaquetador.desdeDirectorio(BUNDLES.resolve(bundle));
        Sobre sobre = new ClienteEjecutor(socket()).ejecutar(UUID.randomUUID().toString(), PERFIL, tar);

        assertFalse(sobre.reporteAusente(), "el ejecutor no encontro bloque de reporte");

        SobreCapa1 capa1  = Desempaquetador.leerSobre(sobre.reporte());
        Buzon      buzon  = Desempaquetador.abrirBuzon(capa1);

        Optional<Evidencia> evidencia = Verificadores.porDefecto()
                .para("junit-xml")
                .map(v -> v.verificar(buzon));

        return Juez.juzgar(capa1, sobre.oomKilled(), evidencia);
    }

    @Test
    void unaEntregaQueLlamaSystemExitCeroNoPuedeAprobar() {
        assumeTrue(Files.exists(socket()), "sin socket del ejecutor: se saltea");

        Fallo fallo = veredictoDe("hostil-exit0");

        assertNotEquals(Veredicto.EXITO, fallo.veredicto(),
                "hostil-exit0 aprobo: la guarda de D16 no esta sosteniendo nada");
        assertEquals(Veredicto.SALIDA_ANTICIPADA, fallo.veredicto());
    }

    @Test
    void elCaminoFelizDeTresXmlAprueba() {
        assumeTrue(Files.exists(socket()), "sin socket del ejecutor: se saltea");

        // ok-suma deja TRES XML y DOS con tests="0". Si el verificador mirara archivo por
        // archivo en vez de sumar, esto daria SALIDA_ANTICIPADA y el camino feliz estaria roto.
        Fallo fallo = veredictoDe("ok-suma");

        assertEquals(Veredicto.EXITO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unFormatoSinVerificadorNoPuedeAprobarElCaminoFeliz() {
        assumeTrue(Files.exists(socket()), "sin socket del ejecutor: se saltea");

        // El fail-closed contra el sobre REAL del camino feliz: cambiar solo el formato
        // declarado tiene que tumbar el EXITO. Es la propiedad de 12-d16 seccion 5.
        byte[] tar = Empaquetador.desdeDirectorio(BUNDLES.resolve("ok-suma"));
        Sobre sobre = new ClienteEjecutor(socket()).ejecutar(UUID.randomUUID().toString(), PERFIL, tar);
        SobreCapa1 capa1 = Desempaquetador.leerSobre(sobre.reporte());

        Optional<Evidencia> sinVerificador = Verificadores.porDefecto()
                .para("pmd-xml")
                .map(v -> v.verificar(Desempaquetador.abrirBuzon(capa1)));

        Fallo fallo = Juez.juzgar(capa1, sobre.oomKilled(), sinVerificador);

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }
}
