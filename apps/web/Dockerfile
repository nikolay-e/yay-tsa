FROM --platform=$BUILDPLATFORM node:22-alpine AS base

WORKDIR /app

COPY package.json package-lock.json ./
COPY packages/core/package.json ./packages/core/
COPY packages/platform/package.json ./packages/platform/
COPY apps/web/package.json ./apps/web/

RUN --mount=type=cache,target=/root/.npm,sharing=locked \
    npm ci

COPY packages/ ./packages/
COPY apps/ ./apps/
COPY scripts/ ./scripts/
COPY shared-constants.yaml ./

FROM base AS builder

ARG GIT_SHA=dev
# The in-cluster multi-image-ci pipeline passes the image tag as APP_VERSION
# (it does not know the GIT_SHA arg name); GIT_SHA remains the local/manual path.
ARG APP_VERSION=
ARG APP_ENVIRONMENT=development

RUN apk add --no-cache brotli gzip && \
    export BUILD_VERSION="${APP_VERSION:-${GIT_SHA}}" && \
    VITE_APP_VERSION="${BUILD_VERSION}" VITE_APP_ENVIRONMENT="${APP_ENVIRONMENT}" npm run build && \
    # Move hidden sourcemaps out of the served tree, keyed by the build version,
    # BEFORE compression and BEFORE the nginx stage copies dist/. Maps must never
    # reach nginx/CDN (they would leak source). The dist-maps/<version> dir is the
    # offline-symbolication artifact (apps/web/scripts/symbolicate.ts).
    mkdir -p "/app/apps/web/dist-maps/${BUILD_VERSION}" && \
    export DIST_MAPS_DIR="/app/apps/web/dist-maps/${BUILD_VERSION}" && \
    find /app/apps/web/dist -type f -name '*.map' \
        -exec sh -c 'dest="$DIST_MAPS_DIR/${1#/app/apps/web/dist/}"; \
            mkdir -p "$(dirname "$dest")"; mv "$1" "$dest"' _ {} \; && \
    find /app/apps/web/dist -type f \
        \( -name '*.js' -o -name '*.css' -o -name '*.html' \
        -o -name '*.json' -o -name '*.svg' -o -name '*.wasm' \) \
        -exec sh -c 'gzip -9 -k "$1" && brotli -9 -k "$1"' _ {} \;

# Optional CI artifact stage: `docker build --target sourcemaps -o type=local,dest=./out`
# extracts dist-maps/<GIT_SHA> so the pipeline can retain maps keyed by commit
# without ever shipping them in the production image.
FROM scratch AS sourcemaps
COPY --from=builder /app/apps/web/dist-maps /

FROM nginx:1.31-alpine AS production

COPY infra/nginx/nginx.conf.template /etc/nginx/nginx.conf.template
COPY infra/nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY --from=builder /app/apps/web/dist /usr/share/nginx/html
COPY apps/web/docker/entrypoint.sh /docker-entrypoint.sh

RUN apk add --no-cache \
        bash \
        wget \
        jq && \
    rm -rf /etc/nginx/nginx.conf.default /usr/share/nginx/html/.gitkeep && \
    chmod +x /docker-entrypoint.sh && \
    mkdir -p /var/cache/nginx /var/run && \
    chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html && \
    chmod -R 755 /var/cache/nginx /var/run && \
    rm -rf /var/cache/apk/*

USER nginx

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost/health || exit 1

ENTRYPOINT ["/docker-entrypoint.sh"]
