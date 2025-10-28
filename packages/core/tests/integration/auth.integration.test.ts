/**
 * Integration Tests: Authentication
 * Tests real authentication against a Jellyfin server using API key
 */

import { describe, it, expect, beforeAll } from 'vitest';
import { JellyfinClient } from '../../src/api/client.js';
import { AuthService } from '../../src/api/auth.js';
import { integrationConfig } from './setup.js';
import type { AuthResponse } from '../../src/models/types.js';

describe('Authentication Integration', () => {
  let client: JellyfinClient;
  let authService: AuthService;

  beforeAll(() => {
    client = new JellyfinClient(integrationConfig.serverUrl, {
      name: integrationConfig.clientName,
      device: integrationConfig.deviceName,
      deviceId: integrationConfig.deviceId,
      version: '0.1.0',
    });
    authService = new AuthService(client);
  });

  describe('API Key Authentication', () => {
    it.skipIf(!integrationConfig.useApiKey)('should initialize with API key', async () => {
      // Initialize with API key from environment
      await client.initWithApiKey(integrationConfig.apiKey);

      // Should have token and userId set
      expect(client.getToken()).toBe(integrationConfig.apiKey);
      expect(client.getUserId()).toBeTruthy();
    });

    it.skipIf(!integrationConfig.useApiKey)(
      'should validate session with existing API key',
      async () => {
        // Initialize with API key
        await client.initWithApiKey(integrationConfig.apiKey);

        // Validate the session
        const isValid = await authService.validateSession();

        expect(isValid).toBe(true);
      }
    );

    it.skipIf(!integrationConfig.useApiKey)(
      'should make authenticated requests with API key',
      async () => {
        await client.initWithApiKey(integrationConfig.apiKey);

        // Test authenticated request - get system info
        const response = await client.get('/System/Info');

        expect(response).toBeDefined();
        expect(response.ServerName).toBeDefined();
        expect(response.Version).toBeDefined();
        expect(response.Id).toBeDefined();
      }
    );

    it.skipIf(!integrationConfig.useApiKey)('should include auth header in requests', async () => {
      await client.initWithApiKey(integrationConfig.apiKey);

      const header = client.buildAuthHeader();

      expect(header).toContain('MediaBrowser');
      expect(header).toContain(`Client="${integrationConfig.clientName}"`);
      expect(header).toContain(`Device="${integrationConfig.deviceName}"`);
      expect(header).toContain(`DeviceId="${integrationConfig.deviceId}"`);
      expect(header).toContain(`Token="${integrationConfig.apiKey}"`);
    });
  });

  describe('Server Connection', () => {
    it('should connect to server and get public info', async () => {
      // Public endpoint - no auth needed
      const response = await client.get('/System/Info/Public');

      expect(response).toBeDefined();
      expect(response.ServerName).toBeDefined();
      expect(response.Version).toBeDefined();
    });

    it.skipIf(!integrationConfig.useApiKey)('should get server configuration', async () => {
      await client.initWithApiKey(integrationConfig.apiKey);

      const response = await client.get('/System/Configuration');

      expect(response).toBeDefined();
      expect(response.EnableMetrics).toBeDefined();
    });
  });

  describe('User Information', () => {
    it.skipIf(!integrationConfig.useApiKey)('should get current user information', async () => {
      await client.initWithApiKey(integrationConfig.apiKey);

      // Get users endpoint
      const users = await client.get('/Users');

      expect(users).toBeDefined();
      expect(Array.isArray(users)).toBe(true);
      expect(users.length).toBeGreaterThan(0);

      const user = users[0];
      expect(user.Id).toBeDefined();
      expect(user.Name).toBeDefined();
    });

    it.skipIf(!integrationConfig.useApiKey)('should get user library access', async () => {
      await client.initWithApiKey(integrationConfig.apiKey);

      const users = await client.get('/Users');
      const userId = users[0].Id;

      const views = await client.get(`/Users/${userId}/Views`);

      expect(views).toBeDefined();
      expect(views.Items).toBeDefined();
      expect(Array.isArray(views.Items)).toBe(true);
    });
  });

  describe('Error Handling', () => {
    it.skipIf(!integrationConfig.useApiKey)('should fail with invalid API key', async () => {
      const invalidClient = new JellyfinClient(integrationConfig.serverUrl, {
        name: integrationConfig.clientName,
        device: integrationConfig.deviceName,
        deviceId: integrationConfig.deviceId + '-invalid',
        version: '0.1.0',
      });

      // Try to init with invalid key (should fail)
      try {
        await invalidClient.initWithApiKey('invalid-api-key-12345678901234567890');
      } catch (error) {
        // Expected to fail
      }
      const invalidAuthService = new AuthService(invalidClient);

      const isValid = await invalidAuthService.validateSession();

      expect(isValid).toBe(false);
    });

    it('should handle network errors gracefully', async () => {
      const offlineClient = new JellyfinClient('http://invalid-server-that-does-not-exist:9999', {
        name: integrationConfig.clientName,
        device: integrationConfig.deviceName,
        deviceId: integrationConfig.deviceId,
        version: '0.1.0',
      });

      await expect(offlineClient.get('/System/Info/Public')).rejects.toThrow();
    });
  });
});
