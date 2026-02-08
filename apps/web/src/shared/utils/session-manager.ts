import { STORAGE_KEYS } from '@yay-tsa/core';

export interface SessionData {
  token: string;
  userId: string;
}

export function saveSession(data: SessionData): void {
  if (typeof sessionStorage === 'undefined') {
    return;
  }

  try {
    sessionStorage.setItem(STORAGE_KEYS.SESSION, data.token);
    sessionStorage.setItem(STORAGE_KEYS.USER_ID, data.userId);
  } catch {
    // QuotaExceededError or SecurityError in private mode
  }
}

function loadSession(): SessionData | null {
  if (typeof sessionStorage === 'undefined') {
    return null;
  }

  try {
    const token = sessionStorage.getItem(STORAGE_KEYS.SESSION);
    const userId = sessionStorage.getItem(STORAGE_KEYS.USER_ID);

    if (!token || !userId) {
      return null;
    }

    return { token, userId };
  } catch {
    return null;
  }
}

function clearSession(): void {
  if (typeof sessionStorage === 'undefined') {
    return;
  }

  try {
    sessionStorage.removeItem(STORAGE_KEYS.SESSION);
    sessionStorage.removeItem(STORAGE_KEYS.USER_ID);
  } catch {
    // SecurityError in private mode
  }
}

export function saveSessionPersistent(data: SessionData): void {
  if (typeof localStorage === 'undefined') {
    return;
  }

  try {
    localStorage.setItem(STORAGE_KEYS.SESSION_PERSISTENT, data.token);
    localStorage.setItem(STORAGE_KEYS.USER_ID_PERSISTENT, data.userId);
    localStorage.setItem(STORAGE_KEYS.REMEMBER_ME, 'true');
  } catch {
    // QuotaExceededError or SecurityError
  }
}

function loadSessionPersistent(): SessionData | null {
  if (typeof localStorage === 'undefined') {
    return null;
  }

  try {
    const rememberMe = localStorage.getItem(STORAGE_KEYS.REMEMBER_ME);
    if (rememberMe !== 'true') {
      return null;
    }

    const token = localStorage.getItem(STORAGE_KEYS.SESSION_PERSISTENT);
    const userId = localStorage.getItem(STORAGE_KEYS.USER_ID_PERSISTENT);

    if (!token || !userId) {
      return null;
    }

    return { token, userId };
  } catch {
    return null;
  }
}

function clearSessionPersistent(): void {
  if (typeof localStorage === 'undefined') {
    return;
  }

  try {
    localStorage.removeItem(STORAGE_KEYS.SESSION_PERSISTENT);
    localStorage.removeItem(STORAGE_KEYS.USER_ID_PERSISTENT);
    localStorage.removeItem(STORAGE_KEYS.REMEMBER_ME);
  } catch {
    // SecurityError
  }
}

export function loadSessionAuto(): SessionData | null {
  const persistentSession = loadSessionPersistent();
  if (persistentSession) {
    return persistentSession;
  }
  return loadSession();
}

export function clearAllSessions(): void {
  clearSession();
  clearSessionPersistent();
}
