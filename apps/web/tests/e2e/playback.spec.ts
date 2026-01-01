import { test, expect } from './fixtures/playback.fixture';

test.describe('Playback and Player Controls', () => {
  test('should start playback when album play button clicked', async ({
    playAlbumFromLibrary,
    playerBar,
  }) => {
    await playAlbumFromLibrary();

    expect(await playerBar.isPlaying()).toBe(true);
    expect(await playerBar.getCurrentTrackTitle()).toBeTruthy();
  });

  test('should pause and resume playback', async ({ playAlbumFromLibrary, playerBar }) => {
    await playAlbumFromLibrary();

    await playerBar.pause();
    expect(await playerBar.isPlaying()).toBe(false);

    await playerBar.play();
    expect(await playerBar.isPlaying()).toBe(true);
  });

  test('should skip to next track', async ({ libraryPage, albumPage, playerBar }) => {
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

  test('should skip to previous track', async ({ libraryPage, albumPage, playerBar }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 2, 'Album has fewer than 2 tracks');

    await albumPage.playTrack(1);
    await playerBar.waitForPlayerToLoad();

    const secondTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.clickPreviousAndWait();

    const firstTrack = await playerBar.getCurrentTrackTitle();
    expect(firstTrack).not.toBe(secondTrack);
  });

  test('should display player bar with track info', async ({ playAlbumFromLibrary, playerBar }) => {
    await playAlbumFromLibrary();

    expect(await playerBar.getCurrentTrackTitle()).toBeTruthy();
    expect(await playerBar.getCurrentTrackArtist()).toBeTruthy();
    expect(await playerBar.getTotalTime()).not.toBe('0:00');
  });

  test('should seek forward in track', async ({ playAlbumFromLibrary, playerBar }) => {
    await playAlbumFromLibrary();

    // Wait for audio to be ready for seeking
    await playerBar.waitForAudioReady();

    await playerBar.seek(50);
    await playerBar.waitForSeekComplete();

    const currentTime = await playerBar.getCurrentTime();
    expect(currentTime).not.toBe('0:00');
  });

  test.skip('should adjust volume', async ({ playAlbumFromLibrary, playerBar }) => {
    // Skipped: Volume slider UI not implemented yet
    await playAlbumFromLibrary();

    await playerBar.setVolume(50);
    await playerBar.setVolume(80);
  });

  test('should show player on all pages during playback', async ({
    playAlbumFromLibrary,
    libraryPage,
    playerBar,
  }) => {
    await playAlbumFromLibrary();

    await libraryPage.navigateHome();
    expect(await playerBar.isVisible()).toBe(true);

    await libraryPage.navigateToSearch();
    expect(await playerBar.isVisible()).toBe(true);
  });

  test('should persist playback state across page navigation', async ({
    playAlbumFromLibrary,
    libraryPage,
    playerBar,
  }) => {
    await playAlbumFromLibrary();

    const trackBeforeNavigation = await playerBar.getCurrentTrackTitle();

    await libraryPage.navigateHome();

    const trackAfterNavigation = await playerBar.getCurrentTrackTitle();
    expect(trackAfterNavigation).toBe(trackBeforeNavigation);
  });

  test('should play specific track from album', async ({ libraryPage, albumPage, playerBar }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 3, 'Album has fewer than 3 tracks');

    const thirdTrackTitle = await albumPage.getTrackTitle(2);
    await albumPage.playTrack(2);

    await playerBar.waitForPlayerToLoad();
    const currentTrack = await playerBar.getCurrentTrackTitle();

    expect(currentTrack).toContain(thirdTrackTitle.split(' - ')[0]);
  });

  test('should recover from seek to invalid position', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    // Try to seek to an invalid position (beyond duration)
    const duration = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.audioEngine?.getDuration() ?? 0;
    });

    // Seek beyond end - should be clamped or handled gracefully
    await authenticatedPage.evaluate(
      dur => {
        const player = (window as any).__playerStore__;
        player?.audioEngine?.seek(dur + 100);
      },
      duration
    );

    // Player should still be functional after invalid seek
    expect(await playerBar.isVisible()).toBe(true);
    const currentTime = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.audioEngine?.getCurrentTime() ?? -1;
    });

    // Time should be valid (clamped to duration or reset)
    expect(currentTime).toBeGreaterThanOrEqual(0);
    expect(currentTime).toBeLessThanOrEqual(duration + 1);
  });
});
