import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';

export class AlbumPage {
  readonly page: Page;
  readonly albumTitle: Locator;
  readonly playButton: Locator;
  readonly shuffleButton: Locator;
  readonly favoriteButton: Locator;
  readonly trackRows: Locator;
  readonly backButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.albumTitle = page.locator('[data-testid="album-title"]');
    this.playButton = page.locator('[data-testid="play-button"]');
    this.shuffleButton = page.locator('[data-testid="shuffle-button"]');
    this.favoriteButton = page.locator('[data-testid="favorite-button"]');
    this.trackRows = page.locator('[data-testid="track-row"]');
    this.backButton = page.locator('[data-testid="back-button"]');
  }

  async waitForAlbumToLoad(): Promise<void> {
    await expect(this.albumTitle).toBeVisible({ timeout: 10000 });
    await expect(this.trackRows.first()).toBeVisible({ timeout: 10000 });
  }

  async getAlbumTitle(): Promise<string> {
    const title = await this.albumTitle.textContent();
    return title || '';
  }

  async playAlbum(): Promise<void> {
    await this.playButton.click();
    await this.waitForPlayerToAppear();
  }

  async playTrack(index: number): Promise<void> {
    await this.trackRows.nth(index).click();
    await this.waitForPlayerToAppear();
  }

  async getTrackCount(): Promise<number> {
    return await this.trackRows.count();
  }

  async getTrackTitle(index: number): Promise<string> {
    const titleElement = await this.trackRows
      .nth(index)
      .locator('[data-testid="track-title"]')
      .textContent();
    return titleElement || '';
  }

  async shuffleAlbum(): Promise<void> {
    await this.shuffleButton.click();
    await this.waitForPlayerToAppear();
  }

  async toggleFavorite(): Promise<void> {
    await this.favoriteButton.click();
  }

  async isFavorite(): Promise<boolean> {
    const isFavorited = await this.favoriteButton.getAttribute('aria-pressed');
    return isFavorited === 'true';
  }

  async goBack(): Promise<void> {
    await this.backButton.click();
  }

  private async waitForPlayerToAppear(): Promise<void> {
    await expect(this.page.locator('[data-testid="player-bar"]')).toBeVisible({ timeout: 10000 });
  }
}
