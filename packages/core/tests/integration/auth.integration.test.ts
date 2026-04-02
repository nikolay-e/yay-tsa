/**
 * Integration Authentication Tests
 * Tests authentication flows against real Media Server
 */

import { describe, it, expect, beforeAll } from 'vitest';
import { MediaServerClient } from '../../src/api/api.client.js';
import { AuthService } from '../../src/api/auth.service.js';
import { loadTestConfig, delay, retryableLogin, AUTH_DELAY } from './setup.js';
import type { ClientInfo } from '../../src/index.js';

describe('Integration: Authentication', () => {
  let config: ReturnType<typeof loadTestConfig>;
  let client: MediaServerClient;
  let authService: AuthService;

  const clientInfo: ClientInfo = {
    name: 'Media Server Client Integration Tests',
    device: 'Test Runner',
    deviceId: 'integration-test-device-id',
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
    expect(response.AccessToken).toMatch(/^[a-f0-9]{64}$/);
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

  it('should authenticate with a fresh client instance', async () => {
    const freshClient = new MediaServerClient(config.serverUrl, clientInfo);
    const freshAuthService = new AuthService(freshClient);

    const response = await retryableLogin(
      () => freshAuthService.login(config.username, config.password),
      'Fresh client login'
    );

    expect(response).toBeDefined();
    expect(response.AccessToken).toBeDefined();
    expect(freshClient.isAuthenticated()).toBe(true);

    await delay(AUTH_DELAY);
  });

  it('should support concurrent sessions from different devices', async () => {
    await delay(AUTH_DELAY);

    const client1 = new MediaServerClient(config.serverUrl, {
      ...clientInfo,
      deviceId: 'device-1',
      device: 'Phone',
    });
    const client2 = new MediaServerClient(config.serverUrl, {
      ...clientInfo,
      deviceId: 'device-2',
      device: 'Tablet',
    });

    const authService1 = new AuthService(client1);
    const authService2 = new AuthService(client2);

    const session1 = await retryableLogin(
      () => authService1.login(config.username, config.password),
      'Device 1 login'
    );
    await delay(AUTH_DELAY);

    const session2 = await retryableLogin(
      () => authService2.login(config.username, config.password),
      'Device 2 login'
    );

    expect(session1.User.Id).toBe(session2.User.Id);
    expect(client1.isAuthenticated()).toBe(true);
    expect(client2.isAuthenticated()).toBe(true);

    await delay(AUTH_DELAY);
    await authService1.logout();
    await delay(AUTH_DELAY);
    await authService2.logout();
    await delay(AUTH_DELAY);
  });

  it('should create new session on re-login after logout', async () => {
    await delay(AUTH_DELAY);

    const freshClient = new MediaServerClient(config.serverUrl, clientInfo);
    const freshAuthService = new AuthService(freshClient);

    await retryableLogin(
      () => freshAuthService.login(config.username, config.password),
      'First session login'
    );
    const firstUserId = freshClient.getUserId();

    await delay(AUTH_DELAY);
    await freshAuthService.logout();
    await delay(AUTH_DELAY);

    const secondLogin = await retryableLogin(
      () => freshAuthService.login(config.username, config.password),
      'Second session login'
    );

    expect(freshClient.isAuthenticated()).toBe(true);
    expect(secondLogin.User.Id).toBe(firstUserId);
    await delay(AUTH_DELAY);
  });
});
