/**
 * E2E Authentication Tests
 * Tests authentication flows against real Media Server
 */

import { describe, it, expect, beforeAll } from 'vitest';
import { MediaServerClient } from '../../src/api/client.js';
import { AuthService } from '../../src/api/auth.js';
import { loadTestConfig, delay, retryableLogin, AUTH_DELAY } from './setup.js';
import type { ClientInfo } from '../../src/models/types.js';

describe('E2E: Authentication', () => {
  let config: ReturnType<typeof loadTestConfig>;
  let client: MediaServerClient;
  let authService: AuthService;

  const clientInfo: ClientInfo = {
    name: 'Media Server Client E2E Tests',
    device: 'Test Runner',
    deviceId: 'e2e-test-device-id',
    version: '0.1.0-test',
  };

  beforeAll(() => {
    config = loadTestConfig();
    client = new MediaServerClient(config.serverUrl, clientInfo);
    authService = new AuthService(client);
  });

  it('should successfully authenticate with valid credentials', async () => {
    const response = await retryableLogin(
      () => authService.login(config.username, config.password),
      'Initial authentication'
    );

    expect(response).toBeDefined();
    expect(response.AccessToken).toBeDefined();
    expect(response.AccessToken).toMatch(/^[a-f0-9]{32}$/);
    expect(response.User).toBeDefined();
    expect(response.User.Id).toBeDefined();
    expect(response.User.Name).toBe(config.username);

    await delay(AUTH_DELAY);
  });

  it('should set token on client after successful login', async () => {
    const response = await retryableLogin(
      () => authService.login(config.username, config.password),
      'Token verification login'
    );

    expect(client.getToken()).toBe(response.AccessToken);
    expect(client.getUserId()).toBe(response.User.Id);

    await delay(AUTH_DELAY);
  });

  it('should get server info without authentication', async () => {
    const freshClient = new MediaServerClient(config.serverUrl, clientInfo);
    const serverInfo = await freshClient.getServerInfo();

    expect(serverInfo).toBeDefined();
    expect(serverInfo.Id).toBeDefined();
    expect(serverInfo.ServerName).toBeDefined();
    expect(serverInfo.Version).toBeDefined();
  });

  it('should fail authentication with invalid credentials', async () => {
    await delay(AUTH_DELAY); // Delay even before failed auth to avoid server load

    const freshClient = new MediaServerClient(config.serverUrl, clientInfo);
    const freshAuthService = new AuthService(freshClient);

    await expect(freshAuthService.login('invalid-user', 'invalid-password')).rejects.toThrow();
  });

  it('should logout successfully', async () => {
    await delay(AUTH_DELAY);

    await retryableLogin(
      () => authService.login(config.username, config.password),
      'Logout test login'
    );
    await expect(authService.logout()).resolves.not.toThrow();

    await delay(AUTH_DELAY);
  });

  it('should validate HTTPS server URL in production mode', async () => {
    const freshClient = new MediaServerClient(config.serverUrl, clientInfo);
    const freshAuthService = new AuthService(freshClient);

    const response = await retryableLogin(
      () => freshAuthService.login(config.username, config.password),
      'HTTPS validation login'
    );

    expect(response).toBeDefined();

    await delay(AUTH_DELAY);
  });
});
