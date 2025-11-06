import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';

export class LoginPage {
  readonly page: Page;
  readonly serverUrlInput: Locator;
  readonly usernameInput: Locator;
  readonly passwordInput: Locator;
  readonly loginButton: Locator;
  readonly errorMessage: Locator;

  constructor(page: Page) {
    this.page = page;
    this.serverUrlInput = page.locator('input[name="serverUrl"]');
    this.usernameInput = page.locator('input[name="username"]');
    this.passwordInput = page.locator('input[name="password"]');
    this.loginButton = page.locator('button[type="submit"]');
    this.errorMessage = page.locator('[data-testid="error-message"]');
  }

  async goto(): Promise<void> {
    await this.page.goto('/login');
    await expect(this.serverUrlInput).toBeVisible({ timeout: 10000 });
  }

  async login(serverUrl: string, username: string, password: string): Promise<void> {
    await this.serverUrlInput.fill(serverUrl);
    await this.usernameInput.fill(username);
    await this.passwordInput.fill(password);
    await this.loginButton.click();
  }

  async waitForRedirectToHome(): Promise<void> {
    await this.page.waitForURL('/', { timeout: 15000 });
  }

  async expectError(errorText?: string): Promise<void> {
    await expect(this.errorMessage).toBeVisible();
    if (errorText) {
      await expect(this.errorMessage).toContainText(errorText);
    }
  }

  async isLoginFormVisible(): Promise<boolean> {
    return await this.serverUrlInput.isVisible();
  }
}
