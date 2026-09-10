package sandbox.ejecutor;

import com.github.dockerjava.api.model.Container;

import java.util.Set;

/** Limpieza de huerfanos (seccion 10). Cierra la invariante I6: todo contenedor creado termina borrado. */
final class Barrido {

    private final Docker docker;
    private final Set<String> enVuelo;

    Barrido(Docker docker, Set<String> enVuelo) {
        this.docker = docker;
        this.enVuelo = enVuelo;
    }

    /** R10.3: un fallo del barrido se registra y no afecta las ejecuciones en curso. */
    void barrer() {
        try {
            long limite = System.currentTimeMillis() - Constantes.EDAD_HUERFANO_MS;
            int borrados = 0;
            for (Container c : docker.listarSandbox()) {
                String id = c.getId();
                long creado = c.getCreated() == null ? 0 : c.getCreated() * 1000L;
                // R10.2: el filtro por edad alcanza, pero saltear lo nuestro es gratis y explicito.
                if (id == null || id.isEmpty() || enVuelo.contains(id) || creado > limite) continue;
                if (docker.borrar(id)) borrados++;
            }
            if (borrados > 0) Log.info("barrido borro %d huerfano(s)", borrados);
        } catch (RuntimeException e) {
            Log.info("barrido fallo: %s", e.getMessage());
        }
    }
}
