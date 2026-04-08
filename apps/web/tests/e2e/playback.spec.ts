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
    test.skip(test.info().project.name === 'mobile', 'Skip controls require desktop viewport');
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
    test.skip(test.info().project.name === 'mobile', 'Skip controls require desktop viewport');
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 2, 'Album has fewer than 2 tracks');

    await albumPage.playTrack(1);
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.seek(0);

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
    test.skip(test.info().project.name === 'mobile', 'Seek controls require desktop viewport');
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
    test.skip(test.info().project.name === 'mobile', 'Seek controls require desktop viewport');
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    // Get duration from the DOM audio element
    const duration = await authenticatedPage.evaluate(() => {
      const audio = document.querySelector('audio');
      return audio?.duration ?? 0;
    });

    // Seek beyond end via DOM audio element - should be clamped or handled gracefully
    await authenticatedPage.evaluate(dur => {
      const audio = document.querySelector('audio');
      if (audio) audio.currentTime = dur + 100;
    }, duration);

    // Player should still be functional after invalid seek
    expect(await playerBar.isVisible()).toBe(true);
    const currentTime = await authenticatedPage.evaluate(() => {
      const audio = document.querySelector('audio');
      return audio?.currentTime ?? -1;
    });

    // Time should be valid (clamped to duration or reset)
    expect(currentTime).toBeGreaterThanOrEqual(0);
    expect(currentTime).toBeLessThanOrEqual(duration + 1);
  });

  test('should auto-advance to next track when current track ends via seek', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
    test.skip(test.info().project.name === 'mobile', 'Seek controls require desktop viewport');
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 2, 'Album has fewer than 2 tracks');

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const firstTrack = await playerBar.getCurrentTrackTitle();

    // Seek near the end of the track to trigger natural completion
    await playerBar.seek(98);

    // Wait for the track to change — natural ended event fires when playback reaches end
    await expect(async () => {
      const currentTrack = await playerBar.getCurrentTrackTitle();
      expect(currentTrack).not.toBe(firstTrack);
    }).toPass({ timeout: 30000 });

    const secondTrack = await playerBar.getCurrentTrackTitle();
    expect(secondTrack).not.toBe(firstTrack);
    expect(secondTrack).toBeTruthy();
  });

  test('should toggle play/pause via keyboard on focused button', async ({
    playAlbumFromLibrary,
    playerBar,
  }) => {
    test.skip(test.info().project.name === 'mobile', 'Keyboard controls require desktop viewport');
    await playAlbumFromLibrary();
    await playerBar.waitForPlayingState();

    // Focus the play/pause button and press Space
    await playerBar.playPauseButton.focus();
    await playerBar.playPauseButton.press('Space');
    await playerBar.waitForPausedState();
    expect(await playerBar.isPlaying()).toBe(false);

    // Press Enter to resume
    await playerBar.playPauseButton.press('Enter');
    await playerBar.waitForPlayingState();
    expect(await playerBar.isPlaying()).toBe(true);
  });
});
