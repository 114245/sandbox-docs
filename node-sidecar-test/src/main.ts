import * as C from './constantes.js';
import { Barrido } from './barrido.js';
import { ClienteDocker } from './cliente-docker.js';
import { Ejecucion } from './ejecucion.js';
import * as Log from './log.js';
import { Servidor } from './servidor.js';

/**
 * Cableado del proceso.
 *
 * Las dos rutas de socket son configuracion de despliegue, no de la spec del contenedor: no tocan
 * ningun campo de la seccion 4.1, asi que no violan P1.
 */
const socketEjecutor = process.env['EJECUTOR_SOCKET'] || '/run/ejecutor/ejecutor.sock';
const socketDaemon = process.env['DOCKER_SOCKET'] || '/var/run/docker.sock';

const cliente = new ClienteDocker(socketDaemon);
const ejecucion = new Ejecucion(cliente);
const barrido = new Barrido(cliente, ejecucion.enVuelo);
const servidor = new Servidor(socketEjecutor, ejecucion);

await servidor.escuchar();

// R10.1: al arrancar, y despues cada INTERVALO_BARRIDO_MS.
void barrido.barrer();
const reloj = setInterval(() => void barrido.barrer(), C.INTERVALO_BARRIDO_MS);
reloj.unref();

// R11.7: dejar de aceptar, esperar a las ejecuciones en vuelo, borrar sus contenedores, salir.
let apagando = false;
for (const senal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(senal, () => {
    if (apagando) return;
    apagando = true;
    Log.info(`${senal}: apagado ordenado`);
    clearInterval(reloj);
    servidor.cerrar().then(() => process.exit(0), () => process.exit(1));
  });
}
