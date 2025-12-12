import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './pages/LibraryPage';
import { AlbumPage } from './pages/AlbumPage';
import { PlayerBar } from './pages/PlayerBar';

const test = baseTest;

test.describe('Queue Management', () => {
  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;
  let playerBar: PlayerBar;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    playerBar = new PlayerBar(authenticatedPage);
    await libraryPage.goto();
  });

  test('should create queue from album', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isVisible()).toBe(true);
    expect(trackCount).toBeGreaterThan(0);
  });

  test('should advance through queue automatically', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 2, 'Album has fewer than 2 tracks');

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const firstTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.clickNextAndWait();

    const secondTrack = await playerBar.getCurrentTrackTitle();
    expect(secondTrack).not.toBe(firstTrack);
  });

  test('should maintain queue when navigating between pages', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const trackBefore = await playerBar.getCurrentTrackTitle();

    await libraryPage.navigateHome();
    const trackAfterNavigation = await playerBar.getCurrentTrackTitle();

    expect(trackAfterNavigation).toBe(trackBefore);
  });

  test('should skip forward and backward through queue', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 2, 'Album has fewer than 2 tracks');

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await playerBar.clickNextAndWait();
    await expect(playerBar.playerBar).toBeVisible();
    const secondTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.clickNextAndWait();

    await playerBar.clickPreviousAndWait();
    await expect(playerBar.playerBar).toBeVisible();
    const backToSecond = await playerBar.getCurrentTrackTitle();

    expect(backToSecond).toBe(secondTrack);
  });

  test('should clear queue on new album play', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const firstAlbumTrack = await playerBar.getCurrentTrackTitle();

    await albumPage.goBack();
    await libraryPage.clickAlbum(1);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const secondAlbumTrack = await playerBar.getCurrentTrackTitle();
    expect(secondAlbumTrack).not.toBe(firstAlbumTrack);
  });

  test('should handle queue end', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();

    await albumPage.playTrack(trackCount - 1);
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isVisible()).toBe(true);
  });
});
