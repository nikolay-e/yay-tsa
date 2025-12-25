/**
 * HTTP Client for Media Server API
 * Handles authentication headers and token injection
 */

import {
  ClientInfo,
  ServerConfig,
  MediaServerError,
  NetworkError,
  AuthenticationError,
} from '../models/types.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('API');

export class MediaServerClient {
  private serverUrl: string;
  private clientInfo: ClientInfo;
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

  constructor(serverUrl: string, clientInfo: ClientInfo) {
    this.serverUrl = this.normalizeUrl(serverUrl);
    this.clientInfo = clientInfo;
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

  /**
   * Build Emby/Media Server authorization header
   * Required for authentication and authenticated requests
   */
  buildAuthHeader(token?: string): string {
    const parts = [
      `MediaBrowser Client="${this.clientInfo.name}"`,
      `Device="${this.clientInfo.device}"`,
      `DeviceId="${this.clientInfo.deviceId}"`,
      `Version="${this.clientInfo.version}"`,
    ];

    const authToken = token || this.token;
    if (authToken) {
      parts.push(`Token="${authToken}"`);
    }

    return parts.join(', ');
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

  /**
   * Set callback for global 401/403 authentication errors
   * This callback will be invoked before throwing AuthenticationError
   * Use this for auto-logout or redirecting to login page
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
   * Get server configuration
   */
  getConfig(): ServerConfig {
    return {
      serverUrl: this.serverUrl,
      userId: this.userId || undefined,
      token: this.token || undefined,
    };
  }

  /**
   * Build request headers with authentication
   */
  private buildRequestHeaders(
    additionalHeaders?: HeadersInit,
    includeContentType: boolean = false
  ): Record<string, string> {
    const headers: Record<string, string> = {
      'X-Emby-Authorization': this.buildAuthHeader(),
      ...this.headersFromInit(additionalHeaders),
    };

    // Only set Content-Type when we actually send a body to avoid CORS preflights on GETs
    if (includeContentType && !headers['Content-Type']) {
      headers['Content-Type'] = 'application/json';
    }

    if (this.token) {
      headers['X-Emby-Token'] = this.token;
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
      return isGatewayError || is500AndIdempotent;
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

    // Full jitter: random value between 0 and maxDelay
    return Math.floor(Math.random() * maxDelay);
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
    const method = options.method || 'GET';
    const isIdempotent = method === 'GET' || method === 'DELETE';
    const hasBody = options.body !== undefined;
    const startTime = Date.now();

    log.debug(`${method} ${endpoint}`, { attempt: 1, maxRetries: retries });

    for (let attempt = 0; attempt <= retries; attempt++) {
      try {
        const headers = this.buildRequestHeaders(options.headers, hasBody);
        const response = await fetch(url, { ...options, headers });
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

        // Retry network errors for idempotent requests
        if (this.shouldRetryRequest(error as Error, isIdempotent, attempt, retries)) {
          const delay = this.calculateRetryDelay(attempt);
          log.warn(`${method} ${endpoint} network error, retrying`, {
            error: (error as Error).message,
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
          `Network request failed: ${(error as Error).message}`,
          error as Error
        );
      }
    }

    // This should never be reached, but TypeScript needs it
    throw new NetworkError('Request failed after all retries');
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
    if (!headers) return {};

    if (headers instanceof Headers) {
      const result: Record<string, string> = {};
      headers.forEach((value, key) => {
        result[key] = value;
      });
      return result;
    }

    if (Array.isArray(headers)) {
      const result: Record<string, string> = {};
      headers.forEach(([key, value]) => {
        result[key] = value;
      });
      return result;
    }

    return headers;
  }

  private buildQueryString(params: Record<string, any>, joinArrays: boolean = false): string {
    const searchParams = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        if (joinArrays && Array.isArray(value)) {
          searchParams.append(key, value.join(','));
        } else {
          searchParams.append(key, String(value));
        }
      }
    });
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

    const message =
      (typeof errorData === 'object' &&
      errorData !== null &&
      'message' in errorData &&
      typeof errorData.message === 'string'
        ? errorData.message
        : undefined) ||
      response.statusText ||
      'Request failed';

    if (response.status === 401 || response.status === 403) {
      // Invoke global auth error callback (e.g., auto-logout) before throwing
      if (this.authErrorCallback) {
        try {
          this.authErrorCallback();
        } catch (callbackError) {
          log.error('Auth error callback failed', callbackError);
        }
      }

      throw new AuthenticationError(message, response.status, errorData as Record<string, unknown>);
    }

    throw new MediaServerError(message, response.status, errorData as Record<string, unknown>);
  }

  /**
   * GET request
   */
  async get<T>(endpoint: string, params?: Record<string, any>): Promise<T | undefined> {
    let url = endpoint;
    if (params) {
      const queryString = this.buildQueryString(params, true);
      if (queryString) url += `?${queryString}`;
    }
    return this.request<T>(url, { method: 'GET' });
  }

  /**
   * POST request
   */
  async post<T>(
    endpoint: string,
    data?: any,
    params?: Record<string, any>
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
  async delete<T>(endpoint: string, params?: Record<string, any>): Promise<T | undefined> {
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
      throw new MediaServerError(errorMessage || `Empty response from ${method} ${endpoint}`, 500);
    }
    return result;
  }

  /**
   * GET request that requires a non-empty response
   * Throws an error if the response is empty
   */
  async getRequired<T>(
    endpoint: string,
    params?: Record<string, any>,
    errorMessage?: string
  ): Promise<T> {
    const result = await this.get<T>(endpoint, params);
    return this.requireResponse(result, 'GET', endpoint, errorMessage);
  }

  /**
   * POST request that requires a non-empty response
   * Throws an error if the response is empty
   */
  async postRequired<T>(endpoint: string, data?: any, errorMessage?: string): Promise<T> {
    const result = await this.post<T>(endpoint, data);
    return this.requireResponse(result, 'POST', endpoint, errorMessage);
  }

  /**
   * DELETE request that requires a non-empty response
   * Throws an error if the response is empty
   */
  async deleteRequired<T>(endpoint: string, errorMessage?: string): Promise<T> {
    const result = await this.delete<T>(endpoint);
    return this.requireResponse(result, 'DELETE', endpoint, errorMessage);
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
  getStreamUrl(
    itemId: string,
    options?: {
      audioCodec?: string;
      audioBitRate?: number;
      container?: string;
      static?: boolean;
    }
  ): string {
    if (!this.token) {
      throw new AuthenticationError('Cannot build stream URL: not authenticated');
    }

    const params = new URLSearchParams({
      api_key: this.token,
      deviceId: this.clientInfo.deviceId,
      audioCodec: options?.audioCodec || 'aac,mp3,opus',
      container: options?.container || 'opus,mp3,aac,m4a,flac',
    });

    // Add audioBitRate if specified, otherwise use static=true for direct streaming
    if (options?.audioBitRate) {
      params.append('audioBitRate', String(options.audioBitRate));
    } else if (options?.static !== false) {
      params.append('static', 'true');
    }

    return `${this.serverUrl}/Audio/${itemId}/stream?${params}`;
  }

  /**
   * Get server info
   */
  async getServerInfo(): Promise<any> {
    return this.get('/System/Info/Public');
  }

  /**
   * Check if client is authenticated
   */
  isAuthenticated(): boolean {
    return this.token !== null && this.userId !== null;
  }
}
