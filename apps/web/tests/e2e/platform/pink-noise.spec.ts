import { test, expect } from '../fixtures/auth.fixture';

test.describe('@yaytsa/platform: PinkNoiseGenerator', () => {
  test('Given: PinkNoiseGenerator, When: start called, Then: isPlaying returns true', async ({
    authenticatedPage,
  }) => {
    const isPlaying = await authenticatedPage.evaluate(async () => {
      const { PinkNoiseGenerator } = (window as any).__platformClasses__;
      const generator = new PinkNoiseGenerator();
      await generator.start({ initialVolume: 0.5 });

      const playing = generator.getIsPlaying();
      generator.dispose();
      return playing;
    });

    expect(isPlaying).toBe(true);
  });

  test('Given: PinkNoiseGenerator running, When: stop called, Then: isPlaying returns false', async ({
    authenticatedPage,
  }) => {
    const isPlaying = await authenticatedPage.evaluate(async () => {
      const { PinkNoiseGenerator } = (window as any).__platformClasses__;
      const generator = new PinkNoiseGenerator();
      await generator.start({ initialVolume: 0.5 });

      generator.stop();
      const playing = generator.getIsPlaying();
      generator.dispose();
      return playing;
    });

    expect(isPlaying).toBe(false);
  });

  test('Given: PinkNoiseGenerator running, When: setVolume called, Then: Volume updates', async ({
    authenticatedPage,
  }) => {
    const volume = await authenticatedPage.evaluate(async () => {
      const { PinkNoiseGenerator } = (window as any).__platformClasses__;
      const generator = new PinkNoiseGenerator();
      await generator.start({ initialVolume: 0.3 });

      generator.setVolume(0.8);
      const vol = generator.getVolume();
      generator.dispose();
      return vol;
    });

    expect(volume).toBeCloseTo(0.8, 1);
  });

  test('Given: PinkNoiseGenerator, When: fadeVolume called, Then: Volume transitions', async ({
    authenticatedPage,
  }) => {
    const result = await authenticatedPage.evaluate(async () => {
      const { PinkNoiseGenerator } = (window as any).__platformClasses__;
      const generator = new PinkNoiseGenerator();
      await generator.start({ initialVolume: 1.0 });

      const samples: number[] = [];
      const fade = generator.fadeVolume(1.0, 0.2, 400);

      for (let i = 0; i < 5; i++) {
        await new Promise(r => setTimeout(r, 100));
        samples.push(generator.getVolume());
      }

      await fade.promise;
      const finalVolume = generator.getVolume();
      generator.dispose();

      return { samples, finalVolume };
    });

    expect(result.samples[0]).toBeLessThan(1.0);
    expect(result.finalVolume).toBeCloseTo(0.2, 1);
  });

  test('Given: PinkNoiseGenerator fade in progress, When: cancel called, Then: Fade stops', async ({
    authenticatedPage,
  }) => {
    const cancelledVolume = await authenticatedPage.evaluate(async () => {
      const { PinkNoiseGenerator } = (window as any).__platformClasses__;
      const generator = new PinkNoiseGenerator();
      await generator.start({ initialVolume: 1.0 });

      const fade = generator.fadeVolume(1.0, 0.0, 2000);
      await new Promise(r => setTimeout(r, 200));
      fade.cancel();

      const vol = generator.getVolume();
      generator.dispose();
      return vol;
    });

    expect(cancelledVolume).toBeGreaterThan(0.5);
    expect(cancelledVolume).toBeLessThan(1.0);
  });

  test('Given: PinkNoiseGenerator, When: dispose called, Then: Resources cleaned up', async ({
    authenticatedPage,
  }) => {
    const result = await authenticatedPage.evaluate(async () => {
      const { PinkNoiseGenerator } = (window as any).__platformClasses__;
      const generator = new PinkNoiseGenerator();
      await generator.start({ initialVolume: 0.5 });

      generator.dispose();
      return generator.getIsPlaying();
    });

    expect(result).toBe(false);
  });

  test('Given: Shared AudioContext, When: PinkNoiseGenerator uses it, Then: Works correctly', async ({
    authenticatedPage,
  }) => {
    const result = await authenticatedPage.evaluate(async () => {
      const { PinkNoiseGenerator } = (window as any).__platformClasses__;
      const AudioContextClass =
        window.AudioContext || (window as any).webkitAudioContext;
      const sharedContext = new AudioContextClass();

      const generator = new PinkNoiseGenerator();
      await generator.start({ initialVolume: 0.5, audioContext: sharedContext });

      const isPlaying = generator.getIsPlaying();
      generator.dispose();

      const contextState = sharedContext.state;
      await sharedContext.close();

      return { isPlaying, contextState };
    });

    expect(result.isPlaying).toBe(true);
    expect(result.contextState).not.toBe('closed');
  });
});
