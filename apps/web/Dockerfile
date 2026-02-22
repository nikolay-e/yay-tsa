FROM node:25-alpine AS base

RUN apk add --no-cache bash git curl wget

WORKDIR /app

COPY package.json package-lock.json ./
COPY packages/core/package.json ./packages/core/
COPY packages/platform/package.json ./packages/platform/
COPY apps/web/package.json ./apps/web/

RUN --mount=type=cache,target=/root/.npm,sharing=locked \
    npm ci

COPY packages/ ./packages/
COPY apps/ ./apps/

FROM base AS development

EXPOSE 5173

CMD ["npm", "run", "dev"]

FROM base AS builder

ARG GIT_SHA=dev
ARG APP_ENVIRONMENT=development

RUN apk add --no-cache brotli gzip && \
    VITE_APP_VERSION=${GIT_SHA} VITE_APP_ENVIRONMENT=${APP_ENVIRONMENT} npm run build && \
    find /app/apps/web/dist -type f \( -name '*.js' -o -name '*.css' -o -name '*.html' -o -name '*.json' -o -name '*.svg' -o -name '*.wasm' \) \
        -exec sh -c 'gzip -9 -k "$1" && brotli -9 -k "$1"' _ {} \;

FROM mcr.microsoft.com/playwright:v1.58.0-noble AS test

WORKDIR /app

COPY package.json package-lock.json ./
COPY packages/core/package.json ./packages/core/
COPY packages/platform/package.json ./packages/platform/
COPY apps/web/package.json ./apps/web/

RUN npm ci

COPY packages/ ./packages/
COPY apps/ ./apps/

RUN npm run build:core && npm run build:platform

RUN npx playwright install chromium

ENV NO_COLOR=1

CMD ["sh", "-c", "cd packages/core && npm run test:integration && cd ../../apps/web && npm run test:e2e"]

FROM nginx:1.29.4-alpine AS production

COPY infra/nginx/nginx.conf.template /etc/nginx/nginx.conf.template
COPY --from=builder /app/apps/web/dist /usr/share/nginx/html
COPY apps/web/docker/entrypoint.sh /docker-entrypoint.sh

RUN apk add --no-cache \
        bash \
        wget \
        jq \
        openssl && \
    rm -rf /etc/nginx/nginx.conf.default /usr/share/nginx/html/.gitkeep && \
    chmod +x /docker-entrypoint.sh && \
    mkdir -p /var/cache/nginx /var/run /media && \
    chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html /media && \
    chmod -R 755 /var/cache/nginx /var/run && \
    rm -rf /var/cache/apk/*

USER nginx

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost/health || exit 1

ENTRYPOINT ["/docker-entrypoint.sh"]
