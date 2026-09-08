import * as C from './constantes.js';
import { ErrorDaemon } from './errores.js';

/** Demultiplexado de la salida enmarcada de Docker (seccion 6). */

export interface Salida {
  stdout: string;
  stderr: string;
  truncada: boolean;
}

/**
 * Separa los dos streams de un cuerpo enmarcado.
 * @throws ErrorDaemon si el stream esta corrupto o declara un frame fuera de rango.
 */
export function demultiplexar(datos: Buffer): Salida {
  const out = new Acumulador();
  const err = new Acumulador();

  let i = 0;
  while (i < datos.length) {
    // R6.5: los frames pueden llegar partidos; un encabezado incompleto al final es corrupcion.
    if (datos.length - i < 8) throw new ErrorDaemon('frame truncado en el encabezado');

    const tipo = datos[i]!;
    // R6.4: adivinar stdout es como se corrompe un resultado en silencio.
    if (tipo !== 1 && tipo !== 2) throw new ErrorDaemon(`tipo de stream invalido: ${tipo}`);

    // R6.2: el largo es un uint32 y admite hasta 4 GiB; se valida ANTES de reservar nada.
    const largo = datos.readUInt32BE(i + 4);
    if (largo > C.MAX_FRAME_BYTES) throw new ErrorDaemon(`frame de ${largo} bytes`);

    i += 8;
    if (datos.length - i < largo) throw new ErrorDaemon('frame truncado en el payload');

    // R6.3: largo 0 es valido; el bucle avanza igual porque i ya sumo el encabezado.
    (tipo === 1 ? out : err).escribir(datos, i, largo);
    i += largo;
  }

  // R6.6: recien aca, con el stream entero concatenado, se decodifica. Nunca por linea ni por frame.
  return { stdout: out.texto(), stderr: err.texto(), truncada: out.truncada || err.truncada };
}

/** R6.7: conserva el principio y descarta el resto. El reporte y los errores de compilacion estan al principio. */
class Acumulador {
  private readonly trozos: Buffer[] = [];
  private tamano = 0;
  truncada = false;

  escribir(datos: Buffer, desde: number, largo: number): void {
    const espacio = C.MAX_SALIDA_BYTES - this.tamano;
    if (largo > espacio) {
      this.truncada = true;
      largo = Math.max(espacio, 0);
    }
    if (largo > 0) {
      this.trozos.push(datos.subarray(desde, desde + largo));
      this.tamano += largo;
    }
  }

  texto(): string {
    // El corte por bytes puede partir un caracter UTF-8: toString lo reemplaza en vez de fallar.
    return Buffer.concat(this.trozos).toString('utf8');
  }
}
