# @yay-tsa/core

Framework-agnostic business logic for Yay-Tsa music player.

## Usage

```typescript
import { MediaServerClient, ItemsService, AuthService } from '@yay-tsa/core';

const client = new MediaServerClient('https://your-server.com', {
  name: 'Yay-Tsa',
  version: '1.0.0',
  deviceId: 'device-id',
  deviceName: 'My Device',
});

const authService = new AuthService(client);
const session = await authService.authenticate(username, password, deviceId, deviceName);

const itemsService = new ItemsService(client);
const albums = await itemsService.getAlbums();
```

## Modules

- **api/** - HTTP client with Emby-compatible auth
- **player/** - Playback queue state machine
- **services/** - Auth, Items, Favorites, Playlists services
- **models/** - TypeScript types for media entities

## Architecture

This package has **zero UI dependencies** and can be used with any frontend framework (React, Svelte, Vue, etc.).

See [CLAUDE.md](../../CLAUDE.md) for detailed architecture.
