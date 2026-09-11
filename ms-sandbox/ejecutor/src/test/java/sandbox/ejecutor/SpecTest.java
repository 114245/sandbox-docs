package sandbox.ejecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A1 y A2: golden test de la spec. El test mas importante de la suite.
 *
 * Con docker-java la spec ya no se serializa a mano, pero el golden NO se perdio: el comando de
 * create es el modelo del cuerpo del pedido y {@link Spec#bytesDeCreate} lo serializa con el mismo
 * ObjectMapper que usa la libreria para mandarlo. Los bytes que compara este test son los bytes que
 * salen al cable, y {@code EjecucionTest#elCuerpoDeCreateEsElGolden} lo confirma contra lo que el
 * daemon efectivamente recibio.
 *
 * Paso 2 del handoff (catalogo de perfiles): el golden ahora es POR PERFIL. La referencia se
 * regenero con el perfil de referencia de abajo ({@link #PERFIL_REFERENCIA}), que es el mismo
 * java21-junit@4 del catalogo real salvo por el contenido del guion, que no toca la spec del
 * contenedor (el guion viaja por stdin, no por ningun campo de create).
 */
class SpecTest {

    private static final String PLACEHOLDER = "<EJECUCION_ID>";

    /** El perfil con el que se regenero spec-create-referencia.json. */
    private static final Catalogo.Perfil PERFIL_REFERENCIA = Catalogo.Perfil.armar(
            "java21-junit", 3, "sandbox-runner:2.0.0-capa1",
            "#!/bin/sh\necho referencia\n".getBytes(StandardCharsets.UTF_8),
            "junit-xml", 512, 20);

    private static String referencia() throws Exception {
        try (InputStream in = SpecTest.class.getResourceAsStream("/spec-create-referencia.json")) {
            assertNotNull(in, "falta src/test/resources/spec-create-referencia.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /** A1: tres ids distintos producen el mismo JSON una vez normalizado el label, para el mismo perfil. */
    @Test
    void a1_jsonDeCreateEsIdenticoEntreEjecuciones() throws Exception {
        List<String> ids = List.of(
                "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
                "00000000-0000-4000-8000-000000000000",
                "ffffffff-ffff-4fff-bfff-ffffffffffff");

        String esperado = referencia();
        for (String id : ids) {
            String normalizado = new String(Golden.bytes(id, PERFIL_REFERENCIA), StandardCharsets.UTF_8)
                    .replace(id, PLACEHOLDER);
            assertEquals(esperado, normalizado, "la spec cambio para el id " + id);
        }
    }

    /** A1: el nombre del contenedor es la otra unica variacion permitida por ejecucion. */
    @Test
    void a1_nombreDelContenedor() {
        assertEquals("sandbox-abc", Spec.nombre("abc"));
        assertEquals("sandbox-abc", Golden.comando("abc", PERFIL_REFERENCIA).getName());
    }

    /**
     * Guarda contra un archivo de referencia generado desde un bug: los campos de seguridad
     * se afirman aca de forma explicita, no por comparacion con el golden.
     */
    @Test
    void camposDeSeguridadExplicitos() throws Exception {
        JsonNode n = new ObjectMapper().readTree(Golden.bytes("un-id", PERFIL_REFERENCIA));
        JsonNode h = n.get("HostConfig");

        assertEquals("none", h.get("NetworkMode").asText());
        assertTrue(h.get("ReadonlyRootfs").asBoolean());
        // uid/gid no son cosmeticos: sin ellos el tmpfs queda de root y el contenedor,
        // que corre como 1000:1000, no puede escribir en su unico directorio escribible.
        assertEquals("rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000",
                     h.get("Tmpfs").get("/work").asText());
        long memoriaEsperada = PERFIL_REFERENCIA.memoriaMb() * 1024L * 1024L;
        assertEquals(memoriaEsperada, h.get("Memory").asLong());
        assertEquals(h.get("Memory").asLong(), h.get("MemorySwap").asLong(), "Memory debe ser igual a MemorySwap: sin swap");
        assertEquals(0, h.get("MemorySwappiness").asInt());
        assertEquals(1000000000L, h.get("NanoCpus").asLong());
        assertEquals(128, h.get("PidsLimit").asInt());
        assertEquals("ALL", h.get("CapDrop").get(0).asText());
        assertEquals(0, h.get("CapAdd").size());
        assertEquals("no-new-privileges:true", h.get("SecurityOpt").get(0).asText());
        assertFalse(h.get("Privileged").asBoolean());
        assertFalse(h.get("AutoRemove").asBoolean(), "AutoRemove true haria desaparecer el contenedor antes de leer logs");
        assertEquals(0, h.get("Binds").size());
        assertEquals(0, h.get("Mounts").size());
        assertEquals(0, h.get("Devices").size());
        // docker-java manda "" y no "no". Para el daemon son lo mismo -"" es la ausencia de
        // politica, que es lo que pone `docker run` sin --restart- pero el byte cambio respecto del
        // cliente a mano, asi que se afirma lo que efectivamente sale al cable.
        assertEquals("", h.get("RestartPolicy").get("Name").asText());
        assertEquals(0, h.get("RestartPolicy").get("MaximumRetryCount").asInt());
        assertEquals("json-file", h.get("LogConfig").get("Type").asText());

        assertFalse(n.get("Tty").asBoolean(), "Tty true haria que la salida no venga enmarcada");
        assertTrue(n.get("NetworkDisabled").asBoolean());
        assertTrue(n.get("OpenStdin").asBoolean());
        assertTrue(n.get("StdinOnce").asBoolean(),
                "sin StdinOnce, cerrar el canal adjunto no le da EOF al stdin del contenedor");
        assertTrue(n.get("AttachStdin").asBoolean());
        assertFalse(n.get("AttachStdout").asBoolean());
        assertFalse(n.get("AttachStderr").asBoolean());
        assertEquals("1000:1000", n.get("User").asText());
        assertEquals("/work", n.get("WorkingDir").asText());
        assertEquals(0, n.get("Env").size());
        assertEquals(Constantes.ENTRYPOINT, n.get("Entrypoint").get(0).asText());
        assertEquals("1", n.get("Labels").get("sandbox").asText());
        assertEquals(PERFIL_REFERENCIA.imagen(), n.get("Image").asText());
    }

    /**
     * A32 (C1), adaptado al catalogo: el ulimit `cpu` sale del perfil, y CPU_MAX_S -el techo que
     * Catalogo hace cumplir al cargar cualquier perfil- sigue guardando la misma holgura de R4.1
     * que antes garantizaba la constante fija.
     */
    @Test
    void a32_ulimitCpuYSuRelacionConElRelojDePared() throws Exception {
        JsonNode ulimits = new ObjectMapper().readTree(Golden.bytes("un-id", PERFIL_REFERENCIA))
                .get("HostConfig").get("Ulimits");

        JsonNode cpu = null;
        for (JsonNode u : ulimits) if ("cpu".equals(u.get("Name").asText())) cpu = u;
        assertNotNull(cpu, "falta el ulimit cpu: vuelven los TIMEOUT intermitentes por varianza del pool");
        assertEquals(PERFIL_REFERENCIA.cpuS(), cpu.get("Soft").asInt());
        assertEquals(PERFIL_REFERENCIA.cpuS(), cpu.get("Hard").asInt());

        // Assert sobre las constantes, no sobre el JSON: protege contra que alguien suba el techo
        // de CPU del catalogo sin subir el reloj de pared y deje R4.1 decorativo para todo perfil.
        long relojDeParedSegundos = Constantes.TIMEOUT_EJECUCION_MS / 1000;
        assertTrue(Constantes.CPU_MAX_S < relojDeParedSegundos,
                "CPU_MAX_S (" + Constantes.CPU_MAX_S + ") tiene que ser menor que "
                        + relojDeParedSegundos + " s de reloj de pared");
        assertTrue(Constantes.CPU_MAX_S * 2 <= relojDeParedSegundos,
                "la holgura de R4.1 se perdio: con NanoCpus=1 el reloj de pared dispararia primero");
    }

    /** I2: el id de ejecucion aparece solo en el label, en ningun otro campo del cuerpo. */
    @Test
    void a1_elIdSoloTocaElLabel() throws Exception {
        String id = "11111111-2222-4333-8444-555555555555";
        JsonNode n = new ObjectMapper().readTree(Golden.bytes(id, PERFIL_REFERENCIA));
        assertEquals(id, n.get("Labels").get("sandbox.ejecucion").asText());

        String sinLabel = new String(Golden.bytes(id, PERFIL_REFERENCIA), StandardCharsets.UTF_8)
                .replace("\"sandbox.ejecucion\":\"" + id + "\"", "");
        assertFalse(sinLabel.contains(id), "el id se filtro a otro campo de la spec");
    }

    /**
     * P1 (Paso 2 del handoff): el test que reemplaza al golden fijo unico. Dos perfiles bien
     * distintos producen dos cuerpos de create que difieren SOLO en Image, Memory, MemorySwap y
     * Ulimits[cpu], e identicos en todo lo demas.
     */
    @Test
    void p1_dosPerfilesDifierenSoloEnLosCuatroCamposVariables() throws Exception {
        Catalogo.Perfil a = PERFIL_REFERENCIA;
        Catalogo.Perfil b = Catalogo.Perfil.armar(
                "python3-pytest", 1, "sandbox-runner:otra-imagen-2.0.0",
                "#!/bin/sh\necho otro perfil\n".getBytes(StandardCharsets.UTF_8),
                "pytest-json", 256, 10);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode ja = (ObjectNode) mapper.readTree(Golden.bytes("mismo-id", a));
        ObjectNode jb = (ObjectNode) mapper.readTree(Golden.bytes("mismo-id", b));

        // Los cuatro campos SI tienen que diferir con estos dos perfiles.
        assertNotEquals(ja.get("Image").asText(), jb.get("Image").asText());
        ObjectNode ha = (ObjectNode) ja.get("HostConfig");
        ObjectNode hb = (ObjectNode) jb.get("HostConfig");
        assertNotEquals(ha.get("Memory").asLong(), hb.get("Memory").asLong());
        assertNotEquals(ha.get("MemorySwap").asLong(), hb.get("MemorySwap").asLong());
        assertNotEquals(cpuSoft(ha), cpuSoft(hb));

        // Normalizados esos cuatro campos, el resto tiene que ser byte a byte identico.
        ja.put("Image", "IGUAL");
        jb.put("Image", "IGUAL");
        ha.put("Memory", 0L);
        hb.put("Memory", 0L);
        ha.put("MemorySwap", 0L);
        hb.put("MemorySwap", 0L);
        normalizarUlimitCpu(ha);
        normalizarUlimitCpu(hb);

        assertEquals(ja, jb, "los perfiles difieren en algo mas que Image, Memory, MemorySwap y Ulimits[cpu]");
    }

    private static int cpuSoft(ObjectNode host) {
        for (JsonNode u : host.get("Ulimits")) {
            if ("cpu".equals(u.get("Name").asText())) return u.get("Soft").asInt();
        }
        return fail("falta el ulimit cpu");
    }

    private static void normalizarUlimitCpu(ObjectNode host) {
        ArrayNode ulimits = ((ArrayNode) host.get("Ulimits")).deepCopy();
        for (JsonNode u : ulimits) {
            if ("cpu".equals(u.get("Name").asText())) {
                ((ObjectNode) u).put("Soft", 0).put("Hard", 0);
            }
        }
        host.set("Ulimits", ulimits);
    }
}
