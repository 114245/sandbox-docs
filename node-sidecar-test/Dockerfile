# R11.1: base distroless, sin shell y sin gestor de paquetes.
# R11.2: el proceso corre como usuario no-root, en un grupo con acceso al socket de Docker.
FROM node:22-bookworm-slim AS build
WORKDIR /build
COPY package.json package-lock.json tsconfig.json ./
RUN npm ci
COPY src ./src
RUN npx tsc

FROM gcr.io/distroless/nodejs22-debian12:nonroot
WORKDIR /app
COPY --from=build /build/dist/src ./src
# Solo para que Node lea `"type": "module"`; no se instala ninguna dependencia de runtime (R11.3).
COPY --from=build /build/package.json ./package.json
USER nonroot
ENTRYPOINT ["/nodejs/bin/node", "/app/src/main.js"]
