# Karaoke Feature Plan: Vocal Removal for Yaytsa

## Executive Summary

This document outlines the implementation plan for a karaoke mode that plays music without vocals. Based on research of modern approaches (2024-2025), the recommended strategy is a **hybrid multi-tier approach** offering instant low-quality client-side processing with optional high-quality server-side AI separation.

## Research Findings

### Approach Comparison

| Approach                         | Quality                 | Speed         | Resources     | Complexity |
| -------------------------------- | ----------------------- | ------------- | ------------- | ---------- |
| Phase Cancellation (Web Audio)   | Poor (removes bass too) | Real-time     | None          | Low        |
| Client-side AI (WASM Demucs)     | High (9.0 dB SDR)       | 5-17 min/song | 4-16GB RAM    | Medium     |
| Server-side AI (Demucs/Spleeter) | Highest                 | Seconds (GPU) | GPU + storage | High       |
| Cloud API (lalal.ai, etc.)       | High                    | Seconds       | API costs     | Low        |

### Key Technical Insights

1. **Phase Cancellation Limitations**
   - Only removes center-panned content (vocals, bass, kick drums)
   - Workaround: High-pass filter at 120Hz preserves bass
   - Fails on mono tracks, stereo effects, reverb-heavy vocals
   - Source: [Sound on Sound](https://www.soundonsound.com/sound-advice/q-can-remove-vocals-track-using-phase)

2. **AI-Based Separation (Demucs v4)**
   - State-of-the-art: Hybrid Transformer architecture
   - 9.0 dB SDR on MUSDB HQ test set
   - Models: htdemucs (81MB), htdemucs_6s (53MB)
   - Separates: vocals, drums, bass, other (optionally piano, guitar)
   - License: MIT
   - Source: [Facebook Research Demucs](https://github.com/facebookresearch/demucs)

3. **Browser WASM Implementation**
   - Project: [free-music-demixer](https://github.com/sevagh/free-music-demixer)
   - Processing time: ~5-17 minutes per song (depends on workers)
   - Memory: 4-16GB RAM required
   - Model size: 53-81MB download
   - Note: Repository archived April 2025

4. **Server-Side Implementation**
   - [spleeter-web](https://github.com/JeffreyCA/spleeter-web): Django + React + Celery
   - GPU processing: 100x real-time (seconds per song)
   - VRAM requirement: 7GB recommended, min 3GB
   - Supports: Spleeter, Demucs, BS-RoFormer

## Architecture

### Current Audio Stack

```
┌─────────────────────────────────────────────────────────┐
│ Web Layer (Svelte)                                       │
│   player.ts → HTML5AudioEngine → MediaSessionManager    │
└─────────────────────────────────────────────────────────┘
                            │
                    getAudioContext()
                            │
┌─────────────────────────────────────────────────────────┐
│ Platform Layer                                           │
│   AudioEngine interface → HTML5AudioEngine              │
│   PinkNoiseGenerator (example of Web Audio processing)  │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│ Backend (Java Spring Boot)                              │
│   StreamingService → Direct file streaming              │
│   No transcoding/DSP currently implemented              │
└─────────────────────────────────────────────────────────┘
```

### Karaoke Architecture

```
Original Audio (FLAC/MP3)
        ↓
Java Backend (trigger)
        ↓ HTTP POST
Python audio-separator (Docker + GPU)
        ↓ Demucs v4 / MDX-Net
Stems: vocal.wav + instrumental.wav
        ↓
Storage (filesystem)
        ↓
Frontend toggle: Original ↔ Karaoke
```

## Implementation

### Tier 1: Instant Mode (Phase Cancellation)

**Quality**: Low-Medium | **Speed**: Real-time | **Cost**: Zero

```
Audio Stream → ChannelSplitterNode → [Phase Inversion + HPF] → ChannelMergerNode → Output
```

Implementation using Web Audio API:

- Split stereo channels with `ChannelSplitterNode`
- Apply `GainNode` with gain=-1 to one channel (phase inversion)
- Add `BiquadFilterNode` highpass at 120Hz (preserve bass)
- Merge with `ChannelMergerNode`

**Pros:**

- Instant, no loading time
- Works offline
- Zero server cost

**Cons:**

- Removes some instruments (centered ones)
- Variable quality based on original mix

### Tier 2: Quality Mode (Server-Side AI)

**Quality**: High | **Speed**: 5-30 seconds (GPU) | **Cost**: Server resources

```
┌─────────────────────────────────────────────────────────┐
│ User clicks "Karaoke Mode"                              │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│ Check cache: GET /Karaoke/{itemId}/status               │
│   Cache hit → stream cached instrumental                │
│   Cache miss → enqueue separation job                   │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│ Separation Worker (Background)                          │
│   1. Download original audio file                       │
│   2. Run Demucs separation (GPU accelerated)            │
│   3. Store instrumental stem                            │
│   4. Notify client via SSE                              │
└─────────────────────────────────────────────────────────┘
```

## API Endpoints

| Method | Endpoint                         | Description              |
| ------ | -------------------------------- | ------------------------ |
| GET    | /Karaoke/enabled                 | Check if feature enabled |
| GET    | /Karaoke/{trackId}/status        | Processing status        |
| GET    | /Karaoke/{trackId}/status/stream | SSE stream for updates   |
| POST   | /Karaoke/{trackId}/process       | Request processing       |
| GET    | /Karaoke/{trackId}/instrumental  | Stream instrumental      |
| GET    | /Karaoke/{trackId}/vocals        | Stream vocals only       |

## Components

### Python Microservice (audio-separator)

Docker image: Custom build with CUDA support

Files:

- `docker/audio-separator/Dockerfile`
- `docker/audio-separator/app.py` (FastAPI wrapper)

### Java Backend

Files:

- `server/.../domain/service/KaraokeService.java`
- `server/.../infra/client/AudioSeparatorClient.java`
- `server/.../controller/KaraokeController.java`

Database changes:

- `audio_tracks.karaoke_ready` (BOOLEAN)
- `audio_tracks.instrumental_path` (TEXT)
- `audio_tracks.vocal_path` (TEXT)

### Frontend (SvelteKit)

Files:

- `packages/platform/src/web/vocal-removal.ts` - Phase cancellation
- `packages/web/src/lib/stores/karaoke.ts` - State management
- `packages/web/src/lib/components/player/KaraokeModeButton.svelte`

## Resource Requirements

| Component  | CPU Mode                              | GPU Mode     |
| ---------- | ------------------------------------- | ------------ |
| RAM        | 8-16 GB                               | 4-8 GB       |
| Time/track | ~60-90s (Spleeter) / ~3-4min (Demucs) | 5-30 seconds |
| Storage    | ~50MB per track (stems)               | Same         |

## UX Flow

1. User opens track → sees "Karaoke" button
2. If stems not ready → shows "Prepare karaoke" option
3. Click → starts processing → progress indicator (~1-2 min)
4. After ready → toggle switches Original ↔ Instrumental
5. Stems cached → instant switching next time

## Risk Assessment

| Risk                            | Probability | Impact | Mitigation                                        |
| ------------------------------- | ----------- | ------ | ------------------------------------------------- |
| GPU not available on server     | Medium      | High   | Start with phase cancellation, cloud API fallback |
| Poor phase cancellation quality | High        | Low    | Clear UI indicating "instant" vs "quality" mode   |
| Large storage for cached stems  | Medium      | Medium | Configurable cache limits, LRU eviction           |
| Demucs Python dependency        | Low         | Medium | Docker containerization, process isolation        |

## References

- [Demucs (Facebook Research)](https://github.com/facebookresearch/demucs)
- [Free Music Demixer (WASM)](https://github.com/sevagh/free-music-demixer)
- [Spleeter Web (Self-hosted)](https://github.com/JeffreyCA/spleeter-web)
- [Web Audio API (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/Web_Audio_API)
- [Phase Cancellation Technique](https://www.soundonsound.com/sound-advice/q-can-remove-vocals-track-using-phase)
