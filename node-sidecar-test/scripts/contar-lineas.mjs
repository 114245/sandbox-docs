// R14.0: la comparacion con Java se hace sobre la tabla de la seccion 14, con lineas efectivas
// medidas igual — sin comentarios y sin blancos.
import { readFileSync } from 'node:fs';

const GRUPOS = {
  'Cliente HTTP sobre socket Unix': ['cliente-docker.ts'],
  'Spec del contenedor': ['spec.ts', 'constantes.ts'],
  'Demultiplexador': ['demux.ts'],
  'Extraccion del reporte': ['reporte.ts'],
  'Orquestacion de la secuencia': ['ejecucion.ts'],
  'Servidor HTTP + cola': ['servidor.ts'],
  'Barrido de huerfanos': ['barrido.ts'],
  'Cableado y frontera': ['main.ts', 'log.ts', 'errores.ts'],
};

/** Lineas efectivas: ni blancos, ni `//`, ni bloques `/* ... *\/`. */
function efectivas(archivo) {
  const texto = readFileSync(new URL(`../src/${archivo}`, import.meta.url), 'utf8');
  let enBloque = false;
  let total = 0;
  for (const cruda of texto.split('\n')) {
    const linea = cruda.trim();
    if (enBloque) {
      if (linea.includes('*/')) enBloque = false;
      continue;
    }
    if (linea === '' || linea.startsWith('//')) continue;
    if (linea.startsWith('/*')) {
      if (!linea.includes('*/')) enBloque = true;
      continue;
    }
    total++;
  }
  return total;
}

let total = 0;
console.log('| Modulo | Archivos | Lineas efectivas |');
console.log('|---|---|---|');
for (const [grupo, archivos] of Object.entries(GRUPOS)) {
  const n = archivos.reduce((suma, a) => suma + efectivas(a), 0);
  total += n;
  console.log(`| ${grupo} | ${archivos.join(', ')} | ${n} |`);
}
console.log(`| **Total propio** | | **${total}** |`);
console.log(`\nObjetivo de R11.5: por debajo de 400 lineas. ${total < 400 ? 'Se cumple.' : 'NO se cumple.'}`);
