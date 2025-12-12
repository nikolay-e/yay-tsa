FROM node:25-alpine AS base

RUN apk add --no-cache bash git curl

WORKDIR /app

COPY package.json package-lock.json ./
COPY packages/core/package.json ./packages/core/
COPY packages/platform/package.json ./packages/platform/
COPY packages/web/package.json ./packages/web/

RUN --mount=type=cache,target=/root/.npm,sharing=locked \
    npm ci --ignore-scripts

WORKDIR /app/node_modules/esbuild
RUN npm run postinstall
WORKDIR /app

COPY packages/ ./packages/

FROM base AS development

EXPOSE 5173

CMD ["npm", "run", "dev"]

FROM base AS builder

RUN npm run build

FROM nginx:1.29.4-alpine AS production

COPY nginx.conf.template /etc/nginx/nginx.conf.template
COPY --from=builder /app/packages/web/build /usr/share/nginx/html
COPY docker-entrypoint.sh /docker-entrypoint.sh

RUN apk add --no-cache bash wget jq openssl && \
    rm -rf /etc/nginx/nginx.conf.default /usr/share/nginx/html/.gitkeep && \
    chmod +x /docker-entrypoint.sh && \
    mkdir -p /var/cache/nginx /var/run && \
    chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html && \
    chmod -R 755 /var/cache/nginx /var/run

USER nginx

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost/health || exit 1

ENTRYPOINT ["/docker-entrypoint.sh"]
