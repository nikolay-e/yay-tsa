import { DEFAULT_CLIENT_NAME, DEFAULT_DEVICE_NAME, STORAGE_KEYS } from './constants.js';

export interface EnvironmentConfig {
  yaytsaServerUrl?: string;
  yaytsaClientName?: string;
  yaytsaDeviceName?: string;
  yaytsaDeviceId?: string;
}

export function loadEnvironmentConfig(): EnvironmentConfig {
  const isNode = typeof process !== 'undefined' && process.env !== undefined;
  const hasImportMetaEnv = import.meta?.env !== undefined;
  const hasRuntimeConfig = globalThis.window?.__YAYTSA_CONFIG__ !== undefined;

  let env: Record<string, string | undefined>;

  if (hasImportMetaEnv) {
    env = import.meta.env as Record<string, string | undefined>;
  } else if (isNode && typeof process !== 'undefined') {
    env = process.env;
  } else {
    env = {};
  }

  const runtimeConfig = hasRuntimeConfig ? globalThis.window.__YAYTSA_CONFIG__ : undefined;

  return {
    yaytsaServerUrl:
      runtimeConfig?.serverUrl ?? env.VITE_YAYTSA_SERVER_URL ?? env.YAYTSA_SERVER_URL,
    yaytsaClientName:
      runtimeConfig?.clientName ??
      env.VITE_YAYTSA_CLIENT_NAME ??
      env.YAYTSA_CLIENT_NAME ??
      DEFAULT_CLIENT_NAME,
    yaytsaDeviceName:
      runtimeConfig?.deviceName ??
      env.VITE_YAYTSA_DEVICE_NAME ??
      env.YAYTSA_DEVICE_NAME ??
      DEFAULT_DEVICE_NAME,
    yaytsaDeviceId: env.YAYTSA_DEVICE_ID,
  };
}

export function getRequiredConfig(): Required<Omit<EnvironmentConfig, 'yaytsaDeviceId'>> {
  const config = loadEnvironmentConfig();

  if (!config.yaytsaServerUrl) {
    throw new Error(
      'YAYTSA_SERVER_URL is required. Please set it in .env file or environment variables.'
    );
  }

  return {
    yaytsaServerUrl: config.yaytsaServerUrl,
    yaytsaClientName: config.yaytsaClientName ?? DEFAULT_CLIENT_NAME,
    yaytsaDeviceName: config.yaytsaDeviceName ?? DEFAULT_DEVICE_NAME,
  };
}

export function getOrCreateDeviceId(): string {
  const config = loadEnvironmentConfig();

  if (config.yaytsaDeviceId) {
    return config.yaytsaDeviceId;
  }

  if (typeof localStorage !== 'undefined') {
    const stored = localStorage.getItem(STORAGE_KEYS.DEVICE_ID);
    if (stored) {
      return stored;
    }

    const newId = generateDeviceId();
    localStorage.setItem(STORAGE_KEYS.DEVICE_ID, newId);
    return newId;
  }

  return generateDeviceId();
}

function generateDeviceId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }

  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    const buffer = new Uint8Array(16);
    crypto.getRandomValues(buffer);
    buffer[6] = (buffer[6] & 0x0f) | 0x40;
    buffer[8] = (buffer[8] & 0x3f) | 0x80;
    const hex = Array.from(buffer, byte => byte.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  throw new Error('crypto API required for secure device ID generation');
}
