import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { LIBRARY_TEST_IDS, NAVIGATION_TEST_IDS } from '../../../src/lib/test-ids';

export class LibraryPage {
  readonly page: Page;
  readonly albumCards: Locator;
  readonly searchInput: Locator;
  readonly navHomeTab: Locator;
  readonly navAlbumsTab: Locator;

  constructor(page: Page) {
    this.page = page;
    this.albumCards = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_CARD);
    this.searchInput = page.getByTestId(NAVIGATION_TEST_IDS.SEARCH_INPUT);
    this.navHomeTab = page.getByTestId(NAVIGATION_TEST_IDS.NAV_HOME);
    this.navAlbumsTab = page.getByTestId(NAVIGATION_TEST_IDS.NAV_ALBUMS);
  }

  async goto(): Promise<void> {
    await this.page.goto('/');
    await this.waitForAlbumsToLoad();
  }

  async navigateHome(): Promise<void> {
    // SPA navigation - preserves app state (unlike page.goto which reloads)
    await this.page.click('a[href="/"]');
    await this.waitForAlbumsToLoad();
  }

  async waitForAlbumsToLoad(): Promise<void> {
    await expect(this.albumCards.first()).toBeVisible({ timeout: 10000 });
  }

  async getAlbumCount(): Promise<number> {
    await this.waitForAlbumsToLoad();
    return await this.albumCards.count();
  }

  async clickAlbum(index: number = 0): Promise<void> {
    await this.albumCards.nth(index).click();
  }

  async getAlbumTitle(index: number = 0): Promise<string> {
    const titleElement = await this.albumCards
      .nth(index)
      .getByTestId(LIBRARY_TEST_IDS.ALBUM_TITLE)
      .textContent();
    return titleElement || '';
  }

  async navigateToSearch(): Promise<void> {
    // Search is now on the home page
    const currentPath = new URL(this.page.url()).pathname;
    if (currentPath !== '/') {
      await this.page.goto('/');
    }
    await expect(this.searchInput).toBeVisible({ timeout: 10000 });
  }

  async search(query: string): Promise<void> {
    await this.searchInput.fill(query);
    await this.page.waitForTimeout(500);
  }

  async clearSearch(): Promise<void> {
    await this.searchInput.clear();
  }

  async expectNoResults(): Promise<void> {
    await expect(this.page.locator('text=No albums found')).toBeVisible();
  }

  async scrollToBottom(): Promise<void> {
    await this.page.evaluate(() => {
      window.scrollTo(0, document.body.scrollHeight);
    });
  }

  async waitForMoreAlbumsToLoad(previousCount: number): Promise<void> {
    await expect(async () => {
      const currentCount = await this.albumCards.count();
      expect(currentCount).toBeGreaterThan(previousCount);
    }).toPass({ timeout: 10000 });
  }
}
