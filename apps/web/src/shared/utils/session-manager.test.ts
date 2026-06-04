import { describe, it, expect, beforeEach } from 'vitest';
import { STORAGE_KEYS } from '@yay-tsa/core';
import {
  saveSession,
  saveSessionPersistent,
  loadSessionAuto,
  clearAllSessions,
  promoteLegacySession,
} from './session-manager';

const SESSION = { token: 'token-abc-123', userId: 'user-42' };

function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find(c => c.startsWith(`${name}=`));
  const value = match?.split('=')[1];
  return value ? decodeURIComponent(value) : null;
}

describe('session-manager', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    clearAllSessions();
  });

  describe('persistent storage (Remember me / default)', () => {
    it('persists to localStorage so a reload still finds the session', () => {
      saveSessionPersistent(SESSION);

      // A reload re-reads from storage; localStorage is untouched by reloads.
      expect(loadSessionAuto()).toMatchObject(SESSION);
      expect(localStorage.getItem(STORAGE_KEYS.SESSION_PERSISTENT)).toBe(SESSION.token);
      expect(localStorage.getItem(STORAGE_KEYS.REMEMBER_ME)).toBe('true');
    });

    it('writes the auth token to a cookie for header-less stream/image requests', () => {
      saveSessionPersistent(SESSION);
      loadSessionAuto();
      expect(readCookie('yay_token')).toBe(SESSION.token);
    });

    it('survives an app version change (storage keys are version-independent)', () => {
      saveSessionPersistent(SESSION);

      // A frontend deploy only swaps cached JS/CSS assets; it never touches the
      // auth storage keys, which carry no version segment.
      const persistedKeys = Object.keys(localStorage);
      for (const key of persistedKeys) {
        expect(key).not.toMatch(/\d+\.\d+\.\d+|dev|version/i);
      }
      expect(loadSessionAuto()).toMatchObject(SESSION);
    });
  });

  describe('tab-scoped storage (Remember me unchecked)', () => {
    it('stores in sessionStorage and is readable within the same tab', () => {
      saveSession(SESSION);
      expect(loadSessionAuto()).toMatchObject(SESSION);
      expect(sessionStorage.getItem(STORAGE_KEYS.SESSION)).toBe(SESSION.token);
      // Not persisted to localStorage — would not survive a PWA restart.
      expect(localStorage.getItem(STORAGE_KEYS.SESSION_PERSISTENT)).toBeNull();
    });
  });

  describe('loadSessionAuto metadata', () => {
    it('flags persistent sessions', () => {
      saveSessionPersistent(SESSION);
      expect(loadSessionAuto()).toMatchObject({ persistent: true, explicitTabScope: false });
    });

    it('flags an explicit tab-scoped session (Remember me unchecked)', () => {
      saveSession(SESSION);
      expect(loadSessionAuto()).toMatchObject({ persistent: false, explicitTabScope: true });
    });

    it('treats a legacy sessionStorage session (no scope marker) as non-explicit', () => {
      // Emulate an older app version that wrote only the raw sessionStorage keys.
      sessionStorage.setItem(STORAGE_KEYS.SESSION, SESSION.token);
      sessionStorage.setItem(STORAGE_KEYS.USER_ID, SESSION.userId);
      expect(loadSessionAuto()).toMatchObject({ persistent: false, explicitTabScope: false });
    });
  });

  describe('promoteLegacySession (migration from older versions)', () => {
    it('promotes a legacy tab-scoped session into persistent storage', () => {
      sessionStorage.setItem(STORAGE_KEYS.SESSION, SESSION.token);
      sessionStorage.setItem(STORAGE_KEYS.USER_ID, SESSION.userId);

      const loaded = loadSessionAuto()!;
      const promoted = promoteLegacySession(loaded);

      expect(promoted).toBe(true);
      expect(localStorage.getItem(STORAGE_KEYS.SESSION_PERSISTENT)).toBe(SESSION.token);
      expect(localStorage.getItem(STORAGE_KEYS.REMEMBER_ME)).toBe('true');
      // The old tab-scoped copy is cleared; persistent now wins.
      expect(sessionStorage.getItem(STORAGE_KEYS.SESSION)).toBeNull();
      expect(loadSessionAuto()).toMatchObject({ ...SESSION, persistent: true });
    });

    it('does NOT promote an explicit tab-scoped session', () => {
      saveSession(SESSION);
      const loaded = loadSessionAuto()!;

      expect(promoteLegacySession(loaded)).toBe(false);
      expect(localStorage.getItem(STORAGE_KEYS.SESSION_PERSISTENT)).toBeNull();
    });

    it('is a no-op for an already-persistent session', () => {
      saveSessionPersistent(SESSION);
      const loaded = loadSessionAuto()!;
      expect(promoteLegacySession(loaded)).toBe(false);
    });
  });

  describe('precedence', () => {
    it('prefers the persistent session over a tab-scoped one', () => {
      saveSession({ token: 'tab-token', userId: 'tab-user' });
      saveSessionPersistent(SESSION);
      expect(loadSessionAuto()).toMatchObject(SESSION);
    });
  });

  describe('clearAllSessions', () => {
    it('removes every trace of the session from all stores', () => {
      saveSessionPersistent(SESSION);
      saveSession(SESSION);

      clearAllSessions();

      expect(loadSessionAuto()).toBeNull();
      expect(localStorage.getItem(STORAGE_KEYS.SESSION_PERSISTENT)).toBeNull();
      expect(localStorage.getItem(STORAGE_KEYS.REMEMBER_ME)).toBeNull();
      expect(sessionStorage.getItem(STORAGE_KEYS.SESSION)).toBeNull();
      expect(sessionStorage.getItem('yaytsa_session_scope')).toBeNull();
      expect(readCookie('yay_token')).toBeNull();
    });
  });
});
