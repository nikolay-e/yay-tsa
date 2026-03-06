# Yay-Tsa

[![CI](https://github.com/nikolay-e/yay-tsa/actions/workflows/ci.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/ci.yml)
[![Nightly](https://github.com/nikolay-e/yay-tsa/actions/workflows/nightly.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/nightly.yml)
[![Helm](https://github.com/nikolay-e/yay-tsa/actions/workflows/release-chart.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/release-chart.yml)

Self-hosted music streaming system: custom Java media server + React PWA client.

## Features

- Browse and stream your music library from any browser
- Progressive Web App — installable on mobile and desktop
- Karaoke mode — vocal separation powered by Meta's Demucs (optional)
- Lyrics display with time-synced scrolling
- Multi-arch images: `linux/amd64` and `linux/arm64` (Apple Silicon, Raspberry Pi)

## Quick Start with Docker Compose

```bash
mkdir yay-tsa && cd yay-tsa

# Download configuration files
curl -O https://raw.githubusercontent.com/nikolay-e/yay-tsa/main/docker/docker-compose.yml
curl -O https://raw.githubusercontent.com/nikolay-e/yay-tsa/main/docker/example.env
cp example.env .env

# Edit .env — set MEDIA_PATH to your music library and change passwords
# Then start:
docker compose up -d

# Open http://localhost:3000
```

To enable karaoke (vocal separation):

```bash
docker compose --profile karaoke up -d
```

Upgrade to the latest version:

```bash
docker compose pull && docker compose up -d
```

## Install with Helm (Kubernetes)

```bash
helm repo add yay-tsa https://nikolay-e.github.io/yay-tsa
helm repo update
helm install yay-tsa yay-tsa/yay-tsa \
  --namespace yay-tsa --create-namespace \
  --set backend.enabled=true \
  --set backend.database.secretName=my-postgres-secret \
  --set backend.media.enabled=true \
  --set backend.media.hostPath=/path/to/music \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=music.example.com
```

See [charts/yay-tsa/values.yaml](charts/yay-tsa/values.yaml) for all configuration options.

## Stack

- **Frontend**: React 19, Vite 7, TailwindCSS 4, React Router 7
- **Backend**: Java 21 Spring Boot, PostgreSQL 16, Virtual Threads
- **Karaoke**: Python FastAPI, Meta Hybrid Demucs
- **Infrastructure**: Docker, Helm, Argo CD, GitHub Actions

## Structure

```
apps/web/                # React PWA frontend
packages/core/           # Framework-agnostic business logic
packages/platform/       # Platform-specific audio adapters
services/server/         # Java Spring Boot backend
services/audio-separator/# Vocal separation sidecar (Demucs)
charts/yay-tsa/          # Helm chart
docker/                  # Production Docker Compose for self-hosting
```

## Development

```bash
npm install && npm run dev    # Frontend dev server with HMR
docker compose up             # Full stack (DB + backend + frontend)
```

## Testing

```bash
npm run type-check            # TypeScript checking
npm run format                # Prettier formatting
docker compose --profile test up  # Integration & E2E tests
```

## Architecture

See [CLAUDE.md](./CLAUDE.md) for detailed architecture documentation.
