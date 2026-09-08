// tsc no copia archivos que no son .ts; el golden de A1/A2 tiene que viajar a dist/ junto al test.
import { cpSync } from 'node:fs';
cpSync(new URL('../test/fixtures/', import.meta.url), new URL('../dist/test/fixtures/', import.meta.url),
  { recursive: true });
