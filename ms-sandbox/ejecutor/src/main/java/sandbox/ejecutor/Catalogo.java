package sandbox.ejecutor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El catalogo de perfiles (Paso 1 de la Opcion 1 del handoff). Directorio read-only, un archivo
 * por version, nombrado {@code <perfilId>@<version>.json}.
 *
 * Se carga ENTERO al arrancar, y las validaciones de abajo hacen fallar el ARRANQUE del proceso,
 * nunca una ejecucion individual: es el mismo criterio que ya tenia {@code Main} con el guion
 * unico de EJECUTOR_PERFIL, generalizado a todo un directorio.
 *
 * El hash del guion se CALCULA aca, nunca se declara en el JSON: un campo declarado podria mentir.
 */
final class Catalogo {

    private final Map<String, Perfil> perfiles;

    private Catalogo(Map<String, Perfil> perfiles) {
        this.perfiles = perfiles;
    }

    /**
     * Un perfil ya validado y con el hash de su guion calculado.
     *
     * El orden de los campos no importa aca (a diferencia de {@link Ejecucion.Salida}): este
     * record nunca se serializa entero, solo aporta valores sueltos a la spec y a la respuesta.
     */
    record Perfil(String perfilId, int version, String imagen, byte[] script, String scriptHash,
                  String reportFormat, int memoriaMb, int cpuS) {

        /** La clave de busqueda del catalogo, y el formato exacto del header X-Perfil. */
        String clave() { return perfilId + "@" + version; }

        static Perfil armar(String perfilId, int version, String imagen, byte[] script,
                             String reportFormat, int memoriaMb, int cpuS) {
            return new Perfil(perfilId, version, imagen, script, hashDe(script),
                    reportFormat, memoriaMb, cpuS);
        }
    }

    /** DTO de deserializacion: el JSON tal como lo escribe quien arma el catalogo. */
    private record PerfilJson(String perfilId, Integer version, String imagen, String script,
                               String reportFormat, LimitesJson limites) {}

    private record LimitesJson(Integer memoriaMb, Integer cpuS) {}

    Perfil buscar(String clave) {
        return perfiles.get(clave);
    }

    int cantidad() {
        return perfiles.size();
    }

    /** Para tests: un catalogo de un solo perfil, sin tocar disco. */
    static Catalogo deUnSolo(Perfil perfil) {
        return new Catalogo(Map.of(perfil.clave(), perfil));
    }

    /**
     * Carga el directorio entero. Cualquier archivo invalido hace fallar el arranque completo:
     * un catalogo a medias es peor que uno que nunca arranco.
     */
    static Catalogo cargar(Path directorio) throws IOException {
        if (!Files.isDirectory(directorio)) {
            throw new IllegalStateException("el catalogo de perfiles no es un directorio: " + directorio);
        }
        List<Path> archivos;
        try (var flujo = Files.list(directorio)) {
            archivos = flujo.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Perfil> perfiles = new LinkedHashMap<>();
        for (Path archivo : archivos) {
            Perfil perfil = cargarUno(mapper, archivo);
            perfiles.put(perfil.clave(), perfil);
        }
        return new Catalogo(Map.copyOf(perfiles));
    }

    private static Perfil cargarUno(ObjectMapper mapper, Path archivo) throws IOException {
        PerfilJson json;
        try {
            json = mapper.readValue(archivo.toFile(), PerfilJson.class);
        } catch (IOException e) {
            throw new IllegalStateException("catalogo: JSON invalido en " + archivo.getFileName(), e);
        }
        if (json.perfilId() == null || json.perfilId().isBlank()) {
            throw new IllegalStateException("catalogo: falta perfilId en " + archivo.getFileName());
        }
        if (json.version() == null) {
            throw new IllegalStateException("catalogo: falta version en " + archivo.getFileName());
        }
        if (json.imagen() == null || json.imagen().isBlank()) {
            throw new IllegalStateException("catalogo: falta imagen en " + archivo.getFileName());
        }
        if (json.script() == null) {
            throw new IllegalStateException("catalogo: falta script en " + archivo.getFileName());
        }
        if (json.limites() == null || json.limites().memoriaMb() == null || json.limites().cpuS() == null) {
            throw new IllegalStateException("catalogo: faltan limites en " + archivo.getFileName());
        }

        int memoriaMb = json.limites().memoriaMb();
        int cpuS = json.limites().cpuS();
        byte[] script = json.script().getBytes(StandardCharsets.UTF_8);

        if (memoriaMb > Constantes.MEMORIA_MAX_MB) {
            throw new IllegalStateException("catalogo: " + archivo.getFileName() + " pide memoriaMb=" + memoriaMb
                    + ", supera el techo de " + Constantes.MEMORIA_MAX_MB + " MB");
        }
        if (cpuS > Constantes.CPU_MAX_S) {
            throw new IllegalStateException("catalogo: " + archivo.getFileName() + " pide cpuS=" + cpuS
                    + ", supera el techo de " + Constantes.CPU_MAX_S + " s");
        }
        // R4.1, generalizada del ulimit fijo de antes a cada perfil del catalogo: el presupuesto de
        // CPU tiene que quedar holgadamente por debajo del reloj de pared, o el reloj dispararia
        // siempre primero y el ulimit quedaria decorativo. Misma relacion que afirmaba SpecTest#a32
        // sobre la constante vieja, aca sobre cada perfil.
        long relojSegundos = Constantes.TIMEOUT_EJECUCION_MS / 1000;
        if (cpuS >= relojSegundos || cpuS * 2 > relojSegundos) {
            throw new IllegalStateException("catalogo: " + archivo.getFileName() + " viola R4.1: cpuS=" + cpuS
                    + " demasiado cerca de los " + relojSegundos + " s de reloj de pared");
        }
        if (script.length > Constantes.MAX_SCRIPT_BYTES) {
            throw new IllegalStateException("catalogo: " + archivo.getFileName()
                    + " tiene un guion mayor a MAX_SCRIPT_BYTES");
        }

        return Perfil.armar(json.perfilId(), json.version(), json.imagen(), script,
                json.reportFormat(), memoriaMb, cpuS);
    }

    private static String hashDe(byte[] script) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(script));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
