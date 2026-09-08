/** Valores de la spec, seccion 4.3. Constantes de compilacion: nunca parametros de request (P1). */

export const MAX_BUNDLE_BYTES = 2_097_152;          // 2 MiB
/** Reloj de pared, desde start. Es la red de ultima instancia, no el mecanismo. */
export const TIMEOUT_EJECUCION_MS = 60_000;
/**
 * Ulimit `cpu` del contenedor: techo de CPU de todas las fases juntas.
 * R4.1 exige que sea holgadamente menor que TIMEOUT_EJECUCION_MS en segundos; si se acercaran,
 * el reloj de pared dispararia siempre primero y este limite quedaria decorativo.
 */
export const TIMEOUT_CPU_SEGUNDOS = 20;
export const TIMEOUT_DAEMON_MS = 5_000;
export const MAX_SALIDA_BYTES = 1_048_576;          // 1 MiB por stream
export const MAX_FRAME_BYTES = 1_048_576;           // 1 MiB por frame
export const MAX_CONCURRENTES = 8;
export const MAX_COLA = 16;
export const INTERVALO_BARRIDO_MS = 300_000;        // 5 min
export const EDAD_HUERFANO_MS = 600_000;            // 10 min
export const VERSION_API_DOCKER = 'v1.43';

/** Tope de una respuesta del daemon leida entera en memoria (seccion 4.3). */
export const MAX_CUERPO_DAEMON_BYTES = 16_777_216;  // 16 MiB

/** Tag fijo de la imagen del runner. Cambiarlo exige redesplegar: es el costo aceptado de P1. */
export const TAG_IMAGEN = '1.0.0';
export const IMAGEN = `sandbox-runner:${TAG_IMAGEN}`;
