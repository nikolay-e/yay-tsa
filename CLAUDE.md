# CLAUDE.md

> Extends [../CLAUDE.md](../CLAUDE.md) - inherits workspace conventions (integration tests only, no docstrings, Russian communication)

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Jellyfin Mini Music Client - a portable, minimal music client for Jellyfin media servers. Built as an npm workspaces monorepo with strict separation between framework-agnostic business logic (core), platform adapters (platform), and UI implementations (web).

**Core Architecture Principle**: Layered architecture with 100% portable core logic, platform abstraction through interfaces, and reactive UI layer. Bundle size target: <150KB gzipped.

## Workspace Structure

```
packages/
├── core/        # @yaytsa/core - Framework-agnostic business logic
├── platform/    # @yaytsa/platform - Platform-specific audio/media adapters
└── web/         # @yaytsa/web - SvelteKit PWA (SPA mode)
```

**Key Files**:

- `package.json:14-16` - npm workspaces configuration
- `packages/core/src/index.ts` - Core package public API
- `packages/platform/src/index.ts` - Platform package public API
- `packages/web/src/lib/stores/` - Svelte stores for state management

## Common Commands

```bash
# Development
npm run dev                    # Build core+platform, start Vite dev server with HMR
npm run build                  # Production build (core → platform → web)
npm run build:core             # TypeScript compilation for core package only
npm run build:platform         # TypeScript compilation for platform package only

# Code Quality
npm run type-check             # TypeScript type checking (strict mode)
npm run lint                   # Prettier check
npm run format                 # Prettier write
npm run pre-commit             # Run all pre-commit hooks manually

# Testing
cd packages/core && npm run test:e2e    # E2E tests against real Jellyfin server
cd packages/web && npm run test:e2e     # Playwright E2E tests

# Docker Deployment
docker-compose up --build      # Development with HMR
docker-compose -f docker-compose.yml up -d    # Production with Nginx

# Kubernetes (via GitOps)
# See /Users/nikolay/code/gitops/helm-charts/yaytsa/
# Deployment managed via Argo CD (gitops repository)
```

## High-Level Architecture

### Three-Layer Architecture

**Layer 1: Core (`@yaytsa/core`)** - 100% portable

- **No UI framework dependencies** - pure TypeScript business logic
- **API Client** (`api/client.ts:14-324`) - HTTP client with Emby-compatible auth headers
- **Services** - AuthService, ItemsService, FavoritesService, PlaylistsService
- **State Machines** - PlaybackQueue (`player/queue.ts:8-426`) with shuffle/repeat logic
- **Models** - TypeScript types for Jellyfin entities

**Layer 2: Platform (`@yaytsa/platform`)** - 0% portable (intentionally)

- **AudioEngine Interface** (`audio.interface.ts:6-90`) - Contract for audio playback
- **HTML5AudioEngine** (`web/html5-audio.ts`) - Browser implementation using Web Audio API
- **MediaSessionManager** (`media-session.ts`) - Background playback control (lock screen, notifications)
- Future: ExpoAudioEngine for React Native, TauriAudioEngine for desktop

**Layer 3: Web (`@yaytsa/web`)** - 85-95% portable UI

- **SvelteKit 2** with `@sveltejs/adapter-static` (SPA mode, no SSR)
- **Svelte Stores** (`lib/stores/`) - Reactive state management
- **Components** - `lib/components/{auth,library,player,navigation,ui}/`
- **Routes** - SvelteKit file-based routing (`routes/`)

### Critical Data Flow

```
User Action (Component)
  ↓
Svelte Store (player.ts, auth.ts)
  ↓
Core Service (AuthService, ItemsService)
  ↓
JellyfinClient (HTTP request with auth headers)
  ↓
Jellyfin Server
```

### Example: Play Album

1. User clicks "Play" in `AlbumCard.svelte`
2. Calls `player.playAlbum(albumId)` from `stores/player.ts:300-350`
3. Player store fetches tracks via `ItemsService.getAlbumTracks()` from core
4. Calls `PlaybackQueue.setQueue()` state machine (core)
5. Calls `HTML5AudioEngine.load()` (platform) with stream URL from `client.getStreamUrl()`
6. Updates `MediaSessionManager.updateMetadata()` for lock screen controls

### State Management Pattern

**Svelte Writable + Derived Stores** (reactive, no global store)

**Auth Store** (`lib/stores/auth.ts:44-232`):

```typescript
interface AuthState {
  client: JellyfinClient | null;
  authService: AuthService | null;
  token: string | null;
  userId: string | null;
  serverUrl: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
}
```

**Key Features**:

- Session restoration from sessionStorage (`restoreSession:150-219`)
- CSRF protection: validates stored URL before use (`auth.ts:166-176`)
- Derived stores: `isAuthenticated`, `client` (read-only)

**Player Store** (`lib/stores/player.ts:21-557`):

```typescript
interface PlayerState {
  queue: PlaybackQueue; // Core package state machine
  state: PlaybackState | null; // Core package playback reporter
  audioEngine: HTML5AudioEngine; // Platform package
  currentTrack: AudioItem | null;
  currentTime: number;
  duration: number;
  volume: number;
  isPlaying: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
}
```

**Event-Driven Updates**:

- `audioEngine.onTimeUpdate()` → updates `currentTime` + reports to server every 10s
- `audioEngine.onEnded()` → auto-advances to next track
- `client.subscribe()` → recreates `PlaybackState` when auth changes

## Critical Jellyfin API Details

### Authentication Flow

**Endpoint**: `POST /Users/AuthenticateByName`

**Critical Implementation** (`packages/core/src/api/auth.ts:16-43`):

```typescript
// Field name is "Pw" not "Password" (Jellyfin 10.8.x compatibility)
const authPayload = {
  Username: username,
  Pw: password,  // NOT "Password" - varies by server version
};

// Emby-compatible header format required
headers: {
  'X-Emby-Authorization': buildAuthHeader(clientInfo),
}
```

**buildAuthHeader Format** (`client.ts:36-50`):

```
MediaBrowser Client="Jellyfin Mini", Device="Chrome", DeviceId="uuid", Version="0.1.0", Token="..."
```

**Session Storage Strategy** (`lib/stores/auth.ts:86-91`):

- Uses `sessionStorage` (not `localStorage`) - cleared on tab close, reduces XSS risk
- Stores: `jf_session`, `jf_user_id`, `jf_server_url`
- HTTPS validation in production (`client.ts:125-148`)

### Music Library Queries

**Critical Parameters** (`api/items.ts`):

```typescript
const params = {
  IncludeItemTypes: 'MusicAlbum', // Required filter
  Recursive: true, // MUST be true or only immediate children returned
  Fields: 'PrimaryImageAspectRatio,Genres,DateCreated', // Explicit opt-in for metadata
  SortBy: 'SortName', // Locale-aware sorting (not 'Name')
};
```

**Common Mistake**: Omitting `Recursive: true` returns only top-level items, not entire library.

### Stream URLs

**Critical Implementation** (`client.ts:281-309`):

```typescript
function getStreamUrl(itemId: string): string {
  const params = {
    api_key: token, // Query param required - browsers don't send headers for <audio> src
    deviceId: deviceId, // Required for session tracking
    static: 'true', // Direct streaming (no transcoding)
    audioCodec: 'aac,mp3,opus',
    container: 'opus,mp3,aac,m4a,flac',
  };
  return `${serverUrl}/Audio/${itemId}/stream?${params}`;
}
```

**NEVER use**: `maxStreamingBitrate` parameter (causes HTTP 500 errors on some servers)

### Playback Reporting

**Ticks Conversion** (`player/state.ts`):

```typescript
const TICKS_PER_SECOND = 10_000_000;

// Jellyfin uses 100-nanosecond "ticks" for time positions
reportPlaybackProgress({
  ItemId: itemId,
  PositionTicks: Math.floor(seconds * TICKS_PER_SECOND),
  IsPaused: isPaused,
});
```

**Endpoints**:

- `POST /Sessions/Playing` - Start playback
- `POST /Sessions/Playing/Progress` - Update position (every 10s)
- `POST /Sessions/Playing/Stopped` - End playback

## Build Configuration

### TypeScript Monorepo Setup

**Workspace References**:

- Core: `tsconfig.json:2-23` - standalone, no dependencies, strict mode
- Platform: Depends on `@yaytsa/core` via workspace
- Web: Extends `.svelte-kit/tsconfig.json`, imports both core + platform

**Transpilation**:

- Core/Platform: `tsc` → `dist/` (CommonJS/ESM dual output)
- Web: Vite (no tsc for bundling, only type checking)

### Vite Configuration (`packages/web/vite.config.ts:11-40`)

**HTTPS Development** (optional):

- Checks for `.certs/cert.pem` and `.certs/key.pem`
- Auto-enables HTTPS + WSS HMR if certificates present
- Falls back to HTTP if missing

**Production Optimizations**:

- Terser minification with `drop: ['console', 'debugger']`
- `optimizeDeps: { include: ['@yaytsa/core', '@yaytsa/platform'] }`
- Manual chunks for code splitting

### SvelteKit Adapter (`packages/web/svelte.config.js:10-19`)

```javascript
adapter: adapter({
  fallback: 'index.html', // SPA mode
  precompress: false, // Nginx handles gzip/brotli
});
```

**SSR Disabled** (`routes/+layout.ts:1-3`):

```typescript
export const ssr = false;
export const prerender = false;
```

## Testing Strategy

### Integration/E2E Tests ONLY (No Unit Tests)

**Core E2E Tests** (`packages/core/tests/e2e/`):

- **Real Jellyfin server** via docker-compose (not mocked)
- **BDD-style** - Given-When-Then structure
- **Data Factory** (`fixtures/data-factory.ts`) - discovers test data from actual server
- **Scenarios** (`fixtures/scenarios.ts`) - reusable test helpers

**Example Test Structure**:

```typescript
describe('Feature: Queue Management', () => {
  test('Given: User viewing album, When: Clicks play album, Then: Queue contains all tracks', async () => {
    // Arrange (Given)
    const album = await dataFactory.getTestAlbum();

    // Act (When)
    queue.setQueue(album.tracks, 0);

    // Assert (Then)
    expect(queue.getCurrentTrack()).toBe(album.tracks[0]);
  });
});
```

**Web E2E Tests** (`packages/web/tests/e2e/`):

- Playwright for browser automation
- Tests against real dev server + Jellyfin instance

**Run Tests**:

```bash
# Core integration tests
cd packages/core
npm run test:e2e

# Web E2E tests
cd packages/web
npm run test:e2e
```

**Environment Variables** (`.env` required for E2E):

```bash
YAYTSA_SERVER_URL=https://your-server.com
YAYTSA_TEST_USERNAME=test-user
YAYTSA_TEST_PASSWORD=test-password
```

## Security Implementation

### HTTPS Enforcement (`client.ts:125-148`)

```typescript
if (import.meta.env.PROD && parsed.protocol !== 'https:') {
  throw new Error('HTTPS required in production');
}
```

Allows `http://localhost` in development, enforces HTTPS in production.

### CSRF Protection (`lib/stores/auth.ts:166-176`)

```typescript
const storedUrl = sessionStorage.getItem(STORAGE_KEYS.SERVER_URL);
const parsed = new URL(storedUrl);

// Validate URL before using (prevent Client-Side SSRF)
if (!parsed.protocol.startsWith('http')) {
  throw new Error('Invalid protocol');
}
```

### Content Security Policy

Configured in `index.html` (production):

```
default-src 'self';
connect-src 'self' https://*.jellyfin-domain.com;
media-src 'self' https://*.jellyfin-domain.com;
script-src 'self';
```

## Deployment

### Docker Multi-Stage Build (`Dockerfile:1-81`)

**Stages**:

1. **base**: Install dependencies (npm install)
2. **builder**: Build core → platform → web
3. **production**: Nginx 1.27-alpine with static files

**Runtime Configuration** (`docker-entrypoint.sh`):

- Injects environment variables into built JS via `envsubst`
- Healthcheck on `/health` endpoint

### Kubernetes (via GitOps)

**GitOps Repository**: `/Users/nikolay/code/gitops/helm-charts/yaytsa/`

**Deployment Workflow**:

1. Build Docker image: `docker build -t yaytsa:tag .`
2. Push to registry
3. Update `values.yaml` in gitops repo
4. ArgoCD auto-syncs to cluster

## Performance Targets

| Metric                 | Target  | Tool            |
| ---------------------- | ------- | --------------- |
| Bundle size (gzipped)  | <150 KB | `gzip-size` CLI |
| First Contentful Paint | <1.2s   | Lighthouse (3G) |
| Time to Interactive    | <2.5s   | Lighthouse      |
| Memory (idle)          | <50 MB  | Chrome DevTools |

## Key Architecture Files Reference

**Core Package**:

- `packages/core/src/api/client.ts:14-324` - JellyfinClient with auth injection
- `packages/core/src/player/queue.ts:8-426` - PlaybackQueue state machine
- `packages/core/src/player/state.ts` - PlaybackState with progress reporting

**Platform Package**:

- `packages/platform/src/audio.interface.ts:6-90` - AudioEngine contract
- `packages/platform/src/web/html5-audio.ts` - Browser audio implementation
- `packages/platform/src/media-session.ts` - Lock screen controls

**Web Package**:

- `packages/web/src/lib/stores/auth.ts:44-232` - Authentication store
- `packages/web/src/lib/stores/player.ts:21-557` - Player store with engine integration
- `packages/web/vite.config.ts:11-40` - Build configuration
- `packages/web/svelte.config.js:10-19` - SvelteKit adapter

**Build & Deployment**:

- `Dockerfile:1-81` - Multi-stage Docker build
- `docker-compose.yml` - Development and production configs
- `/Users/nikolay/code/gitops/helm-charts/yaytsa/` - Kubernetes manifests

## Additional Documentation

- **DESIGN.md** - Detailed technical architecture, Jellyfin API spec reference, platform comparison
- **jellyfin-api-spec.json** - Full OpenAPI 3.0 specification (v10.10.7)
- **packages/core/tests/e2e/README.md** - E2E testing guide
