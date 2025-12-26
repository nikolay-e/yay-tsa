/**
 * Media Server Client - Core Package
 * Framework-agnostic business logic
 */

// API modules
export { MediaServerClient, type KaraokeStatus } from './api/api.client.js';
export { BaseService } from './api/base-api.service.js';
export { AuthService, validateServerUrl } from './api/auth.service.js';
export { ItemsService } from './api/items.service.js';
export { PlaylistsService } from './api/playlists.service.js';

// Player modules
export { PlaybackQueue } from './player/queue.js';
export { PlaybackState, PlaybackReporter } from './player/playback-state.js';

// Configuration
export {
  loadEnvironmentConfig,
  getRequiredConfig,
  getOrCreateDeviceId,
  type EnvironmentConfig,
} from './internal/config/env.js';

export {
  APP_VERSION,
  DEFAULT_CLIENT_NAME,
  DEFAULT_DEVICE_NAME,
  STORAGE_KEYS,
  TICKS_PER_SECOND,
  secondsToTicks,
  ticksToSeconds,
} from './internal/config/constants.js';

// Cache modules
export { IndexedDBCache } from './cache/indexed-db-cache.js';
export { ICache, CacheConfig, CacheEntry, TTL } from './cache/cache.interface.js';

// Logging
export {
  createLogger,
  logger,
  type Logger,
  type LogContext,
  type LogLevel,
} from './internal/utils/logger.js';

// Types and models
export * from './internal/models/types.js';
