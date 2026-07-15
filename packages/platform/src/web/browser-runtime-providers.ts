import type { KeyValueStorage, RuntimeConfigSource, VisibilitySignal } from '@yay-tsa/core';

export const browserKeyValueStorage: KeyValueStorage = {
  getItem: key => localStorage.getItem(key),
  setItem: (key, value) => {
    localStorage.setItem(key, value);
  },
};

export const browserRuntimeConfigSource: RuntimeConfigSource = {
  read: () => globalThis.window?.__YAYTSA_CONFIG__,
};

export const browserVisibilitySignal: VisibilitySignal = {
  onVisible: listener => {
    const handler = (): void => {
      if (document.visibilityState === 'visible') listener();
    };
    document.addEventListener('visibilitychange', handler);
    return () => {
      document.removeEventListener('visibilitychange', handler);
    };
  },
};
