package sandbox.ejecutor;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Ulimit;
import com.github.dockerjava.core.DockerClientConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La spec del contenedor (seccion 4.1). Es el corazon del componente.
 *
 * P1 (invariante de spec fija): lo unico que varia entre ejecuciones es el label
 * "sandbox.ejecucion" y el nombre del contenedor. Ningun otro byte proviene de quien llama.
 * El golden test A1/A2 es lo que convierte esa afirmacion en evidencia.
 *
 * Con docker-java ya no armamos el JSON a mano: configuramos el {@link CreateContainerCmd}, que
 * ES el modelo del cuerpo (sus campos llevan @JsonProperty con los nombres de la API). Por eso el
 * golden sigue siendo posible y sigue siendo sobre los bytes del cable: ver {@link #bytesDeCreate}.
 */
final class Spec {
    private Spec() {}

    /** Nombre del contenedor. Viaja como query param de create, no en el cuerpo. */
    static String nombre(String ejecucionId) {
        return "sandbox-" + ejecucionId;
    }

    /**
     * Aplica la spec entera sobre el comando. El comando ya viene con la imagen, porque
     * {@code createContainerCmd(imagen)} la exige; se vuelve a poner igual para que este metodo
     * sea la unica fuente de verdad de los campos.
     *
     * P1 (Paso 2 del handoff, catalogo de perfiles): exactamente CUATRO campos salen del perfil
     * elegido -Image, Memory, MemorySwap y Ulimits[cpu]-; todo lo demas sigue constante entre
     * ejecuciones, sea cual sea el perfil.
     */
    static CreateContainerCmd configurar(CreateContainerCmd cmd, String ejecucionId, Catalogo.Perfil perfil) {
        long memoriaBytes = perfil.memoriaMb() * 1024L * 1024L;
        HostConfig host = HostConfig.newHostConfig()
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                // uid/gid: el tmpfs lo crea Docker como root. Con mode=0700 y sin estas dos opciones,
                // el contenedor -que corre como 1000:1000- no puede escribir en su unico directorio
                // escribible, y la capa 1 muere con Permission denied antes de leer el bundle.
                .withTmpFs(mapa("/work", "rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000"))
                // Memory y MemorySwap variables (Paso 2): siempre iguales entre si -sin swap-, ahora
                // trackeando la memoria del perfil en vez del valor fijo de antes.
                .withMemory(memoriaBytes)
                .withMemorySwap(memoriaBytes)
                .withMemorySwappiness(0L)
                .withNanoCPUs(1000000000L)
                .withPidsLimit(128L)
                .withCapDrop(Capability.ALL)
                .withCapAdd()
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withPrivileged(false)
                // AutoRemove false es obligatorio: con true el contenedor puede desaparecer antes de
                // que leamos los logs.
                .withAutoRemove(false)
                // Binds/Mounts/Devices se escriben aunque esten vacios, para que el golden los cubra.
                .withBinds()
                .withMounts(List.of())
                .withDevices()
                .withRestartPolicy(RestartPolicy.noRestart())
                .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE,
                        mapa("max-size", "8m", "max-file", "1")))
                .withUlimits(List.of(
                        // Tiempo de CPU, no de pared: no cuenta el tiempo en que el host le dio el
                        // procesador a otra ejecucion del pool, que es lo que elimina los TIMEOUT
                        // intermitentes por varianza en vez de acolcharlos con margen. Al agotarse,
                        // el kernel manda SIGXCPU. Variable (Paso 2): sale de perfil.cpuS(), validado
                        // contra R4.1 al cargar el catalogo (ver Catalogo#cargarUno).
                        new Ulimit("cpu", perfil.cpuS(), perfil.cpuS()),
                        new Ulimit("nofile", 256, 256),
                        new Ulimit("nproc", 128, 128),
                        new Ulimit("fsize", 33554432, 33554432)));

        return cmd
                .withName(nombre(ejecucionId))
                // Variable (Paso 2): la imagen sale del perfil, no de Constantes.IMAGEN.
                .withImage(perfil.imagen())
                .withEntrypoint(Constantes.ENTRYPOINT)
                .withCmd(List.of())
                .withUser("1000:1000")
                .withWorkingDir("/work")
                .withEnv(List.of())
                .withStdinOpen(true)
                .withStdInOnce(true)
                .withAttachStdin(true)
                // stdout/stderr NO se adjuntan: el resultado se lee despues por /logs, y el canal
                // adjunto queda de una sola direccion (R5.2).
                .withAttachStdout(false)
                .withAttachStderr(false)
                // Tty true haria que la salida no venga enmarcada y perderiamos la separacion de
                // stdout y stderr (R6.1).
                .withTty(false)
                .withNetworkDisabled(true)
                .withLabels(mapa("sandbox", "1", "sandbox.ejecucion", ejecucionId))
                .withHostConfig(host);
    }

    /**
     * Los bytes del cuerpo de create, tal como salen al cable.
     *
     * Es literalmente lo que hace {@code DefaultInvocationBuilder.encode(entity)}: el mismo
     * ObjectMapper que usa docker-java sobre el mismo objeto. No es una reconstruccion aproximada
     * del pedido: es el pedido. Sobre esto corre el golden A1/A2 (ver SpecTest).
     *
     * Existe en produccion y no solo en los tests a proposito: si la serializacion del comando
     * dejara de ser reproducible, queremos que rompa aca y no en una comparacion casera.
     */
    static byte[] bytesDeCreate(CreateContainerCmd cmd) {
        try {
            return DockerClientConfig.getDefaultObjectMapper().writeValueAsBytes(cmd);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo serializar la spec", e);
        }
    }

    /**
     * LinkedHashMap y no Map.of: los mapas inmutables de Java iteran en un orden que depende de una
     * semilla aleatoria por JVM, asi que con Map.of los bytes de Labels, Tmpfs y LogConfig cambian
     * entre corridas y el golden A1/A2 se vuelve intermitente. Aca el orden es el de escritura.
     */
    private static Map<String, String> mapa(String... paresClaveValor) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < paresClaveValor.length; i += 2) m.put(paresClaveValor[i], paresClaveValor[i + 1]);
        return m;
    }
}
