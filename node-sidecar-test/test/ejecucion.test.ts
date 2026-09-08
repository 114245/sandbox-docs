import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as C from '../src/constantes.js';
import { ClienteDocker } from '../src/cliente-docker.js';
import { Ejecucion } from '../src/ejecucion.js';
import { DaemonDePrueba, frame, type Guion } from './ayudas.js';

/** Secciones 5, 7 y 8 contra un daemon de mentira: la secuencia completa sin Docker real. */

const ID = '3f2b1c4d-0000-4000-8000-000000000001';

async function conDaemon<T>(guion: Guion, cuerpo: (d: DaemonDePrueba, e: Ejecucion) => Promise<T>): Promise<T> {
  const daemon = new DaemonDePrueba(guion);
  await daemon.escuchar();
  try {
    return await cuerpo(daemon, new Ejecucion(new ClienteDocker(daemon.ruta)));
  } finally {
    await daemon.cerrar();
  }
}

/** El stdout que emitiria el entrypoint: el reporte entre los marcadores con el nonce recibido. */
function salidaConReporte(stdinRecibido: Buffer, reporte: string): Buffer {
  const nonce = stdinRecibido.toString('ascii').split('\n')[0] ?? '';
  return frame(1, `hola\n---SANDBOX-${nonce}-INICIO---\n${reporte}\n---SANDBOX-${nonce}-FIN---\n`);
}

test('la secuencia de la seccion 5 corre en orden y borra el contenedor', async () => {
  await conDaemon({ logs: frame(1, 'sin reporte\n') }, async (daemon, ejecucion) => {
    const salida = await ejecucion.ejecutar(ID, Buffer.from('tar de prueba'));

    assert.equal(salida.resultado, 'COMPLETADA');
    assert.equal(salida.exitCode, 0);
    assert.equal(salida.stdout, 'sin reporte\n');
    assert.equal(salida.reporteAusente, true);
    assert.equal(salida.reporte, null);

    const pasos = daemon.llamadas.map((l) => l.replace(/\?.*/, '').replace('contenedor-de-prueba', 'C'));
    assert.deepEqual(pasos, [
      'POST /v1.43/containers/create',      // 1
      'attach /v1.43/containers/C/attach',  // 2 — R5.1: antes del start
      'POST /v1.43/containers/C/start',     // 3
      'POST /v1.43/containers/C/wait',      // 5
      'GET /v1.43/containers/C/json',       // 5b — R5.10
      'GET /v1.43/containers/C/logs',       // 6 — R5.4: antes del delete
      'DELETE /v1.43/containers/C',         // 7 — R5.3
    ]);
    assert.equal(daemon.borrados.length, 1);
    assert.equal(ejecucion.enVuelo.size, 0);
  });
});

test('r5.0 todas las rutas llevan la version de la api explicita en el path', async () => {
  await conDaemon({}, async (daemon, ejecucion) => {
    await ejecucion.ejecutar(ID, Buffer.alloc(0));
    for (const llamada of daemon.llamadas) {
      assert.ok(llamada.includes('/v1.43/'), `sin version en el path: ${llamada}`);
    }
  });
});

test('r7.2 stdin lleva el nonce como primera linea y despues el tar', async () => {
  await conDaemon({}, async (daemon, ejecucion) => {
    const tar = Buffer.from('CONTENIDO-DEL-TAR');
    await ejecucion.ejecutar(ID, tar);

    const salto = daemon.stdinRecibido.indexOf(0x0A);
    const nonce = daemon.stdinRecibido.subarray(0, salto).toString('ascii');
    assert.match(nonce, /^[0-9a-f]{32}$/, 'r7.1: 16 bytes en hexadecimal minuscula');
    assert.deepEqual(daemon.stdinRecibido.subarray(salto + 1), tar);
  });
});

test('r7.1 el nonce cambia en cada ejecucion', async () => {
  await conDaemon({}, async (daemon, ejecucion) => {
    await ejecucion.ejecutar(ID, Buffer.alloc(0));
    const primero = daemon.stdinRecibido.subarray(0, 32).toString('ascii');
    daemon.stdinRecibido = Buffer.alloc(0);
    await ejecucion.ejecutar(ID, Buffer.alloc(0));
    assert.notEqual(daemon.stdinRecibido.subarray(0, 32).toString('ascii'), primero);
  });
});

test('r7.3 el nonce no viaja por env: la spec manda Env vacio', async () => {
  await conDaemon({}, async (daemon, ejecucion) => {
    await ejecucion.ejecutar(ID, Buffer.alloc(0));
    const spec = JSON.parse(daemon.cuerpoCreate!.toString('utf8'));
    assert.deepEqual(spec.Env, []);
    assert.equal(daemon.cuerpoCreate!.toString('utf8').includes('SANDBOX_NONCE'), false);
  });
});

test('a3 el reporte se extrae de stdout y stdout queda sin el bloque', async () => {
  // El daemon arma los logs con el nonce que recibio por stdin, como haria el entrypoint real.
  await conDaemon({ logsDe: (stdin) => salidaConReporte(stdin, '<testsuite tests="3" failures="0"/>') },
    async (_daemon, ejecucion) => {
      const salida = await ejecucion.ejecutar(ID, Buffer.from('tar'));
      assert.equal(salida.resultado, 'COMPLETADA');
      assert.equal(salida.reporte, '<testsuite tests="3" failures="0"/>');
      assert.equal(salida.reporteAusente, false);
      assert.equal(salida.stdout, 'hola\n');
    });
});

test('a21 i5 el reporte falso del alumno no desplaza al verdadero de punta a punta', async () => {
  const falso = '---SANDBOX-deadbeefdeadbeefdeadbeefdeadbeef-INICIO---\n<testsuite tests="9" '
    + 'failures="0"/>\n---SANDBOX-deadbeefdeadbeefdeadbeefdeadbeef-FIN---\n';
  await conDaemon({
    logsDe: (stdin) => {
      const nonce = stdin.toString('ascii').split('\n')[0] ?? '';
      return frame(1, `${falso}---SANDBOX-${nonce}-INICIO---\n<testsuite tests="9" failures="4"/>\n`
        + `---SANDBOX-${nonce}-FIN---\n`);
    },
  }, async (_daemon, ejecucion) => {
    const salida = await ejecucion.ejecutar(ID, Buffer.alloc(0));
    assert.equal(salida.reporte, '<testsuite tests="9" failures="4"/>');
    assert.ok(salida.stdout.includes('failures="0"'), 'el bloque falso queda como salida del alumno');
  });
});

test('a4 a5 un exit code distinto de cero sigue siendo COMPLETADA', async () => {
  await conDaemon({ statusCode: 1, logs: frame(2, 'error: cannot find symbol\n') },
    async (_daemon, ejecucion) => {
      const salida = await ejecucion.ejecutar(ID, Buffer.alloc(0));
      // R3.1: el ejecutor no emite veredicto academico; compilacion fallida es un exitCode, no un error.
      assert.equal(salida.resultado, 'COMPLETADA');
      assert.equal(salida.exitCode, 1);
      assert.equal(salida.stderr, 'error: cannot find symbol\n');
    });
});

test('a7 r6.1 un content-type raw-stream es ERROR_DAEMON', async () => {
  await conDaemon({ contentTypeLogs: 'application/vnd.docker.raw-stream' }, async (daemon, ejecucion) => {
    const salida = await ejecucion.ejecutar(ID, Buffer.alloc(0));
    assert.equal(salida.resultado, 'ERROR_DAEMON');
    assert.equal(salida.exitCode, null);
    // R5.3: aun fallando, el contenedor se borra.
    assert.equal(daemon.borrados.length, 1);
  });
});

test('r5.3 un create que falla no deja contenedor ni rompe el proceso', async () => {
  await conDaemon({ codigoCreate: 500 }, async (daemon, ejecucion) => {
    const salida = await ejecucion.ejecutar(ID, Buffer.alloc(0));
    assert.equal(salida.resultado, 'ERROR_DAEMON');
    assert.equal(daemon.borrados.length, 0, 'no hay nada que borrar si create fallo');
    assert.equal(ejecucion.enVuelo.size, 0);
  });
});

test('r3.2 el mensaje de error no filtra la ruta del socket ni el cuerpo del daemon', async () => {
  await conDaemon({ codigoCreate: 500 }, async (daemon, ejecucion) => {
    const salida = await ejecucion.ejecutar(ID, Buffer.alloc(0));
    const json = JSON.stringify(salida);
    assert.equal(json.includes(daemon.ruta), false);
    assert.equal(json.includes('roto'), false);
  });
});

test('a31 c2 oomKilled viene del inspect, no del exit code', async () => {
  await conDaemon({ oomKilled: true, statusCode: 3 }, async (_daemon, ejecucion) => {
    const salida = await ejecucion.ejecutar(ID, Buffer.alloc(0));
    assert.equal(salida.oomKilled, true);
    assert.equal(salida.exitCode, 3);
    assert.equal(salida.resultado, 'COMPLETADA');
  });
});

test('a31 r5.10 un inspect que falla no aborta la ejecucion', async () => {
  await conDaemon({ inspectFalla: true, statusCode: 7, logs: frame(1, 'salida\n') },
    async (daemon, ejecucion) => {
      const salida = await ejecucion.ejecutar(ID, Buffer.alloc(0));
      // El resultado queda intacto; solo se pierde el dato de diagnostico.
      assert.equal(salida.oomKilled, false);
      assert.equal(salida.resultado, 'COMPLETADA');
      assert.equal(salida.exitCode, 7);
      assert.equal(salida.stdout, 'salida\n');
      assert.equal(daemon.borrados.length, 1);
    });
});

test('r3.4 un exit code 137 sin OOMKilled no se reinterpreta', async () => {
  await conDaemon({ statusCode: 137, oomKilled: false }, async (_daemon, ejecucion) => {
    const salida = await ejecucion.ejecutar(ID, Buffer.alloc(0));
    assert.equal(salida.exitCode, 137);
    assert.equal(salida.oomKilled, false, 'no se infiere del exitCode');
  });
});

// ---------------------------------------------------------------- los que consumen el reloj entero

test('r8.2 r5.10 al vencer el reloj hay kill, logs, inspect y delete, y el resultado es TIMEOUT',
  { timeout: 120_000 }, async () => {
    await conDaemon({ demoraWaitMs: C.TIMEOUT_EJECUCION_MS * 3, oomKilled: true, logs: frame(1, 'parcial\n') },
      async (daemon, ejecucion) => {
        const salida = await ejecucion.ejecutar(ID, Buffer.from('tar'));

        assert.equal(salida.resultado, 'TIMEOUT');
        assert.equal(salida.exitCode, null);
        // La salida parcial se devuelve igual: sirve para diagnosticar.
        assert.equal(salida.stdout, 'parcial\n');
        // R5.10: el paso 5b corre tambien en TIMEOUT.
        assert.equal(salida.oomKilled, true);

        const pasos = daemon.llamadas.map((l) => l.split(' ')[1]!.replace(/\?.*/, ''));
        assert.ok(pasos.some((p) => p.endsWith('/kill')));
        assert.ok(pasos.some((p) => p.endsWith('/logs')));
        assert.equal(daemon.borrados.length, 1);
      });
  });

test('a33 c3 r8.5 una escritura que no avanza no retiene el cupo de concurrencia',
  { timeout: 180_000 }, async () => {
    // El daemon adjunta el socket y no lee nunca: el buffer se llena y el paso 4 queda bloqueado.
    await conDaemon({ consumeStdin: false, demoraWaitMs: C.TIMEOUT_EJECUCION_MS * 3 },
      async (daemon, ejecucion) => {
        const comienzo = Date.now();
        const salida = await ejecucion.ejecutar(ID, Buffer.alloc(C.MAX_BUNDLE_BYTES, 0x41));
        const transcurrido = Date.now() - comienzo;

        // Sin el tope de R8.5 esto no termina nunca y el cupo no se libera jamas.
        assert.ok(transcurrido < C.TIMEOUT_EJECUCION_MS * 2.5,
          `la ejecucion tardo ${transcurrido} ms: la escritura no abandono`);
        assert.equal(salida.resultado, 'TIMEOUT');
        // El cupo se libera: el contenedor se borro y no queda nada en vuelo.
        assert.equal(ejecucion.enVuelo.size, 0);
        assert.equal(daemon.borrados.length, 1);
      });
  });
