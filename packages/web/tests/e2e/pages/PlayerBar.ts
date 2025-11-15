import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { PLAYER_TEST_IDS } from '../../../src/lib/test-ids';

export class PlayerBar {
  readonly page: Page;
  readonly playerBar: Locator;
  readonly playPauseButton: Locator;
  readonly nextButton: Locator;
  readonly previousButton: Locator;
  readonly shuffleButton: Locator;
  readonly repeatButton: Locator;
  readonly volumeSlider: Locator;
  readonly seekSlider: Locator;
  readonly currentTrackTitle: Locator;
  readonly currentTrackArtist: Locator;
  readonly currentTime: Locator;
  readonly totalTime: Locator;
  readonly queueButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.playerBar = page.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR);
    this.playPauseButton = page.getByTestId(PLAYER_TEST_IDS.PLAY_PAUSE_BUTTON);
    this.nextButton = page.getByTestId(PLAYER_TEST_IDS.NEXT_BUTTON);
    this.previousButton = page.getByTestId(PLAYER_TEST_IDS.PREVIOUS_BUTTON);
    this.shuffleButton = page.getByTestId(PLAYER_TEST_IDS.SHUFFLE_BUTTON);
    this.repeatButton = page.getByTestId(PLAYER_TEST_IDS.REPEAT_BUTTON);
    this.volumeSlider = page.getByTestId(PLAYER_TEST_IDS.VOLUME_SLIDER);
    this.seekSlider = page.getByTestId(PLAYER_TEST_IDS.SEEK_SLIDER);
    this.currentTrackTitle = page.getByTestId(PLAYER_TEST_IDS.CURRENT_TRACK_TITLE);
    this.currentTrackArtist = page.getByTestId(PLAYER_TEST_IDS.CURRENT_TRACK_ARTIST);
    this.currentTime = page.getByTestId(PLAYER_TEST_IDS.CURRENT_TIME);
    this.totalTime = page.getByTestId(PLAYER_TEST_IDS.TOTAL_TIME);
    this.queueButton = page.getByTestId(PLAYER_TEST_IDS.QUEUE_BUTTON);
  }

  async waitForPlayerToLoad(): Promise<void> {
    // 1. Wait for UI to be visible
    await expect(this.playerBar).toBeVisible({ timeout: 10000 });
    await expect(this.currentTrackTitle).toBeVisible({ timeout: 5000 });

    // 2. Wait for duration to be displayed (not "0:00")
    // This indirectly confirms audio metadata is loaded
    await expect(this.totalTime).not.toHaveText('0:00', { timeout: 10000 });
  }

  async isVisible(): Promise<boolean> {
    return await this.playerBar.isVisible();
  }

  async play(): Promise<void> {
    await this.playPauseButton.click();
    await this.waitForPlayingState();
  }

  async pause(): Promise<void> {
    await this.playPauseButton.click();
    await this.waitForPausedState();
  }

  async next(): Promise<void> {
    const previousTitle = await this.getCurrentTrackTitle();
    await this.nextButton.click();
    await this.waitForTrackChange(previousTitle);
  }

  async previous(): Promise<void> {
    const previousTitle = await this.getCurrentTrackTitle();
    await this.previousButton.click();
    await this.waitForTrackChange(previousTitle);
  }

  async toggleShuffle(): Promise<void> {
    await this.shuffleButton.click();
  }

  async toggleRepeat(): Promise<void> {
    await this.repeatButton.click();
  }

  async getRepeatMode(): Promise<'off' | 'all' | 'one'> {
    const ariaLabel = await this.repeatButton.getAttribute('aria-label');
    if (ariaLabel?.includes('all')) return 'all';
    if (ariaLabel?.includes('one')) return 'one';
    return 'off';
  }

  async isShuffleOn(): Promise<boolean> {
    const pressed = await this.shuffleButton.getAttribute('aria-pressed');
    return pressed === 'true';
  }

  async getCurrentTrackTitle(): Promise<string> {
    const title = await this.currentTrackTitle.textContent();
    return title || '';
  }

  async getCurrentTrackArtist(): Promise<string> {
    const artist = await this.currentTrackArtist.textContent();
    return artist || '';
  }

  async setVolume(percentage: number): Promise<void> {
    await this.volumeSlider.fill(percentage.toString());
  }

  async seek(percentage: number): Promise<void> {
    await this.seekSlider.fill(percentage.toString());
  }

  async getCurrentTime(): Promise<string> {
    const time = await this.currentTime.textContent();
    return time || '0:00';
  }

  async getTotalTime(): Promise<string> {
    const time = await this.totalTime.textContent();
    return time || '0:00';
  }

  async openQueue(): Promise<void> {
    await this.queueButton.click();
  }

  async waitForPlayingState(): Promise<void> {
    // Give Svelte one tick to update DOM after store update
    await this.page.waitForTimeout(100);
    await expect(this.playPauseButton).toHaveAttribute('aria-label', /Pause/i, { timeout: 5000 });
  }

  async waitForPausedState(): Promise<void> {
    // Give Svelte one tick to update DOM after store update
    await this.page.waitForTimeout(100);
    await expect(this.playPauseButton).toHaveAttribute('aria-label', /Play/i, { timeout: 5000 });
  }

  async isPlaying(): Promise<boolean> {
    const label = await this.playPauseButton.getAttribute('aria-label');
    return label?.includes('Pause') || false;
  }

  async waitForTrackChange(previousTitle: string): Promise<void> {
    await expect(async () => {
      const currentTitle = await this.getCurrentTrackTitle();
      expect(currentTitle).not.toBe(previousTitle);
    }).toPass({ timeout: 10000 });
  }

  async expectTrackTitle(title: string): Promise<void> {
    await expect(this.currentTrackTitle).toHaveText(title);
  }

  // Audio DOM API methods - check actual <audio> element state
  async waitForAudioReady(): Promise<void> {
    const audioLocator = this.page.locator('audio').first();

    // Wait for audio element to exist
    await expect(audioLocator).toBeAttached({ timeout: 5000 });

    // Wait for metadata to load (duration available)
    await audioLocator.evaluate((audio: HTMLAudioElement) =>
      new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => {
          reject(new Error('Audio metadata load timeout after 5s'));
        }, 5000);

        const checkReady = () => {
          if (audio.readyState >= 1 && !isNaN(audio.duration) && audio.duration > 0) {
            clearTimeout(timeout);
            resolve();
          }
        };

        if (audio.readyState >= 1 && !isNaN(audio.duration) && audio.duration > 0) {
          clearTimeout(timeout);
          resolve();
        } else {
          audio.addEventListener('loadedmetadata', checkReady, { once: true });
        }
      })
    );
  }

  async getAudioReadyState(): Promise<number> {
    const audioLocator = this.page.locator('audio').first();
    return await audioLocator.evaluate((audio: HTMLAudioElement) => audio.readyState);
  }

  async isAudioPaused(): Promise<boolean> {
    const audioLocator = this.page.locator('audio').first();
    return await audioLocator.evaluate((audio: HTMLAudioElement) => audio.paused);
  }

  async getAudioDuration(): Promise<number> {
    const audioLocator = this.page.locator('audio').first();
    return await audioLocator.evaluate((audio: HTMLAudioElement) => audio.duration);
  }

  async waitForSeekComplete(): Promise<void> {
    const audioLocator = this.page.locator('audio').first();

    await audioLocator.evaluate((audio: HTMLAudioElement) =>
      new Promise<void>((resolve) => {
        if (!audio.seeking) {
          resolve();
        } else {
          audio.addEventListener('seeked', () => resolve(), { once: true });
        }
      })
    );
  }

  async waitForAudioPlaying(): Promise<void> {
    const audioLocator = this.page.locator('audio').first();

    await audioLocator.evaluate((audio: HTMLAudioElement) =>
      new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => {
          reject(new Error('Audio did not start playing within 3s'));
        }, 3000);

        const checkPlaying = () => {
          if (!audio.paused && audio.readyState >= 3) {
            clearTimeout(timeout);
            resolve();
          }
        };

        if (!audio.paused && audio.readyState >= 3) {
          clearTimeout(timeout);
          resolve();
        } else {
          audio.addEventListener('playing', checkPlaying, { once: true });
        }
      })
    );
  }
}
