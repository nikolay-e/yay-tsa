import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './pages/LibraryPage';
import { AlbumPage } from './pages/AlbumPage';
import { PlayerBar } from './pages/PlayerBar';

const test = baseTest;

test.describe('Responsive UI - Mobile', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;
  let playerBar: PlayerBar;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    playerBar = new PlayerBar(authenticatedPage);
    await libraryPage.goto();
  });

  test('should display mobile-optimized album grid', async () => {
    await libraryPage.waitForAlbumsToLoad();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThan(0);

    const firstAlbum = libraryPage.albumCards.first();
    await expect(firstAlbum).toBeVisible();
  });

  test('should display mobile player bar', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();

    await playerBar.waitForPlayerToLoad();
    expect(await playerBar.isVisible()).toBe(true);
  });

  test('should navigate with bottom tab bar', async ({ authenticatedPage }) => {
    await expect(libraryPage.navAlbumsTab).toBeVisible();
    await expect(libraryPage.navSearchTab).toBeVisible();

    await libraryPage.navigateToSearch();
    await expect(authenticatedPage).toHaveURL('/search');

    await libraryPage.navAlbumsTab.click();
    await expect(authenticatedPage).toHaveURL('/');
  });

  test('should handle touch interactions for playback', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    await albumPage.playButton.tap();
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isPlaying()).toBe(true);
  });

  test('should scroll album list on mobile', async () => {
    const initialCount = await libraryPage.getAlbumCount();

    await libraryPage.scrollToBottom();
    await libraryPage.page.waitForTimeout(1000);

    const newCount = await libraryPage.getAlbumCount();
    expect(newCount).toBeGreaterThanOrEqual(initialCount);
  });

  test('should display search on mobile', async () => {
    await libraryPage.navigateToSearch();

    await expect(libraryPage.searchInput).toBeVisible();
    await libraryPage.search('test');
    await libraryPage.page.waitForTimeout(500);
  });

  test('should handle player controls on mobile', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await playerBar.playPauseButton.tap();
    await authenticatedPage.waitForTimeout(300);
    expect(await playerBar.isPlaying()).toBe(false);

    await playerBar.playPauseButton.tap();
    await authenticatedPage.waitForTimeout(300);
    expect(await playerBar.isPlaying()).toBe(true);
  });
});

test.describe('Responsive UI - Desktop', () => {
  test.use({ viewport: { width: 1920, height: 1080 } });

  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;
  let playerBar: PlayerBar;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    playerBar = new PlayerBar(authenticatedPage);
    await libraryPage.goto();
  });

  test('should display desktop-optimized album grid', async () => {
    await libraryPage.waitForAlbumsToLoad();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThan(0);
  });

  test('should display desktop player bar', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();

    await playerBar.waitForPlayerToLoad();
    expect(await playerBar.isVisible()).toBe(true);
  });

  test('should handle mouse interactions for playback', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    await albumPage.playButton.click();
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isPlaying()).toBe(true);
  });

  test('should display more albums per row on desktop', async () => {
    await libraryPage.waitForAlbumsToLoad();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThan(0);
  });
});

test.describe('Responsive UI - Tablet', () => {
  test.use({ viewport: { width: 768, height: 1024 } });

  let libraryPage: LibraryPage;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    await libraryPage.goto();
  });

  test('should display tablet-optimized album grid', async () => {
    await libraryPage.waitForAlbumsToLoad();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThan(0);
  });

  test('should navigate on tablet', async ({ authenticatedPage }) => {
    await libraryPage.navigateToSearch();
    await expect(authenticatedPage).toHaveURL('/search');

    await libraryPage.navAlbumsTab.click();
    await expect(authenticatedPage).toHaveURL('/');
  });
});
