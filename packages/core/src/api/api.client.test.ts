import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { MediaServerClient } from './api.client.js';
import { AuthenticationError, MediaServerError, NetworkError } from '../internal/models/types.js';

const SERVER_URL = 'https://music.example.com';
const CLIENT_INFO = {
  name: 'TestApp',
  device: 'TestDevice',
  deviceId: 'test-device-123',
  version: '1.0.0',
};

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function emptyResponse(status = 204): Response {
  return new Response(null, {
    status,
    headers: { 'content-length': '0' },
  });
}

function errorResponse(status: number, message = 'Error'): Response {
  return new Response(JSON.stringify({ message }), {
    status,
    statusText: message,
    headers: { 'content-type': 'application/json' },
  });
}

describe('MediaServerClient', () => {
  let client: MediaServerClient;
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    client = new MediaServerClient(SERVER_URL, CLIENT_INFO);
    fetchSpy = vi.spyOn(globalThis, 'fetch');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('successful GET returns parsed response', async () => {
    const data = { Items: [], TotalRecordCount: 0 };
    fetchSpy.mockResolvedValueOnce(jsonResponse(data));

    const result = await client.get('/Items');
    expect(result).toEqual(data);

    const [url, options] = fetchSpy.mock.calls[0];
    expect(url).toBe(`${SERVER_URL}/Items`);
    expect((options as RequestInit).method).toBe('GET');
  });

  it('GET includes auth header and query params', async () => {
    client.setToken('my-token', 'user-123');
    fetchSpy.mockResolvedValueOnce(jsonResponse({ ok: true }));

    await client.get('/Items', { Limit: 10, Recursive: true });

    const [url, options] = fetchSpy.mock.calls[0];
    expect(url).toContain('Limit=10');
    expect(url).toContain('Recursive=true');

    const headers = (options as RequestInit).headers as Record<string, string>;
    expect(headers['X-Emby-Authorization']).toContain('Token="my-token"');
    expect(headers['X-Emby-Token']).toBe('my-token');
  });

  it('POST sends JSON body', async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse({ AccessToken: 'tok' }));

    await client.post('/Auth', { Username: 'admin', Pw: 'pass' });

    const [, options] = fetchSpy.mock.calls[0];
    expect((options as RequestInit).method).toBe('POST');
    expect((options as RequestInit).body).toBe('{"Username":"admin","Pw":"pass"}');
  });

  it('401 throws AuthenticationError and invokes callback', async () => {
    const callback = vi.fn();
    client.setAuthErrorCallback(callback);
    fetchSpy.mockResolvedValueOnce(errorResponse(401, 'Unauthorized'));

    await expect(client.get('/Protected')).rejects.toThrow(AuthenticationError);
    expect(callback).toHaveBeenCalledOnce();
  });

  it('403 throws AuthenticationError without callback', async () => {
    const callback = vi.fn();
    client.setAuthErrorCallback(callback);
    fetchSpy.mockResolvedValueOnce(errorResponse(403, 'Forbidden'));

    await expect(client.get('/Admin')).rejects.toThrow(AuthenticationError);
    expect(callback).not.toHaveBeenCalled();
  });

  it('404 throws MediaServerError with status 404', async () => {
    fetchSpy.mockResolvedValueOnce(errorResponse(404, 'Not Found'));

    try {
      await client.get('/Missing');
      expect.unreachable('should throw');
    } catch (err) {
      expect(err).toBeInstanceOf(MediaServerError);
      expect((err as MediaServerError).statusCode).toBe(404);
    }
  });

  it('network error throws NetworkError', async () => {
    fetchSpy.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(client.request('/Offline', { method: 'POST' }, 0)).rejects.toThrow(NetworkError);
  });

  it('idempotent GET retries on 502', async () => {
    fetchSpy
      .mockResolvedValueOnce(errorResponse(502, 'Bad Gateway'))
      .mockResolvedValueOnce(jsonResponse({ ok: true }));

    const result = await client.get('/Flaky');
    expect(result).toEqual({ ok: true });
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it('non-idempotent POST does not retry on 500', async () => {
    fetchSpy.mockResolvedValueOnce(errorResponse(500, 'Server Error'));

    await expect(client.post('/Action', { data: 1 })).rejects.toThrow(MediaServerError);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('getStreamUrl includes api_key and deviceId', () => {
    client.setToken('stream-token', 'user-1');
    const url = client.getStreamUrl('item-42');
    expect(url).toBe(
      `${SERVER_URL}/Audio/item-42/stream?api_key=stream-token&deviceId=test-device-123`
    );
  });

  it('getStreamUrl throws when not authenticated', () => {
    expect(() => client.getStreamUrl('item-1')).toThrow(AuthenticationError);
  });

  it('getImageUrl builds correct path with params', () => {
    client.setToken('img-token');
    const url = client.getImageUrl('item-1', 'Primary', { maxWidth: 300, quality: 90 });
    expect(url).toContain(`${SERVER_URL}/Items/item-1/Images/Primary`);
    expect(url).toContain('api_key=img-token');
    expect(url).toContain('maxWidth=300');
    expect(url).toContain('quality=90');
  });

  it('getImageUrl returns empty string for empty itemId', () => {
    expect(client.getImageUrl('')).toBe('');
    expect(client.getImageUrl('  ')).toBe('');
  });

  it('204 response returns undefined', async () => {
    fetchSpy.mockResolvedValueOnce(emptyResponse(204));
    const result = await client.get('/NoContent');
    expect(result).toBeUndefined();
  });

  it('buildAuthHeader format is Emby-compatible PascalCase', () => {
    client.setToken('abc');
    const header = client.buildAuthHeader();
    expect(header).toContain('MediaBrowser Client="TestApp"');
    expect(header).toContain('Device="TestDevice"');
    expect(header).toContain('DeviceId="test-device-123"');
    expect(header).toContain('Version="1.0.0"');
    expect(header).toContain('Token="abc"');
  });

  it('requireAuth throws when not authenticated', () => {
    expect(() => client.requireAuth()).toThrow(AuthenticationError);
  });

  it('requireAuth returns userId when authenticated', () => {
    client.setToken('tok', 'user-1');
    expect(client.requireAuth()).toBe('user-1');
  });
});
