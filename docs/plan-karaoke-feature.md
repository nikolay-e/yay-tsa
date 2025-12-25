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

## Yaytsa Architecture Compatibility

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

### Extensibility Points

1. **`getAudioContext()`** - Access underlying Web Audio API context
2. **PinkNoiseGenerator pattern** - Shows how to add audio processing nodes
3. **Backend transcoding config** - FFmpeg integration planned but not implemented
4. **Sleep timer architecture** - Demonstrates advanced audio manipulation

## Recommended Implementation: Hybrid Multi-Tier

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
- Output mono result

**Pros**:

- Instant, no loading time
- Works offline
- Zero server cost
- Good enough for casual karaoke

**Cons**:

- Removes some instruments (centered ones)
- Variable quality based on original mix
- Mono output

### Tier 2: Quality Mode (Server-Side AI)

**Quality**: High | **Speed**: 5-30 seconds (GPU) | **Cost**: Server resources

Architecture:

```
┌─────────────────────────────────────────────────────────┐
│ User clicks "Karaoke Mode"                              │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│ Check cache: GET /api/karaoke/{itemId}/instrumental     │
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
│   4. Notify client via WebSocket/polling                │
└─────────────────────────────────────────────────────────┘
```

**Server Components**:

- `/api/karaoke/{itemId}/status` - Check if instrumental exists
- `/api/karaoke/{itemId}/request` - Request separation
- `/api/karaoke/{itemId}/stream` - Stream instrumental file
- Background worker with Demucs (Python sidecar or Java Process)

**Caching Strategy**:

- Store instrumental alongside original in library
- Cache key: `{itemId}_instrumental.{format}`
- Optional: Store all 4 stems for future features (drums, bass isolated practice)

### Tier 3: Optional Cloud API Fallback

If self-hosting GPU is not feasible:

- [LALAL.AI API](https://www.lalal.ai/) - Commercial, high quality
- [vocalremover.org API](https://vocalremover.org/) - Commercial
- Pay-per-use model, good for low-volume usage

## Implementation Plan

### Phase 1: Client-Side Instant Mode

**Effort**: 1-2 days | **Priority**: High

Files to create/modify:

```
packages/platform/src/web/
├── vocal-remover.ts          # Phase cancellation processor
└── audio-effects-chain.ts    # Pluggable effects architecture

packages/web/src/lib/
├── stores/karaoke.ts         # Karaoke mode state
└── components/player/
    └── KaraokeModeButton.svelte
```

Implementation steps:

1. Create `VocalRemover` class using Web Audio API
2. Extend `HTML5AudioEngine` with effects chain support
3. Add karaoke toggle to player controls
4. Persist karaoke preference per track (localStorage)

### Phase 2: Server-Side AI Separation

**Effort**: 1-2 weeks | **Priority**: Medium

Backend additions:

```
server/src/main/java/com/yaytsa/server/
├── domain/service/
│   └── KaraokeService.java       # Separation job management
├── infra/separation/
│   ├── DemucsProcessor.java      # Python process wrapper
│   └── SeparationJobEntity.java  # Job tracking
└── presentation/api/
    └── KaraokeController.java    # REST endpoints
```

Requirements:

- Python environment with Demucs on server
- GPU (NVIDIA) for reasonable performance
- Storage for cached stems (~2x original file size for instrumental)

### Phase 3: UI Enhancements

**Effort**: 1 week | **Priority**: Low

Features:

- Vocals volume slider (0-100% vocal blend)
- Real-time mixing of original + instrumental
- Queue-level karaoke mode (apply to all tracks)
- Visual indicator when in karaoke mode

## Technical Decisions

### Decision 1: Start with Phase Cancellation

**Rationale**: Provides immediate value with zero infrastructure cost. Users can enjoy basic karaoke functionality while AI separation is developed.

### Decision 2: Server-Side over Client-Side AI

**Rationale**:

- Client WASM requires 4-16GB RAM, 5-17 min processing
- Server GPU processes in seconds
- Caching eliminates repeated processing
- Better UX (request once, play forever)

### Decision 3: Cache Instrumental Stems

**Rationale**:

- Separation is expensive (even with GPU)
- Same song played multiple times = same instrumental
- Storage is cheap (~doubling library size worst case)

### Decision 4: Demucs over Spleeter

**Rationale**:

- Higher quality (9.0 dB SDR vs ~6 dB)
- MIT license (same as project)
- Active development (forked to adefossez/demucs)
- Better guitar/piano separation for future features

## Risk Assessment

| Risk                            | Probability | Impact | Mitigation                                        |
| ------------------------------- | ----------- | ------ | ------------------------------------------------- |
| GPU not available on server     | Medium      | High   | Start with phase cancellation, cloud API fallback |
| Poor phase cancellation quality | High        | Low    | Clear UI indicating "instant" vs "quality" mode   |
| Large storage for cached stems  | Medium      | Medium | Configurable cache limits, LRU eviction           |
| Demucs Python dependency        | Low         | Medium | Docker containerization, process isolation        |

## Open Questions

1. **Should karaoke mode be per-track or global?**
   - Recommendation: Per-track with "apply to queue" option

2. **Store all 4 stems or just instrumental?**
   - Recommendation: Start with instrumental only, expand later

3. **Background processing notification method?**
   - Options: WebSocket, SSE, polling
   - Recommendation: Polling (simplest, already used for playback reporting)

4. **Mobile browser compatibility for phase cancellation?**
   - Need to test Web Audio API performance on iOS Safari

## References

- [Demucs (Facebook Research)](https://github.com/facebookresearch/demucs)
- [Free Music Demixer (WASM)](https://github.com/sevagh/free-music-demixer)
- [Spleeter Web (Self-hosted)](https://github.com/JeffreyCA/spleeter-web)
- [Web Audio API (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/Web_Audio_API)
- [Phase Cancellation Technique](https://www.soundonsound.com/sound-advice/q-can-remove-vocals-track-using-phase)
- [LALAL.AI](https://www.lalal.ai/)
- [Moises AI](https://moises.ai/features/vocal-remover/)

## Next Steps

1. Review and approve this plan
2. Implement Phase 1 (client-side instant mode)
3. Set up Demucs on backend server (Docker)
4. Implement Phase 2 (server-side separation)
5. E2E testing with real tracks
6. UI polish (Phase 3)
