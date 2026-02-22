import { test, expect } from '../fixtures/playback.fixture';

test.describe('@yay-tsa/platform: HTML5AudioEngine', () => {
  test('Given: Audio loaded, When: fadeVolume called, Then: Volume transitions smoothly', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const volumeSamples: number[] = [];
    const startVolume = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      if (!audioEngine) throw new Error('AudioEngine not found');

      audioEngine.setVolume(1.0);
      return audioEngine.getVolume();
    });

    expect(startVolume).toBeCloseTo(1.0, 1);

    await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      audioEngine.fadeVolume(1.0, 0.2, 500);
    });

    for (let i = 0; i < 6; i++) {
      await authenticatedPage.waitForTimeout(100);
      const volume = await authenticatedPage.evaluate(() => {
        const player = (window as any).__playerStore__;
        return player?.audioEngine?.getVolume() ?? 0;
      });
      volumeSamples.push(volume);
    }

    expect(volumeSamples[0]).toBeLessThan(1.0);
    expect(volumeSamples[volumeSamples.length - 1]).toBeLessThan(volumeSamples[0]);
    expect(volumeSamples[volumeSamples.length - 1]).toBeCloseTo(0.2, 1);
  });

  test('Given: Fade in progress, When: cancel called, Then: Fade stops immediately', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const cancelledVolume = await authenticatedPage.evaluate(async () => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      if (!audioEngine) throw new Error('AudioEngine not found');

      audioEngine.setVolume(1.0);
      const fade = audioEngine.fadeVolume(1.0, 0.0, 2000);

      await new Promise(r => setTimeout(r, 200));
      fade.cancel();

      return audioEngine.getVolume();
    });

    expect(cancelledVolume).toBeGreaterThan(0.5);
    expect(cancelledVolume).toBeLessThan(1.0);
  });

  test('Given: Audio playing, When: getCurrentTime called during playback, Then: Time progresses', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.waitForAudioPlaying();

    const initialTime = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.audioEngine?.getCurrentTime() ?? 0;
    });

    await authenticatedPage.waitForTimeout(1500);

    const finalTime = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.audioEngine?.getCurrentTime() ?? 0;
    });

    expect(finalTime).toBeGreaterThan(initialTime);
    expect(finalTime - initialTime).toBeGreaterThanOrEqual(1.0);
  });

  test('Given: Audio loaded, When: seek called, Then: CurrentTime updates to seek position', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const seekPosition = 10;
    const resultTime = await authenticatedPage.evaluate(pos => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      if (!audioEngine) throw new Error('AudioEngine not found');

      audioEngine.seek(pos);
      return audioEngine.getCurrentTime();
    }, seekPosition);

    expect(resultTime).toBeCloseTo(seekPosition, 0);
  });

  test('Given: Audio engine, When: getDuration called after load, Then: Returns valid duration', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const duration = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.audioEngine?.getDuration() ?? 0;
    });

    expect(duration).toBeGreaterThan(0);
    expect(Number.isFinite(duration)).toBe(true);
  });

  test('Given: Audio engine, When: isPlaying checked during playback, Then: Returns true', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.waitForAudioPlaying();

    const isPlaying = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.audioEngine?.isPlaying() ?? false;
    });

    expect(isPlaying).toBe(true);
  });

  test('Given: Audio playing, When: pause called, Then: isPlaying returns false', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();
    await playerBar.waitForAudioPlaying();

    await playerBar.pause();
    await playerBar.waitForPausedState();

    const isPlaying = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.audioEngine?.isPlaying() ?? true;
    });

    expect(isPlaying).toBe(false);
  });

  test('Given: Audio engine, When: getAudioContext called, Then: Returns valid AudioContext', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const contextState = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      const ctx = audioEngine?.getAudioContext?.();

      if (!ctx) return null;
      return {
        state: ctx.state,
        sampleRate: ctx.sampleRate,
      };
    });

    expect(contextState).not.toBeNull();
    expect(contextState?.sampleRate).toBeGreaterThan(0);
  });

  test('Given: Audio engine, When: getAudioElement called, Then: Returns HTMLAudioElement', async ({
    playAlbumFromLibrary,
    playerBar,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const hasElement = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      const element = audioEngine?.getAudioElement?.();
      return element instanceof HTMLAudioElement;
    });

    expect(hasElement).toBe(true);
  });
});
