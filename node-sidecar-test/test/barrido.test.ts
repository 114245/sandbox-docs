import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as C from '../src/constantes.js';
import { Barrido } from '../src/barrido.js';
import { ClienteDocker } from '../src/cliente-docker.js';
import { DaemonDePrueba, type Guion } from './ayudas.js';

/** Seccion 10: la limpieza de huerfanos, que cierra la invariante I6. */

const segundos = (ms: number) => Math.floor((Date.now() - ms) / 1000);

async function conDaemon<T>(guion: Guion, cuerpo: (d: DaemonDePrueba, b: Barrido, enVuelo: Set<string>)
  => Promise<T>): Promise<T> {
  const daemon = new DaemonDePrueba(guion);
  await daemon.escuchar();
  const enVuelo = new Set<string>();
  try {
    return await cuerpo(daemon, new Barrido(new ClienteDocker(daemon.ruta), enVuelo), enVuelo);
  } finally {
    await daemon.cerrar();
  }
}

test('a28 i6 borra los contenedores etiquetados que superan la edad de huerfano', async () => {
  await conDaemon({
    contenedores: [
      { Id: 'viejo', Created: segundos(C.EDAD_HUERFANO_MS + 60_000) },
      { Id: 'reciente', Created: segundos(1_000) },
    ],
  }, async (daemon, barrido) => {
    await barrido.barrer();
    assert.equal(daemon.borrados.length, 1);
    assert.ok(daemon.borrados[0]!.includes('viejo'));
  });
});

test('r10.1 el filtro pide exactamente la etiqueta sandbox=1', async () => {
  await conDaemon({ contenedores: [] }, async (daemon, barrido) => {
    await barrido.barrer();
    const listado = daemon.llamadas.find((l) => l.includes('/containers/json'));
    assert.ok(listado);
    assert.ok(listado.includes('all=1'));
    assert.equal(decodeURIComponent(listado.split('filters=')[1]!), '{"label":["sandbox=1"]}');
  });
});

test('r10.2 no borra contenedores en vuelo de esta instancia', async () => {
  await conDaemon({
    contenedores: [{ Id: 'mio', Created: segundos(C.EDAD_HUERFANO_MS + 60_000) }],
  }, async (daemon, barrido, enVuelo) => {
    enVuelo.add('mio');
    await barrido.barrer();
    assert.equal(daemon.borrados.length, 0);
  });
});

test('r10.3 un fallo del listado se registra y no propaga', async () => {
  await conDaemon({}, async (daemon, barrido) => {
    await daemon.cerrar();                       // el daemon deja de responder a mitad de camino
    await barrido.barrer();                      // no debe lanzar
    assert.equal(daemon.borrados.length, 0);
  });
});
