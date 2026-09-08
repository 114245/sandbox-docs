/**
 * Log de una linea por evento a stderr (R11.6).
 * Nunca recibe el bundle, la salida del alumno ni el nonce: eso se controla en los sitios de llamada.
 */
export function info(mensaje: string): void {
  process.stderr.write(`[ejecutor] ${mensaje}\n`);
}
