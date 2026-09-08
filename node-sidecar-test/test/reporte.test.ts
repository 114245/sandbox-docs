import assert from 'node:assert/strict';
import { test } from 'node:test';
import { extraer } from '../src/reporte.js';

/** Seccion 7.5 a 7.8, y A21: el alumno no puede falsificar el bloque de reporte. */

const NONCE = '0123456789abcdef0123456789abcdef';
const INICIO = `---SANDBOX-${NONCE}-INICIO---`;
const FIN = `---SANDBOX-${NONCE}-FIN---`;

test('extrae el bloque y lo remueve de stdout', () => {
  const r = extraer(`antes\n${INICIO}\n<testsuite/>\n${FIN}\ndespues\n`, NONCE);
  assert.equal(r.reporte, '<testsuite/>');
  assert.equal(r.stdout, 'antes\ndespues\n');
});

test('r7.5 sin marcadores devuelve stdout intacto y reporte nulo', () => {
  const r = extraer('salida comun\n', NONCE);
  assert.equal(r.reporte, null);
  assert.equal(r.stdout, 'salida comun\n');
});

test('r7.5 un inicio sin fin posterior no produce reporte', () => {
  const r = extraer(`antes\n${INICIO}\nreporte a medio escribir`, NONCE);
  assert.equal(r.reporte, null);
  assert.equal(r.stdout, `antes\n${INICIO}\nreporte a medio escribir`);
});

test('r7.6 con varios inicios se usa el ultimo', () => {
  const r = extraer(`${INICIO}\nviejo\n${INICIO}\nbueno\n${FIN}\n`, NONCE);
  assert.equal(r.reporte, 'bueno');
  assert.equal(r.stdout, `${INICIO}\nviejo\n`);
});

test('r7.5 con varios fines se usa el primero posterior al inicio', () => {
  const r = extraer(`${INICIO}\nbueno\n${FIN}\nbasura\n${FIN}\n`, NONCE);
  assert.equal(r.reporte, 'bueno');
  assert.equal(r.stdout, `basura\n${FIN}\n`);
});

test('a21 i5 un reporte falso con otro nonce no reemplaza al verdadero', () => {
  const falso = '---SANDBOX-deadbeefdeadbeefdeadbeefdeadbeef-INICIO---';
  const falsoFin = '---SANDBOX-deadbeefdeadbeefdeadbeefdeadbeef-FIN---';
  const stdout = `${falso}\n<testsuite tests="1" failures="0"/>\n${falsoFin}\n`
    + `${INICIO}\n<testsuite tests="1" failures="1"/>\n${FIN}\n`;
  const r = extraer(stdout, NONCE);
  assert.equal(r.reporte, '<testsuite tests="1" failures="1"/>');
  // El bloque falso queda en stdout: es salida del alumno, no un reporte.
  assert.ok(r.stdout.includes(falso));
});

test('a21 el alumno que imita el formato sin el nonce no produce reporte', () => {
  const r = extraer('---SANDBOX-0000-INICIO---\ntodo aprobado\n---SANDBOX-0000-FIN---\n', NONCE);
  assert.equal(r.reporte, null);
});

test('r7.8 se recorta el salto que sigue al inicio y el que precede al fin, y nada mas', () => {
  const r = extraer(`${INICIO}\n\nlinea\n\n${FIN}\n`, NONCE);
  assert.equal(r.reporte, '\nlinea\n');
});

test('r7.8 soporta crlf sin comerse un caracter de mas', () => {
  const r = extraer(`antes\r\n${INICIO}\r\n<xml/>\r\n${FIN}\r\ndespues`, NONCE);
  assert.equal(r.reporte, '<xml/>');
  assert.equal(r.stdout, 'antes\r\ndespues');
});

test('r7.8 sin salto despues del fin no se come nada del texto siguiente', () => {
  const r = extraer(`${INICIO}\nx\n${FIN}cola`, NONCE);
  assert.equal(r.reporte, 'x');
  assert.equal(r.stdout, 'cola');
});

test('un reporte vacio es un reporte, no una ausencia', () => {
  const r = extraer(`${INICIO}\n${FIN}\n`, NONCE);
  assert.equal(r.reporte, '');
  assert.equal(r.stdout, '');
});

test('a20 un nombre de archivo con saltos de linea no ensucia el bloque', () => {
  // El tar nunca se interpreta (I7): lo que llega es la salida del contenedor, y lo que separa el
  // reporte es el nonce, no la forma del texto.
  const r = extraer(`tar: raro\n${FIN}\nmas\n${INICIO}\nreal\n${FIN}\n`, NONCE);
  assert.equal(r.reporte, 'real');
});
