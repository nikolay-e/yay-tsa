/**
 * Media Server Client - Platform Package
 * Platform-specific adapters and interfaces
 */

// Audio engine interface and implementations
export type { AudioEngine } from './audio.interface.js';
export { HTML5AudioEngine } from './web/html5-audio.js';

// Pink noise generator for sleep mode
export { PinkNoiseGenerator } from './web/pink-noise-generator.js';
export type { PinkNoiseConfig } from './web/pink-noise-generator.js';

// Vocal removal processor for karaoke mode
export { VocalRemovalProcessor } from './web/vocal-removal.js';
export type { VocalRemovalConfig } from './web/vocal-removal.js';

// Media Session API for background playback
export { MediaSessionManager } from './web/media-session.js';
export type { MediaMetadata, MediaSessionHandlers } from './web/media-session.js';
