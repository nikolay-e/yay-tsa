import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { PLAYER_TEST_IDS } from '../helpers/test-ids';

export class PlayerBar {
  readonly page: Page;
  readonly playerBar: Locator;
  readonly currentTrackTitle: Locator;
  readonly currentTrackArtist: Locator;
  readonly currentTime: Locator;
  readonly totalTime: Locator;
  readonly queueButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.playerBar = page.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR);
    this.currentTrackTitle = page.locator(
      `[data-testid="${PLAYER_TEST_IDS.CURRENT_TRACK_TITLE}"]:visible`
    );
    this.currentTrackArtist = page.locator(
      `[data-testid="${PLAYER_TEST_IDS.CURRENT_TRACK_ARTIST}"]:visible`
    );
    this.currentTime = page.getByTestId(PLAYER_TEST_IDS.CURRENT_TIME);
    this.totalTime = page.getByTestId(PLAYER_TEST_IDS.TOTAL_TIME);
    this.queueButton = page.getByTestId(PLAYER_TEST_IDS.QUEUE_BUTTON);
  }

  private get playPauseButton(): Locator {
    return this.page.locator(`[data-testid="${PLAYER_TEST_IDS.PLAY_PAUSE_BUTTON}"]:visible`);
  }

  private get nextButton(): Locator {
    return this.page.locator(`[data-testid="${PLAYER_TEST_IDS.NEXT_BUTTON}"]:visible`);
  }

  private get previousButton(): Locator {
    return this.page.locator(`[data-testid="${PLAYER_TEST_IDS.PREVIOUS_BUTTON}"]:visible`);
  }

  private get seekSlider(): Locator {
    return this.page.locator(`[data-testid="${PLAYER_TEST_IDS.SEEK_SLIDER}"]:visible`);
  }

  private get volumeSlider(): Locator {
    return this.page.getByTestId(PLAYER_TEST_IDS.VOLUME_SLIDER);
  }

  private async ensureControlsVisible(): Promise<void> {
    if (await this.nextButton.isVisible().catch(() => false)) return;
    const openPlayerButton = this.playerBar.getByRole('button', { name: 'Open player' });
    if (await openPlayerButton.isVisible().catch(() => false)) {
      await openPlayerButton.click();
      await expect(this.nextButton).toBeVisible({ timeout: 5000 });
    }
  }

  async waitForPlayerToLoad(): Promise<void> {
    await expect(this.playerBar).toBeVisible({ timeout: 10000 });
    await expect(this.currentTrackTitle).toBeVisible({ timeout: 5000 });
    if (await this.totalTime.isVisible().catch(() => false)) {
      await expect(this.totalTime).not.toHaveText('0:00', { timeout: 10000 });
    }
    await this.waitForPlayingState();
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

  private async clickAndWaitForTrackChange(button: Locator): Promise<void> {
    await this.ensureControlsVisible();
    const previousTitle = await this.getCurrentTrackTitle();
    await button.click();
    await this.waitForTrackChange(previousTitle);
  }

  async next(): Promise<void> {
    await this.clickAndWaitForTrackChange(this.nextButton);
  }

  async clickNext(): Promise<void> {
    await this.ensureControlsVisible();
    await this.nextButton.click();
  }

  async previous(): Promise<void> {
    await this.clickAndWaitForTrackChange(this.previousButton);
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
    await this.volumeSlider.evaluate((el, pct) => {
      const input = el as HTMLInputElement;
      const value = pct / 100;
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        'value'
      )!.set!;
      nativeInputValueSetter.call(input, value.toString());
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }, percentage);
  }

  async seek(percentage: number): Promise<void> {
    await this.ensureControlsVisible();
    await this.seekSlider.evaluate((el, pct) => {
      const input = el as HTMLInputElement;
      const max = Number.parseFloat(input.max) || 100;
      const min = Number.parseFloat(input.min) || 0;
      const value = min + ((max - min) * pct) / 100;
      input.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        'value'
      )!.set!;
      nativeInputValueSetter.call(input, value.toString());
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      input.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
    }, percentage);
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
    await this.clickAndWaitForTrackChange(this.nextButton);
  }

  async clickPreviousAndWait(): Promise<void> {
    await this.clickAndWaitForTrackChange(this.previousButton);
  }

  async expectTrackTitle(title: string): Promise<void> {
    await expect(this.currentTrackTitle).toHaveText(title);
  }

  async waitForAudioReady(timeout = 15000): Promise<void> {
    await this.page.waitForFunction(
      () => {
        const audio = document.querySelector('audio');
        return audio && audio.readyState >= 2 && audio.duration > 0;
      },
      { timeout }
    );
  }

  async waitForSeekComplete(): Promise<void> {
    await expect(this.currentTime).not.toHaveText('0:00', { timeout: 5000 });
  }

  async waitForAudioPlaying(timeout = 15000): Promise<void> {
    await this.page.waitForFunction(
      () => {
        const audio = document.querySelector('audio');
        return audio && !audio.paused && audio.currentTime > 0;
      },
      { timeout }
    );
  }

  async waitForTimeProgress(): Promise<void> {
    await expect(async () => {
      const currentTime = await this.getCurrentTime();
      expect(currentTime).not.toBe('0:00');
    }).toPass({ timeout: 10000 });
  }

  async getVolume(): Promise<number> {
    return await this.page.evaluate(() => {
      const audio = document.querySelector('audio');
      return audio?.volume ?? 0;
    });
  }

  async waitForVolumeChange(previousVolume: number): Promise<void> {
    await expect(async () => {
      const currentVolume = await this.getVolume();
      expect(currentVolume).not.toBe(previousVolume);
    }).toPass({ timeout: 5000 });
  }

  async waitForVolume(expectedVolume: number, tolerance: number = 0.01): Promise<void> {
    await expect(async () => {
      const currentVolume = await this.getVolume();
      expect(Math.abs(currentVolume - expectedVolume)).toBeLessThanOrEqual(tolerance);
    }).toPass({ timeout: 5000 });
  }
}
