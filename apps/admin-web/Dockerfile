FROM node:24.20.0-alpine AS build
WORKDIR /repo
RUN corepack enable

# Vite reads these at BUILD time. Defaults match compose.prod edge paths.
ARG VITE_API_BASE_URL=
ARG VITE_KEYCLOAK_URL=/auth
ARG VITE_KEYCLOAK_REALM=lyreo
ARG VITE_KEYCLOAK_CLIENT_ID=lyreo-admin-web
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL \
    VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM \
    VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID

COPY package.json pnpm-workspace.yaml ./
COPY packages/design-system packages/design-system
COPY packages/i18n packages/i18n
COPY apps/admin-web apps/admin-web
RUN pnpm install --no-frozen-lockfile
RUN pnpm --filter @lyreo/admin-web build

FROM nginx:1.29-alpine
COPY apps/admin-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/apps/admin-web/dist /usr/share/nginx/html
