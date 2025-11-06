import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';

export class LibraryPage {
  readonly page: Page;
  readonly albumCards: Locator;
  readonly searchInput: Locator;
  readonly navAlbumsTab: Locator;
  readonly navSearchTab: Locator;

  constructor(page: Page) {
    this.page = page;
    this.albumCards = page.locator('[data-testid="album-card"]');
    this.searchInput = page.locator('input[type="search"]');
    this.navAlbumsTab = page.locator('[data-testid="nav-albums"]');
    this.navSearchTab = page.locator('[data-testid="nav-search"]');
  }

  async goto(): Promise<void> {
    await this.page.goto('/');
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
      .locator('[data-testid="album-title"]')
      .textContent();
    return titleElement || '';
  }

  async navigateToSearch(): Promise<void> {
    await this.navSearchTab.click();
    await expect(this.searchInput).toBeVisible();
  }

  async search(query: string): Promise<void> {
    await this.searchInput.fill(query);
    await this.page.waitForTimeout(500);
  }

  async clearSearch(): Promise<void> {
    await this.searchInput.clear();
  }

  async expectNoResults(): Promise<void> {
    await expect(this.page.locator('text=No results found')).toBeVisible();
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
