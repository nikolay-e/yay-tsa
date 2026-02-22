import { test, expect } from './fixtures/playback.fixture';

test.describe('Queue Management', () => {
  test('should create queue from album', async ({ libraryPage, albumPage, playerBar }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isVisible()).toBe(true);
    expect(trackCount).toBeGreaterThan(0);
  });

  test('should advance through queue automatically', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
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

  test('should maintain queue when navigating between pages', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const trackBefore = await playerBar.getCurrentTrackTitle();

    await libraryPage.navigateHome();
    const trackAfterNavigation = await playerBar.getCurrentTrackTitle();

    expect(trackAfterNavigation).toBe(trackBefore);
  });

  test('should skip forward and backward through queue', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
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

  test('should clear queue on new album play', async ({ libraryPage, albumPage, playerBar }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const firstAlbumTrack = await playerBar.getCurrentTrackTitle();

    await albumPage.goBack();
    await libraryPage.clickAlbum(1);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForTrackChange(firstAlbumTrack);

    const secondAlbumTrack = await playerBar.getCurrentTrackTitle();
    expect(secondAlbumTrack).not.toBe(firstAlbumTrack);
  });

  test('should stop at queue end when repeat is off', async ({
    libraryPage,
    albumPage,
    playerBar,
    authenticatedPage,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();

    await albumPage.playTrack(trackCount - 1);
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const lastTrackTitle = await playerBar.getCurrentTrackTitle();

    // Click next on the last track - should stay on last track or stop
    await playerBar.clickNext();
    await authenticatedPage.waitForTimeout(500);

    // Verify we haven't looped back to first track (no repeat)
    const currentTrackAfterNext = await playerBar.getCurrentTrackTitle();

    // Expected behavior: either stays on last track or playback stops
    // It should NOT jump to first track when repeat is off
    if (trackCount > 1) {
      expect(currentTrackAfterNext).toBe(lastTrackTitle);
    }
  });
});
