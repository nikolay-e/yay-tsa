import { describe, it, expect, beforeEach, vi } from 'vitest';
import { JellyfinClient } from '../src/api/client.js';
import { ClientInfo, AuthenticationError, JellyfinError, NetworkError } from '../src/models/types.js';

// Mock global fetch
global.fetch = vi.fn();

describe('JellyfinClient', () => {
  let client: JellyfinClient;
  let clientInfo: ClientInfo;

  beforeEach(() => {
    clientInfo = {
      name: 'Jellyfin Mini Test',
      device: 'Test Device',
      deviceId: 'test-device-id',
      version: '1.0.0'
    };

    client = new JellyfinClient('https://demo.jellyfin.org', clientInfo);

    vi.clearAllMocks();
  });

  describe('Initialization', () => {
    it('should normalize server URL', () => {
      const clientWithSlash = new JellyfinClient('https://demo.jellyfin.org/', clientInfo);

      expect(clientWithSlash.getConfig().serverUrl).toBe('https://demo.jellyfin.org');
    });

    it('should start without authentication', () => {
      expect(client.isAuthenticated()).toBe(false);
      expect(client.getToken()).toBeNull();
      expect(client.getUserId()).toBeNull();
    });
  });

  describe('Auth header building', () => {
    it('should build auth header without token', () => {
      const header = client.buildAuthHeader();

      expect(header).toContain('MediaBrowser');
      expect(header).toContain('Client="Jellyfin Mini Test"');
      expect(header).toContain('Device="Test Device"');
      expect(header).toContain('DeviceId="test-device-id"');
      expect(header).toContain('Version="1.0.0"');
      expect(header).not.toContain('Token=');
    });

    it('should build auth header with token', () => {
      client.setToken('test-token', 'user-123');

      const header = client.buildAuthHeader();

      expect(header).toContain('Token="test-token"');
    });

    it('should build auth header with provided token', () => {
      const header = client.buildAuthHeader('custom-token');

      expect(header).toContain('Token="custom-token"');
    });
  });

  describe('Token management', () => {
    it('should set and get token', () => {
      client.setToken('test-token', 'user-123');

      expect(client.getToken()).toBe('test-token');
      expect(client.getUserId()).toBe('user-123');
      expect(client.isAuthenticated()).toBe(true);
    });

    it('should clear token', () => {
      client.setToken('test-token', 'user-123');
      client.clearToken();

      expect(client.getToken()).toBeNull();
      expect(client.getUserId()).toBeNull();
      expect(client.isAuthenticated()).toBe(false);
    });
  });

  describe('HTTP requests', () => {
    it('should make GET request', async () => {
      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({ data: 'test' })
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      const result = await client.get('/test');

      expect(result).toEqual({ data: 'test' });
      expect(global.fetch).toHaveBeenCalledWith(
        'https://demo.jellyfin.org/test',
        expect.objectContaining({
          method: 'GET'
        })
      );
    });

    it('should make GET request with params', async () => {
      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({ data: 'test' })
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await client.get('/test', { param1: 'value1', param2: 'value2' });

      expect(global.fetch).toHaveBeenCalledWith(
        'https://demo.jellyfin.org/test?param1=value1&param2=value2',
        expect.any(Object)
      );
    });

    it('should handle array params', async () => {
      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({})
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await client.get('/test', { ids: ['1', '2', '3'] });

      expect(global.fetch).toHaveBeenCalledWith(
        'https://demo.jellyfin.org/test?ids=1%2C2%2C3',
        expect.any(Object)
      );
    });

    it('should skip null and undefined params', async () => {
      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({})
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await client.get('/test', { a: 'value', b: null, c: undefined });

      expect(global.fetch).toHaveBeenCalledWith(
        'https://demo.jellyfin.org/test?a=value',
        expect.any(Object)
      );
    });

    it('should make POST request', async () => {
      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({ result: 'success' })
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      const result = await client.post('/test', { data: 'value' });

      expect(result).toEqual({ result: 'success' });
      expect(global.fetch).toHaveBeenCalledWith(
        'https://demo.jellyfin.org/test',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ data: 'value' })
        })
      );
    });

    it('should make DELETE request', async () => {
      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({})
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await client.delete('/test');

      expect(global.fetch).toHaveBeenCalledWith(
        'https://demo.jellyfin.org/test',
        expect.objectContaining({
          method: 'DELETE'
        })
      );
    });

    it('should include auth headers', async () => {
      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({})
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await client.get('/test');

      expect(global.fetch).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({
          headers: expect.objectContaining({
            'X-Emby-Authorization': expect.stringContaining('MediaBrowser')
          })
        })
      );
    });

    it('should include token header when authenticated', async () => {
      client.setToken('test-token', 'user-123');

      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({})
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await client.get('/test');

      expect(global.fetch).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({
          headers: expect.objectContaining({
            'X-Emby-Token': 'test-token'
          })
        })
      );
    });
  });

  describe('Error handling', () => {
    it('should throw AuthenticationError on 401', async () => {
      const mockResponse = {
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        json: () => Promise.resolve({ message: 'Invalid token' })
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await expect(client.get('/test')).rejects.toThrow(AuthenticationError);
    });

    it('should throw AuthenticationError on 403', async () => {
      const mockResponse = {
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: () => Promise.resolve({})
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await expect(client.get('/test')).rejects.toThrow(AuthenticationError);
    });

    it('should throw JellyfinError on other errors', async () => {
      const mockResponse = {
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: () => Promise.resolve({ message: 'Server error' })
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await expect(client.get('/test')).rejects.toThrow(JellyfinError);
    });

    it('should throw NetworkError on network failure', async () => {
      (global.fetch as any).mockRejectedValue(new Error('Network failure'));

      await expect(client.get('/test')).rejects.toThrow(NetworkError);
    });

    it('should handle error response without JSON', async () => {
      const mockResponse = {
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: () => Promise.reject(new Error('Not JSON'))
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      await expect(client.get('/test')).rejects.toThrow(JellyfinError);
    });
  });

  describe('URL building', () => {
    beforeEach(() => {
      client.setToken('test-token', 'user-123');
    });

    it('should build image URL', () => {
      const url = client.getImageUrl('item-123', 'Primary');

      expect(url).toBe('https://demo.jellyfin.org/Items/item-123/Images/Primary');
    });

    it('should build image URL with options', () => {
      const url = client.getImageUrl('item-123', 'Primary', {
        tag: 'abc123',
        maxWidth: 300,
        maxHeight: 300,
        quality: 90
      });

      expect(url).toContain('tag=abc123');
      expect(url).toContain('maxWidth=300');
      expect(url).toContain('maxHeight=300');
      expect(url).toContain('quality=90');
    });

    it('should build stream URL', () => {
      const url = client.getStreamUrl('item-123');

      expect(url).toContain('https://demo.jellyfin.org/Audio/item-123/stream');
      expect(url).toContain('api_key=test-token');
      expect(url).toContain('audioCodec=aac%2Cmp3%2Copus');
      expect(url).toContain('maxStreamingBitrate=320000');
    });

    it('should build stream URL with custom options', () => {
      const url = client.getStreamUrl('item-123', {
        audioCodec: 'mp3',
        maxStreamingBitrate: '128000',
        container: 'mp3'
      });

      expect(url).toContain('audioCodec=mp3');
      expect(url).toContain('maxStreamingBitrate=128000');
      expect(url).toContain('container=mp3');
    });

    it('should throw when building stream URL without auth', () => {
      client.clearToken();

      expect(() => client.getStreamUrl('item-123')).toThrow(AuthenticationError);
    });
  });

  describe('Server info', () => {
    it('should get server info', async () => {
      const mockResponse = {
        ok: true,
        json: () => Promise.resolve({ ServerName: 'Test Server' })
      };

      (global.fetch as any).mockResolvedValue(mockResponse);

      const info = await client.getServerInfo();

      expect(info).toEqual({ ServerName: 'Test Server' });
      expect(global.fetch).toHaveBeenCalledWith(
        'https://demo.jellyfin.org/System/Info/Public',
        expect.any(Object)
      );
    });
  });
});
