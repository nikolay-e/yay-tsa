# @yaytsa/platform

Platform-specific audio and media adapters for Yaytsa music player.

## Installation

```bash
npm install @yaytsa/platform
```

## Usage

```typescript
import { HTML5AudioEngine, MediaSessionManager } from '@yaytsa/platform';

const audioEngine = new HTML5AudioEngine();
await audioEngine.load(streamUrl);
audioEngine.play();

const mediaSession = new MediaSessionManager();
mediaSession.updateMetadata({ title, artist, album, artworkUrl });
```

## Adapters

- **HTML5AudioEngine** - Web browser audio playback using Web Audio API
- **MediaSessionManager** - Lock screen controls (play/pause, skip, metadata)

## Future Adapters

- ExpoAudioEngine - React Native audio playback
- TauriAudioEngine - Desktop native audio

## Dependencies

Depends on `@yaytsa/core` for playback state types.

See [CLAUDE.md](../../CLAUDE.md) for detailed architecture.
