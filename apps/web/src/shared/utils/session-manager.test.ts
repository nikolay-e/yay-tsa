import { describe, it, expect, beforeEach } from 'vitest';
import { STORAGE_KEYS } from '@yay-tsa/core';
import {
  saveSession,
  saveSessionPersistent,
  loadSessionAuto,
  clearAllSessions,
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
      expect(loadSessionAuto()).toEqual(SESSION);
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
      expect(loadSessionAuto()).toEqual(SESSION);
    });
  });

  describe('tab-scoped storage (Remember me unchecked)', () => {
    it('stores in sessionStorage and is readable within the same tab', () => {
      saveSession(SESSION);
      expect(loadSessionAuto()).toEqual(SESSION);
      expect(sessionStorage.getItem(STORAGE_KEYS.SESSION)).toBe(SESSION.token);
      // Not persisted to localStorage — would not survive a PWA restart.
      expect(localStorage.getItem(STORAGE_KEYS.SESSION_PERSISTENT)).toBeNull();
    });
  });

  describe('precedence', () => {
    it('prefers the persistent session over a tab-scoped one', () => {
      saveSession({ token: 'tab-token', userId: 'tab-user' });
      saveSessionPersistent(SESSION);
      expect(loadSessionAuto()).toEqual(SESSION);
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
      expect(readCookie('yay_token')).toBeNull();
    });
  });
});
