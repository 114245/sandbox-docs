/**
 * El daemon fallo, no respondio, o respondio algo que no podemos interpretar.
 * R3.2: el mensaje no lleva rutas del host, versiones del daemon ni el cuerpo de la respuesta de Docker.
 */
export class ErrorDaemon extends Error {
  /**
   * `true` cuando la llamada se abandono por su propio reloj y no por un fallo del daemon.
   * Es lo que le permite al paso 5 distinguir un TIMEOUT de ejecucion de un ERROR_DAEMON
   * sin dejar colgada la peticion cinco segundos de mas (R8.3).
   */
  readonly vencido: boolean;

  constructor(mensaje: string, vencido = false) {
    super(mensaje);
    this.name = 'ErrorDaemon';
    this.vencido = vencido;
  }
}
