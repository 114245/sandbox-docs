import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as C from '../src/constantes.js';
import type { Motor, Salida } from '../src/ejecucion.js';
import { Servidor } from '../src/servidor.js';
import { pedir, pedirCrudo, rutaSocket } from './ayudas.js';

/** Secciones 3 y 9: el contrato HTTP hacia el worker y la saturacion. */

const ID = '3f2b1c4d-0000-4000-8000-000000000001';

type Respuesta = Awaited<ReturnType<typeof pedir>>;

/** Motor de mentira: cuenta llamadas y se puede dejar colgado para llenar la cola. */
class MotorDePrueba implements Motor {
  llamadas = 0;
  ultimoTar: Buffer | null = null;
  daemonOk = true;
  private liberar: (() => void) | null = null;
  private readonly bloqueado: Promise<void>;

  constructor(colgado = false) {
    this.bloqueado = colgado ? new Promise((r) => { this.liberar = r; }) : Promise.resolve();
  }

  async ejecutar(ejecucionId: string, tar: Buffer): Promise<Salida> {
    this.llamadas++;
    this.ultimoTar = tar;
    await this.bloqueado;
    return {
      ejecucionId, resultado: 'COMPLETADA', exitCode: 0, oomKilled: false, duracionMs: 1,
      stdout: '', stderr: '', reporte: null, reporteAusente: true, salidaTruncada: false,
    };
  }

  daemonVivo(): Promise<boolean> { return Promise.resolve(this.daemonOk); }
  limpiarEnVuelo(): Promise<void> { return Promise.resolve(); }
  soltar(): void { this.liberar?.(); }
}

async function conServidor<T>(motor: Motor, cuerpo: (ruta: string) => Promise<T>): Promise<T> {
  const ruta = rutaSocket('ejecutor');
  const servidor = new Servidor(ruta, motor);
  await servidor.escuchar();
  try {
    return await cuerpo(ruta);
  } finally {
    await servidor.cerrar();
  }
}

const cabeceras = (largo: number) => ({ 'X-Ejecucion-Id': ID, 'Content-Length': String(largo) });

test('el camino feliz devuelve 200 con el json de la seccion 3.1', async () => {
  const motor = new MotorDePrueba();
  await conServidor(motor, async (ruta) => {
    const r = await pedir(ruta, {
      metodo: 'POST', ruta: '/ejecutar', cabeceras: cabeceras(3), cuerpo: Buffer.from('tar'),
    });
    assert.equal(r.codigo, 200);
    const salida = JSON.parse(r.cuerpo);
    assert.deepEqual(Object.keys(salida), ['ejecucionId', 'resultado', 'exitCode', 'oomKilled',
      'duracionMs', 'stdout', 'stderr', 'reporte', 'reporteAusente', 'salidaTruncada']);
    assert.deepEqual(motor.ultimoTar, Buffer.from('tar'));
  });
});

test('400 si falta X-Ejecucion-Id o no es un uuid', async () => {
  const motor = new MotorDePrueba();
  await conServidor(motor, async (ruta) => {
    const sinId = await pedir(ruta, { metodo: 'POST', ruta: '/ejecutar', cabeceras: { 'Content-Length': '0' } });
    assert.equal(sinId.codigo, 400);
    const malId = await pedir(ruta, {
      metodo: 'POST', ruta: '/ejecutar', cabeceras: { 'X-Ejecucion-Id': 'no-uuid', 'Content-Length': '0' },
    });
    assert.equal(malId.codigo, 400);
    assert.equal(motor.llamadas, 0);
  });
});

test('el id no puede traer barras ni query: el nombre del contenedor se concatena en la url', async () => {
  const motor = new MotorDePrueba();
  await conServidor(motor, async (ruta) => {
    const r = await pedir(ruta, {
      metodo: 'POST',
      ruta: '/ejecutar',
      cabeceras: { 'X-Ejecucion-Id': '3f2b1c4d-0000-4000-8000-000000000001/../x', 'Content-Length': '0' },
    });
    assert.equal(r.codigo, 400);
    assert.equal(motor.llamadas, 0);
  });
});

test('411 si no viene Content-Length: no se acepta chunked en la entrada', async () => {
  const motor = new MotorDePrueba();
  await conServidor(motor, async (ruta) => {
    // Necesitamos conocer el tamano antes de aceptar bytes, asi que un cuerpo chunked se rechaza.
    const r = await pedirCrudo(ruta, `POST /ejecutar HTTP/1.1\r\nHost: localhost\r\n`
      + `X-Ejecucion-Id: ${ID}\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n`
      + `3\r\ntar\r\n0\r\n\r\n`);
    assert.match(r, /^HTTP\/1\.1 411 /);
    assert.equal(motor.llamadas, 0);
  });
});

test('a27 413 con un bundle mayor al tope, sin leer el cuerpo', async () => {
  const motor = new MotorDePrueba();
  await conServidor(motor, async (ruta) => {
    // Se declaran MAX_BUNDLE_BYTES + 1 y se mandan solo unos pocos bytes: si el servidor esperara
    // el cuerpo completo esto no responderia nunca.
    const r = await pedir(ruta, {
      metodo: 'POST', ruta: '/ejecutar',
      cabeceras: cabeceras(C.MAX_BUNDLE_BYTES + 1), cuerpo: Buffer.alloc(16),
    });
    assert.equal(r.codigo, 413);
    assert.equal(motor.llamadas, 0);
  });
});

test('el bundle de exactamente MAX_BUNDLE_BYTES se acepta', async () => {
  const motor = new MotorDePrueba();
  await conServidor(motor, async (ruta) => {
    const r = await pedir(ruta, {
      metodo: 'POST', ruta: '/ejecutar',
      cabeceras: cabeceras(C.MAX_BUNDLE_BYTES), cuerpo: Buffer.alloc(C.MAX_BUNDLE_BYTES, 0x41),
    });
    assert.equal(r.codigo, 200);
    assert.equal(motor.ultimoTar!.length, C.MAX_BUNDLE_BYTES);
  });
});

/** Lanza `cuantos` pedidos y espera a que el servidor los tenga contados: en vuelo mas en cola. */
async function saturar(ruta: string, cuantos: number): Promise<Array<Promise<Respuesta>>> {
  const pedidos: Array<Promise<Respuesta>> = [];
  for (let i = 0; i < cuantos; i++) {
    pedidos.push(pedir(ruta, {
      metodo: 'POST', ruta: '/ejecutar', cabeceras: cabeceras(1), cuerpo: Buffer.from('x'),
    }));
  }
  // Sin esta espera la carrera seria entre el cliente y el event loop, no entre los pedidos.
  for (let intento = 0; intento < 500; intento++) {
    const salud = JSON.parse((await pedir(ruta, { metodo: 'GET', ruta: '/salud' })).cuerpo);
    if (salud.enVuelo + salud.enCola >= cuantos) return pedidos;
    await new Promise((r) => setTimeout(r, 5));
  }
  throw new Error('el servidor no llego a admitir todos los pedidos');
}

test('a26 con MAX_CONCURRENTES + MAX_COLA + 1 pedidos simultaneos exactamente uno recibe 503', async () => {
  const motor = new MotorDePrueba(true);
  await conServidor(motor, async (ruta) => {
    const admitidos = await saturar(ruta, C.MAX_CONCURRENTES + C.MAX_COLA);
    // Solo MAX_CONCURRENTES llegan al motor; el resto espera turno (R9.1).
    assert.equal(motor.llamadas, C.MAX_CONCURRENTES);

    // El que sobra: por encima de MAX_CONCURRENTES + MAX_COLA, 503 inmediato (R9.2).
    const sobrante = await pedir(ruta, {
      metodo: 'POST', ruta: '/ejecutar', cabeceras: cabeceras(1), cuerpo: Buffer.from('x'),
    });
    assert.equal(sobrante.codigo, 503);
    assert.equal(sobrante.cabeceras['retry-after'], '5');
    assert.equal(JSON.parse(sobrante.cuerpo).resultado, 'RECHAZADA');

    motor.soltar();
    const respuestas = await Promise.all(admitidos);
    assert.equal(respuestas.filter((r) => r.codigo === 200).length, C.MAX_CONCURRENTES + C.MAX_COLA);
  });
});

test('r3.3 con la cola llena /salud sigue devolviendo 200', async () => {
  const motor = new MotorDePrueba(true);
  await conServidor(motor, async (ruta) => {
    const admitidos = await saturar(ruta, C.MAX_CONCURRENTES + C.MAX_COLA);
    const salud = await pedir(ruta, { metodo: 'GET', ruta: '/salud' });
    assert.equal(salud.codigo, 200, 'saturacion no es enfermedad');
    const cuerpo = JSON.parse(salud.cuerpo);
    assert.equal(cuerpo.daemon, 'ok');
    assert.equal(cuerpo.enVuelo, C.MAX_CONCURRENTES);
    assert.equal(cuerpo.enCola, C.MAX_COLA);

    motor.soltar();
    await Promise.all(admitidos);
  });
});

test('a29 /salud devuelve 503 si el daemon no responde', async () => {
  const motor = new MotorDePrueba();
  motor.daemonOk = false;
  await conServidor(motor, async (ruta) => {
    const r = await pedir(ruta, { metodo: 'GET', ruta: '/salud' });
    assert.equal(r.codigo, 503);
    assert.equal(JSON.parse(r.cuerpo).daemon, 'caido');
  });
});

test('un ERROR_DAEMON del motor se responde con 502', async () => {
  const motor: Motor = {
    ejecutar: async (ejecucionId) => ({
      ejecucionId, resultado: 'ERROR_DAEMON', exitCode: null, oomKilled: false, duracionMs: 4,
      stdout: '', stderr: '', reporte: null, reporteAusente: true, salidaTruncada: false,
    }),
    daemonVivo: async () => true,
    limpiarEnVuelo: async () => undefined,
  };
  await conServidor(motor, async (ruta) => {
    const r = await pedir(ruta, { metodo: 'POST', ruta: '/ejecutar', cabeceras: cabeceras(0) });
    assert.equal(r.codigo, 502);
  });
});

test('un TIMEOUT se responde con 200: es un resultado, no un error', async () => {
  const motor: Motor = {
    ejecutar: async (ejecucionId) => ({
      ejecucionId, resultado: 'TIMEOUT', exitCode: null, oomKilled: false, duracionMs: 60000,
      stdout: 'parcial', stderr: '', reporte: null, reporteAusente: true, salidaTruncada: true,
    }),
    daemonVivo: async () => true,
    limpiarEnVuelo: async () => undefined,
  };
  await conServidor(motor, async (ruta) => {
    const r = await pedir(ruta, { metodo: 'POST', ruta: '/ejecutar', cabeceras: cabeceras(0) });
    assert.equal(r.codigo, 200);
    assert.equal(JSON.parse(r.cuerpo).resultado, 'TIMEOUT');
  });
});

test('no existe ningun otro endpoint', async () => {
  const motor = new MotorDePrueba();
  await conServidor(motor, async (ruta) => {
    for (const prueba of ['/ejecutar/otro', '/containers/create', '/']) {
      assert.equal((await pedir(ruta, { metodo: 'GET', ruta: prueba })).codigo, 404);
    }
    // R2.2 / no-requerimiento: no hay forma de elegir imagen, limites, red ni comando.
    assert.equal((await pedir(ruta, { metodo: 'PUT', ruta: '/ejecutar' })).codigo, 404);
  });
});

test('a30 r11.7 tras cerrar, el socket deja de aceptar', async () => {
  const motor = new MotorDePrueba();
  const ruta = rutaSocket('ejecutor');
  const servidor = new Servidor(ruta, motor);
  await servidor.escuchar();
  assert.equal((await pedir(ruta, { metodo: 'GET', ruta: '/salud' })).codigo, 200);
  await servidor.cerrar();
  await assert.rejects(pedir(ruta, { metodo: 'GET', ruta: '/salud' }));
});
