package sandbox.ejecutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * La spec del contenedor (seccion 4.1). Es el corazon del componente.
 *
 * P1 (invariante de spec fija): lo unico que varia entre ejecuciones es el label
 * "sandbox.ejecucion" y el nombre del contenedor. Ningun otro byte proviene de quien llama.
 * El golden test A1/A2 es lo que convierte esa afirmacion en evidencia.
 *
 * Jackson serializa ObjectNode en orden de insercion (esta respaldado por un LinkedHashMap),
 * de modo que el orden de este metodo es el orden de los bytes que salen.
 */
final class Spec {
    private Spec() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Nombre del contenedor, para la query de create. */
    static String nombre(String ejecucionId) {
        return "sandbox-" + ejecucionId;
    }

    static byte[] crear(String ejecucionId) {
        ObjectNode raiz = MAPPER.createObjectNode();
        raiz.put("Image", Constantes.IMAGEN);
        raiz.set("Entrypoint", arreglo("/opt/sandbox/entrypoint.sh"));
        raiz.set("Cmd", MAPPER.createArrayNode());
        raiz.put("User", "1000:1000");
        raiz.put("WorkingDir", "/work");
        raiz.set("Env", MAPPER.createArrayNode());
        raiz.put("OpenStdin", true);
        raiz.put("StdinOnce", true);
        raiz.put("AttachStdin", true);
        raiz.put("AttachStdout", false);
        raiz.put("AttachStderr", false);
        raiz.put("Tty", false);
        raiz.put("NetworkDisabled", true);

        ObjectNode labels = raiz.putObject("Labels");
        labels.put("sandbox", "1");
        labels.put("sandbox.ejecucion", ejecucionId);

        ObjectNode host = raiz.putObject("HostConfig");
        host.put("NetworkMode", "none");
        host.put("ReadonlyRootfs", true);
        // uid/gid: el tmpfs lo crea Docker como root. Con mode=0700 y sin estas dos opciones,
        // el contenedor -que corre como 1000:1000- no puede escribir en su unico directorio
        // escribible, y el entrypoint muere con Permission denied antes de leer el bundle.
        host.putObject("Tmpfs").put("/work", "rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000");
        host.put("Memory", 536870912L);
        host.put("MemorySwap", 536870912L);
        host.put("MemorySwappiness", 0);
        host.put("NanoCpus", 1000000000L);
        host.put("PidsLimit", 128);
        host.set("CapDrop", arreglo("ALL"));
        host.set("CapAdd", MAPPER.createArrayNode());
        host.set("SecurityOpt", arreglo("no-new-privileges:true"));
        host.put("Privileged", false);
        // AutoRemove false es obligatorio: con true el contenedor puede desaparecer antes de que leamos logs.
        host.put("AutoRemove", false);
        // Binds/Mounts/Devices se escriben aunque esten vacios, para que el golden test los cubra.
        host.set("Binds", MAPPER.createArrayNode());
        host.set("Mounts", MAPPER.createArrayNode());
        host.set("Devices", MAPPER.createArrayNode());
        host.putObject("RestartPolicy").put("Name", "no");

        ObjectNode log = host.putObject("LogConfig");
        log.put("Type", "json-file");
        ObjectNode logCfg = log.putObject("Config");
        logCfg.put("max-size", "8m");
        logCfg.put("max-file", "1");

        ArrayNode ulimits = host.putArray("Ulimits");
        // Tiempo de CPU, no de pared: no cuenta el tiempo en que el host le dio el procesador a otra
        // ejecucion del pool, que es lo que elimina los TIMEOUT intermitentes por varianza en vez de
        // acolcharlos con margen. Al agotarse, el kernel manda SIGXCPU.
        ulimit(ulimits, "cpu", Constantes.TIMEOUT_CPU_SEGUNDOS, Constantes.TIMEOUT_CPU_SEGUNDOS);
        ulimit(ulimits, "nofile", 256, 256);
        ulimit(ulimits, "nproc", 128, 128);
        ulimit(ulimits, "fsize", 33554432, 33554432);

        try {
            return MAPPER.writeValueAsBytes(raiz);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo serializar la spec", e);
        }
    }

    private static ArrayNode arreglo(String... valores) {
        ArrayNode a = MAPPER.createArrayNode();
        for (String v : valores) a.add(v);
        return a;
    }

    private static void ulimit(ArrayNode destino, String nombre, long blando, long duro) {
        ObjectNode u = destino.addObject();
        u.put("Name", nombre);
        u.put("Soft", blando);
        u.put("Hard", duro);
    }
}
