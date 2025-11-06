import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';

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
    this.playerBar = page.locator('[data-testid="player-bar"]');
    this.playPauseButton = page.locator('[data-testid="play-pause-button"]');
    this.nextButton = page.locator('[data-testid="next-button"]');
    this.previousButton = page.locator('[data-testid="previous-button"]');
    this.shuffleButton = page.locator('[data-testid="shuffle-button"]');
    this.repeatButton = page.locator('[data-testid="repeat-button"]');
    this.volumeSlider = page.locator('[data-testid="volume-slider"]');
    this.seekSlider = page.locator('[data-testid="seek-slider"]');
    this.currentTrackTitle = page.locator('[data-testid="current-track-title"]');
    this.currentTrackArtist = page.locator('[data-testid="current-track-artist"]');
    this.currentTime = page.locator('[data-testid="current-time"]');
    this.totalTime = page.locator('[data-testid="total-time"]');
    this.queueButton = page.locator('[data-testid="queue-button"]');
  }

  async waitForPlayerToLoad(): Promise<void> {
    await expect(this.playerBar).toBeVisible({ timeout: 10000 });
    await expect(this.currentTrackTitle).toBeVisible({ timeout: 5000 });
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

  async expectTrackTitle(title: string): Promise<void> {
    await expect(this.currentTrackTitle).toHaveText(title);
  }
}
