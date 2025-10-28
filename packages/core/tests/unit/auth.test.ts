import { describe, it, expect } from 'vitest';
import { validateServerUrl } from '../../src/api/auth.js';

describe('validateServerUrl', () => {
  it('should accept HTTPS URLs', () => {
    expect(() => validateServerUrl('https://demo.jellyfin.org')).not.toThrow();
  });

  it('should accept HTTP localhost in development', () => {
    expect(() => validateServerUrl('http://localhost:8096', true)).not.toThrow();
    expect(() => validateServerUrl('http://127.0.0.1:8096', true)).not.toThrow();
  });

  it('should reject HTTP in production', () => {
    expect(() => validateServerUrl('http://demo.jellyfin.org', false)).toThrow();
  });

  it('should reject HTTP non-localhost in development', () => {
    expect(() => validateServerUrl('http://demo.jellyfin.org', true)).toThrow();
  });

  it('should reject invalid URLs', () => {
    expect(() => validateServerUrl('not-a-url')).toThrow('Invalid server URL');
  });

  it('should default to production mode', () => {
    expect(() => validateServerUrl('http://example.com')).toThrow();
  });
});
