/**
 * A6 — el test que valida la seccion 7, contra un daemon de Docker REAL.
 *
 * Escribe `<nonce>\n<tar>` por el socket adjunto, cierra, y verifica que el contenedor haya podido
 * leer la primera linea como nonce Y listar el tar completo del mismo descriptor. Si esto falla,
 * la seccion 7 hay que rediseniarla y bloquea todo lo demas.
 *
 *   node scripts/verificar-a6.mjs            (requiere `npm run build` y un daemon accesible)
 *   DOCKER_SOCKET=/var/run/docker.sock node scripts/verificar-a6.mjs
 */
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { ClienteDocker } from '../dist/src/cliente-docker.js';
import { demultiplexar } from '../dist/src/demux.js';

const SOCKET = process.env.DOCKER_SOCKET
  || (process.platform === 'win32' ? '\\\\.\\pipe\\docker_engine' : '/var/run/docker.sock');
const IMAGEN = 'sandbox-a6:prueba';
const NOMBRE = `a6-${randomBytes(4).toString('hex')}`;

/** Un tar POSIX minimo, armado a mano: el ejecutor nunca lo interpreta (I7), asi que no hace falta mas. */
function tar(entradas) {
  const bloques = [];
  for (const [nombre, contenido] of entradas) {
    const datos = Buffer.from(contenido, 'utf8');
    const cabecera = Buffer.alloc(512);
    cabecera.write(nombre, 0, 100, 'ascii');
    cabecera.write('000644 \0', 100, 8, 'ascii');
    cabecera.write('000000 \0', 108, 8, 'ascii');
    cabecera.write('000000 \0', 116, 8, 'ascii');
    cabecera.write(datos.length.toString(8).padStart(11, '0') + ' ', 124, 12, 'ascii');
    cabecera.write('00000000000 ', 136, 12, 'ascii');
    cabecera.write('        ', 148, 8, 'ascii');          // checksum en blanco para calcularlo
    cabecera.write('0', 156, 1, 'ascii');
    cabecera.write('ustar\0' + '00', 257, 8, 'ascii');
    let suma = 0;
    for (const b of cabecera) suma += b;
    cabecera.write(suma.toString(8).padStart(6, '0') + '\0 ', 148, 8, 'ascii');
    const relleno = Buffer.alloc((512 - (datos.length % 512)) % 512);
    bloques.push(cabecera, datos, relleno);
  }
  bloques.push(Buffer.alloc(1024));                        // fin de archivo
  return Buffer.concat(bloques);
}

const cliente = new ClienteDocker(SOCKET);
const contexto = fileURLToPath(new URL('../test/fixtures/a6/', import.meta.url));
console.log(`construyendo ${IMAGEN}...`);
execFileSync('docker', ['build', '-q', '-t', IMAGEN, contexto], { stdio: ['ignore', 'ignore', 'inherit'] });

const nonce = randomBytes(16).toString('hex');
const bundle = tar([['Solucion.java', 'class Solucion {}\n'], ['pom.xml', '<project/>\n'],
  ['src/test/java/SolucionTest.java', 'class SolucionTest {}\n']]);

// Spec propia del harness, no la del ejecutor: A6 prueba el protocolo de la seccion 7, no la 4.1.
const crear = await cliente.pedir('POST', `/v1.43/containers/create?name=${NOMBRE}`,
  Buffer.from(JSON.stringify({
    Image: IMAGEN, OpenStdin: true, StdinOnce: true, AttachStdin: true,
    AttachStdout: false, AttachStderr: false, Tty: false,
    HostConfig: { NetworkMode: 'none', AutoRemove: false, LogConfig: { Type: 'json-file' } },
  })), 5000);
if (crear.codigo !== 201) throw new Error(`create respondio ${crear.codigo}`);
const id = JSON.parse(crear.cuerpo.toString('utf8')).Id;

try {
  const entrada = await cliente.adjuntarStdin(id);        // paso 2, antes del start (R5.1)
  await cliente.iniciar(id);                               // paso 3
  entrada.write(Buffer.from(`${nonce}\n`, 'ascii'));       // paso 4: nonce y despues el tar (R7.2)
  await new Promise((r) => entrada.end(bundle, r));

  await cliente.esperar(id, 30_000);
  const salida = demultiplexar(await cliente.logs(id));

  const listado = salida.stdout;
  const errores = [];
  if (!listado.includes(`FIN ${nonce}`)) errores.push('el entrypoint no leyo el nonce de la primera linea');
  for (const esperado of ['Solucion.java', 'pom.xml', 'src/test/java/SolucionTest.java']) {
    if (!listado.includes(esperado)) errores.push(`falta ${esperado} en el listado del tar`);
  }

  console.log('--- stdout del contenedor ---');
  console.log(listado.replace(nonce, '<nonce>'));
  if (salida.stderr) console.log(`--- stderr ---\n${salida.stderr}`);

  if (errores.length > 0) {
    console.error(`\nA6 FALLA:\n  ${errores.join('\n  ')}`);
    console.error('La seccion 7 hay que rediseniarla; este test bloquea todo lo demas.');
    process.exitCode = 1;
  } else {
    console.log('\nA6 PASA: el shell leyo la primera linea como nonce y le dejo el resto del descriptor a tar.');
  }
} finally {
  await cliente.borrar(id);                                // paso 7 (R5.3)
}
