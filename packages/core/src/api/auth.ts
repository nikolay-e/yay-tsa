/**
 * Authentication Module
 * Handles user authentication with Jellyfin server
 */

import { JellyfinClient } from './client.js';
import { AuthPayload, AuthResponse, AuthenticationError } from '../models/types.js';

export class AuthService {
  constructor(private client: JellyfinClient) {}

  /**
   * Authenticate user with username and password
   * Note: Uses "Pw" field name for compatibility with Jellyfin 10.8.x+
   */
  async login(username: string, password: string): Promise<AuthResponse> {
    // Clear any existing token
    this.client.clearToken();

    // Build auth payload with correct field names
    const payload: AuthPayload = {
      Username: username,
      Pw: password, // "Pw" not "Password" per Jellyfin API
    };

    try {
      const response = await this.client.post<AuthResponse>('/Users/AuthenticateByName', payload);

      // Store token and user ID in client
      this.client.setToken(response.AccessToken, response.User.Id);

      return response;
    } catch (error) {
      if (error instanceof AuthenticationError) {
        throw new AuthenticationError(
          'Login failed: Invalid username or password',
          error.statusCode,
          error.response
        );
      }
      throw error;
    }
  }

  /**
   * Logout current user
   */
  async logout(): Promise<void> {
    if (!this.client.isAuthenticated()) {
      return;
    }

    try {
      await this.client.post('/Sessions/Logout');
    } finally {
      // Always clear token even if request fails
      this.client.clearToken();
    }
  }

  /**
   * Validate current session
   * Returns true if session is valid, false otherwise
   */
  async validateSession(): Promise<boolean> {
    if (!this.client.isAuthenticated()) {
      return false;
    }

    try {
      const userId = this.client.getUserId();
      if (!userId) {
        return false;
      }

      // Try to fetch user info to validate session
      await this.client.get(`/Users/${userId}`);
      return true;
    } catch (error) {
      if (error instanceof AuthenticationError) {
        this.client.clearToken();
        return false;
      }
      throw error;
    }
  }

  /**
   * Get current user information
   */
  async getCurrentUser(): Promise<any> {
    const userId = this.client.getUserId();
    if (!userId) {
      throw new AuthenticationError('Not authenticated');
    }

    return this.client.get(`/Users/${userId}`);
  }

  /**
   * Check if user is authenticated
   */
  isAuthenticated(): boolean {
    return this.client.isAuthenticated();
  }

  /**
   * Get auth token for external use (e.g., stream URLs)
   */
  getToken(): string | null {
    return this.client.getToken();
  }

  /**
   * Get current user ID
   */
  getUserId(): string | null {
    return this.client.getUserId();
  }
}

/**
 * Validate server URL format and protocol
 */
export function validateServerUrl(url: string, isDevelopment: boolean = false): void {
  let parsed: URL;

  try {
    parsed = new URL(url);
  } catch {
    throw new Error('Invalid server URL format');
  }

  // Validate protocol (always, regardless of development mode)
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('Invalid protocol: only HTTP and HTTPS are allowed');
  }

  // In production, require HTTPS (unless localhost)
  const isLocalhost =
    parsed.hostname === 'localhost' ||
    parsed.hostname === '127.0.0.1' ||
    parsed.hostname === '[::1]';

  if (!isDevelopment && parsed.protocol === 'http:' && !isLocalhost) {
    throw new Error('HTTPS required in production (except for localhost)');
  }
}
