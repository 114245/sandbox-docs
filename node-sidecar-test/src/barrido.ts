import * as C from './constantes.js';
import { ClienteDocker, mensaje } from './cliente-docker.js';
import * as Log from './log.js';

/**
 * Limpieza de huerfanos (seccion 10). Cierra la invariante I6: todo contenedor creado termina
 * borrado, por la via normal o por el barrido.
 *
 * R10.4: este es el UNICO barrido del sistema. La limpieza vive donde vive el privilegio.
 */
export class Barrido {
  constructor(private readonly cliente: ClienteDocker, private readonly enVuelo: Set<string>) {}

  /** R10.3: un fallo del barrido se registra y no afecta las ejecuciones en curso. */
  async barrer(): Promise<void> {
    try {
      const limite = Date.now() - C.EDAD_HUERFANO_MS;
      let borrados = 0;
      for (const c of await this.cliente.listarSandbox()) {
        const id = typeof c.Id === 'string' ? c.Id : '';
        const creado = typeof c.Created === 'number' ? c.Created * 1000 : Date.now();
        // R10.2: el filtro por edad alcanza —EDAD_HUERFANO_MS es 10 veces TIMEOUT_EJECUCION_MS—,
        // pero saltear lo nuestro es gratis y explicito.
        if (id === '' || this.enVuelo.has(id) || creado > limite) continue;
        if (await this.cliente.borrar(id)) borrados++;
      }
      if (borrados > 0) Log.info(`barrido borro ${borrados} huerfano(s)`);
    } catch (e) {
      Log.info(`barrido fallo: ${mensaje(e)}`);
    }
  }
}
