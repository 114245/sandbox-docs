import * as http from 'node:http';
import type * as net from 'node:net';
import * as C from './constantes.js';
import { ErrorDaemon } from './errores.js';
import * as Log from './log.js';
import * as Spec from './spec.js';

/**
 * Cliente de la API de Docker sobre el socket Unix del daemon.
 *
 * R11.4: no se usa dockerode ni equivalente. Esas librerias ponen, en el mismo proceso que tiene
 * el socket, un objeto con un setter equivalente a `Privileged: true`; reintroducen exactamente la
 * superficie que este componente existe para eliminar.
 *
 * El transporte es `node:http` con `socketPath`, que ya trae HTTP/1.1, `chunked` (R5.7) y el evento
 * `upgrade` con sus bytes de lectura anticipada (R5.9). No sigue redirecciones (R5.6).
 */

const PREFIJO = `/${C.VERSION_API_DOCKER}`;

export interface Respuesta {
  codigo: number;
  contentType: string;
  cuerpo: Buffer;
}

export class ClienteDocker {
  constructor(private readonly socketDaemon: string) {}

  // -------------------------------------------------------------- operaciones de la seccion 5

  /** Paso 1. Devuelve el id del contenedor creado. */
  async crear(ejecucionId: string): Promise<string> {
    const r = await this.pedir('POST', `${PREFIJO}/containers/create?name=${Spec.nombre(ejecucionId)}`,
      Spec.crear(ejecucionId), C.TIMEOUT_DAEMON_MS);
    if (r.codigo !== 201) throw new ErrorDaemon(`create respondio ${r.codigo}`);
    const id = json(r).Id;
    if (typeof id !== 'string' || id === '') throw new ErrorDaemon('create no devolvio Id');
    return id;
  }

  /**
   * Paso 2, antes de start (R5.1). Devuelve el socket crudo tras el 101 (R5.8).
   * El canal es de una sola direccion: se escribe y se cierra (R5.2).
   */
  adjuntarStdin(contenedorId: string): Promise<net.Socket> {
    const ruta = `${PREFIJO}/containers/${contenedorId}/attach?stream=1&stdin=1`;
    return new Promise((resolver, rechazar) => {
      const pedido = http.request({
        socketPath: this.socketDaemon, method: 'POST', path: ruta,
        headers: { Host: 'localhost', Connection: 'Upgrade', Upgrade: 'tcp', 'Content-Length': '0' },
      });
      const reloj = setTimeout(() => pedido.destroy(new ErrorDaemon('attach no respondio', true)),
        C.TIMEOUT_DAEMON_MS);

      pedido.on('upgrade', (_res, socket: net.Socket, head: Buffer) => {
        clearTimeout(reloj);
        // R5.9: los bytes que el cliente ya leyo del stream se anteponen. Nosotros solo escribimos,
        // asi que en la practica no hay nada que preservar, pero ignorarlos seria el bug intermitente
        // que la regla describe, y devolver el socket integro cuesta una linea.
        if (head?.length) socket.unshift(head);
        resolver(socket);
      });
      // R5.8: cualquier codigo que no sea 101 es ERROR_DAEMON.
      pedido.on('response', (res) => {
        clearTimeout(reloj);
        pedido.destroy();
        rechazar(new ErrorDaemon(`attach respondio ${res.statusCode}`));
      });
      pedido.on('error', (e) => {
        clearTimeout(reloj);
        rechazar(e instanceof ErrorDaemon ? e : new ErrorDaemon('attach fallo'));
      });
      pedido.end();
    });
  }

  /** Paso 3. */
  async iniciar(contenedorId: string): Promise<void> {
    const r = await this.pedir('POST', `${PREFIJO}/containers/${contenedorId}/start`, null, C.TIMEOUT_DAEMON_MS);
    if (r.codigo !== 204 && r.codigo !== 304) throw new ErrorDaemon(`start respondio ${r.codigo}`);
  }

  /** Paso 5. Devuelve el codigo de salida. Es la unica llamada que no usa TIMEOUT_DAEMON_MS. */
  async esperar(contenedorId: string, timeoutMs: number): Promise<number> {
    const r = await this.pedir('POST', `${PREFIJO}/containers/${contenedorId}/wait?condition=not-running`,
      null, timeoutMs);
    if (r.codigo !== 200) throw new ErrorDaemon(`wait respondio ${r.codigo}`);
    const codigo = json(r).StatusCode;
    if (typeof codigo !== 'number') throw new ErrorDaemon('wait no devolvio StatusCode');
    return codigo;
  }

  async matar(contenedorId: string): Promise<void> {
    const r = await this.pedir('POST', `${PREFIJO}/containers/${contenedorId}/kill`, null, C.TIMEOUT_DAEMON_MS);
    // 409 = ya no estaba corriendo; es exactamente el estado que buscabamos.
    if (r.codigo !== 204 && r.codigo !== 409) throw new ErrorDaemon(`kill respondio ${r.codigo}`);
  }

  /**
   * Paso 5b. Del inspect se lee UNICAMENTE State.OOMKilled; el resto se ignora.
   *
   * R5.10: si la llamada falla no se aborta la ejecucion. El resultado ya esta, y perderlo por un
   * dato de diagnostico seria peor que devolverlo con oomKilled en false.
   *
   * R3.4: este dato no se puede inferir del exitCode. Con la JVM bien configurada el que se queda
   * sin memoria es la JVM y no el cgroup, asi que el out-of-memory llega como exitCode 3 con
   * OOMKilled false, no como el 137 que uno esperaria. Los dos caminos existen.
   */
  async oomKilled(contenedorId: string): Promise<boolean> {
    try {
      const r = await this.pedir('GET', `${PREFIJO}/containers/${contenedorId}/json`, null, C.TIMEOUT_DAEMON_MS);
      if (r.codigo !== 200) throw new ErrorDaemon(`inspect respondio ${r.codigo}`);
      return json(r).State?.OOMKilled === true;
    } catch (e) {
      Log.info(`inspect fallo (${mensaje(e)}); se devuelve oomKilled=false`);
      return false;
    }
  }

  /** Paso 6. Antes del delete (R5.4): borrado el contenedor, los logs no existen mas. */
  async logs(contenedorId: string): Promise<Buffer> {
    const r = await this.pedir('GET', `${PREFIJO}/containers/${contenedorId}/logs?stdout=1&stderr=1`,
      null, C.TIMEOUT_DAEMON_MS);
    if (r.codigo !== 200) throw new ErrorDaemon(`logs respondio ${r.codigo}`);
    // R6.1: raw-stream significa que el contenedor tiene TTY y la salida no viene enmarcada.
    // Es un assert de dos lineas que convierte una corrupcion silenciosa en un error inmediato.
    if (!r.contentType.startsWith('application/vnd.docker.multiplexed-stream')) {
      throw new ErrorDaemon('logs devolvio un content-type inesperado');
    }
    return r.cuerpo;
  }

  /** Paso 7. No lanza: el fallo se registra y se confia en el barrido (R5.3). */
  async borrar(contenedorId: string): Promise<boolean> {
    try {
      const r = await this.pedir('DELETE', `${PREFIJO}/containers/${contenedorId}?force=1&v=1`,
        null, C.TIMEOUT_DAEMON_MS);
      return r.codigo === 204 || r.codigo === 404;
    } catch {
      return false;
    }
  }

  // -------------------------------------------------------------- salud y barrido

  async ping(): Promise<boolean> {
    try {
      return (await this.pedir('GET', '/_ping', null, C.TIMEOUT_DAEMON_MS)).codigo === 200;
    } catch {
      return false;
    }
  }

  /** R10.1: lista los contenedores etiquetados por nosotros. */
  async listarSandbox(): Promise<Array<Record<string, unknown>>> {
    const filtro = encodeURIComponent('{"label":["sandbox=1"]}');
    const r = await this.pedir('GET', `${PREFIJO}/containers/json?all=1&filters=${filtro}`,
      null, C.TIMEOUT_DAEMON_MS);
    if (r.codigo !== 200) throw new ErrorDaemon(`list respondio ${r.codigo}`);
    const lista = JSON.parse(r.cuerpo.toString('utf8'));
    if (!Array.isArray(lista)) throw new ErrorDaemon('list no devolvio un arreglo');
    return lista;
  }

  // -------------------------------------------------------------- transporte

  /** Una peticion completa contra el socket del daemon, con su propio reloj (R8.3). */
  pedir(metodo: string, ruta: string, cuerpo: Buffer | null, timeoutMs: number): Promise<Respuesta> {
    return new Promise((resolver, rechazar) => {
      const cabeceras: Record<string, string> = {
        Host: 'localhost',              // R5.5
        Connection: 'close',
        'Content-Length': String(cuerpo?.length ?? 0),
      };
      if (cuerpo) cabeceras['Content-Type'] = 'application/json';

      const pedido = http.request({ socketPath: this.socketDaemon, method: metodo, path: ruta, headers: cabeceras });
      // Un daemon colgado no debe colgar al ejecutor: al vencer se destruye el socket y el `error`
      // que sigue trae la marca `vencido`.
      const reloj = setTimeout(() => pedido.destroy(new ErrorDaemon('el daemon no respondio', true)), timeoutMs);

      pedido.on('response', (res) => {
        const codigo = res.statusCode ?? 0;
        // R5.6: no seguimos redirecciones. Un 3xx del daemon es una anomalia, no una instruccion.
        if (codigo >= 300 && codigo < 400) {
          clearTimeout(reloj);
          pedido.destroy();
          rechazar(new ErrorDaemon(`el daemon respondio ${codigo}`));
          return;
        }
        const trozos: Buffer[] = [];
        let total = 0;
        res.on('data', (t: Buffer) => {
          total += t.length;
          // Existe para que un daemon anomalo no nos haga crecer sin limite (seccion 4.3).
          if (total > C.MAX_CUERPO_DAEMON_BYTES) {
            pedido.destroy(new ErrorDaemon('respuesta del daemon demasiado grande'));
            return;
          }
          trozos.push(t);
        });
        res.on('end', () => {
          clearTimeout(reloj);
          resolver({ codigo, contentType: String(res.headers['content-type'] ?? ''), cuerpo: Buffer.concat(trozos) });
        });
      });
      pedido.on('error', (e) => {
        clearTimeout(reloj);
        // R3.2: hacia afuera nunca viaja la ruta del socket ni el cuerpo de la respuesta de Docker.
        rechazar(e instanceof ErrorDaemon ? e : new ErrorDaemon('fallo la llamada al daemon'));
      });
      if (cuerpo) pedido.write(cuerpo);
      pedido.end();
    });
  }
}

function json(r: Respuesta): any {
  try {
    return JSON.parse(r.cuerpo.toString('utf8'));
  } catch {
    throw new ErrorDaemon('respuesta del daemon ilegible');
  }
}

export function mensaje(e: unknown): string {
  return e instanceof Error ? e.message : 'error desconocido';
}
