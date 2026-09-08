// integracion-runner.mjs — la prueba que faltaba (P0 del README de arquitectura).
//
// Las suites de aceptacion de las dos implementaciones corren contra una imagen
// busybox de fixture: prueban que el ejecutor cumple la spec, NO que el sistema
// funcione. Esto corre el ejecutor real contra la imagen real del runner.
//
//   node scripts/integracion-runner.mjs <bundle.tar> [--esperado EXITO|FALLO]
//
// Variables:
//   EJECUTOR_SOCKET   named pipe o socket del ejecutor (default: pipe de prueba)
//
// Sale 0 si el camino feliz da un reporte con tests > 0 y sin fallas.

import { readFileSync } from 'node:fs';
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { gunzipSync } from 'node:zlib';

const tarPath = process.argv[2];
if (!tarPath) {
  console.error('uso: node scripts/integracion-runner.mjs <bundle.tar>');
  process.exit(64);
}

const socket = process.env['EJECUTOR_SOCKET'] || '\\\\.\\pipe\\ejecutor-integracion';
const cuerpo = readFileSync(tarPath);
const ejecucionId = randomUUID();

console.log(`>> POST /ejecutar  socket=${socket}`);
console.log(`   X-Ejecucion-Id: ${ejecucionId}`);
console.log(`   bundle: ${tarPath} (${cuerpo.length} bytes)`);

const t0 = Date.now();

const respuesta = await new Promise((resolver, rechazar) => {
  const pedido = http.request(
    {
      socketPath: socket,
      method: 'POST',
      path: '/ejecutar',
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Length': String(cuerpo.length),
        'X-Ejecucion-Id': ejecucionId,
      },
    },
    (res) => {
      const trozos = [];
      res.on('data', (t) => trozos.push(t));
      res.on('end', () => resolver({ estado: res.statusCode, cuerpo: Buffer.concat(trozos).toString('utf8') }));
    },
  );
  pedido.setTimeout(120_000, () => {
    pedido.destroy(new Error('timeout del cliente: 120 s sin respuesta'));
  });
  pedido.on('error', rechazar);
  pedido.end(cuerpo);
});

const ms = Date.now() - t0;
console.log(`\n<< ${respuesta.estado}  en ${ms} ms\n`);

if (respuesta.estado !== 200) {
  console.log(respuesta.cuerpo);
  process.exit(70);
}

const r = JSON.parse(respuesta.cuerpo);

console.log('=== respuesta del ejecutor ===');
for (const campo of ['resultado', 'exitCode', 'oomKilled', 'duracionMs', 'reporteAusente', 'salidaTruncada']) {
  console.log(`  ${campo.padEnd(16)}: ${r[campo]}`);
}
console.log(`  stdout          : ${r.stdout ? r.stdout.length + ' bytes' : '(vacio)'}`);
console.log(`  stderr          : ${r.stderr ? r.stderr.length + ' bytes' : '(vacio)'}`);
console.log(`  reporte         : ${r.reporte ? r.reporte.length + ' bytes' : 'null'}`);

if (r.stdout) console.log('\n--- stdout ---\n' + r.stdout.slice(0, 2000));
if (r.stderr) console.log('\n--- stderr ---\n' + r.stderr.slice(0, 2000));
if (r.reporte) console.log('\n--- reporte (primeros 1500) ---\n' + r.reporte.slice(0, 1500));

// El veredicto lo produce el worker, no el ejecutor (R3.1). Esto es lo minimo
// que el worker haria con esta respuesta, para saber si el camino feliz cierra.
//
// OJO: `reporte` NO es el XML de JUnit. Es el sobre JSON del runner, que trae
// los XML adentro como tar.gz en base64. La spec (08 §3.1) muestra un ejemplo
// con `"<testsuite ...>"` y eso induce a error: el campo es opaco para el
// ejecutor, y el que sabe interpretarlo es el worker.
console.log('\n=== lo que haria el worker ===');
if (r.resultado !== 'COMPLETADA') {
  console.log(`  resultado ${r.resultado} -> no hay veredicto academico`);
  process.exit(75);
}
if (r.reporteAusente) {
  console.log('  reporte ausente con exitCode ' + r.exitCode + ' -> SALIDA_ANTICIPADA');
  process.exit(75);
}

let sobre;
try {
  sobre = JSON.parse(r.reporte);
} catch {
  console.log('  el sobre no es JSON valido -> ERROR_INTERNO');
  process.exit(75);
}
console.log(`  sobre: fase=${sobre.fase} resultado=${sobre.resultado} testsEnReporte=${sobre.testsEnReporte}`);
console.log(`  recursos: compilacion=${sobre.recursos?.tiempoCompilacionMs}ms pruebas=${sobre.recursos?.tiempoPruebasMs}ms cpu=${sobre.recursos?.cpuPruebasMs}ms`);

// El mapeo real: la version ejecutable de la tabla de 04 §6.
//
// HALLAZGO: el veredicto NO se puede derivar de los campos del ejecutor solos.
// `resultado: COMPLETADA` + `exitCode: 26` + reporte presente es un TIMEOUT_CPU,
// y `tests > 0` solo alcanza para no aprobar de mas -- no para clasificar. El
// que manda es el `resultado` del sobre del runner.
const VEREDICTO = {
  SALIDA_ANTICIPADA: 'SALIDA_ANTICIPADA',
  TIMEOUT_CPU: 'TIMEOUT',
  TIMEOUT_PARED: 'TIMEOUT',
  TIMEOUT_COMPILACION: 'TIMEOUT',
  LIMITE_MEMORIA: 'LIMITE_MEMORIA',
  ERROR_COMPILACION: 'ERROR_COMPILACION',
  SUITE_INVALIDA: 'SUITE_INVALIDA',
  VEREDICTO_NO_CONFIABLE: 'VEREDICTO_NO_CONFIABLE',
  SIN_REPORTE: 'ERROR_INTERNO',
  MUERTO_POR_SENAL: 'ERROR_INTERNO',
  BUNDLE_INVALIDO: 'ERROR_INTERNO',
};

if (sobre.resultado !== 'OK') {
  const v = VEREDICTO[sobre.resultado] ?? 'ERROR_INTERNO';
  const vida = v === 'ERROR_INTERNO' || v === 'SUITE_INVALIDA' ? 'NO consume vida' : 'consume vida';
  console.log(`  veredicto -> ${v}  (${vida})`);
  process.exit(v === 'ERROR_INTERNO' ? 70 : 75);
}

// `OK` significa "el pipeline corrio y hay reporte", no "aprobo". La guarda del
// veredicto (03 §1.6c) es tests > 0 leido del XML de verdad, no del contador del
// runner. El tar.gz se descomprime y se busca en los bytes crudos: alcanza para
// esta prueba y evita meter una dependencia de tar.
if (!sobre.reportesTarGzB64) {
  console.log('  sin reportes -> SALIDA_ANTICIPADA (nunca EXITO)');
  process.exit(75);
}
const crudo = gunzipSync(Buffer.from(sobre.reportesTarGzB64, 'base64')).toString('latin1');
const tests = [...crudo.matchAll(/tests="(\d+)"/g)].reduce((a, m) => a + Number(m[1]), 0);
const fallas = [...crudo.matchAll(/failures="(\d+)"/g)].reduce((a, m) => a + Number(m[1]), 0);
const errores = [...crudo.matchAll(/errors="(\d+)"/g)].reduce((a, m) => a + Number(m[1]), 0);
console.log(`  XML de JUnit: tests=${tests} failures=${fallas} errors=${errores}`);
if (tests === 0) {
  console.log('  tests = 0 -> SALIDA_ANTICIPADA (nunca EXITO)');
  process.exit(75);
}
const verde = fallas === 0 && errores === 0;
console.log(`  veredicto -> ${verde ? 'EXITO' : 'FALLO_TESTS'}`);
process.exit(verde ? 0 : 1);
