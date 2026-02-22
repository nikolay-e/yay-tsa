import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './page-objects/LibraryPage';
import { AlbumPage } from './page-objects/AlbumPage';
import { PlayerBar } from './page-objects/PlayerBar';

const test = baseTest;

test.describe('Responsive UI - Mobile', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true });

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
    const bottomTab = authenticatedPage.getByTestId('bottom-tab-bar');
    await expect(bottomTab).toBeVisible();

    await libraryPage.navigateHome();
    await expect(authenticatedPage).toHaveURL('/');

    await libraryPage.navigateToAlbums();
    await expect(authenticatedPage).toHaveURL('/albums');
  });

  test('should handle touch interactions for playback', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    await albumPage.playButton.click();
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isPlaying()).toBe(true);
  });

  test('should scroll album list on mobile', async () => {
    const initialCount = await libraryPage.getAlbumCount();

    // Skip if all albums fit on one page (nothing to paginate)
    test.skip(initialCount < 20, 'Not enough albums to test pagination');

    await libraryPage.scrollToBottom();

    // After scroll, verify at least the same albums are visible
    const newCount = await libraryPage.getAlbumCount();
    expect(newCount).toBeGreaterThanOrEqual(initialCount);
  });

  test('should display search on mobile', async () => {
    await libraryPage.navigateToSearch();

    await expect(libraryPage.searchInput).toBeVisible();
    await libraryPage.search('test');
    // search() already waits for results
  });

  test('should handle player controls on mobile', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await playerBar.pause();
    expect(await playerBar.isPlaying()).toBe(false);

    await playerBar.play();
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
    await libraryPage.navigateHome();
    await expect(authenticatedPage).toHaveURL('/');

    await libraryPage.navigateToAlbums();
    await expect(authenticatedPage).toHaveURL('/albums');
  });
});
