import { logger } from './logger.js';

const DB_NAME = 'yaytsa-passkeys';
const DB_VERSION = 1;
const STORE_NAME = 'credentials';

export interface PasskeyCredential {
  id: string;
  publicKey: Uint8Array;
  counter: number;
  transports: string[];
  createdAt: string;
  name: string;
}

async function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onerror = () => {
      logger.error('[PasskeyStorage] Failed to open database:', request.error);
      reject(request.error);
    };

    request.onsuccess = () => {
      resolve(request.result);
    };

    request.onupgradeneeded = event => {
      const db = (event.target as IDBOpenDBRequest).result;

      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'id' });
        logger.info('[PasskeyStorage] Created credentials object store');
      }
    };
  });
}

export async function saveCredential(credential: PasskeyCredential): Promise<void> {
  try {
    const db = await openDatabase();
    const transaction = db.transaction([STORE_NAME], 'readwrite');
    const store = transaction.objectStore(STORE_NAME);

    await new Promise<void>((resolve, reject) => {
      const request = store.put(credential);

      request.onsuccess = () => {
        logger.info(`[PasskeyStorage] Saved credential: ${credential.name}`);
        resolve();
      };

      request.onerror = () => {
        logger.error('[PasskeyStorage] Failed to save credential:', request.error);
        reject(request.error);
      };
    });

    db.close();
  } catch (error) {
    logger.error('[PasskeyStorage] saveCredential error:', error);
    throw error;
  }
}

export async function getCredentials(): Promise<PasskeyCredential[]> {
  try {
    const db = await openDatabase();
    const transaction = db.transaction([STORE_NAME], 'readonly');
    const store = transaction.objectStore(STORE_NAME);

    const credentials = await new Promise<PasskeyCredential[]>((resolve, reject) => {
      const request = store.getAll();

      request.onsuccess = () => {
        resolve(request.result as PasskeyCredential[]);
      };

      request.onerror = () => {
        logger.error('[PasskeyStorage] Failed to get credentials:', request.error);
        reject(request.error);
      };
    });

    db.close();
    return credentials;
  } catch (error) {
    logger.error('[PasskeyStorage] getCredentials error:', error);
    return [];
  }
}

export async function deleteCredential(id: string): Promise<void> {
  try {
    const db = await openDatabase();
    const transaction = db.transaction([STORE_NAME], 'readwrite');
    const store = transaction.objectStore(STORE_NAME);

    await new Promise<void>((resolve, reject) => {
      const request = store.delete(id);

      request.onsuccess = () => {
        logger.info(`[PasskeyStorage] Deleted credential: ${id}`);
        resolve();
      };

      request.onerror = () => {
        logger.error('[PasskeyStorage] Failed to delete credential:', request.error);
        reject(request.error);
      };
    });

    db.close();
  } catch (error) {
    logger.error('[PasskeyStorage] deleteCredential error:', error);
    throw error;
  }
}

export async function clearAll(): Promise<void> {
  try {
    const db = await openDatabase();
    const transaction = db.transaction([STORE_NAME], 'readwrite');
    const store = transaction.objectStore(STORE_NAME);

    await new Promise<void>((resolve, reject) => {
      const request = store.clear();

      request.onsuccess = () => {
        logger.info('[PasskeyStorage] Cleared all credentials');
        resolve();
      };

      request.onerror = () => {
        logger.error('[PasskeyStorage] Failed to clear credentials:', request.error);
        reject(request.error);
      };
    });

    db.close();
  } catch (error) {
    logger.error('[PasskeyStorage] clearAll error:', error);
    throw error;
  }
}
