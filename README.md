# Yay-Tsa

[![CI](https://github.com/nikolay-e/yay-tsa/actions/workflows/ci.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/ci.yml)
[![Nightly](https://github.com/nikolay-e/yay-tsa/actions/workflows/nightly.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/nightly.yml)
[![Helm](https://github.com/nikolay-e/yay-tsa/actions/workflows/release-chart.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/release-chart.yml)

Self-hosted music streaming system: custom Java media server + React PWA client.

## Stack

- **Frontend**: React 19, Vite 7, TailwindCSS 4, React Router 7
- **Backend**: Java 21 Spring Boot, PostgreSQL 16, Virtual Threads
- **Infrastructure**: Docker, Helm, Argo CD, GitHub Actions

## Structure

```
apps/web/                # React PWA frontend
packages/core/           # Framework-agnostic business logic
packages/platform/       # Platform-specific audio adapters
services/server/         # Java Spring Boot backend
services/audio-separator/# Vocal separation sidecar (Demucs)
charts/yay-tsa/          # Helm chart
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
