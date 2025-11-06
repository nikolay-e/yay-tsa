/**
 * Base service class for Jellyfin API services
 * Provides common functionality for all API services
 */

import { JellyfinClient } from './client.js';

export abstract class BaseService {
  constructor(protected client: JellyfinClient) {}

  /**
   * Require authentication and return user ID
   * Throws AuthenticationError if not authenticated
   */
  protected requireAuth(): string {
    return this.client.requireAuth();
  }

  /**
   * Build URL with user ID
   * Example: buildUserUrl('/Items') -> '/Users/{userId}/Items'
   */
  protected buildUserUrl(path: string): string {
    const userId = this.requireAuth();
    return `/Users/${userId}${path}`;
  }

  /**
   * Check if user is authenticated
   */
  protected isAuthenticated(): boolean {
    return this.client.isAuthenticated();
  }
}
