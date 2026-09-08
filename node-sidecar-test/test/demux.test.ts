import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as C from '../src/constantes.js';
import { demultiplexar } from '../src/demux.js';
import { ErrorDaemon } from '../src/errores.js';
import { frame } from './ayudas.js';

/** Seccion 13.3 — A8 a A12, sobre streams sinteticos. */

test('a8 muchos frames chicos reconstruyen el texto exacto', () => {
  const partes = ['hola', ' ', 'mundo', '!', '\n', 'segunda linea'];
  const stream = Buffer.concat(partes.map((p) => frame(1, p)));
  const salida = demultiplexar(stream);
  assert.equal(salida.stdout, partes.join(''));
  assert.equal(salida.stderr, '');
  assert.equal(salida.truncada, false);
});

test('a8 los dos streams se separan sin entrelazarse', () => {
  const stream = Buffer.concat([frame(1, 'out1'), frame(2, 'err1'), frame(1, 'out2'), frame(2, 'err2')]);
  const salida = demultiplexar(stream);
  assert.equal(salida.stdout, 'out1out2');
  assert.equal(salida.stderr, 'err1err2');
});

test('a9 un frame que declara 4 gib aborta sin reservar memoria', () => {
  // Se declara el largo sin adjuntar el payload: si el demuxer reservara antes de validar, moriria aca.
  const stream = frame(1, '', 0xFFFFFFFF);
  assert.throws(() => demultiplexar(stream), ErrorDaemon);
});

test('a9 el limite es MAX_FRAME_BYTES, no el tamano del buffer', () => {
  const stream = frame(1, '', C.MAX_FRAME_BYTES + 1);
  assert.throws(() => demultiplexar(stream), /frame de 1048577 bytes/);
});

test('a10 un frame de largo 0 en el medio no traba el bucle', () => {
  const stream = Buffer.concat([frame(1, 'antes'), frame(1, ''), frame(1, 'despues')]);
  assert.equal(demultiplexar(stream).stdout, 'antesdespues');
});

test('a11 un tipo de stream 7 es error fatal, no se asume stdout', () => {
  const stream = Buffer.concat([frame(1, 'ok'), frame(7, 'sospechoso')]);
  assert.throws(() => demultiplexar(stream), /tipo de stream invalido: 7/);
});

test('a12 una linea que cruza dos frames no se corta', () => {
  const stream = Buffer.concat([frame(1, 'una linea par'), frame(1, 'tida al medio\n')]);
  assert.equal(demultiplexar(stream).stdout, 'una linea partida al medio\n');
});

test('r6.5 un encabezado incompleto al final es corrupcion, no fin de stream', () => {
  const stream = Buffer.concat([frame(1, 'ok'), Buffer.from([1, 0, 0])]);
  assert.throws(() => demultiplexar(stream), /frame truncado en el encabezado/);
});

test('r6.5 un payload mas corto que el largo declarado es corrupcion', () => {
  const stream = Buffer.concat([frame(1, 'abc', 99)]);
  assert.throws(() => demultiplexar(stream), /frame truncado en el payload/);
});

test('r6.7 al pasar MAX_SALIDA_BYTES se conserva el principio y se marca truncada', () => {
  const bloque = 'x'.repeat(64 * 1024);
  const frames: Buffer[] = [];
  for (let i = 0; i < 20; i++) frames.push(frame(1, bloque));   // 1.25 MiB > 1 MiB
  const salida = demultiplexar(Buffer.concat(frames));
  assert.equal(salida.truncada, true);
  assert.equal(salida.stdout.length, C.MAX_SALIDA_BYTES);
  assert.ok(salida.stdout.startsWith('xxxx'));
});

test('r6.7 el truncado de un stream no marca ni recorta al otro', () => {
  const frames = [frame(2, 'error corto')];
  for (let i = 0; i < 20; i++) frames.push(frame(1, 'x'.repeat(64 * 1024)));
  const salida = demultiplexar(Buffer.concat(frames));
  assert.equal(salida.stderr, 'error corto');
  assert.equal(salida.truncada, true);
});

test('un corte en medio de un caracter utf-8 se reemplaza, no revienta', () => {
  const enie = Buffer.from('ñ', 'utf8');
  const stream = Buffer.concat([frame(1, enie.subarray(0, 1)), frame(1, enie.subarray(1))]);
  // Los dos frames se concatenan antes de decodificar (R6.6), asi que el caracter sobrevive entero.
  assert.equal(demultiplexar(stream).stdout, 'ñ');
});

test('un stream vacio da dos strings vacios', () => {
  assert.deepEqual(demultiplexar(Buffer.alloc(0)), { stdout: '', stderr: '', truncada: false });
});
