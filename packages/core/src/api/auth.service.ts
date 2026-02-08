/**
 * Authentication Module
 * Handles user authentication with Media Server
 */

import {
  type AuthPayload,
  type AuthResponse,
  AuthenticationError,
} from '../internal/models/types.js';
import { type MediaServerClient } from './api.client.js';

export class AuthService {
  constructor(private client: MediaServerClient) {}

  /**
   * Authenticate user with username and password
   * Note: Uses "Pw" field name for compatibility with Media Server API
   */
  async login(username: string, password: string): Promise<AuthResponse> {
    // Clear any existing token
    this.client.clearToken();

    // Build auth payload with correct field names
    const payload: AuthPayload = {
      Username: username,
      Pw: password, // "Pw" not "Password" per Media Server API
    };

    try {
      const response = await this.client.post<AuthResponse>('/Users/AuthenticateByName', payload);

      if (!response) {
        throw new AuthenticationError('Login failed: Empty response from server');
      }

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
   * Check if user is authenticated
   */
  isAuthenticated(): boolean {
    return this.client.isAuthenticated();
  }
}

function isPrivateIP(hostname: string): boolean {
  const parts = hostname.split('.').map(Number);
  if (parts.length !== 4 || parts.some(p => isNaN(p) || p < 0 || p > 255)) return false;

  const [a, b] = parts;
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

function isDockerServiceName(hostname: string): boolean {
  return (
    !hostname.includes('.') &&
    !/^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(hostname) &&
    /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/i.test(hostname)
  );
}

export function validateServerUrl(url: string, isDevelopment: boolean = false): void {
  // Allow relative paths for reverse proxy mode (e.g., "/api")
  if (url.startsWith('/')) {
    return;
  }

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

  // In production, require HTTPS (unless localhost or private network)
  const isLocalhost =
    parsed.hostname === 'localhost' ||
    parsed.hostname === '127.0.0.1' ||
    parsed.hostname === '[::1]';

  const isPrivateNetwork = isPrivateIP(parsed.hostname);
  const isDockerService = isDockerServiceName(parsed.hostname);

  if (
    !isDevelopment &&
    parsed.protocol === 'http:' &&
    !isLocalhost &&
    !isPrivateNetwork &&
    !isDockerService
  ) {
    throw new Error('HTTPS required in production (except for localhost and private networks)');
  }
}
