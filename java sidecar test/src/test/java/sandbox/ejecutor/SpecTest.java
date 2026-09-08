package sandbox.ejecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A1 y A2: golden test de la spec. El test mas importante de la suite. */
class SpecTest {

    private static final String PLACEHOLDER = "<EJECUCION_ID>";

    private static String referencia() throws Exception {
        try (InputStream in = SpecTest.class.getResourceAsStream("/spec-create-referencia.json")) {
            assertNotNull(in, "falta src/test/resources/spec-create-referencia.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /** A1: tres ids distintos producen el mismo JSON una vez normalizado el label. */
    @Test
    void a1_jsonDeCreateEsIdenticoEntreEjecuciones() throws Exception {
        List<String> ids = List.of(
                "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
                "00000000-0000-4000-8000-000000000000",
                "ffffffff-ffff-4fff-bfff-ffffffffffff");

        String esperado = referencia();
        for (String id : ids) {
            String normalizado = new String(Spec.crear(id), StandardCharsets.UTF_8).replace(id, PLACEHOLDER);
            assertEquals(esperado, normalizado, "la spec cambio para el id " + id);
        }
    }

    /** A1: el nombre del contenedor es la otra unica variacion permitida. */
    @Test
    void a1_nombreDelContenedor() {
        assertEquals("sandbox-abc", Spec.nombre("abc"));
    }

    /**
     * Guarda contra un archivo de referencia generado desde un bug: los campos de seguridad
     * se afirman aca de forma explicita, no por comparacion con el golden.
     */
    @Test
    void camposDeSeguridadExplicitos() throws Exception {
        JsonNode n = new ObjectMapper().readTree(Spec.crear("un-id"));
        JsonNode h = n.get("HostConfig");

        assertEquals("none", h.get("NetworkMode").asText());
        assertTrue(h.get("ReadonlyRootfs").asBoolean());
        // uid/gid no son cosmeticos: sin ellos el tmpfs queda de root y el contenedor,
        // que corre como 1000:1000, no puede escribir en su unico directorio escribible.
        assertEquals("rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000",
                     h.get("Tmpfs").get("/work").asText());
        assertEquals(536870912L, h.get("Memory").asLong());
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
        assertEquals("no", h.get("RestartPolicy").get("Name").asText());
        assertEquals("json-file", h.get("LogConfig").get("Type").asText());

        assertFalse(n.get("Tty").asBoolean(), "Tty true haria que la salida no venga enmarcada");
        assertTrue(n.get("NetworkDisabled").asBoolean());
        assertTrue(n.get("OpenStdin").asBoolean());
        assertTrue(n.get("AttachStdin").asBoolean());
        assertFalse(n.get("AttachStdout").asBoolean());
        assertFalse(n.get("AttachStderr").asBoolean());
        assertEquals("1000:1000", n.get("User").asText());
        assertEquals("/work", n.get("WorkingDir").asText());
        assertEquals(0, n.get("Env").size());
        assertEquals("1", n.get("Labels").get("sandbox").asText());
    }

    /**
     * A32 (C1): el ulimit `cpu` esta en la spec con TIMEOUT_CPU_SEGUNDOS, y el presupuesto de CPU
     * es holgadamente menor que el reloj de pared (R4.1).
     */
    @Test
    void a32_ulimitCpuYSuRelacionConElRelojDePared() throws Exception {
        JsonNode ulimits = new ObjectMapper().readTree(Spec.crear("un-id")).get("HostConfig").get("Ulimits");

        JsonNode cpu = null;
        for (JsonNode u : ulimits) if ("cpu".equals(u.get("Name").asText())) cpu = u;
        assertNotNull(cpu, "falta el ulimit cpu: vuelven los TIMEOUT intermitentes por varianza del pool");
        assertEquals(Constantes.TIMEOUT_CPU_SEGUNDOS, cpu.get("Soft").asInt());
        assertEquals(Constantes.TIMEOUT_CPU_SEGUNDOS, cpu.get("Hard").asInt());

        // Assert sobre las constantes, no sobre el JSON: protege contra que alguien suba el
        // presupuesto de CPU sin subir el reloj de pared y deje el limite decorativo.
        long relojDePardSegundos = Constantes.TIMEOUT_EJECUCION_MS / 1000;
        assertTrue(Constantes.TIMEOUT_CPU_SEGUNDOS < relojDePardSegundos,
                "TIMEOUT_CPU_SEGUNDOS (" + Constantes.TIMEOUT_CPU_SEGUNDOS + ") tiene que ser menor que "
                        + relojDePardSegundos + " s de reloj de pared");
        assertTrue(Constantes.TIMEOUT_CPU_SEGUNDOS * 2 <= relojDePardSegundos,
                "la holgura de R4.1 se perdio: con NanoCpus=1 el reloj de pared dispararia primero");
    }

    /** I2: el id de ejecucion aparece solo en el label, en ningun otro campo. */
    @Test
    void a1_elIdSoloTocaElLabel() throws Exception {
        String id = "11111111-2222-4333-8444-555555555555";
        JsonNode n = new ObjectMapper().readTree(Spec.crear(id));
        assertEquals(id, n.get("Labels").get("sandbox.ejecucion").asText());

        String sinLabel = new String(Spec.crear(id), StandardCharsets.UTF_8)
                .replace("\"sandbox.ejecucion\":\"" + id + "\"", "");
        assertFalse(sinLabel.contains(id), "el id se filtro a otro campo de la spec");
    }
}
