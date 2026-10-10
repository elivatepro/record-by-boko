# Built from the repo root (pnpm workspace). Serves the static app with Caddy.
FROM node:24-slim AS build
RUN corepack enable
WORKDIR /repo
COPY pnpm-workspace.yaml pnpm-lock.yaml package.json ./
COPY apps/web/package.json apps/web/
COPY packages/design-tokens/package.json packages/design-tokens/
RUN pnpm install --frozen-lockfile --filter @roudio/web...
COPY apps/web apps/web
# Public values baked into the bundle; Railway passes service variables as build args.
ARG VITE_API_URL
ARG VITE_GOOGLE_CLIENT_ID
RUN pnpm --filter @roudio/web build

FROM caddy:2-alpine
COPY apps/web/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /repo/apps/web/dist /srv
