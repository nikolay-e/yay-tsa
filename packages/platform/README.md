# @yay-tsa/platform

Platform-specific audio and media adapters for Yay-Tsa music player.

## Installation

```bash
npm install @yay-tsa/platform
```

## Usage

```typescript
import { HTML5AudioEngine, MediaSessionManager } from '@yay-tsa/platform';

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

Depends on `@yay-tsa/core` for playback state types.

See [CLAUDE.md](../../CLAUDE.md) for detailed architecture.
