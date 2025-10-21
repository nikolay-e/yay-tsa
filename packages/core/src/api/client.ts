/**
 * HTTP Client for Jellyfin API
 * Handles authentication headers and token injection
 */

import {
  ClientInfo,
  ServerConfig,
  JellyfinError,
  NetworkError,
  AuthenticationError
} from '../models/types.js';

export class JellyfinClient {
  private serverUrl: string;
  private clientInfo: ClientInfo;
  private token: string | null = null;
  private userId: string | null = null;

  constructor(serverUrl: string, clientInfo: ClientInfo) {
    this.serverUrl = this.normalizeUrl(serverUrl);
    this.clientInfo = clientInfo;
  }

  /**
   * Normalize server URL (remove trailing slash)
   */
  private normalizeUrl(url: string): string {
    return url.replace(/\/$/, '');
  }

  /**
   * Build Emby/Jellyfin authorization header
   * Required for authentication and authenticated requests
   */
  buildAuthHeader(token?: string): string {
    const parts = [
      `MediaBrowser Client="${this.clientInfo.name}"`,
      `Device="${this.clientInfo.device}"`,
      `DeviceId="${this.clientInfo.deviceId}"`,
      `Version="${this.clientInfo.version}"`
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
   * Initialize client with API key (fetches user ID automatically)
   */
  async initWithApiKey(apiKey: string): Promise<void> {
    this.token = apiKey;

    // Fetch current user to get user ID
    try {
      const users = await this.get<any[]>('/Users');
      if (users && users.length > 0) {
        // Use the first user (typically the admin user when using API key)
        this.userId = users[0].Id;
      } else {
        throw new AuthenticationError('No users found for this API key');
      }
    } catch (error) {
      this.token = null;
      throw error;
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
   * Get server configuration
   */
  getConfig(): ServerConfig {
    return {
      serverUrl: this.serverUrl,
      userId: this.userId || undefined,
      token: this.token || undefined
    };
  }

  /**
   * Make HTTP request to Jellyfin API
   */
  async request<T = any>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${this.serverUrl}${endpoint}`;

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Emby-Authorization': this.buildAuthHeader(),
      ...this.headersFromInit(options.headers)
    };

    // Add token header if available
    if (this.token) {
      headers['X-Emby-Token'] = this.token;
    }

    try {
      const response = await fetch(url, {
        ...options,
        headers
      });

      // Handle non-OK responses
      if (!response.ok) {
        await this.handleErrorResponse(response);
      }

      // Parse JSON response
      const data = await response.json();
      return data as T;
    } catch (error) {
      // Network errors or parsing errors
      if (error instanceof JellyfinError) {
        throw error;
      }

      throw new NetworkError(
        `Network request failed: ${(error as Error).message}`,
        error as Error
      );
    }
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

    return headers as Record<string, string>;
  }

  /**
   * Handle error response from API
   */
  private async handleErrorResponse(response: Response): Promise<never> {
    let errorData: any;
    try {
      errorData = await response.json();
    } catch {
      errorData = null;
    }

    const message = errorData?.message || response.statusText || 'Request failed';

    if (response.status === 401 || response.status === 403) {
      throw new AuthenticationError(
        message,
        response.status,
        errorData
      );
    }

    throw new JellyfinError(
      message,
      response.status,
      errorData
    );
  }

  /**
   * GET request
   */
  async get<T = any>(endpoint: string, params?: Record<string, any>): Promise<T> {
    let url = endpoint;

    if (params) {
      const searchParams = new URLSearchParams();
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null) {
          if (Array.isArray(value)) {
            searchParams.append(key, value.join(','));
          } else {
            searchParams.append(key, String(value));
          }
        }
      });

      const queryString = searchParams.toString();
      if (queryString) {
        url += `?${queryString}`;
      }
    }

    return this.request<T>(url, { method: 'GET' });
  }

  /**
   * POST request
   */
  async post<T = any>(endpoint: string, data?: any): Promise<T> {
    return this.request<T>(endpoint, {
      method: 'POST',
      body: data ? JSON.stringify(data) : undefined
    });
  }

  /**
   * DELETE request
   */
  async delete<T = any>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'DELETE' });
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
    }
  ): string {
    const params = new URLSearchParams();

    if (options?.tag) params.append('tag', options.tag);
    if (options?.maxWidth) params.append('maxWidth', String(options.maxWidth));
    if (options?.maxHeight) params.append('maxHeight', String(options.maxHeight));
    if (options?.quality) params.append('quality', String(options.quality));

    const queryString = params.toString();
    const query = queryString ? `?${queryString}` : '';

    return `${this.serverUrl}/Items/${itemId}/Images/${imageType}${query}`;
  }

  /**
   * Build stream URL for audio playback
   * Note: Uses api_key query parameter as browsers don't send headers for <audio> src
   */
  getStreamUrl(itemId: string, options?: {
    audioCodec?: string;
    maxStreamingBitrate?: string;
    container?: string;
  }): string {
    if (!this.token) {
      throw new AuthenticationError('Cannot build stream URL: not authenticated');
    }

    const params = new URLSearchParams({
      api_key: this.token,
      audioCodec: options?.audioCodec || 'aac,mp3,opus',
      maxStreamingBitrate: options?.maxStreamingBitrate || '320000',
      container: options?.container || 'opus,mp3,aac,m4a,flac'
    });

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
