package sandbox.ejecutor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Las operaciones de la seccion 5 sobre docker-java.
 *
 * <b>Por que docker-java y no el cliente a mano.</b> R11.4 lo prohibia argumentando que la libreria
 * "pone un withPrivileged(true) en el mismo proceso que tiene el socket". El argumento es debil:
 * quien ya ejecuta codigo en este proceso tiene el socket y puede mandar el JSON que quiera a mano.
 * La libreria no agrega capacidad, agrega comodidad. Lo que si costaba era el golden A1/A2, y eso
 * se recupero entero (ver {@link Spec#bytesDeCreate}). El premio: el transporte httpclient5 habla
 * named pipe, asi que el ejecutor corre nativo en Windows contra Docker Desktop en vez de tener que
 * meterse adentro de un contenedor para probarse.
 *
 * <b>Timeouts.</b> Ya no hay watchdogs propios: el responseTimeout del transporte es el tope de
 * lectura de cualquier llamada (R8.3). Es el mismo para todas, asi que se dimensiona por la mas
 * larga -el wait- y el tope fino de cada operacion lo pone el llamador con su propio reloj.
 */
final class Docker implements Closeable {

    private final DockerClient docker;
    private final DockerHttpClient transporte;

    private Docker(DockerClient docker, DockerHttpClient transporte) {
        this.docker = docker;
        this.transporte = transporte;
    }

    /**
     * @param dockerHost unix:///var/run/docker.sock, npipe:////./pipe/docker_engine o tcp://host:puerto
     */
    static Docker conectar(String dockerHost) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                // R5.0: la version va explicita, nunca el default del daemon.
                .withApiVersion(Constantes.VERSION_API_DOCKER)
                .withDockerTlsVerify(false)
                .build();

        DockerHttpClient transporte = new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create(dockerHost))
                .maxConnections(Constantes.MAX_CONCURRENTES * 2)
                .connectionTimeout(Duration.ofMillis(Constantes.TIMEOUT_DAEMON_MS))
                .responseTimeout(Duration.ofMillis(
                        Constantes.TIMEOUT_EJECUCION_MS + Constantes.TIMEOUT_DAEMON_MS))
                .build();

        return new Docker(DockerClientImpl.getInstance(config, transporte), transporte);
    }

    // ---------------------------------------------------------------- operaciones de la seccion 5

    /** Paso 1. Devuelve el id del contenedor creado. La imagen sale del perfil elegido (Paso 2). */
    String crear(String ejecucionId, Catalogo.Perfil perfil) {
        try (CreateContainerCmd cmd = docker.createContainerCmd(perfil.imagen())) {
            String id = Spec.configurar(cmd, ejecucionId, perfil).exec().getId();
            if (id == null || id.isEmpty()) throw new ErrorDaemon("create no devolvio Id");
            return id;
        } catch (RuntimeException e) {
            throw comoErrorDaemon("create", e);
        }
    }

    /**
     * Paso 2, antes de start (R5.1). Vuelve recien cuando el daemon contesto el 101, o sea cuando
     * el canal esta puesto: si volviera antes, el contenedor podria leer stdin sin nadie del otro
     * lado.
     *
     * El canal es de una sola direccion: la spec crea el contenedor con AttachStdout/Stderr en
     * false, asi que por aca no vuelve nada (R5.2). El resultado se lee despues con /logs.
     */
    Adjunto adjuntar(String contenedorId, Entrada entrada) {
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>();
        docker.attachContainerCmd(contenedorId)
                .withStdIn(entrada)
                .withFollowStream(true)
                .exec(callback);
        try {
            if (!callback.awaitStarted(Constantes.TIMEOUT_DAEMON_MS, TimeUnit.MILLISECONDS)) {
                cerrarCallejero(callback);
                throw new ErrorDaemon("el attach no llego a establecerse");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cerrarCallejero(callback);
            throw new ErrorDaemon("attach interrumpido");
        }
        return new Adjunto(callback);
    }

    /**
     * El canal adjunto abierto. Cerrarlo es lo que le da EOF al stdin del contenedor.
     *
     * <b>Esto es nuevo y no es cosmetico.</b> El cliente a mano hacia shutdownOutput() sobre el
     * socket y el contenedor veia EOF. docker-java no hace media-clausura: su escritor tira del
     * InputStream hasta el final y despues se queda quieto, con la conexion abierta. Como la spec
     * crea el contenedor con StdinOnce, cerrar la conexion adjunta ES el EOF. Sin este cierre la
     * capa 1 se cuelga en el {@code cat} del bundle hasta el reloj de pared.
     */
    record Adjunto(ResultCallback.Adapter<Frame> callback) implements Closeable {
        @Override
        public void close() {
            cerrarCallejero(callback);
        }
    }

    /** Paso 3. */
    void iniciar(String contenedorId) {
        try {
            docker.startContainerCmd(contenedorId).exec();
        } catch (RuntimeException e) {
            throw comoErrorDaemon("start", e);
        }
    }

    /** Paso 5. Devuelve el codigo de salida. Es la unica llamada con un tope propio del llamador. */
    int esperar(String contenedorId, long topeMs) throws TimeoutException {
        Espera espera = new Espera();
        docker.waitContainerCmd(contenedorId).exec(espera);
        try {
            if (!espera.awaitCompletion(Math.max(topeMs, 0), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("el contenedor no termino dentro del tope");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrorDaemon("espera interrumpida");
        } catch (RuntimeException e) {
            throw comoErrorDaemon("wait", e);
        }
        if (espera.codigo == null) throw new ErrorDaemon("wait no devolvio StatusCode");
        return espera.codigo;
    }

    void matar(String contenedorId) {
        try {
            docker.killContainerCmd(contenedorId).exec();
        } catch (com.github.dockerjava.api.exception.NotModifiedException ignorado) {
            // ya no estaba corriendo; es exactamente el estado que buscabamos
        } catch (RuntimeException e) {
            throw comoErrorDaemon("kill", e);
        }
    }

    /**
     * Paso 5b. Del inspect se lee UNICAMENTE State.OOMKilled; el resto se ignora.
     *
     * R5.10: si la llamada falla no se aborta la ejecucion. El resultado ya esta, y perderlo por un
     * dato de diagnostico seria peor que devolverlo con oomKilled en false.
     *
     * R3.4: este dato no se puede inferir del exitCode. Con la JVM bien configurada, el que se queda
     * sin memoria es la JVM y no el cgroup, asi que el out-of-memory llega como exitCode 3 con
     * OOMKilled false, no como el 137 que uno esperaria. Los dos caminos existen.
     */
    boolean oomKilled(String contenedorId) {
        try {
            var estado = docker.inspectContainerCmd(contenedorId).exec().getState();
            return estado != null && Boolean.TRUE.equals(estado.getOOMKilled());
        } catch (RuntimeException e) {
            Log.info("inspect fallo (%s); se devuelve oomKilled=false", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Paso 6. Antes del delete (R5.4): borrado el contenedor, los logs no existen mas. */
    Acumulador.Salida logs(String contenedorId) {
        Acumulador acumulador = new Acumulador();
        var callback = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                acumulador.agregar(frame);
            }
        };
        docker.logContainerCmd(contenedorId).withStdOut(true).withStdErr(true).exec(callback);
        try {
            if (!callback.awaitCompletion(Constantes.TIMEOUT_DAEMON_MS, TimeUnit.MILLISECONDS)) {
                throw new ErrorDaemon("los logs no terminaron de llegar");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrorDaemon("lectura de logs interrumpida");
        } catch (RuntimeException e) {
            throw comoErrorDaemon("logs", e);
        }
        return acumulador.salida();
    }

    /** Paso 7. No lanza: el fallo se registra y se confia en el barrido (R5.3). */
    boolean borrar(String contenedorId) {
        try {
            docker.removeContainerCmd(contenedorId).withForce(true).withRemoveVolumes(true).exec();
            return true;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            return true;   // ya no estaba: el objetivo se cumplio igual
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- salud y barrido

    boolean ping() {
        try {
            docker.pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** R10.1: lista los contenedores etiquetados por nosotros. */
    List<Container> listarSandbox() {
        try {
            return docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of("sandbox", "1"))
                    .exec();
        } catch (RuntimeException e) {
            throw comoErrorDaemon("list", e);
        }
    }

    @Override
    public void close() throws IOException {
        docker.close();
        transporte.close();
    }

    // ---------------------------------------------------------------- apoyo

    /** Junta el StatusCode del wait sin usar el callback deprecado, que resuelve el timeout con excepcion. */
    private static final class Espera extends ResultCallback.Adapter<WaitResponse> {
        private volatile Integer codigo;

        @Override
        public void onNext(WaitResponse respuesta) {
            codigo = respuesta.getStatusCode();
        }
    }

    /**
     * R3.2: hacia afuera nunca viaja la ruta del socket ni el cuerpo de la respuesta de Docker.
     * De la excepcion de la libreria se conserva solo el tipo, que es diagnostico y no filtra nada.
     */
    private static ErrorDaemon comoErrorDaemon(String operacion, RuntimeException causa) {
        if (causa instanceof ErrorDaemon propio) return propio;
        return new ErrorDaemon(operacion + " fallo (" + causa.getClass().getSimpleName() + ")", causa);
    }

    private static void cerrarCallejero(Closeable cerrable) {
        try {
            cerrable.close();
        } catch (IOException ignorado) {
            // cerrar es lo ultimo que hacemos con el canal; que falle no cambia nada
        }
    }
}
