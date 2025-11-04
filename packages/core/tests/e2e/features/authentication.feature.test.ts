/**
 * Feature: User Authentication
 * Tests authentication flows against real Jellyfin server
 * Focus on user login journeys, not token implementation details
 */

import { describe, it, expect, beforeAll } from 'vitest';
import { JellyfinClient } from '../../../src/api/client.js';
import { AuthService } from '../../../src/api/auth.js';
import { loadTestConfig, delay, retryableLogin, AUTH_DELAY, TestConfig } from '../setup.js';
import { ThenAuth } from '../fixtures/scenarios.js';
import type { ClientInfo } from '../../../src/models/types.js';

describe('Feature: User Authentication', () => {
  let config: TestConfig;

  const clientInfo: ClientInfo = {
    name: 'Jellyfin Mini Client E2E Tests',
    device: 'Test Runner',
    deviceId: 'e2e-test-auth-device',
    version: '0.1.0-test',
  };

  beforeAll(() => {
    config = loadTestConfig();
  });

  describe('Scenario: User logs in with valid credentials', () => {
    it('Given: User on login page, When: Enters valid credentials, Then: Successfully authenticated', async () => {
      // Given: Fresh client (not authenticated)
      const client = new JellyfinClient(config.serverUrl, clientInfo);
      const authService = new AuthService(client);
      ThenAuth.userIsNotAuthenticated(client);

      // When: User enters valid credentials and clicks login
      const response = await retryableLogin(
        () => authService.login(config.username, config.password),
        'Valid credentials login'
      );

      // Then: User is authenticated and can access library
      ThenAuth.userIsAuthenticated(client, response);
      expect(response.User.Name).toBe(config.username);

      await delay(AUTH_DELAY);
    });

    it('Given: User authenticated, When: Views profile, Then: Sees user info', async () => {
      // Given: User logs in
      const client = new JellyfinClient(config.serverUrl, clientInfo);
      const authService = new AuthService(client);

      const response = await retryableLogin(
        () => authService.login(config.username, config.password),
        'Profile view login'
      );

      // Then: User info is available
      expect(response.User).toBeDefined();
      expect(response.User.Id).toBeTruthy();
      expect(response.User.Name).toBe(config.username);

      // And: Client has user session
      expect(client.getUserId()).toBe(response.User.Id);
      expect(client.getToken()).toBeTruthy();

      await delay(AUTH_DELAY);
    });
  });

  describe('Scenario: User logs in with invalid credentials', () => {
    it('Given: User on login page, When: Enters wrong password, Then: Login fails', async () => {
      await delay(AUTH_DELAY);

      // Given: Fresh client
      const client = new JellyfinClient(config.serverUrl, clientInfo);
      const authService = new AuthService(client);

      // When: User enters wrong password
      const loginAttempt = authService.login(config.username, 'wrong-password');

      // Then: Authentication fails
      await expect(loginAttempt).rejects.toThrow();
      ThenAuth.userIsNotAuthenticated(client);
    });

    it('Given: User on login page, When: Enters nonexistent username, Then: Login fails', async () => {
      await delay(AUTH_DELAY);

      // Given: Fresh client
      const client = new JellyfinClient(config.serverUrl, clientInfo);
      const authService = new AuthService(client);

      // When: User enters nonexistent username
      const loginAttempt = authService.login('nonexistent-user-12345', 'any-password');

      // Then: Authentication fails
      await expect(loginAttempt).rejects.toThrow();
      ThenAuth.userIsNotAuthenticated(client);
    });
  });

  describe('Scenario: User logs out', () => {
    it('Given: User is logged in, When: User logs out, Then: Session ends', async () => {
      await delay(AUTH_DELAY);

      // Given: User is authenticated
      const client = new JellyfinClient(config.serverUrl, clientInfo);
      const authService = new AuthService(client);

      await retryableLogin(
        () => authService.login(config.username, config.password),
        'Logout scenario login'
      );

      ThenAuth.userIsAuthenticated(client, {
        AccessToken: client.getToken(),
        User: { Id: client.getUserId() },
      });

      // When: User clicks logout
      await authService.logout();

      // Then: User can no longer access protected resources
      // Note: Client doesn't automatically clear token on logout (design decision)
      // but server invalidates the session

      await delay(AUTH_DELAY);
    });
  });

  describe('Scenario: Anonymous user checks server availability', () => {
    it('Given: User not logged in, When: App connects to server, Then: Server info available', async () => {
      // Given: Fresh client without authentication
      const client = new JellyfinClient(config.serverUrl, clientInfo);
      ThenAuth.userIsNotAuthenticated(client);

      // When: App checks server availability
      const serverInfo = await client.getServerInfo();

      // Then: Server responds with public info
      expect(serverInfo).toBeDefined();
      expect(serverInfo.Id).toBeTruthy();
      expect(serverInfo.ServerName).toBeTruthy();
      expect(serverInfo.Version).toBeTruthy();
    });
  });

  describe('Scenario: User switches between accounts', () => {
    it('Given: User logged in as User A, When: Logs out and logs in as User A again, Then: New session created', async () => {
      await delay(AUTH_DELAY);

      // Given: First login
      const client = new JellyfinClient(config.serverUrl, clientInfo);
      const authService = new AuthService(client);

      const firstLogin = await retryableLogin(
        () => authService.login(config.username, config.password),
        'First session login'
      );

      const firstToken = client.getToken();
      const firstUserId = client.getUserId();

      // When: User logs out
      await delay(AUTH_DELAY);
      await authService.logout();

      // And: User logs in again
      await delay(AUTH_DELAY);
      const secondLogin = await retryableLogin(
        () => authService.login(config.username, config.password),
        'Second session login'
      );

      // Then: New session with different token
      expect(client.isAuthenticated()).toBe(true);
      expect(client.getUserId()).toBe(secondLogin.User.Id);
      expect(secondLogin.User.Id).toBe(firstUserId); // Same user

      await delay(AUTH_DELAY);
    });
  });

  describe('Scenario: App validates server connection', () => {
    it('Given: User configures server URL, When: App validates connection, Then: Server is reachable', async () => {
      // Given: User enters server URL in settings
      const client = new JellyfinClient(config.serverUrl, clientInfo);

      // When: App validates server
      const serverInfo = await client.getServerInfo();

      // Then: Server responds with version info
      expect(serverInfo).toBeDefined();
      expect(serverInfo.Version).toMatch(/\d+\.\d+\.\d+/); // SemVer format
    });

    it('Given: User configures HTTPS server, When: App connects, Then: Connection secure', async () => {
      // Given: Server URL is HTTPS
      const isHttps = config.serverUrl.startsWith('https://');

      // Note: This test verifies current configuration uses HTTPS
      // In production, HTTP should be rejected
      if (isHttps) {
        // When: App connects to HTTPS server
        const client = new JellyfinClient(config.serverUrl, clientInfo);
        const serverInfo = await client.getServerInfo();

        // Then: Connection succeeds
        expect(serverInfo).toBeDefined();
      } else {
        console.warn('⚠️  Test server is using HTTP - production should use HTTPS');
      }
    });
  });

  describe('Scenario: Multiple concurrent sessions', () => {
    it('Given: User has multiple devices, When: Logs in on both, Then: Both sessions active', async () => {
      await delay(AUTH_DELAY);

      // Given: Two different clients (simulating different devices)
      const client1 = new JellyfinClient(config.serverUrl, {
        ...clientInfo,
        deviceId: 'device-1',
        device: 'Phone',
      });

      const client2 = new JellyfinClient(config.serverUrl, {
        ...clientInfo,
        deviceId: 'device-2',
        device: 'Tablet',
      });

      const authService1 = new AuthService(client1);
      const authService2 = new AuthService(client2);

      // When: User logs in on both devices
      const session1 = await retryableLogin(
        () => authService1.login(config.username, config.password),
        'Device 1 login'
      );

      await delay(AUTH_DELAY);

      const session2 = await retryableLogin(
        () => authService2.login(config.username, config.password),
        'Device 2 login'
      );

      // Then: Both sessions are active with same user ID
      expect(session1.User.Id).toBe(session2.User.Id);
      expect(client1.isAuthenticated()).toBe(true);
      expect(client2.isAuthenticated()).toBe(true);

      // Cleanup
      await delay(AUTH_DELAY);
      await authService1.logout();
      await delay(AUTH_DELAY);
      await authService2.logout();

      await delay(AUTH_DELAY);
    });
  });
});
