# @jellyfin-mini/core

Core business logic for Jellyfin Mini Client. Framework-agnostic TypeScript package.

## Features

✅ **Authentication** - Secure login with Jellyfin server
✅ **Library Queries** - Fetch albums, artists, and tracks
✅ **Queue Management** - Full-featured playback queue with shuffle and repeat
✅ **Playback State** - State management with server reporting
✅ **Type-Safe** - Complete TypeScript types for Jellyfin API

## Installation

```bash
npm install @jellyfin-mini/core
```

## Quick Start

```typescript
import { JellyfinClient, AuthService, ItemsService, PlaybackQueue, PlaybackState } from '@jellyfin-mini/core';

// Initialize client
const client = new JellyfinClient('https://your-server.com', {
  name: 'My Jellyfin Client',
  device: 'Web Browser',
  deviceId: 'unique-device-id',
  version: '1.0.0'
});

// Authenticate
const auth = new AuthService(client);
const authResponse = await auth.login('username', 'password');

// Query library
const items = new ItemsService(client);
const albums = await items.getAlbums({ limit: 20 });

// Manage playback queue
const queue = new PlaybackQueue();
const tracks = await items.getAlbumTracks(albums.Items[0].Id);
queue.setQueue(tracks);

// Manage playback state
const state = new PlaybackState(client);
state.setCurrentItem(queue.getCurrentItem());
state.setStatus('playing');
await state.reportPlaybackStart();
```

## API Overview

### JellyfinClient

HTTP client with authentication and token management.

```typescript
const client = new JellyfinClient(serverUrl, clientInfo);

// Get data
const data = await client.get('/Users/{userId}/Items', { Recursive: true });

// Post data
await client.post('/Sessions/Playing', playbackInfo);

// Get stream URL
const streamUrl = client.getStreamUrl(itemId);

// Get image URL
const imageUrl = client.getImageUrl(itemId, 'Primary', { maxWidth: 300 });
```

### AuthService

Handle user authentication.

```typescript
const auth = new AuthService(client);

// Login
const response = await auth.login('username', 'password');

// Validate session
const isValid = await auth.validateSession();

// Logout
await auth.logout();
```

### ItemsService

Query music library items.

```typescript
const items = new ItemsService(client);

// Get albums
const albums = await items.getAlbums({ limit: 20, sortBy: 'DateCreated' });

// Get artists
const artists = await items.getArtists({ limit: 50 });

// Get tracks
const tracks = await items.getTracks({ albumId: 'album-123' });

// Search
const results = await items.search('query', { limit: 10 });

// Get single item
const item = await items.getItem('item-id');
```

### PlaybackQueue

Manage playback queue with shuffle and repeat.

```typescript
const queue = new PlaybackQueue();

// Set queue
queue.setQueue(tracks, startIndex);

// Navigate
const next = queue.next();
const prev = queue.previous();
queue.jumpTo(index);

// Modes
queue.setRepeatMode('all'); // 'off' | 'all' | 'one'
queue.setShuffleMode('on'); // 'off' | 'on'

// Queue manipulation
queue.addToQueue(track);
queue.removeAt(index);
queue.moveItem(fromIndex, toIndex);

// State
const currentItem = queue.getCurrentItem();
const hasNext = queue.hasNext();
const state = queue.getState();
```

### PlaybackState

Manage playback state and server reporting.

```typescript
const state = new PlaybackState(client);

// Set state
state.setCurrentItem(audioItem);
state.setStatus('playing');
state.setCurrentTime(30);
state.setVolume(0.7);

// Server reporting
await state.reportPlaybackStart();
await state.reportPlaybackProgress();
await state.reportPlaybackStop();

// Get state
const currentState = state.getState();

// Time conversion
const ticks = PlaybackState.secondsToTicks(30);
const seconds = PlaybackState.ticksToSeconds(300_000_000);
```

## Development

### Setup

```bash
npm install
```

### Build

```bash
npm run build
```

### Test

```bash
npm test
```

### Test with coverage

```bash
npm run test:coverage
```

### Watch mode

```bash
npm run test:watch
```

## Project Structure

```
packages/core/
├── src/
│   ├── api/
│   │   ├── client.ts       # HTTP client with auth
│   │   ├── auth.ts         # Authentication service
│   │   └── items.ts        # Library queries
│   ├── player/
│   │   ├── queue.ts        # Queue state machine
│   │   └── state.ts        # Playback state
│   ├── models/
│   │   └── types.ts        # TypeScript types
│   └── index.ts            # Public API
└── tests/
    ├── client.test.ts
    ├── auth.test.ts
    ├── queue.test.ts
    └── state.test.ts
```

## Key Concepts

### Jellyfin API Details

Per the [Jellyfin API documentation](../../../README.md):

- **Auth Field**: Use `Pw` not `Password` (varies by version)
- **Recursive Queries**: Must set `Recursive: true` for deep traversal
- **Stream URLs**: Use `api_key` query parameter for `<audio>` tags
- **Time Format**: Jellyfin uses ticks (100-nanosecond intervals)

### Ticks Conversion

```typescript
// Jellyfin stores time as ticks (100-nanosecond intervals)
const TICKS_PER_SECOND = 10_000_000;

// 30 seconds = 300,000,000 ticks
const ticks = PlaybackState.secondsToTicks(30);

// Convert back
const seconds = PlaybackState.ticksToSeconds(300_000_000);
```

### Queue State Machine

The queue implements a full state machine with:
- Navigation (next, previous, jump)
- Repeat modes (off, all, one)
- Shuffle with position preservation
- Queue manipulation (add, remove, move)
- State snapshots

### Error Types

```typescript
import {
  JellyfinError,        // Base error
  AuthenticationError,  // 401/403 errors
  NetworkError          // Network failures
} from '@jellyfin-mini/core';

try {
  await client.get('/endpoint');
} catch (error) {
  if (error instanceof AuthenticationError) {
    // Handle auth error
  } else if (error instanceof NetworkError) {
    // Handle network error
  }
}
```

## Testing

Comprehensive test suite with:
- Unit tests for all modules
- Mock HTTP client
- Edge case coverage
- State machine verification

Run tests:

```bash
npm test
```

## License

MIT
