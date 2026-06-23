# Yay-Tsa

[![CI](https://github.com/nikolay-e/yay-tsa/actions/workflows/ci.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/ci.yml)
[![Nightly](https://github.com/nikolay-e/yay-tsa/actions/workflows/nightly.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/nightly.yml)
[![Helm](https://github.com/nikolay-e/yay-tsa/actions/workflows/release-chart.yml/badge.svg)](https://github.com/nikolay-e/yay-tsa/actions/workflows/release-chart.yml)

Self-hosted music streaming system: multi-protocol Kotlin media server + React PWA client.

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

The `yay-tsa-stack` umbrella chart installs the whole system — PWA + backend + a
bundled Postgres/pgvector — with one command on a fresh namespace. ML, LLM, karaoke,
MPD, ingress and NetworkPolicy are all off by default.

```bash
helm repo add yay-tsa https://nikolay-e.github.io/yay-tsa
helm repo update

helm install yay-tsa yay-tsa/yay-tsa-stack \
  --namespace yay-tsa --create-namespace \
  --set yay-tsa-v2.backend.adminBootstrap.password=CHANGE_ME
```

That's it — a strong DB password is generated and preserved across upgrades, and the
first admin (`admin` / your password) is seeded on the empty database. Reach it with:

```bash
kubectl -n yay-tsa rollout status deploy/yay-tsa-yay-tsa-v2-backend
kubectl -n yay-tsa port-forward svc/yay-tsa 8080:80   # then open http://localhost:8080
```

Point it at your music and expose it publicly:

```bash
helm upgrade yay-tsa yay-tsa/yay-tsa-stack -n yay-tsa \
  --reuse-values \
  --set yay-tsa-v2.backend.media.enabled=true \
  --set yay-tsa-v2.backend.media.existingClaim=my-music-pvc \
  --set yay-tsa.ingress.enabled=true \
  --set yay-tsa.ingress.hosts[0].host=music.example.com \
  --set yay-tsa-v2.ingress.enabled=true \
  --set yay-tsa-v2.ingress.hosts[0].host=music.example.com
```

`media.existingClaim` (a pre-created PVC) works on any/multi-node cluster; on a single node
you can use `media.hostPath=/path/to/music` instead. After mounting, trigger a scan:
`POST /api/Admin/Library/Rescan` with your admin bearer token.

**TLS:** the ingress examples set a host but no certificate. Terminate TLS at your ingress
(e.g. cert-manager `cluster-issuer` annotation + a `tls:` block) before exposing publicly —
login credentials and tokens travel in clear over plain HTTP.

**Production / external database.** Disable the bundled Postgres and point at your own
(must have the `vector`, `pg_trgm`, `citext`, `unaccent` extensions available — the app
creates them on first boot if the DB user may, e.g. an RDS/CloudSQL master user):

```bash
helm install yay-tsa yay-tsa/yay-tsa-stack -n yay-tsa --create-namespace \
  --set postgresql.enabled=false \
  --set externalDatabase.host=db.internal \
  --set externalDatabase.user=yaytsa \
  --set externalDatabase.password=... \
  --set externalDatabase.database=yaytsa \
  --set yay-tsa-v2.backend.adminBootstrap.password=CHANGE_ME
# (or --set externalDatabase.existingSecret=my-db-secret and
#  --set global.yaytsaDatabase.secretName=my-db-secret, with the four POSTGRES_* keys)
```

See [charts/yay-tsa-stack/values.yaml](charts/yay-tsa-stack/values.yaml) for every option.
The individual [`yay-tsa`](charts/yay-tsa/values.yaml) (PWA) and
[`yay-tsa-v2`](charts/yay-tsa-v2/values.yaml) (backend) charts remain available for
GitOps deployments that manage their own database.

## Stack

- **Frontend**: React 19, Vite 8, TailwindCSS 4, React Router 7
- **Backend**: Kotlin 2.1, JDK 21, Spring Boot 3.4, PostgreSQL 17 (pgvector, pg_trgm, CITEXT)
- **Karaoke**: Python FastAPI, BS-Roformer / Hybrid Demucs
- **Infrastructure**: Docker, Helm, Argo CD, GitHub Actions

## Structure

```
apps/web/                # React PWA frontend
packages/core/           # Framework-agnostic business logic
packages/platform/       # Platform-specific audio adapters
yay-tsa-v2/              # Kotlin hexagonal backend (8 bounded contexts)
services/audio-ml/        # Audio ML sidecar: stem separation (Demucs) + feature extraction (Essentia)
charts/yay-tsa-stack/    # Umbrella chart (PWA + backend + bundled Postgres) — the install entrypoint
charts/yay-tsa/          # Helm chart (PWA + audio-separator)
charts/yay-tsa-v2/       # Helm chart (backend)
charts/postgres-pgvector/ # Bundled Postgres/pgvector subchart (used by the umbrella)
docker/                  # Docker Compose for self-hosting
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
