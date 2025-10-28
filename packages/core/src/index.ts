/**
 * Jellyfin Mini Client - Core Package
 * Framework-agnostic business logic
 */

// API modules
export { JellyfinClient } from './api/client.js';
export { AuthService, validateServerUrl } from './api/auth.js';
export { ItemsService } from './api/items.js';

// Player modules
export { PlaybackQueue } from './player/queue.js';
export { PlaybackState } from './player/state.js';

// Configuration
export {
  loadEnvironmentConfig,
  getRequiredConfig,
  getOrCreateDeviceId,
  type EnvironmentConfig,
} from './config/env.js';

// Types and models
export * from './models/types.js';
