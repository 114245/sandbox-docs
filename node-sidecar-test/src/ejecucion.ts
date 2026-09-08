import { randomBytes } from 'node:crypto';
import type * as net from 'node:net';
import * as C from './constantes.js';
import { ClienteDocker, mensaje } from './cliente-docker.js';
import { demultiplexar } from './demux.js';
import { ErrorDaemon } from './errores.js';
import * as Log from './log.js';
import * as Reporte from './reporte.js';

/** Los siete pasos de la seccion 5, con limpieza garantizada. */

export type Estado = 'COMPLETADA' | 'TIMEOUT' | 'ERROR_DAEMON' | 'RECHAZADA';

/** Los campos y su orden son los de la seccion 3.1; JSON.stringify los emite en este mismo orden. */
export interface Salida {
  ejecucionId: string;
  resultado: Estado;
  exitCode: number | null;
  oomKilled: boolean;
  duracionMs: number;
  stdout: string;
  stderr: string;
  reporte: string | null;
  reporteAusente: boolean;
  salidaTruncada: boolean;
}

/**
 * Lo unico que el servidor necesita saber hacer del mundo de Docker.
 * Mantener la frontera aca es lo que deja al servidor sin ninguna referencia al daemon.
 */
export interface Motor {
  ejecutar(ejecucionId: string, tar: Buffer): Promise<Salida>;
  daemonVivo(): Promise<boolean>;
  /** Borra los contenedores que quedaron vivos al apagar (R11.7). */
  limpiarEnVuelo(): Promise<void>;
}

export function rechazada(ejecucionId: string): Salida {
  return armar(ejecucionId, 'RECHAZADA', 0);
}

function armar(ejecucionId: string, resultado: Estado, duracionMs: number): Salida {
  return {
    ejecucionId, resultado, exitCode: null, oomKilled: false, duracionMs,
    stdout: '', stderr: '', reporte: null, reporteAusente: true, salidaTruncada: false,
  };
}

export class Ejecucion implements Motor {
  /** Contenedores vivos de esta instancia, para el apagado ordenado (R11.7) y para R10.2. */
  readonly enVuelo = new Set<string>();

  constructor(private readonly cliente: ClienteDocker) {}

  daemonVivo(): Promise<boolean> {
    return this.cliente.ping();
  }

  async limpiarEnVuelo(): Promise<void> {
    for (const contenedor of this.enVuelo) await this.cliente.borrar(contenedor);
  }

  async ejecutar(ejecucionId: string, tar: Buffer): Promise<Salida> {
    const comienzo = ahora();
    // R7.1: 16 bytes de un generador criptografico, en hexadecimal minuscula.
    const nonce = randomBytes(16).toString('hex');

    let contenedor: string | null = null;
    try {
      contenedor = await this.cliente.crear(ejecucionId);          // paso 1
      this.enVuelo.add(contenedor);
      return await this.correr(ejecucionId, contenedor, tar, nonce, comienzo);
    } catch (e) {
      if (!(e instanceof ErrorDaemon)) throw e;
      Log.info(`id=${ejecucionId} resultado=ERROR_DAEMON causa=${e.message}`);
      return armar(ejecucionId, 'ERROR_DAEMON', ahora() - comienzo);
    } finally {
      // R5.3: el borrado ocurre siempre — camino feliz, timeout y cualquier excepcion.
      if (contenedor !== null) {
        if (!(await this.cliente.borrar(contenedor))) {
          Log.info(`id=${ejecucionId} delete fallo; queda para el barrido`);
        }
        this.enVuelo.delete(contenedor);
      }
    }
  }

  private async correr(ejecucionId: string, contenedor: string, tar: Buffer, nonce: string,
                       comienzo: number): Promise<Salida> {
    // R5.1: el attach va ANTES del start, o el contenedor puede leer stdin sin nadie del otro lado.
    const entrada = await this.cliente.adjuntarStdin(contenedor);   // paso 2
    await this.cliente.iniciar(contenedor);                          // paso 3
    const arranque = ahora();                                        // R8.1: el reloj arranca aca

    const escritura = await escribirEntrada(entrada, nonce, tar,     // paso 4
      Math.max(C.TIMEOUT_EJECUCION_MS - (ahora() - arranque), 1));
    // R8.4: un TIMEOUT es indistinguible de un while(true) y de un half-close mal hecho.
    Log.info(`id=${ejecucionId} stdin_bytes=${escritura.bytes} cierre_completo=${escritura.completo}`);

    let exitCode: number | null;
    let estado: Estado;
    const restante = Math.max(C.TIMEOUT_EJECUCION_MS - (ahora() - arranque), 0);
    try {
      exitCode = await this.cliente.esperar(contenedor, restante);   // paso 5
      estado = 'COMPLETADA';
    } catch (e) {
      // Solo el vencimiento del propio reloj es TIMEOUT; un fallo del daemon sigue siendo ERROR_DAEMON.
      if (!(e instanceof ErrorDaemon) || !e.vencido) throw e;
      // R8.2: kill, wait corto, y la salida parcial se devuelve igual porque sirve para diagnosticar.
      await this.cliente.matar(contenedor);
      try {
        await this.cliente.esperar(contenedor, C.TIMEOUT_DAEMON_MS);
      } catch {
        Log.info(`id=${ejecucionId} el contenedor no termino tras el kill`);
      }
      exitCode = null;
      estado = 'TIMEOUT';
    }

    // R5.10: el paso 5b va tambien en TIMEOUT. Un contenedor matado por el limite de memoria que
    // ademas llego al reloj es un caso real.
    const oomKilled = await this.cliente.oomKilled(contenedor);      // paso 5b

    // R5.4: los logs se leen antes del delete.
    const salida = demultiplexar(await this.cliente.logs(contenedor));  // paso 6

    // R7.7: el truncado ocurre antes de extraer el reporte. Extraer primero exigiria bufferear el
    // stream sin tope, que es justo lo que R6.7 evita. Si el reporte quedo fuera de los bytes
    // conservados, sale reporteAusente=true junto con salidaTruncada=true.
    const extraccion = Reporte.extraer(salida.stdout, nonce);
    const duracionMs = ahora() - comienzo;

    Log.info(`id=${ejecucionId} resultado=${estado} exit=${exitCode} oom=${oomKilled} `
      + `duracion_ms=${duracionMs} stdout_bytes=${extraccion.stdout.length} `
      + `stderr_bytes=${salida.stderr.length} truncada=${salida.truncada} `
      + `reporte_ausente=${extraccion.reporte === null}`);

    return {
      ejecucionId, resultado: estado, exitCode, oomKilled, duracionMs,
      stdout: extraccion.stdout, stderr: salida.stderr, reporte: extraccion.reporte,
      reporteAusente: extraccion.reporte === null, salidaTruncada: salida.truncada,
    };
  }
}

/**
 * Paso 4: primero el nonce como primera linea, despues el tar, y se cierra entero (R7.2).
 * El nonce no viaja por variable de entorno ni por archivo (R7.3): /proc/1/environ lo devolveria
 * aunque el entrypoint hiciera unset.
 *
 * R8.5: la escritura lleva su propio tope. Si el contenedor no consume stdin, el buffer del pipe se
 * llena a las pocas decenas de KiB y esto queda bloqueado para siempre; el reloj del paso 5 todavia
 * no se esta evaluando, asi que sin el tope el cupo de concurrencia no se libera nunca. El tope es lo
 * que queda del reloj de ejecucion: la spec no define una constante propia, y acotar por el mismo
 * presupuesto deja al paso 5 resolviendo en TIMEOUT sin margen extra.
 */
function escribirEntrada(socket: net.Socket, nonce: string, tar: Buffer,
                         topeMs: number): Promise<{ bytes: number; completo: boolean }> {
  return new Promise((resolver) => {
    const reloj = setTimeout(() => {
      socket.destroy();
      resolver({ bytes: socket.bytesWritten, completo: false });
    }, topeMs);

    // Un fallo aca no aborta la ejecucion: el contenedor arranco igual y va a morir por su cuenta o
    // por timeout. Lo que importa es que quede registrado (R8.4, R8.5).
    socket.on('error', (e) => {
      clearTimeout(reloj);
      Log.info(`la escritura de stdin fallo: ${mensaje(e)}`);
      resolver({ bytes: socket.bytesWritten, completo: false });
    });

    socket.write(Buffer.from(`${nonce}\n`, 'ascii'));
    // `end` cierra el canal entero: es de una sola direccion, se escribe y se cierra (R5.2).
    socket.end(tar, () => {
      clearTimeout(reloj);
      resolver({ bytes: socket.bytesWritten, completo: true });
    });
  });
}

/** Reloj monotonico: no lo corre para atras un ajuste de hora del host. */
function ahora(): number {
  return Math.trunc(performance.now());
}
