import { test, expect } from './fixtures/playback.fixture';
import {
  getAudioElementState,
  getAudioContextState,
  getRMSLevel,
  waitForAudioContextRunning,
  waitForRMSAboveThreshold,
  waitForRMSBelowThreshold,
  monitorRMSLevels,
  calculateAverageRMS,
} from './helpers/audio-helpers';

test.describe('Audio Playback Verification', () => {
  test('Given: User plays track, When: Playback starts, Then: Audio element is actually playing', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const audioState = await getAudioElementState(authenticatedPage);

    expect(audioState.paused).toBe(false);
    expect(audioState.currentTime).toBeGreaterThan(0);
    expect(audioState.duration).toBeGreaterThan(0);
    expect(audioState.readyState).toBeGreaterThanOrEqual(2);
  });

  test('Given: Track playing, When: Audio is active, Then: RMS level is above threshold', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.waitForAudioPlaying();

    await waitForRMSAboveThreshold(authenticatedPage, 0.01, 10000);

    const rmsLevel = await getRMSLevel(authenticatedPage);
    expect(rmsLevel).toBeGreaterThan(0.01);
  });

  test('Given: Track playing, When: User pauses, Then: RMS level drops to near zero', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.waitForAudioPlaying();

    await waitForRMSAboveThreshold(authenticatedPage, 0.01, 10000);

    await playerBar.pause();
    await playerBar.waitForPausedState();

    await waitForRMSBelowThreshold(authenticatedPage, 0.001, 5000);

    const rmsLevel = await getRMSLevel(authenticatedPage);
    expect(rmsLevel).toBeLessThan(0.001);
  });

  test('Given: User plays track, When: Playback active, Then: AudioContext state is running', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    await waitForAudioContextRunning(authenticatedPage, 10000);

    const contextState = await getAudioContextState(authenticatedPage);
    expect(contextState).not.toBeNull();
    expect(contextState?.state).toBe('running');
  });

  test('Given: Track playing, When: Monitoring RMS over time, Then: Average RMS is above silence threshold', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.waitForAudioPlaying();

    await waitForRMSAboveThreshold(authenticatedPage, 0.01, 10000);

    const rmsLevels = await monitorRMSLevels(authenticatedPage, 4000, 200);
    const averageRMS = calculateAverageRMS(rmsLevels);

    expect(rmsLevels.length).toBeGreaterThan(5);
    expect(averageRMS).toBeGreaterThan(0.01);

    const activeSamples = rmsLevels.filter(rms => rms > 0.01);
    const activePercentage = (activeSamples.length / rmsLevels.length) * 100;
    expect(activePercentage).toBeGreaterThan(50);
  });

  test('Given: Track loaded, When: Duration available, Then: Duration is loaded before playback', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();

    const audioState = await getAudioElementState(authenticatedPage);
    expect(audioState.duration).toBeGreaterThan(0);
    expect(isFinite(audioState.duration)).toBe(true);
  });

  test('Given: Track playing, When: CurrentTime progresses, Then: CurrentTime increases over time', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.waitForAudioPlaying();

    const initialState = await getAudioElementState(authenticatedPage);
    const initialTime = initialState.currentTime;

    await authenticatedPage.waitForTimeout(2000);

    const finalState = await getAudioElementState(authenticatedPage);
    const finalTime = finalState.currentTime;

    expect(finalTime).toBeGreaterThan(initialTime);
    expect(finalTime - initialTime).toBeGreaterThanOrEqual(1.5);
  });

  test('Given: Track paused, When: User resumes, Then: Audio element transitions to playing state', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    await playerBar.pause();
    await playerBar.waitForPausedState();

    let audioState = await getAudioElementState(authenticatedPage);
    expect(audioState.paused).toBe(true);

    await playerBar.play();
    await playerBar.waitForPlayingState();

    audioState = await getAudioElementState(authenticatedPage);
    expect(audioState.paused).toBe(false);
  });

  test('Given: Track playing, When: Audio progresses, Then: ReadyState indicates enough data loaded', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const audioState = await getAudioElementState(authenticatedPage);

    expect(audioState.readyState).toBeGreaterThanOrEqual(2);
  });

  test('Given: Multiple tracks in queue, When: First track plays, Then: Audio is active for first track', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.waitForAudioPlaying();

    const firstTrackTitle = await playerBar.getCurrentTrackTitle();
    await waitForRMSAboveThreshold(authenticatedPage, 0.01, 10000);

    const rmsLevel = await getRMSLevel(authenticatedPage);
    expect(rmsLevel).toBeGreaterThan(0.01);
    expect(firstTrackTitle).toBeTruthy();
  });
});
