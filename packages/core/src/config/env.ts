/**
 * Environment Configuration
 * Loads configuration from environment variables
 */

import { DEFAULT_CLIENT_NAME, DEFAULT_DEVICE_NAME, STORAGE_KEYS } from './constants.js';

export interface EnvironmentConfig {
  jellyfinServerUrl?: string;
  jellyfinClientName?: string;
  jellyfinDeviceName?: string;
  jellyfinDeviceId?: string;
}

/**
 * Runtime configuration interface (injected via config.js in production)
 */
declare global {
  interface Window {
    __JELLYFIN_CONFIG__?: {
      serverUrl?: string;
      clientName?: string;
      deviceName?: string;
      version?: string;
    };
  }
}

/**
 * Load configuration from environment variables
 * Works in both Node.js and browser environments
 * Priority: Runtime config (window.__JELLYFIN_CONFIG__) > Vite env > Node env
 */
export function loadEnvironmentConfig(): EnvironmentConfig {
  // Check if we're in Node.js environment
  const isNode = typeof process !== 'undefined' && process.env !== undefined;

  // Check if we're using import.meta.env (Vite/ESM)
  const hasImportMetaEnv = typeof import.meta?.env !== 'undefined';

  // Check for runtime config (injected by Docker entrypoint)
  const hasRuntimeConfig =
    typeof window !== 'undefined' && window.__JELLYFIN_CONFIG__ !== undefined;

  let env: Record<string, string | undefined>;

  if (hasImportMetaEnv) {
    // Vite/ESM environment
    env = import.meta.env as Record<string, string | undefined>;
  } else if (isNode) {
    // Node.js environment
    env = process.env;
  } else {
    // Browser without build tool - no env variables available
    env = {};
  }

  // Runtime config takes precedence over build-time config
  const runtimeConfig = hasRuntimeConfig ? window.__JELLYFIN_CONFIG__ : undefined;

  return {
    jellyfinServerUrl:
      runtimeConfig?.serverUrl || env.VITE_JELLYFIN_SERVER_URL || env.JELLYFIN_SERVER_URL,
    jellyfinClientName:
      runtimeConfig?.clientName ||
      env.VITE_JELLYFIN_CLIENT_NAME ||
      env.JELLYFIN_CLIENT_NAME ||
      DEFAULT_CLIENT_NAME,
    jellyfinDeviceName:
      runtimeConfig?.deviceName ||
      env.VITE_JELLYFIN_DEVICE_NAME ||
      env.JELLYFIN_DEVICE_NAME ||
      DEFAULT_DEVICE_NAME,
    jellyfinDeviceId: env.JELLYFIN_DEVICE_ID,
  };
}

/**
 * Get environment config with validation
 */
export function getRequiredConfig(): Required<Omit<EnvironmentConfig, 'jellyfinDeviceId'>> {
  const config = loadEnvironmentConfig();

  if (!config.jellyfinServerUrl) {
    throw new Error(
      'JELLYFIN_SERVER_URL is required. Please set it in .env file or environment variables.'
    );
  }

  return {
    jellyfinServerUrl: config.jellyfinServerUrl,
    jellyfinClientName: config.jellyfinClientName!,
    jellyfinDeviceName: config.jellyfinDeviceName!,
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
    const stored = localStorage.getItem(STORAGE_KEYS.DEVICE_ID);
    if (stored) {
      return stored;
    }

    // Generate new ID
    const newId = generateDeviceId();
    localStorage.setItem(STORAGE_KEYS.DEVICE_ID, newId);
    return newId;
  }

  // In Node.js or no storage: generate new ID each time
  return generateDeviceId();
}

/**
 * Generate a cryptographically secure device ID
 * Uses Web Crypto API for better security than Math.random()
 */
function generateDeviceId(): string {
  // Use crypto.randomUUID() if available (modern browsers and Node 19+)
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }

  // Fallback to crypto.getRandomValues() for older environments
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    const buffer = new Uint8Array(16);
    crypto.getRandomValues(buffer);
    // Convert to UUID format (xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx)
    buffer[6] = (buffer[6] & 0x0f) | 0x40; // Version 4
    buffer[8] = (buffer[8] & 0x3f) | 0x80; // Variant 10
    const hex = Array.from(buffer, byte => byte.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  // Last resort fallback (should rarely be needed)
  console.warn('crypto API not available, using less secure device ID generation');
  return `device-${Date.now()}-${Math.random().toString(36).substring(2, 15)}`;
}
