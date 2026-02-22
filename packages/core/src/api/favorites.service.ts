import { BaseService } from './base-api.service.js';

export class FavoritesService extends BaseService {
  async markFavorite(itemId: string): Promise<void> {
    const userId = this.requireAuth();
    await this.client.post(`/Items/${itemId}/Favorite`, undefined, { userId });
  }

  async unmarkFavorite(itemId: string): Promise<void> {
    const userId = this.requireAuth();
    await this.client.delete(`/Items/${itemId}/Favorite`, { userId });
  }
}
