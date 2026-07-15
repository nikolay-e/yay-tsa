export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface RuntimeConfig {
  serverUrl?: string;
  clientName?: string;
  deviceName?: string;
  version?: string;
  logLevel?: string;
}

export interface RuntimeConfigSource {
  read(): RuntimeConfig | undefined;
}

export interface VisibilitySignal {
  onVisible(listener: () => void): () => void;
}

export interface RuntimeProviders {
  storage?: KeyValueStorage;
  runtimeConfig?: RuntimeConfigSource;
  visibility?: VisibilitySignal;
}

declare global {
  interface Window {
    __YAYTSA_CONFIG__?: RuntimeConfig;
  }
}

let injectedStorage: KeyValueStorage | null = null;
let injectedRuntimeConfig: RuntimeConfigSource | null = null;
let injectedVisibility: VisibilitySignal | null = null;

export function setRuntimeProviders(providers: RuntimeProviders): void {
  if (providers.storage) injectedStorage = providers.storage;
  if (providers.runtimeConfig) injectedRuntimeConfig = providers.runtimeConfig;
  if (providers.visibility) injectedVisibility = providers.visibility;
}

export function getKeyValueStorage(): KeyValueStorage | null {
  if (injectedStorage) return injectedStorage;
  return typeof localStorage === 'undefined' ? null : localStorage;
}

export function getRuntimeConfig(): RuntimeConfig | undefined {
  return injectedRuntimeConfig?.read() ?? globalThis.window?.__YAYTSA_CONFIG__;
}

export function onVisible(listener: () => void): () => void {
  if (injectedVisibility) return injectedVisibility.onVisible(listener);
  if (typeof document === 'undefined') return () => {};
  const handler = (): void => {
    if (document.visibilityState === 'visible') listener();
  };
  document.addEventListener('visibilitychange', handler);
  return () => {
    document.removeEventListener('visibilitychange', handler);
  };
}
