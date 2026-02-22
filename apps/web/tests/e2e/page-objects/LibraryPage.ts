import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { LIBRARY_TEST_IDS, NAVIGATION_TEST_IDS } from '../../../src/shared/testing/test-ids';

export class LibraryPage {
  readonly page: Page;
  readonly albumCards: Locator;
  readonly searchInput: Locator;

  private readonly sidebar: Locator;
  private readonly bottomTab: Locator;

  constructor(page: Page) {
    this.page = page;
    this.albumCards = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_CARD);
    this.searchInput = page.getByTestId(NAVIGATION_TEST_IDS.SEARCH_INPUT);
    this.sidebar = page.getByTestId(NAVIGATION_TEST_IDS.SIDEBAR);
    this.bottomTab = page.getByTestId(NAVIGATION_TEST_IDS.BOTTOM_TAB_BAR);
  }

  get navHomeTab(): Locator {
    return this.sidebar
      .getByTestId(NAVIGATION_TEST_IDS.NAV_HOME)
      .or(this.bottomTab.getByTestId(NAVIGATION_TEST_IDS.NAV_HOME));
  }

  get navAlbumsTab(): Locator {
    return this.sidebar
      .getByTestId(NAVIGATION_TEST_IDS.NAV_ALBUMS)
      .or(this.bottomTab.getByTestId(NAVIGATION_TEST_IDS.NAV_ALBUMS));
  }

  private async clickNavLink(testId: string): Promise<void> {
    if (await this.sidebar.isVisible()) {
      await this.sidebar.getByTestId(testId).click();
    } else {
      await this.bottomTab.getByTestId(testId).click();
    }
  }

  async goto(): Promise<void> {
    const currentPath = new URL(this.page.url()).pathname;
    if (currentPath === '/albums') {
      await this.waitForAlbumsToLoad();
      return;
    }
    await this.navigateToAlbums();
  }

  async navigateHome(): Promise<void> {
    await this.clickNavLink(NAVIGATION_TEST_IDS.NAV_HOME);
    await this.page.waitForURL('/');
  }

  async waitForAlbumsToLoad(): Promise<void> {
    await expect(this.albumCards.first()).toBeVisible({ timeout: 10000 });
  }

  async getAlbumCount(): Promise<number> {
    await this.waitForSearchResults();
    const noResults = this.page.getByText('No albums found');
    if (await noResults.isVisible()) return 0;
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
    const currentPath = new URL(this.page.url()).pathname;
    if (currentPath !== '/albums') {
      await this.navigateToAlbums();
    }
    await expect(this.searchInput).toBeVisible({ timeout: 10000 });
  }

  async navigateToAlbums(): Promise<void> {
    await this.clickNavLink(NAVIGATION_TEST_IDS.NAV_ALBUMS);
    await this.waitForAlbumsToLoad();
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
    await this.page.waitForURL('/albums');
    await this.waitForAlbumsToLoad();
  }
}
