# Yaytsa

Minimal music player with custom Java-based media server backend.

## Quick Start

```bash
npm install
npm run dev
```

## Stack

- **Frontend**: React 19, Vite 7, TailwindCSS 4, React Router 7
- **Backend**: Java 21 Spring Boot, PostgreSQL
- **Packages**: npm workspaces monorepo

## Structure

```
apps/web/           # React PWA frontend
packages/core/      # Framework-agnostic business logic
packages/platform/  # Platform-specific audio adapters
services/server/    # Java Spring Boot backend
```

## Commands

```bash
npm run dev              # Start development server
npm run build            # Production build
npm run type-check       # TypeScript checking
npm run format           # Prettier formatting
```

## Docker

```bash
docker compose up        # Development with HMR
docker compose --profile test up  # Run E2E tests
```

## Architecture

See [CLAUDE.md](./CLAUDE.md) for detailed architecture documentation.
