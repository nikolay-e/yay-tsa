import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { LIBRARY_TEST_IDS, NAVIGATION_TEST_IDS } from '../../../src/lib/shared/test-ids';

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
    // Navigate to home using SPA-friendly navigation
    // Works on both mobile and desktop by clicking any visible home link
    const visibleHomeLink = this.page.locator('a[href="/"]:visible').first();
    await visibleHomeLink.click();
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
    await this.waitForSearchResults();
  }

  async clearSearch(): Promise<void> {
    await this.searchInput.clear();
    await this.waitForSearchResults();
  }

  async waitForSearchResults(): Promise<void> {
    // Wait for either albums to appear OR "No albums found" message
    // This handles both successful search and empty results
    const albumCard = this.page.getByTestId('album-card').first();
    const noResults = this.page.getByText('No albums found');
    await expect(albumCard.or(noResults)).toBeVisible({ timeout: 10000 });
  }

  async expectNoResults(): Promise<void> {
    await expect(this.page.getByText('No albums found')).toBeVisible();
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

  async expectAlbumCoverVisible(index: number = 0): Promise<void> {
    const albumCard = this.albumCards.nth(index);
    const cover = albumCard.locator('[data-testid="album-cover"]');
    await expect(cover).toBeVisible();
  }

  async expectAlbumTitleVisible(index: number = 0): Promise<void> {
    const albumCard = this.albumCards.nth(index);
    const title = albumCard.locator('[data-testid="album-title"]');
    await expect(title).toBeVisible();
  }

  async goBack(): Promise<void> {
    await this.page.goBack();
    await this.waitForAlbumsToLoad();
  }
}
