import { test, expect } from './fixtures/playback.fixture';
import { getVolumeFromLocalStorage, getVolumeFromPlayerStore } from './helpers/audio-helpers';

test.describe('Volume Control', () => {
  test('Given: Track playing, When: User sets volume to 50%, Then: Audio element volume updates', async ({
    playAlbumFromLibrary,
    playerBar,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    const initialVolume = await playerBar.getVolume();

    await playerBar.setVolume(50);
    await playerBar.waitForVolume(0.5);

    const finalVolume = await playerBar.getVolume();
    expect(finalVolume).toBeCloseTo(0.5, 2);
    expect(finalVolume).not.toBe(initialVolume);
  });

  test('Given: Track playing, When: User sets volume to 0%, Then: Audio element is silent', async ({
    playAlbumFromLibrary,
    playerBar,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    await playerBar.setVolume(0);
    await playerBar.waitForVolume(0);

    const volume = await playerBar.getVolume();
    expect(volume).toBe(0);
  });

  test('Given: Track playing, When: User sets volume to 100%, Then: Audio element is at maximum', async ({
    playAlbumFromLibrary,
    playerBar,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    await playerBar.setVolume(100);
    await playerBar.waitForVolume(1.0);

    const volume = await playerBar.getVolume();
    expect(volume).toBe(1.0);
  });

  test('Given: User sets volume, When: Volume changes, Then: Player store syncs with audio element', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    await playerBar.setVolume(75);
    await playerBar.waitForVolume(0.75);

    const audioVolume = await playerBar.getVolume();
    const storeVolume = await getVolumeFromPlayerStore(authenticatedPage);

    expect(audioVolume).toBeCloseTo(0.75, 2);
    expect(storeVolume).toBeCloseTo(0.75, 2);
    expect(audioVolume).toBeCloseTo(storeVolume, 2);
  });

  test('Given: User sets volume, When: Volume changes, Then: Volume persists in localStorage', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    await playerBar.setVolume(60);
    await playerBar.waitForVolume(0.6);

    await authenticatedPage.waitForTimeout(500);

    const storedVolume = await getVolumeFromLocalStorage(authenticatedPage);
    expect(storedVolume).not.toBeNull();
    expect(storedVolume).toBeCloseTo(0.6, 2);
  });

  test('Given: Volume set in localStorage, When: Page reloads, Then: Volume is restored', async ({
    authenticatedPage,
    libraryPage,
    albumPage,
    playerBar,
  }) => {
    await authenticatedPage.evaluate(() => {
      localStorage.setItem('yaytsa_volume', '0.35');
    });

    await authenticatedPage.reload();
    await libraryPage.waitForAlbumsToLoad();

    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const volume = await playerBar.getVolume();
    expect(volume).toBeCloseTo(0.35, 2);
  });

  test('Given: Multiple volume changes, When: User adjusts volume rapidly, Then: Final volume is correct', async ({
    playAlbumFromLibrary,
    playerBar,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    await playerBar.setVolume(80);
    await playerBar.setVolume(40);
    await playerBar.setVolume(90);
    await playerBar.setVolume(25);

    await playerBar.waitForVolume(0.25);

    const finalVolume = await playerBar.getVolume();
    expect(finalVolume).toBeCloseTo(0.25, 2);
  });

  test('Given: Track playing at volume 70%, When: User pauses and resumes, Then: Volume remains 70%', async ({
    playAlbumFromLibrary,
    playerBar,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    await playerBar.setVolume(70);
    await playerBar.waitForVolume(0.7);

    await playerBar.pause();
    await playerBar.waitForPausedState();

    await playerBar.play();
    await playerBar.waitForPlayingState();

    const volumeAfterResume = await playerBar.getVolume();
    expect(volumeAfterResume).toBeCloseTo(0.7, 2);
  });

  test('Given: Track playing at volume 80%, When: User skips to next track, Then: Volume remains 80%', async ({
    playAlbumFromLibrary,
    playerBar,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForAudioReady();

    await playerBar.setVolume(80);
    await playerBar.waitForVolume(0.8);

    await playerBar.next();
    await playerBar.waitForAudioReady();

    const volumeAfterSkip = await playerBar.getVolume();
    expect(volumeAfterSkip).toBeCloseTo(0.8, 2);
  });
});
