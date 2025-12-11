# syntax=docker/dockerfile:1.4

# ============================================
# Base Stage - Common dependencies
# ============================================
FROM node:25-alpine AS base

# Install system dependencies
RUN apk add --no-cache bash git curl

# Set working directory
WORKDIR /app

# Copy package files for dependency caching
COPY package.json package-lock.json ./
COPY packages/core/package.json ./packages/core/
COPY packages/platform/package.json ./packages/platform/
COPY packages/web/package.json ./packages/web/

# Install all dependencies with npm ci (deterministic builds)
# Use BuildKit cache mount for faster rebuilds
RUN --mount=type=cache,target=/root/.npm,sharing=locked \
    npm ci --ignore-scripts

# Copy source code
COPY packages/ ./packages/

# ============================================
# Development Stage - Vite dev server
# ============================================
FROM base AS development

# Note: Running as root in dev for volume permissions
# Volumes mounted from host need write access

# Expose Vite dev server port
EXPOSE 5173

# Start dev server (builds core and platform first)
CMD ["npm", "run", "dev"]

# ============================================
# Builder Stage - Build for production
# ============================================
FROM base AS builder

# Note: Running as root for build
# Build all packages (core, platform, and web)
RUN npm run build

# ============================================
# Production Stage - Nginx serving static files
# ============================================
FROM nginx:1.29.4-alpine AS production

# Copy nginx configuration template (will be populated at runtime)
COPY nginx.conf.template /etc/nginx/nginx.conf.template

# Copy built static files from builder stage
COPY --from=builder /app/packages/web/build /usr/share/nginx/html

# Copy entrypoint script for runtime config injection
COPY docker-entrypoint.sh /docker-entrypoint.sh

# Install utilities, setup nginx, and configure permissions
RUN apk add --no-cache bash wget jq openssl && \
    rm -rf /etc/nginx/nginx.conf.default /usr/share/nginx/html/.gitkeep && \
    chmod +x /docker-entrypoint.sh && \
    mkdir -p /var/cache/nginx /var/run && \
    chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html && \
    chmod -R 755 /var/cache/nginx /var/run

# Use non-root user
USER nginx

# Expose HTTP port
EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost/health || exit 1

# Set entrypoint (nginx started by entrypoint script with generated config)
ENTRYPOINT ["/docker-entrypoint.sh"]
