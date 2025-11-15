import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { LIBRARY_TEST_IDS, PLAYER_TEST_IDS } from '../../../src/lib/test-ids';

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
    this.albumTitle = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_DETAIL_TITLE);
    this.playButton = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_PLAY_BUTTON);
    this.shuffleButton = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_SHUFFLE_BUTTON);
    this.favoriteButton = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_FAVORITE_BUTTON);
    this.trackRows = page.getByTestId(LIBRARY_TEST_IDS.TRACK_ROW);
    this.backButton = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_BACK_BUTTON);
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
      .getByTestId(LIBRARY_TEST_IDS.TRACK_TITLE)
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
    await expect(this.page.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR)).toBeVisible({ timeout: 10000 });
  }
}
