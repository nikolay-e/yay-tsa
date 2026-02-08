# @yay-tsa/core

Framework-agnostic business logic for Yay-Tsa music player.

## Installation

```bash
npm install @yay-tsa/core
```

## Usage

```typescript
import { MediaServerClient, PlaybackQueue } from '@yay-tsa/core';

const client = new MediaServerClient({
  serverUrl: 'https://your-server.com',
  clientInfo: { name: 'Yay-Tsa', version: '1.0.0' },
});

await client.authenticate(username, password);
const albums = await client.items.getAlbums();
```

## Modules

- **api/** - HTTP client with Emby-compatible auth
- **player/** - Playback queue state machine
- **services/** - Auth, Items, Favorites, Playlists services
- **models/** - TypeScript types for media entities

## Architecture

This package has **zero UI dependencies** and can be used with any frontend framework (React, Svelte, Vue, etc.).

See [CLAUDE.md](../../CLAUDE.md) for detailed architecture.
