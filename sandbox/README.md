# `sandbox/` — banco de trabajo del ms-sandbox

**Provisorio.** El repo que se entrega todavía no existe. Acá se construye, se
mide y se rompe; lo que sobreviva se documenta y se migra allá.

TPI · UTN FRC · Programación 4 + Metodología de Sistemas 2 · **TEMA 06 — Sandbox / Runtime**

## Qué hay

```
sandbox/
├── runner/          la imagen de ejecución: Dockerfile + entrypoint + bundles de prueba
│   └── README.md    ← el contrato del contenedor (stdin/stdout, relojes, exit codes)
└── (api/)           todavía no: Spring Boot, perfiles `api` y `worker`
```

## Por qué se arranca por el `runner` y no por la API

Tres razones, en orden:

1. **Ahí viven las afirmaciones sin verificar.** Los tres relojes, el reporte no
   escribible, el classpath ordenado, el no-escaneo. Son las que el documento marca
   como pendientes 1 a 5, y ninguna se prueba desde Spring.
2. **Define la forma del reporte**, que es el input de todo lo demás. Si primero se
   arma la API y después el runner cambia el sobre, se reescribe el normalizador.
3. **No arrastra dependencias.** Es bash y Docker. Ni Spring, ni RabbitMQ, ni T05.

## Cómo empezar

```sh
cd runner
./build.sh                 # necesita el demonio de Docker corriendo
./run.sh bundles/ok-suma
```

## Lo que sigue, en orden

1. **Construir y correr el camino feliz.** Hasta que eso pase, todo lo escrito es
   hipótesis.
2. **La suite de entregas hostiles** — `System.exit(0)`, fork bomb, symlink, hard
   link, reescritura del reporte, socket de red, fuga de memoria, sleep infinito,
   paquete usurpado. Es el pendiente 7 y es *la evidencia para la defensa*: no
   "diseñamos un sandbox seguro" sino "acá está el ataque y acá está contenido".
3. **Medir de nuevo** con el reloj de CPU y con el no-escaneo, y anotar qué cambió
   respecto de lo que midió el spike.
4. Recién ahí, la API.

## Cuando llegue el turno de la API

Se arranca del scaffolding propio (`C:\dev\scaffolding\be-scaffolding`), con estos
cambios ya identificados:

- **Sacar** todo el bloque de auth JWT (`User`, `AuthService`, `JwtService`, jjwt,
  los DTOs): el sandbox es un servicio interno, no autentica usuarios finales.
  También sobran websocket, devtools y ModelMapper.
- **Cambiar** H2 por PostgreSQL + Flyway + Testcontainers — el esquema usa `JSONB`
  e índices parciales, y H2 no da ninguno de los dos.
- **Cambiar** `ErrorApi` por `ProblemDetail` (RFC 7807), que es lo que especifica
  `03` §6.5.
- **Agregar** `starter-amqp`, cliente de Docker, `micrometer-prometheus`,
  `resilience4j`.
- **Estructurar** para un jar con dos perfiles, `api` y `worker`: solo el worker
  toca el socket de Docker, y eso es una decisión de seguridad (`04` §10).
- **Revisar** el umbral de cobertura del 95% de línea: acá lo crítico se prueba con
  integración contra Docker, y forzar el número empuja a tests malos.
