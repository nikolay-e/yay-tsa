import { get, type Writable, type Readable } from 'svelte/store';

export interface AsyncState {
  isLoading: boolean;
  error: string | null;
}

export interface ServiceState<T> {
  service: T | null;
}

export function createServiceWaiter<T>(
  store: Readable<ServiceState<T>>,
  isAuthenticated: Readable<boolean>,
  timeoutMs: number = 5000,
  serviceName: string = 'Service'
): () => Promise<T> {
  return async () => {
    if (!get(isAuthenticated)) {
      throw new Error('Not authenticated');
    }

    const state = get(store);
    if (state.service) {
      return state.service;
    }

    return new Promise((resolve, reject) => {
      let resolved = false;

      const timeout = setTimeout(() => {
        if (!resolved) {
          resolved = true;
          unsubscribe();
          reject(new Error(`${serviceName} not initialized after ${timeoutMs}ms`));
        }
      }, timeoutMs);

      const unsubscribe = store.subscribe($state => {
        if ($state.service && !resolved) {
          resolved = true;
          clearTimeout(timeout);
          queueMicrotask(() => unsubscribe());
          resolve($state.service);
        }
      });
    });
  };
}

export interface AsyncStoreHandler<T extends AsyncState> {
  /** Mark operation as started (sets isLoading=true, error=null) */
  start(): void;
  /** Mark operation as successful (sets isLoading=false, applies data) */
  success(data: Partial<T>): void;
  /** Mark operation as failed (sets isLoading=false, error=message) */
  error(error: Error): void;
}

/**
 * Creates a handler for async store operations with standardized loading/error states
 *
 * @example
 * ```typescript
 * async function loadAlbums() {
 *   const handler = createAsyncStoreHandler(libraryStore);
 *   handler.start();
 *
 *   try {
 *     const result = await itemsService.getAlbums();
 *     handler.success({ albums: result.Items });
 *   } catch (error) {
 *     handler.error(error as Error);
 *     throw error;
 *   }
 * }
 * ```
 */
export function createAsyncStoreHandler<T extends AsyncState>(
  store: Writable<T>
): AsyncStoreHandler<T> {
  return {
    start(): void {
      store.update(s => ({ ...s, isLoading: true, error: null }) as T);
    },

    success(data: Partial<T>): void {
      store.update(s => ({ ...s, ...data, isLoading: false }) as T);
    },

    error(error: Error): void {
      console.error('Store operation error:', error);
      store.update(
        s =>
          ({
            ...s,
            isLoading: false,
            error: error.message,
          }) as T
      );
    },
  };
}
