/**
 * Jellyfin Mini Client - Core Package
 * Framework-agnostic business logic
 */

// API modules
export { JellyfinClient } from './api/client.js';
export { BaseService } from './api/base-service.js';
export { AuthService, validateServerUrl } from './api/auth.js';
export { ItemsService } from './api/items.js';
export { PlaylistsService } from './api/playlists.js';

// Player modules
export { PlaybackQueue } from './player/queue.js';
export { PlaybackState, PlaybackReporter } from './player/state.js';

// Configuration
export {
  loadEnvironmentConfig,
  getRequiredConfig,
  getOrCreateDeviceId,
  type EnvironmentConfig,
} from './config/env.js';

export {
  APP_VERSION,
  DEFAULT_CLIENT_NAME,
  DEFAULT_DEVICE_NAME,
  STORAGE_KEYS,
  TICKS_PER_SECOND,
  secondsToTicks,
  ticksToSeconds,
} from './config/constants.js';

// Types and models
export * from './models/types.js';
