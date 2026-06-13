/**
 * HTTP Client for Media Server API
 * Handles authentication headers and token injection
 */

import {
  type ClientInfo,
  type ServerInfo,
  MediaServerError,
  NetworkError,
  AuthenticationError,
} from '../internal/models/types.js';
import { createLogger } from '../internal/utils/logger.js';
import type { KaraokeState } from '../generated/constants.js';

const log = createLogger('API');

export interface MediaServerClientOptions {
  requestTimeoutMs?: number;
}

const DEFAULT_REQUEST_TIMEOUT_MS = 30000;

export class MediaServerClient {
  private readonly serverUrl: string;
  private readonly clientInfo: ClientInfo;
  private readonly requestTimeoutMs: number;
  private token: string | null = null;
  private userId: string | null = null;

  // Retry configuration constants
  private readonly DEFAULT_RETRY_COUNT = 3;
  private readonly RETRY_BASE_DELAY_MS = 1000;
  private readonly MAX_BACKOFF_MS = 20000; // 20 seconds (AWS recommendation)
  private readonly GATEWAY_ERROR_MIN = 502;
  private readonly GATEWAY_ERROR_MAX = 504;
  private readonly SERVER_ERROR_STATUS = 500;

  // Global 401 error handler callback
  private authErrorCallback?: () => void;

  constructor(serverUrl: string, clientInfo: ClientInfo, options: MediaServerClientOptions = {}) {
    this.serverUrl = this.normalizeUrl(serverUrl);
    this.clientInfo = clientInfo;
    this.requestTimeoutMs = options.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS;
  }

  /**
   * Normalize server URL (remove trailing slash)
   */
  private normalizeUrl(url: string): string {
    if (!url || typeof url !== 'string') {
      throw new Error('Server URL is required and must be a string');
    }
    return url.replace(/\/$/, '');
  }

  getClientInfo(): ClientInfo {
    return this.clientInfo;
  }

  /**
   * Set authentication token
   */
  setToken(token: string, userId?: string): void {
    this.token = token;
    if (userId) {
      this.userId = userId;
    }
  }

  /**
   * Clear authentication token
   */
  clearToken(): void {
    this.token = null;
    this.userId = null;
  }

  /**
   * Get current token
   */
  getToken(): string | null {
    return this.token;
  }

  /**
   * Get current user ID
   */
  getUserId(): string | null {
    return this.userId;
  }

  getServerUrl(): string {
    return this.serverUrl;
  }

  /**
   * Fire-and-forget POST that survives page unload (visibilitychange→hidden / pagehide).
   * Uses fetch keepalive so the durable resume write still leaves the browser when the user
   * closes the tab or backgrounds the PWA instead of tapping pause. No retries — this is a
   * best-effort backstop behind the regular reporter, not the primary persistence path.
   */
  sendKeepAlive(endpoint: string, data: unknown): void {
    if (!this.token) return;
    try {
      void fetch(`${this.serverUrl}${endpoint}`, {
        method: 'POST',
        headers: this.buildRequestHeaders(undefined, true),
        body: JSON.stringify(data),
        keepalive: true,
        credentials: 'include',
      }).catch(() => {});
    } catch {
      // Some browsers reject keepalive over a size limit or during teardown — ignore.
    }
  }

  /**
   * Set callback for global 401 authentication errors
   * This callback will be invoked before throwing AuthenticationError
   * Use this for auto-logout or redirecting to login page
   * Note: 403 (forbidden) does NOT trigger this callback
   */
  setAuthErrorCallback(callback: () => void): void {
    this.authErrorCallback = callback;
  }

  /**
   * Clear authentication error callback
   */
  clearAuthErrorCallback(): void {
    this.authErrorCallback = undefined;
  }

  /**
   * Require authentication and return user ID
   * Throws AuthenticationError if not authenticated
   */
  requireAuth(): string {
    if (!this.userId) {
      throw new AuthenticationError('Not authenticated');
    }
    return this.userId;
  }

  /**
   * Build request headers with authentication
   */
  private buildRequestHeaders(
    additionalHeaders?: HeadersInit,
    includeContentType: boolean = false,
    idempotencyKey?: string
  ): Record<string, string> {
    const headers: Record<string, string> = {
      ...this.headersFromInit(additionalHeaders),
    };

    if (this.token) {
      headers['Authorization'] = `Bearer ${this.token}`;
    }

    if (idempotencyKey) {
      headers['X-Idempotency-Key'] = idempotencyKey;
    }
    const clientParts = [
      `Client="${this.clientInfo.name}"`,
      `Device="${this.clientInfo.device}"`,
      `DeviceId="${this.clientInfo.deviceId}"`,
      `Version="${this.clientInfo.version}"`,
    ];
    headers['X-Emby-Authorization'] = `MediaBrowser ${clientParts.join(', ')}`;

    const hasContentType = Object.keys(headers).some(k => k.toLowerCase() === 'content-type');
    if (includeContentType && !hasContentType) {
      headers['Content-Type'] = 'application/json';
    }

    return headers;
  }

  /**
   * Determine if request should be retried based on error type and method
   */
  private shouldRetryRequest(
    statusOrError: number | Error,
    isIdempotent: boolean,
    attempt: number,
    maxRetries: number
  ): boolean {
    if (attempt >= maxRetries) {
      return false;
    }

    // HTTP status code retry logic
    if (typeof statusOrError === 'number') {
      const isGatewayError =
        statusOrError >= this.GATEWAY_ERROR_MIN && statusOrError <= this.GATEWAY_ERROR_MAX;
      const is500AndIdempotent = statusOrError === this.SERVER_ERROR_STATUS && isIdempotent;
      return (isGatewayError && isIdempotent) || is500AndIdempotent;
    }

    // Network error retry logic (only for idempotent requests)
    return isIdempotent && !(statusOrError instanceof MediaServerError);
  }

  /**
   * Calculate exponential backoff delay with full jitter (AWS best practice)
   * Full jitter prevents thundering herd problem when multiple clients retry simultaneously
   */
  private calculateRetryDelay(attempt: number): number {
    const exponentialDelay = Math.pow(2, attempt) * this.RETRY_BASE_DELAY_MS;
    const maxDelay = Math.min(exponentialDelay, this.MAX_BACKOFF_MS);

    // Full jitter: random value between 0 and maxDelay (inclusive)
    return Math.floor(Math.random() * (maxDelay + 1));
  }

  /**
   * Parse response and handle empty responses
   */
  private async parseResponse<T>(response: Response): Promise<T | undefined> {
    const contentLength = response.headers.get('content-length');
    const contentType = response.headers.get('content-type');

    // If no content (204) or empty body, return undefined
    if (response.status === 204 || contentLength === '0' || !contentType?.includes('json')) {
      return undefined;
    }

    const data: T = (await response.json()) as T;
    return data;
  }

  /**
   * Make HTTP request to Media Server API
   * Returns undefined for 204 No Content or empty responses
   * Automatically retries requests with exponential backoff on network/gateway errors
   */
  async request<T>(
    endpoint: string,
    options: RequestInit = {},
    retries = this.DEFAULT_RETRY_COUNT
  ): Promise<T | undefined> {
    const url = `${this.serverUrl}${endpoint}`;
    const method = options.method ?? 'GET';
    const isIdempotent = method === 'GET' || method === 'DELETE';
    const hasBody = options.body !== undefined;
    const isMutating =
      method === 'POST' || method === 'PUT' || method === 'PATCH' || method === 'DELETE';
    const idempotencyKey = isMutating ? this.generateIdempotencyKey() : undefined;
    const startTime = Date.now();

    log.debug(`${method} ${endpoint}`, { attempt: 1, maxRetries: retries });

    for (let attempt = 0; attempt <= retries; attempt++) {
      const attemptTimeout = this.createAttemptTimeout();
      try {
        const headers = this.buildRequestHeaders(options.headers, hasBody, idempotencyKey);
        const response = await fetch(url, {
          ...options,
          headers,
          credentials: 'include',
          signal: options.signal ?? attemptTimeout.signal,
        });
        const duration = Date.now() - startTime;

        // Handle non-OK responses
        if (!response.ok) {
          if (this.shouldRetryRequest(response.status, isIdempotent, attempt, retries)) {
            const delay = this.calculateRetryDelay(attempt);
            log.warn(`${method} ${endpoint} failed, retrying`, {
              status: response.status,
              attempt: attempt + 1,
              maxRetries: retries,
              retryDelayMs: delay,
            });
            await this.sleep(delay);
            continue;
          }

          log.error(`${method} ${endpoint} failed`, undefined, {
            status: response.status,
            durationMs: duration,
          });
          await this.handleErrorResponse(response);
        }

        log.debug(`${method} ${endpoint} completed`, {
          status: response.status,
          durationMs: duration,
        });

        // Parse and return response
        return await this.parseResponse<T>(response);
      } catch (error) {
        const duration = Date.now() - startTime;
        const normalizedError = this.isAbortError(error)
          ? new Error(`Request timed out after ${this.requestTimeoutMs}ms`)
          : (error as Error);

        // Retry network errors for idempotent requests
        if (this.shouldRetryRequest(normalizedError, isIdempotent, attempt, retries)) {
          const delay = this.calculateRetryDelay(attempt);
          log.warn(`${method} ${endpoint} network error, retrying`, {
            error: normalizedError.message,
            attempt: attempt + 1,
            maxRetries: retries,
            retryDelayMs: delay,
          });
          await this.sleep(delay);
          continue;
        }

        // Network errors or parsing errors
        if (error instanceof MediaServerError) {
          throw error;
        }

        log.error(`${method} ${endpoint} network error`, error, { durationMs: duration });
        throw new NetworkError(
          `Network request failed: ${normalizedError.message}`,
          error as Error
        );
      } finally {
        attemptTimeout.clear();
      }
    }

    // This should never be reached, but TypeScript needs it
    throw new NetworkError('Request failed after all retries');
  }

  /**
   * Generate a stable idempotency key reused across retries of one logical request
   */
  private generateIdempotencyKey(): string {
    const cryptoRef = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
    if (cryptoRef?.randomUUID) {
      return cryptoRef.randomUUID();
    }
    return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}-${Math.random()
      .toString(16)
      .slice(2)}`;
  }

  private createAttemptTimeout(): { signal: AbortSignal; clear: () => void } {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => {
      controller.abort(
        new DOMException(`Request timed out after ${this.requestTimeoutMs}ms`, 'TimeoutError')
      );
    }, this.requestTimeoutMs);
    return {
      signal: controller.signal,
      clear: () => {
        clearTimeout(timeoutId);
      },
    };
  }

  private isAbortError(error: unknown): boolean {
    return (
      error instanceof DOMException &&
      (error.name === 'AbortError' || error.name === 'TimeoutError')
    );
  }

  /**
   * Sleep for specified milliseconds
   */
  private async sleep(ms: number): Promise<void> {
    return new Promise(resolve => {
      setTimeout(resolve, ms);
    });
  }

  /**
   * Convert headers from RequestInit to Record<string, string>
   */
  private headersFromInit(headers?: HeadersInit): Record<string, string> {
    const result: Record<string, string> = {};
    new Headers(headers ?? {}).forEach((value, key) => {
      result[key] = value;
    });
    return result;
  }

  private buildQueryString(params: Record<string, unknown>): string {
    const searchParams = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (value === undefined || value === null) continue;
      if (Array.isArray(value)) {
        searchParams.append(key, value.join(','));
      } else if (typeof value === 'object') {
        searchParams.append(key, JSON.stringify(value));
      } else if (
        typeof value === 'string' ||
        typeof value === 'number' ||
        typeof value === 'boolean'
      ) {
        searchParams.append(key, String(value));
      }
    }
    return searchParams.toString();
  }

  /**
   * Handle error response from API
   */
  private async handleErrorResponse(response: Response): Promise<never> {
    let errorData: unknown;
    try {
      errorData = (await response.json()) as unknown;
    } catch {
      errorData = null;
    }

    const bodyMessage =
      typeof errorData === 'object' &&
      errorData !== null &&
      'message' in errorData &&
      typeof errorData.message === 'string'
        ? errorData.message
        : undefined;

    const statusMessage =
      response.statusText && response.statusText.trim() !== ''
        ? response.statusText
        : `Request failed with status ${response.status}`;

    const message = bodyMessage ?? statusMessage;

    if (response.status === 401) {
      if (this.authErrorCallback) {
        try {
          this.authErrorCallback();
        } catch (callbackError) {
          log.error('Auth error callback failed', callbackError);
        }
      }

      throw new AuthenticationError(message, response.status, errorData);
    }

    if (response.status === 403) {
      throw new AuthenticationError(message, response.status, errorData);
    }

    throw new MediaServerError(message, response.status, errorData);
  }

  /**
   * GET request
   */
  async get<T>(endpoint: string, params?: Record<string, unknown>): Promise<T | undefined> {
    let url = endpoint;
    if (params) {
      const queryString = this.buildQueryString(params);
      if (queryString) url += `?${queryString}`;
    }
    return this.request<T>(url, { method: 'GET' });
  }

  /**
   * POST request
   */
  async post<T>(
    endpoint: string,
    data?: unknown,
    params?: Record<string, unknown>
  ): Promise<T | undefined> {
    let url = endpoint;
    if (params) {
      const queryString = this.buildQueryString(params);
      if (queryString) url += `?${queryString}`;
    }
    return this.request<T>(url, {
      method: 'POST',
      body: data ? JSON.stringify(data) : undefined,
    });
  }

  /**
   * DELETE request
   */
  async delete<T>(endpoint: string, params?: Record<string, unknown>): Promise<T | undefined> {
    let url = endpoint;
    if (params) {
      const queryString = this.buildQueryString(params);
      if (queryString) url += `?${queryString}`;
    }
    return this.request<T>(url, { method: 'DELETE' });
  }

  /**
   * Require non-empty response or throw error
   * Helper method to reduce duplication across *Required methods
   */
  private requireResponse<T>(
    result: T | undefined,
    method: string,
    endpoint: string,
    errorMessage?: string
  ): T {
    if (result === undefined) {
      throw new MediaServerError(errorMessage ?? `Empty response from ${method} ${endpoint}`, 500);
    }
    return result;
  }

  /**
   * GET request that requires a non-empty response
   * Throws an error if the response is empty
   */
  async getRequired<T>(
    endpoint: string,
    params?: Record<string, unknown>,
    errorMessage?: string
  ): Promise<T> {
    const result = await this.get<T>(endpoint, params);
    return this.requireResponse(result, 'GET', endpoint, errorMessage);
  }

  /**
   * POST request that requires a non-empty response
   * Throws an error if the response is empty
   */
  async postRequired<T>(endpoint: string, data?: unknown, errorMessage?: string): Promise<T> {
    const result = await this.post<T>(endpoint, data);
    return this.requireResponse(result, 'POST', endpoint, errorMessage);
  }

  /**
   * Build image URL with authentication
   */
  getImageUrl(
    itemId: string,
    imageType: string = 'Primary',
    options?: {
      tag?: string;
      maxWidth?: number;
      maxHeight?: number;
      quality?: number;
      format?: 'webp' | 'jpg' | 'png';
    }
  ): string {
    if (!itemId || itemId.trim() === '') {
      return '';
    }

    const params = new URLSearchParams();

    // Browsers don't send custom headers for <img> tags, so include api_key in URL
    if (this.token) params.append('api_key', this.token);

    if (options?.tag) params.append('tag', options.tag);
    if (options?.maxWidth) params.append('maxWidth', String(options.maxWidth));
    if (options?.maxHeight) params.append('maxHeight', String(options.maxHeight));

    // Default quality 85 for good balance (WebP quality ~7 points higher than JPEG)
    params.append('quality', String(options?.quality ?? 85));

    // Default to WebP format (30-40% smaller than JPEG with same quality)
    params.append('format', options?.format ?? 'webp');

    const queryString = params.toString();
    const query = queryString ? `?${queryString}` : '';

    return `${this.serverUrl}/Items/${itemId}/Images/${imageType}${query}`;
  }

  /**
   * Build stream URL for audio playback
   * Note: Uses api_key query parameter as browsers don't send headers for <audio> src
   */
  getStreamUrl(itemId: string): string {
    if (!this.token) {
      throw new AuthenticationError('Cannot build stream URL: not authenticated');
    }

    const params = new URLSearchParams({
      api_key: this.token,
      deviceId: this.clientInfo.deviceId,
    });

    return `${this.serverUrl}/Audio/${itemId}/stream?${params}`;
  }

  /**
   * Get server info
   */
  async getServerInfo(): Promise<ServerInfo | undefined> {
    return this.get<ServerInfo>('/System/Info/Public');
  }

  /**
   * Check if client is authenticated
   */
  isAuthenticated(): boolean {
    return this.token !== null && this.userId !== null;
  }

  /**
   * Check if karaoke feature is enabled on server
   * Returns false for 404/network errors (feature not implemented)
   * Throws for auth errors (401/403) so global handler can react
   */
  async getKaraokeEnabled(): Promise<boolean> {
    try {
      const result = await this.get<{ enabled: boolean }>('/Karaoke/enabled');
      return result?.enabled ?? false;
    } catch (error) {
      // Let auth errors propagate so global 401/403 handler can react
      if (error instanceof AuthenticationError) {
        throw error;
      }
      // Swallow 404 (feature not implemented) and network errors
      return false;
    }
  }

  /**
   * Get karaoke processing status for a track
   */
  async getKaraokeStatus(trackId: string): Promise<KaraokeStatus> {
    const result = await this.get<KaraokeStatus>(`/Karaoke/${trackId}/status`);
    return result ?? { state: 'NOT_STARTED', message: null };
  }

  /**
   * Request karaoke processing for a track
   */
  async requestKaraokeProcessing(trackId: string): Promise<KaraokeStatus> {
    const result = await this.post<KaraokeStatus>(`/Karaoke/${trackId}/process`);
    return result ?? { state: 'PROCESSING', message: null };
  }

  /**
   * Build instrumental stream URL for karaoke playback
   */
  getInstrumentalStreamUrl(itemId: string): string {
    if (!this.token) {
      throw new AuthenticationError('Cannot build stream URL: not authenticated');
    }

    const params = new URLSearchParams({
      api_key: this.token,
    });

    return `${this.serverUrl}/Karaoke/${itemId}/instrumental?${params}`;
  }

  getKaraokeStatusStreamUrl(trackId: string): string {
    if (!this.token) {
      throw new AuthenticationError('Cannot build stream URL: not authenticated');
    }

    const params = new URLSearchParams({ api_key: this.token });
    return `${this.serverUrl}/Karaoke/${trackId}/status/stream?${params}`;
  }

  /**
   * Fetch lyrics on demand for a track
   */
  async fetchLyrics(trackId: string): Promise<{ found: boolean; lyrics: string; source: string }> {
    const result = await this.post<{ found: boolean; lyrics: string; source: string }>(
      `/Lyrics/${trackId}/fetch`
    );
    return result ?? { found: false, lyrics: '', source: '' };
  }
}

export interface KaraokeStatus {
  state: KaraokeState;
  message: string | null;
}
