import { BaseService } from './base-api.service.js';

export class FavoritesService extends BaseService {
  async markFavorite(itemId: string): Promise<void> {
    this.requireAuth();
    await this.client.post(`/UserFavoriteItems/${itemId}`);
  }

  async unmarkFavorite(itemId: string): Promise<void> {
    this.requireAuth();
    await this.client.delete(`/UserFavoriteItems/${itemId}`);
  }

  async reorderFavorites(itemIds: string[]): Promise<void> {
    const userId = this.requireAuth();
    await this.client.post('/Items/FavoriteOrder', { UserId: userId, ItemIds: itemIds });
  }
}
