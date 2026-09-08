package sandbox.ejecutor;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** Limpieza de huerfanos (seccion 10). Cierra la invariante I6: todo contenedor creado termina borrado. */
final class Barrido {

    private final ClienteDocker cliente;
    private final Set<String> enVuelo;

    Barrido(ClienteDocker cliente, Set<String> enVuelo) {
        this.cliente = cliente;
        this.enVuelo = enVuelo;
    }

    /** R10.3: un fallo del barrido se registra y no afecta las ejecuciones en curso. */
    void barrer() {
        try {
            long limite = System.currentTimeMillis() - Constantes.EDAD_HUERFANO_MS;
            int borrados = 0;
            for (JsonNode c : cliente.listarSandbox()) {
                String id = c.path("Id").asText();
                long creado = c.path("Created").asLong() * 1000L;
                // R10.2: el filtro por edad alcanza, pero saltear lo nuestro es gratis y explicito.
                if (id.isEmpty() || enVuelo.contains(id) || creado > limite) continue;
                if (cliente.borrar(id)) borrados++;
            }
            if (borrados > 0) Log.info("barrido borro %d huerfano(s)", borrados);
        } catch (RuntimeException e) {
            Log.info("barrido fallo: %s", e.getMessage());
        }
    }
}
