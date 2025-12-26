import type { Page, Locator } from '@playwright/test';

export class SleepTimerModal {
  readonly page: Page;
  readonly modal: Locator;
  readonly sleepTimerButton: Locator;
  readonly preset15min: Locator;
  readonly preset30min: Locator;
  readonly preset60min: Locator;
  readonly cancelButton: Locator;
  readonly customTimeInput: Locator;

  constructor(page: Page) {
    this.page = page;
    this.modal = page.getByRole('dialog');
    this.sleepTimerButton = page.getByRole('button', { name: /sleep/i });
    this.preset15min = page.locator('button.preset-btn:has-text("15 min")');
    this.preset30min = page.locator('button.preset-btn:has-text("30 min")');
    this.preset60min = page.locator('button.preset-btn:has-text("60 min")');
    this.cancelButton = page.locator('button.action-btn.cancel');
    this.customTimeInput = page.locator('input[type="number"]');
  }

  async open(): Promise<void> {
    await this.sleepTimerButton.click();
    await this.modal.waitFor({ state: 'visible' });
  }

  async selectPreset(minutes: 15 | 30 | 60): Promise<void> {
    await this.open();
    const presetButton =
      minutes === 15 ? this.preset15min : minutes === 30 ? this.preset30min : this.preset60min;
    await presetButton.click();
  }

  async cancel(): Promise<void> {
    await this.cancelButton.click();
    await this.modal.waitFor({ state: 'hidden' });
  }

  async isVisible(): Promise<boolean> {
    return this.modal.isVisible();
  }

  async setCustomTime(minutes: number): Promise<void> {
    await this.customTimeInput.fill(minutes.toString());
  }
}
