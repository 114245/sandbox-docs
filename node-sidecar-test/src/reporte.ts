/**
 * Separacion del bloque de reporte (seccion 7).
 *
 * El codigo del alumno escribe en el mismo stdout por donde viaja el reporte. Sin un separador que
 * el alumno no pueda producir, un println con el formato del reporte falsifica el resultado.
 * El separador es el nonce, que solo conocen el ejecutor y el entrypoint.
 */

export interface Extraccion {
  /** El stdout ya sin el bloque. */
  stdout: string;
  /** El contenido del reporte, o null si no aparecio. */
  reporte: string | null;
}

export function extraer(stdout: string, nonce: string): Extraccion {
  const marcaInicio = `---SANDBOX-${nonce}-INICIO---`;
  const marcaFin = `---SANDBOX-${nonce}-FIN---`;

  // R7.5/R7.6: la ultima aparicion del marcador de inicio...
  const inicio = stdout.lastIndexOf(marcaInicio);
  if (inicio < 0) return { stdout, reporte: null };

  // ...y la primera del de fin posterior a ella. Fijar el criterio evita que dos implementaciones
  // diverjan ante el mismo stream.
  const fin = stdout.indexOf(marcaFin, inicio + marcaInicio.length);
  if (fin < 0) return { stdout, reporte: null };

  const contenido = recortarSaltos(stdout.slice(inicio + marcaInicio.length, fin));

  // R7.8: se remueve desde el primer caracter del marcador de inicio hasta el ultimo del de fin,
  // mas el salto de linea inmediatamente posterior si existe.
  let corte = fin + marcaFin.length;
  if (stdout[corte] === '\r') corte++;
  if (stdout[corte] === '\n') corte++;

  return { stdout: stdout.slice(0, inicio) + stdout.slice(corte), reporte: contenido };
}

/** Quita el salto que sigue al marcador de inicio y el que precede al de fin, y nada mas. */
function recortarSaltos(s: string): string {
  if (s.startsWith('\r\n')) s = s.slice(2);
  else if (s.startsWith('\n')) s = s.slice(1);
  if (s.endsWith('\r\n')) s = s.slice(0, -2);
  else if (s.endsWith('\n')) s = s.slice(0, -1);
  return s;
}
