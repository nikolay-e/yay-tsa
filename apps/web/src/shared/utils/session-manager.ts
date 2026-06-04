import { STORAGE_KEYS } from '@yay-tsa/core';

export interface SessionData {
  token: string;
  userId: string;
}

export interface LoadedSession extends SessionData {
  // true when loaded from localStorage (persistent across PWA restart).
  persistent: boolean;
  // true when the user explicitly opted into a tab-scoped session in this
  // version (Remember me unchecked). Distinguishes an intentional downgrade
  // from a legacy sessionStorage session written by an older app version.
  explicitTabScope: boolean;
}

const COOKIE_NAME = 'yay_token';

// Frontend-only marker (sessionStorage) recording that the current tab-scoped
// session was an explicit user choice in this app version, so it must NOT be
// promoted to persistent storage. Legacy sessions from older versions lack it.
const SESSION_SCOPE_KEY = 'yaytsa_session_scope';
const SESSION_SCOPE_TAB = 'tab';

function writeTokenCookie(token: string): void {
  if (typeof document === 'undefined') return;
  const isSecure = globalThis.location?.protocol === 'https:';
  const attrs = ['Path=/', 'SameSite=Strict', 'Max-Age=2592000'];
  if (isSecure) attrs.push('Secure');
  document.cookie = `${COOKIE_NAME}=${encodeURIComponent(token)}; ${attrs.join('; ')}`;
}

function clearTokenCookie(): void {
  if (typeof document === 'undefined') return;
  document.cookie = `${COOKIE_NAME}=; Path=/; Max-Age=0; SameSite=Strict`;
}

export function saveSession(data: SessionData): void {
  if (typeof sessionStorage === 'undefined') {
    return;
  }

  try {
    sessionStorage.setItem(STORAGE_KEYS.SESSION, data.token);
    sessionStorage.setItem(STORAGE_KEYS.USER_ID, data.userId);
    // Mark this as a deliberate tab-scoped session so restore never promotes it.
    sessionStorage.setItem(SESSION_SCOPE_KEY, SESSION_SCOPE_TAB);
    writeTokenCookie(data.token);
  } catch {
    // QuotaExceededError or SecurityError in private mode
  }
}

function loadSession(): (SessionData & { explicitTabScope: boolean }) | null {
  if (typeof sessionStorage === 'undefined') {
    return null;
  }

  try {
    const token = sessionStorage.getItem(STORAGE_KEYS.SESSION);
    const userId = sessionStorage.getItem(STORAGE_KEYS.USER_ID);

    if (!token || !userId) {
      return null;
    }

    return {
      token,
      userId,
      explicitTabScope: sessionStorage.getItem(SESSION_SCOPE_KEY) === SESSION_SCOPE_TAB,
    };
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
    sessionStorage.removeItem(SESSION_SCOPE_KEY);
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
    writeTokenCookie(data.token);
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

export function loadSessionAuto(): LoadedSession | null {
  const persistentSession = loadSessionPersistent();
  if (persistentSession) {
    writeTokenCookie(persistentSession.token);
    return { ...persistentSession, persistent: true, explicitTabScope: false };
  }
  const session = loadSession();
  if (session) {
    writeTokenCookie(session.token);
    return {
      token: session.token,
      userId: session.userId,
      persistent: false,
      explicitTabScope: session.explicitTabScope,
    };
  }
  return null;
}

/**
 * Promote a legacy tab-scoped session (written by an older app version, before
 * persistent-by-default) into persistent storage so it survives a PWA restart.
 * No-op for sessions that are already persistent or were explicitly tab-scoped.
 * Returns true if a promotion happened.
 */
export function promoteLegacySession(session: LoadedSession): boolean {
  if (session.persistent || session.explicitTabScope) {
    return false;
  }
  saveSessionPersistent({ token: session.token, userId: session.userId });
  clearSession();
  return true;
}

export function clearAllSessions(): void {
  clearSession();
  clearSessionPersistent();
  clearTokenCookie();
}
