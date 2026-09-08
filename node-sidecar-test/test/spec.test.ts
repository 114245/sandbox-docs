import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import * as C from '../src/constantes.js';
import * as Spec from '../src/spec.js';

/**
 * Seccion 13.1 — el golden test de la spec, el mas importante.
 * Es lo que convierte I1 e I2 de afirmacion en evidencia.
 */

const GOLDEN = fileURLToPath(new URL('./fixtures/spec-create-referencia.json', import.meta.url));
const referencia = fs.readFileSync(GOLDEN, 'utf8');

/** Normaliza lo unico que la spec permite que varie: el label de la ejecucion. */
function normalizar(json: string, ejecucionId: string): string {
  return json.replaceAll(ejecucionId, '<EJECUCION_ID>');
}

test('a1_a2 el json de create es identico al archivo de referencia para tres ids distintos', () => {
  for (const id of ['3f2b1c4d-0000-4000-8000-000000000001',
                    '7a9e5f21-1111-4111-8111-111111111111',
                    'ffffffff-2222-4222-8222-222222222222']) {
    const json = Spec.crear(id).toString('utf8');
    assert.equal(normalizar(json, id), referencia,
      'el json de create difiere del archivo de referencia compartido con la implementacion Java');
  }
});

test('a1 lo unico que varia entre dos ejecuciones es el label y el nombre', () => {
  const a = 'aaaaaaaa-0000-4000-8000-000000000000';
  const b = 'bbbbbbbb-0000-4000-8000-000000000000';
  assert.equal(normalizar(Spec.crear(a).toString('utf8'), a),
               normalizar(Spec.crear(b).toString('utf8'), b));
  assert.equal(Spec.nombre(a), `sandbox-${a}`);
});

test('i2 el id no puede inyectar campos en la spec: viaja como valor json escapado', () => {
  const hostil = '","HostConfig":{"Privileged":true},"x":"';
  const json = JSON.parse(Spec.crear(hostil).toString('utf8'));
  assert.equal(json.HostConfig.Privileged, false);
  assert.equal(json.Labels['sandbox.ejecucion'], hostil);
});

test('a32 el json de create trae el ulimit cpu con TIMEOUT_CPU_SEGUNDOS', () => {
  const json = JSON.parse(Spec.crear('00000000-0000-4000-8000-000000000000').toString('utf8'));
  const cpu = json.HostConfig.Ulimits.find((u: { Name: string }) => u.Name === 'cpu');
  assert.deepEqual(cpu, { Name: 'cpu', Soft: C.TIMEOUT_CPU_SEGUNDOS, Hard: C.TIMEOUT_CPU_SEGUNDOS });
});

test('a32 r4.1 el presupuesto de cpu es holgadamente menor que el reloj de pared', () => {
  // Assert sobre las constantes, no sobre el json: protege contra que alguien suba el presupuesto
  // de CPU sin subir el reloj de pared y deje el limite decorativo sin que nada falle.
  assert.ok(C.TIMEOUT_CPU_SEGUNDOS < C.TIMEOUT_EJECUCION_MS / 1000);
  assert.ok(C.TIMEOUT_CPU_SEGUNDOS <= C.TIMEOUT_EJECUCION_MS / 1000 / 2,
    'la holgura tiene que ser real, no de un segundo');
});

test('r10.2 la edad de huerfano es holgadamente mayor que el reloj de ejecucion', () => {
  assert.ok(C.EDAD_HUERFANO_MS >= C.TIMEOUT_EJECUCION_MS * 10);
});

test('la spec fija la version de la api de docker en el path (r5.0)', () => {
  assert.equal(C.VERSION_API_DOCKER, 'v1.43');
});
