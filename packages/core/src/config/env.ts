/**
 * Environment Configuration
 * Loads configuration from environment variables
 */

export interface EnvironmentConfig {
  jellyfinApiKey?: string;
  jellyfinServerUrl?: string;
  jellyfinClientName?: string;
  jellyfinDeviceName?: string;
  jellyfinDeviceId?: string;
}

/**
 * Load configuration from environment variables
 * Works in both Node.js and browser environments
 */
export function loadEnvironmentConfig(): EnvironmentConfig {
  // Check if we're in Node.js environment
  const isNode = typeof process !== 'undefined' &&
                 process.env !== undefined;

  // Check if we're using import.meta.env (Vite/ESM)
  const hasImportMetaEnv = typeof import.meta !== 'undefined' &&
                           typeof import.meta.env !== 'undefined';

  let env: Record<string, string | undefined>;

  if (hasImportMetaEnv) {
    // Vite/ESM environment
    env = import.meta.env as any;
  } else if (isNode) {
    // Node.js environment
    env = process.env;
  } else {
    // Browser without build tool - no env variables available
    env = {};
  }

  return {
    jellyfinApiKey: env.JELLYFIN_API_KEY || env.VITE_JELLYFIN_API_KEY,
    jellyfinServerUrl: env.JELLYFIN_SERVER_URL || env.VITE_JELLYFIN_SERVER_URL,
    jellyfinClientName: env.JELLYFIN_CLIENT_NAME || env.VITE_JELLYFIN_CLIENT_NAME || 'Jellyfin Mini Client',
    jellyfinDeviceName: env.JELLYFIN_DEVICE_NAME || env.VITE_JELLYFIN_DEVICE_NAME || 'Web Browser',
    jellyfinDeviceId: env.JELLYFIN_DEVICE_ID || env.VITE_JELLYFIN_DEVICE_ID
  };
}

/**
 * Get environment config with validation
 */
export function getRequiredConfig(): Required<Omit<EnvironmentConfig, 'jellyfinDeviceId'>> {
  const config = loadEnvironmentConfig();

  if (!config.jellyfinApiKey) {
    throw new Error(
      'JELLYFIN_API_KEY is required. Please set it in .env file or environment variables.'
    );
  }

  if (!config.jellyfinServerUrl) {
    throw new Error(
      'JELLYFIN_SERVER_URL is required. Please set it in .env file or environment variables.'
    );
  }

  return {
    jellyfinApiKey: config.jellyfinApiKey,
    jellyfinServerUrl: config.jellyfinServerUrl,
    jellyfinClientName: config.jellyfinClientName!,
    jellyfinDeviceName: config.jellyfinDeviceName!
  };
}

/**
 * Generate or retrieve device ID
 * In browser: uses localStorage
 * In Node.js: generates new UUID
 */
export function getOrCreateDeviceId(): string {
  const config = loadEnvironmentConfig();

  // If device ID is set in env, use it
  if (config.jellyfinDeviceId) {
    return config.jellyfinDeviceId;
  }

  // In browser: try localStorage
  if (typeof localStorage !== 'undefined') {
    const stored = localStorage.getItem('jellyfin_device_id');
    if (stored) {
      return stored;
    }

    // Generate new ID
    const newId = generateDeviceId();
    localStorage.setItem('jellyfin_device_id', newId);
    return newId;
  }

  // In Node.js or no storage: generate new ID each time
  return generateDeviceId();
}

/**
 * Generate a simple device ID
 */
function generateDeviceId(): string {
  return `device-${Date.now()}-${Math.random().toString(36).substring(2, 15)}`;
}
