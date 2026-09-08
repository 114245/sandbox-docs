import * as fs from 'node:fs';
import * as http from 'node:http';
import * as path from 'node:path';
import * as C from './constantes.js';
import { mensaje } from './cliente-docker.js';
import type { Motor, Salida } from './ejecucion.js';
import { rechazada } from './ejecucion.js';
import * as Log from './log.js';

/**
 * Servidor HTTP/1.1 sobre socket Unix (R2.1). Unica superficie de red del componente.
 *
 * No escucha en TCP (R2.2): un endpoint HTTP sin autenticacion en una red de Docker es alcanzable
 * por todos los contenedores de esa red, y el socket Unix reduce el conjunto de quienes pueden
 * hablarle a "quien tenga el volumen montado".
 */

/**
 * UUID canonico, estricto. No es cosmetica: el id se concatena en la URL de create como nombre del
 * contenedor, asi que cualquier laxitud aca es una inyeccion en la peticion al daemon.
 */
const UUID_CANONICO = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Admitidos = en vuelo + esperando turno. Por encima del tope, 503 inmediato (R9.2). */
const MAX_ADMITIDOS = C.MAX_CONCURRENTES + C.MAX_COLA;

export class Servidor {
  private readonly servidor: http.Server;
  /** R9.1: el control de concurrencia vive aca, que es quien sabe cuantos contenedores hay. */
  private readonly turnos = new Semaforo(C.MAX_CONCURRENTES);
  private admitidos = 0;
  private aceptando = true;
  private cerrando = false;

  constructor(private readonly rutaSocket: string, private readonly motor: Motor) {
    this.servidor = http.createServer((pedido, respuesta) => {
      this.rutear(pedido, respuesta).catch((e) => {
        Log.info(`la peticion fallo: ${mensaje(e)}`);
        if (!respuesta.headersSent) this.responder(respuesta, 500, '{"error":"error interno"}');
      });
    });
  }

  async escuchar(): Promise<void> {
    // Un socket huerfano de un arranque anterior impide el bind; borrarlo es parte del arranque.
    if (!esTuberiaWindows(this.rutaSocket)) {
      await fs.promises.rm(this.rutaSocket, { force: true });
      await fs.promises.mkdir(path.dirname(this.rutaSocket), { recursive: true });
    }
    await new Promise<void>((resolver, rechazar) => {
      this.servidor.once('error', rechazar);
      this.servidor.listen(this.rutaSocket, () => {
        this.servidor.removeListener('error', rechazar);
        resolver();
      });
    });
    permisos0660(this.rutaSocket);
    Log.info(`escuchando en ${this.rutaSocket}`);
  }

  // ---------------------------------------------------------------- ruteo

  private async rutear(pedido: http.IncomingMessage, respuesta: http.ServerResponse): Promise<void> {
    const ruta = (pedido.url ?? '').split('?')[0];
    if (pedido.method === 'POST' && ruta === '/ejecutar') return this.ejecutar(pedido, respuesta);
    if (pedido.method === 'GET' && ruta === '/salud') return this.salud(respuesta);
    this.responder(respuesta, 404, '{"error":"no existe"}');
  }

  private async ejecutar(pedido: http.IncomingMessage, respuesta: http.ServerResponse): Promise<void> {
    const id = cabecera(pedido, 'x-ejecucion-id');
    if (id === null || !UUID_CANONICO.test(id)) {
      return this.responder(respuesta, 400, '{"error":"X-Ejecucion-Id ausente o invalido"}');
    }
    // No se acepta chunked en la entrada: necesitamos el tamano antes de aceptar bytes.
    const cl = cabecera(pedido, 'content-length');
    if (cl === null) return this.responder(respuesta, 411, error(id, 'falta Content-Length'));

    const largo = Number(cl);
    if (!Number.isSafeInteger(largo) || largo < 0) {
      return this.responder(respuesta, 400, error(id, 'Content-Length invalido'));
    }
    // A27: se responde y se cierra sin leer un solo byte del cuerpo.
    if (largo > C.MAX_BUNDLE_BYTES) return this.responder(respuesta, 413, error(id, 'bundle demasiado grande'));

    // R9.2 y R3.3: saturacion es un resultado, no una falla del servicio. La decision de rechazar
    // tiene que ser inmediata, asi que se toma antes de encolar.
    if (!this.aceptando || this.admitidos >= MAX_ADMITIDOS) {
      return this.responder(respuesta, 503, JSON.stringify(rechazada(id)), { 'Retry-After': '5' });
    }
    this.admitidos++;
    try {
      await this.turnos.adquirir();
      try {
        const tar = await leerCuerpo(pedido, largo);
        const salida: Salida = await this.motor.ejecutar(id, tar);
        // 200 tambien para TIMEOUT: es un resultado, no un error (seccion 3.3).
        this.responder(respuesta, salida.resultado === 'ERROR_DAEMON' ? 502 : 200, JSON.stringify(salida));
      } finally {
        this.turnos.liberar();
      }
    } finally {
      this.admitidos--;
    }
  }

  private async salud(respuesta: http.ServerResponse): Promise<void> {
    const enVuelo = C.MAX_CONCURRENTES - this.turnos.disponibles;
    const enCola = Math.max(this.admitidos - enVuelo, 0);
    const vivo = await this.motor.daemonVivo();
    // R3.3: con la cola llena esto sigue devolviendo 200. Si devolviera 503 el orquestador
    // reiniciaria el ejecutor justo cuando mas se lo necesita.
    this.responder(respuesta, vivo ? 200 : 503,
      JSON.stringify({ daemon: vivo ? 'ok' : 'caido', enVuelo, enCola }));
  }

  // ---------------------------------------------------------------- apagado (R11.7)

  async cerrar(): Promise<void> {
    if (this.cerrando) return;   // el apagado corre una sola vez
    this.cerrando = true;
    this.aceptando = false;
    this.servidor.close();
    this.servidor.closeIdleConnections();

    // Esperar a que no quede ninguna ejecucion en vuelo, hasta el reloj de ejecucion.
    const limite = Date.now() + C.TIMEOUT_EJECUCION_MS;
    while (this.turnos.disponibles < C.MAX_CONCURRENTES && Date.now() < limite) {
      await new Promise((r) => setTimeout(r, 50));
    }
    if (this.turnos.disponibles < C.MAX_CONCURRENTES) Log.info('apagado con ejecuciones todavia en vuelo');

    await this.motor.limpiarEnVuelo();
    this.servidor.closeAllConnections();
    if (!esTuberiaWindows(this.rutaSocket)) {
      // El socket huerfano se sobreescribe en el proximo arranque; borrarlo igual es mas prolijo.
      await fs.promises.rm(this.rutaSocket, { force: true }).catch(() => undefined);
    }
  }

  // ---------------------------------------------------------------- respuestas

  private responder(respuesta: http.ServerResponse, codigo: number, cuerpo: string,
                    extra: Record<string, string> = {}): void {
    respuesta.writeHead(codigo, {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(cuerpo),
      Connection: 'close',
      ...extra,
    });
    respuesta.end(cuerpo);
  }
}

/** R3.2: mensaje corto y un id de correlacion, sin rutas del host ni detalles del daemon. */
function error(ejecucionId: string, texto: string): string {
  return JSON.stringify({ ejecucionId, error: texto });
}

function cabecera(pedido: http.IncomingMessage, nombre: string): string | null {
  const v = pedido.headers[nombre];
  return typeof v === 'string' ? v.trim() : null;
}

/**
 * Lee el cuerpo, acotado a los `largo` bytes que declaro Content-Length. El tope ya se verifico
 * antes de llegar aca; esto solo garantiza que lo que se pasa al motor mide lo que dijo medir.
 */
function leerCuerpo(pedido: http.IncomingMessage, largo: number): Promise<Buffer> {
  return new Promise((resolver, rechazar) => {
    const trozos: Buffer[] = [];
    pedido.on('data', (t: Buffer) => trozos.push(t));
    pedido.on('end', () => resolver(Buffer.concat(trozos, largo)));
    pedido.on('error', rechazar);
  });
}

/** Semaforo justo (FIFO): el que llego primero toma el turno que se libera. */
class Semaforo {
  private readonly espera: Array<() => void> = [];

  constructor(public disponibles: number) {}

  adquirir(): Promise<void> {
    if (this.disponibles > 0) {
      this.disponibles--;
      return Promise.resolve();
    }
    return new Promise((resolver) => this.espera.push(resolver));
  }

  liberar(): void {
    const siguiente = this.espera.shift();
    if (siguiente) siguiente();
    else this.disponibles++;
  }
}

/** En Windows los sockets de dominio Unix se nombran como tuberia; solo pasa en los tests. */
function esTuberiaWindows(ruta: string): boolean {
  return ruta.startsWith('\\\\.\\pipe\\') || ruta.startsWith('\\\\?\\pipe\\');
}

/** R2.1: 0660. En Windows el sistema de archivos no tiene permisos POSIX. */
function permisos0660(ruta: string): void {
  if (esTuberiaWindows(ruta)) return;
  try {
    fs.chmodSync(ruta, 0o660);
  } catch {
    Log.info('no se pudieron fijar permisos 0660 sobre el socket');
  }
}
