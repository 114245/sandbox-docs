import * as fs from 'node:fs';
import * as http from 'node:http';
import * as net from 'node:net';
import * as os from 'node:os';
import * as path from 'node:path';
import { randomUUID } from 'node:crypto';

/** Ruta de socket local para los tests. En Windows los sockets de dominio Unix se nombran tuberia. */
export function rutaSocket(prefijo: string): string {
  const nombre = `${prefijo}-${randomUUID()}`;
  return process.platform === 'win32'
    ? `\\\\.\\pipe\\${nombre}`
    : path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'ejecutor-')), `${nombre}.sock`);
}

/** Un frame del protocolo enmarcado de Docker (seccion 6): tipo, 3 ceros, largo BE, payload. */
export function frame(tipo: number, payload: string | Buffer, largoDeclarado?: number): Buffer {
  const datos = Buffer.isBuffer(payload) ? payload : Buffer.from(payload, 'utf8');
  const cabecera = Buffer.alloc(8);
  cabecera[0] = tipo;
  cabecera.writeUInt32BE(largoDeclarado ?? datos.length, 4);
  return Buffer.concat([cabecera, datos]);
}

/** Lo que el daemon de prueba responde en cada paso. Todo tiene un valor por defecto razonable. */
export interface Guion {
  /** `false` deja el stdin sin consumir: es el escenario de A33. */
  consumeStdin?: boolean;
  /** Demora de la respuesta de `wait`. Por encima del reloj de ejecucion dispara el TIMEOUT. */
  demoraWaitMs?: number;
  statusCode?: number;
  oomKilled?: boolean;
  /** `true` hace que el paso 5b responda 500 (R5.10). */
  inspectFalla?: boolean;
  logs?: Buffer;
  /** Logs armados a partir del stdin recibido, para simular al entrypoint que conoce el nonce. */
  logsDe?: (stdin: Buffer) => Buffer;
  contentTypeLogs?: string;
  /** Codigo de `create`; distinto de 201 fuerza ERROR_DAEMON. */
  codigoCreate?: number;
  /** Contenedores que devuelve `GET /containers/json` para el barrido. */
  contenedores?: Array<Record<string, unknown>>;
}

/**
 * Daemon de Docker de mentira: implementa las llamadas de la seccion 5 sobre un socket local.
 * Registra lo que recibio para poder afirmar sobre la secuencia, no solo sobre el resultado.
 */
export class DaemonDePrueba {
  readonly ruta = rutaSocket('daemon');
  readonly llamadas: string[] = [];
  readonly borrados: string[] = [];
  cuerpoCreate: Buffer | null = null;
  stdinRecibido = Buffer.alloc(0);
  private readonly servidor: http.Server;
  private readonly vivos = new Set<net.Socket>();
  /** Timers de las respuestas demoradas: sin cancelarlos, el reloj de 180 s del test de TIMEOUT
   *  mantiene vivo el event loop mucho despues de que el test ya paso. */
  private readonly timers = new Set<NodeJS.Timeout>();

  constructor(private readonly guion: Guion = {}) {
    this.servidor = http.createServer((pedido, respuesta) => this.atender(pedido, respuesta));
    // El attach es un upgrade a 101 y despues bytes crudos (R5.8).
    this.servidor.on('upgrade', (pedido, socket) => this.adjuntar(pedido, socket as net.Socket));
    this.servidor.on('connection', (s) => {
      this.vivos.add(s);
      s.on('close', () => this.vivos.delete(s));
    });
  }

  escuchar(): Promise<void> {
    return new Promise((r) => this.servidor.listen(this.ruta, () => r()));
  }

  async cerrar(): Promise<void> {
    for (const t of this.timers) clearTimeout(t);
    this.timers.clear();
    for (const s of this.vivos) s.destroy();
    await new Promise<void>((r) => this.servidor.close(() => r()));
  }

  private adjuntar(pedido: http.IncomingMessage, socket: net.Socket): void {
    this.llamadas.push(`attach ${pedido.url}`);
    socket.write('HTTP/1.1 101 UPGRADED\r\nContent-Type: application/vnd.docker.raw-stream\r\n'
      + 'Connection: Upgrade\r\nUpgrade: tcp\r\n\r\n');
    if (this.guion.consumeStdin === false) {
      // No leer nada: el buffer del socket se llena y la escritura del paso 4 queda trabada (A33).
      socket.pause();
      return;
    }
    socket.on('data', (t) => { this.stdinRecibido = Buffer.concat([this.stdinRecibido, t]); });
    socket.on('error', () => undefined);
  }

  private atender(pedido: http.IncomingMessage, respuesta: http.ServerResponse): void {
    const url = pedido.url ?? '';
    this.llamadas.push(`${pedido.method} ${url}`);
    const cuerpo: Buffer[] = [];
    pedido.on('data', (t: Buffer) => cuerpo.push(t));
    pedido.on('end', () => {
      const g = this.guion;
      if (url.includes('/containers/create')) {
        this.cuerpoCreate = Buffer.concat(cuerpo);
        return json(respuesta, g.codigoCreate ?? 201, { Id: 'contenedor-de-prueba' });
      }
      if (url.endsWith('/start')) return vacio(respuesta, 204);
      if (url.includes('/wait')) {
        this.timers.add(setTimeout(() => json(respuesta, 200, { StatusCode: g.statusCode ?? 0 }),
          g.demoraWaitMs ?? 0));
        return;
      }
      if (url.endsWith('/kill')) return vacio(respuesta, 204);
      if (url.endsWith('/json') && url.includes('/containers/') && !url.includes('all=1')) {
        if (g.inspectFalla) return json(respuesta, 500, { message: 'roto' });
        return json(respuesta, 200, { State: { OOMKilled: g.oomKilled ?? false } });
      }
      if (url.includes('/logs')) {
        respuesta.writeHead(200, {
          'Content-Type': g.contentTypeLogs ?? 'application/vnd.docker.multiplexed-stream',
        });
        return respuesta.end(g.logsDe ? g.logsDe(this.stdinRecibido) : (g.logs ?? frame(1, '')));
      }
      if (url.includes('/containers/json')) return json(respuesta, 200, g.contenedores ?? []);
      if (pedido.method === 'DELETE') {
        this.borrados.push(url);
        return vacio(respuesta, 204);
      }
      if (url === '/_ping') return vacio(respuesta, 200);
      return vacio(respuesta, 404);
    });
  }
}

function json(respuesta: http.ServerResponse, codigo: number, cuerpo: unknown): void {
  const datos = Buffer.from(JSON.stringify(cuerpo), 'utf8');
  respuesta.writeHead(codigo, { 'Content-Type': 'application/json', 'Content-Length': datos.length });
  respuesta.end(datos);
}

function vacio(respuesta: http.ServerResponse, codigo: number): void {
  respuesta.writeHead(codigo, { 'Content-Length': 0 });
  respuesta.end();
}

/**
 * Escribe una peticion HTTP cruda y devuelve la respuesta entera como texto.
 * Hace falta porque el cliente de node no deja mandar una peticion sin Content-Length: cuando el
 * cuerpo esta vacio la agrega el solo, y sin ella no se puede probar el 411.
 */
export function pedirCrudo(socketPath: string, peticion: string): Promise<string> {
  return new Promise((resolver, rechazar) => {
    const socket = net.connect(socketPath);
    let respuesta = '';
    socket.on('connect', () => socket.write(peticion));
    socket.on('data', (t) => { respuesta += t.toString('utf8'); });
    socket.on('close', () => resolver(respuesta));
    socket.on('error', rechazar);
  });
}

/** Cliente HTTP minimo contra un socket local, para hablarle al ejecutor desde los tests. */
export function pedir(socketPath: string, opciones: {
  metodo: string; ruta: string; cabeceras?: Record<string, string>; cuerpo?: Buffer;
}): Promise<{ codigo: number; cabeceras: http.IncomingHttpHeaders; cuerpo: string }> {
  return new Promise((resolver, rechazar) => {
    const req = http.request({
      socketPath, method: opciones.metodo, path: opciones.ruta,
      headers: { Host: 'localhost', ...opciones.cabeceras },
    });
    req.on('response', (res) => {
      const trozos: Buffer[] = [];
      res.on('data', (t: Buffer) => trozos.push(t));
      res.on('end', () => resolver({
        codigo: res.statusCode ?? 0,
        cabeceras: res.headers,
        cuerpo: Buffer.concat(trozos).toString('utf8'),
      }));
    });
    req.on('error', rechazar);
    if (opciones.cuerpo) req.write(opciones.cuerpo);
    req.end();
  });
}
