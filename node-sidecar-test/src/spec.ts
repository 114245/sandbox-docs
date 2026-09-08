import * as C from './constantes.js';

/**
 * La spec del contenedor (seccion 4.1). Es el corazon del componente.
 *
 * P1 (invariante de spec fija): lo unico que varia entre ejecuciones es el label
 * "sandbox.ejecucion" y el nombre del contenedor. Ningun otro byte proviene de quien llama.
 * El golden test A1/A2 es lo que convierte esa afirmacion en evidencia.
 *
 * JSON.stringify emite las claves de un objeto literal en orden de insercion (ninguna es un
 * indice de arreglo), de modo que el orden de este literal es el orden de los bytes que salen.
 * Es la misma garantia que da Jackson sobre ObjectNode, y por eso las dos implementaciones
 * comparten el mismo archivo de referencia.
 */

/** Nombre del contenedor, para la query de create. */
export function nombre(ejecucionId: string): string {
  return `sandbox-${ejecucionId}`;
}

const ulimit = (Name: string, valor: number) => ({ Name, Soft: valor, Hard: valor });

export function crear(ejecucionId: string): Buffer {
  return Buffer.from(JSON.stringify({
    Image: C.IMAGEN,
    Entrypoint: ['/opt/sandbox/entrypoint.sh'],
    Cmd: [],
    User: '1000:1000',
    WorkingDir: '/work',
    Env: [],
    OpenStdin: true,
    StdinOnce: true,
    AttachStdin: true,
    // No nos adjuntamos a la salida: la drena el log driver. Elimina el deadlock por buffer lleno.
    AttachStdout: false,
    AttachStderr: false,
    // Tty false es lo que hace que la salida venga enmarcada (seccion 6) y cierra CVE-2025-52565.
    Tty: false,
    NetworkDisabled: true,
    Labels: { sandbox: '1', 'sandbox.ejecucion': ejecucionId },
    HostConfig: {
      NetworkMode: 'none',
      ReadonlyRootfs: true,
      // uid/gid: el tmpfs lo crea Docker como root. Con mode=0700 y sin estas dos opciones,
      // el contenedor -que corre como 1000:1000- no puede escribir en su unico directorio
      // escribible y el entrypoint muere con Permission denied antes de leer el bundle.
      Tmpfs: { '/work': 'rw,noexec,nosuid,nodev,size=64m,mode=0700,uid=1000,gid=1000' },
      Memory: 536870912,
      MemorySwap: 536870912,   // igual a Memory: sin swap, o el limite de memoria deja de ser un limite de tiempo
      MemorySwappiness: 0,
      NanoCpus: 1000000000,
      PidsLimit: 128,
      CapDrop: ['ALL'],
      CapAdd: [],
      SecurityOpt: ['no-new-privileges:true'],
      Privileged: false,
      // AutoRemove false es obligatorio: con true el contenedor puede desaparecer antes de que leamos logs.
      AutoRemove: false,
      // Binds/Mounts/Devices se escriben aunque esten vacios, para que el golden test los cubra.
      Binds: [],
      Mounts: [],
      Devices: [],
      RestartPolicy: { Name: 'no' },
      // Explicito: `logs` solo funciona con json-file o local. Evita acoplarse al daemon.json del host.
      LogConfig: { Type: 'json-file', Config: { 'max-size': '8m', 'max-file': '1' } },
      Ulimits: [
        // Tiempo de CPU, no de pared: no cuenta el tiempo en que el host le dio el procesador a otra
        // ejecucion del pool, que es lo que elimina los TIMEOUT intermitentes por varianza en vez de
        // acolcharlos con margen. Al agotarse, el kernel manda SIGXCPU.
        ulimit('cpu', C.TIMEOUT_CPU_SEGUNDOS),
        ulimit('nofile', 256),
        ulimit('nproc', 128),
        ulimit('fsize', 33554432),
      ],
    },
  }), 'utf8');
}
