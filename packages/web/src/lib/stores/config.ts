// packages/web/src/lib/stores/config.ts
import { readable } from 'svelte/store';
import {
  loadEnvironmentConfig,
  DEFAULT_CLIENT_NAME,
  DEFAULT_DEVICE_NAME,
  type EnvironmentConfig,
} from '@jellyfin-mini/core';

export interface AppConfig {
  serverUrl: string | undefined;
  clientName: string;
  deviceName: string;
}

// This function uses the core package's loadEnvironmentConfig
// which properly handles runtime config injection from Docker
const getAppConfig = (): AppConfig => {
  const coreConfig: EnvironmentConfig = loadEnvironmentConfig();

  return {
    serverUrl: coreConfig.jellyfinServerUrl,
    clientName: coreConfig.jellyfinClientName || DEFAULT_CLIENT_NAME,
    deviceName: coreConfig.jellyfinDeviceName || DEFAULT_DEVICE_NAME,
  };
};

export const config = readable<AppConfig>(getAppConfig());
