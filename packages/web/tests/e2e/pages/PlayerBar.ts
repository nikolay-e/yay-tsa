import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { PLAYER_TEST_IDS } from '../../../src/lib/test-ids';

export class PlayerBar {
  readonly page: Page;
  readonly playerBar: Locator;
  readonly playPauseButton: Locator;
  readonly nextButton: Locator;
  readonly previousButton: Locator;
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
    this.volumeSlider = page.getByTestId(PLAYER_TEST_IDS.VOLUME_SLIDER);
    this.seekSlider = page.getByTestId(PLAYER_TEST_IDS.SEEK_SLIDER);
    this.currentTrackTitle = page.getByTestId(PLAYER_TEST_IDS.CURRENT_TRACK_TITLE);
    this.currentTrackArtist = page.getByTestId(PLAYER_TEST_IDS.CURRENT_TRACK_ARTIST);
    this.currentTime = page.getByTestId(PLAYER_TEST_IDS.CURRENT_TIME);
    this.totalTime = page.getByTestId(PLAYER_TEST_IDS.TOTAL_TIME);
    this.queueButton = page.getByTestId(PLAYER_TEST_IDS.QUEUE_BUTTON);
  }

  async waitForPlayerToLoad(): Promise<void> {
    await expect(this.playerBar).toBeVisible({ timeout: 10000 });
    await expect(this.currentTrackTitle).toBeVisible({ timeout: 5000 });
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
    await expect(this.playPauseButton).toHaveAttribute('aria-label', /Pause/i, { timeout: 5000 });
  }

  async waitForPausedState(): Promise<void> {
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

  async clickNextAndWait(): Promise<void> {
    const previousTitle = await this.getCurrentTrackTitle();
    await this.nextButton.click();
    await this.waitForTrackChange(previousTitle);
  }

  async clickPreviousAndWait(): Promise<void> {
    const previousTitle = await this.getCurrentTrackTitle();
    await this.previousButton.click();
    await this.waitForTrackChange(previousTitle);
  }

  async expectTrackTitle(title: string): Promise<void> {
    await expect(this.currentTrackTitle).toHaveText(title);
  }

  async waitForAudioReady(): Promise<void> {
    await expect(async () => {
      const duration = await this.page.evaluate(() => {
        const player = (window as any).__playerStore__;
        const audioEngine = player?.audioEngine;
        return audioEngine?.getDuration?.() ?? 0;
      });
      expect(duration).toBeGreaterThan(0);
    }).toPass({ timeout: 10000 });
  }

  async waitForSeekComplete(): Promise<void> {
    await expect(this.currentTime).not.toHaveText('0:00', { timeout: 5000 });
  }

  async waitForAudioPlaying(): Promise<void> {
    await expect(async () => {
      const isPlaying = await this.page.evaluate(() => {
        const player = (window as any).__playerStore__;
        return player?.isPlaying === true;
      });
      expect(isPlaying).toBe(true);
    }).toPass({ timeout: 10000 });
  }

  async waitForTimeProgress(): Promise<void> {
    await expect(async () => {
      const currentTime = await this.getCurrentTime();
      expect(currentTime).not.toBe('0:00');
    }).toPass({ timeout: 10000 });
  }
}
