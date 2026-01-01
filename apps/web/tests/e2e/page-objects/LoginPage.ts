import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';

export class LoginPage {
  readonly page: Page;
  readonly usernameInput: Locator;
  readonly passwordInput: Locator;
  readonly loginButton: Locator;
  readonly errorMessage: Locator;

  constructor(page: Page) {
    this.page = page;
    this.usernameInput = page.getByLabel('Username');
    this.passwordInput = page.getByLabel('Password');
    this.loginButton = page.getByRole('button', { name: 'Sign In' });
    this.errorMessage = page.getByRole('alert');
  }

  async goto(): Promise<void> {
    await this.page.goto('/login');
    await expect(this.usernameInput).toBeVisible({ timeout: 10000 });
  }

  async login(username: string, password: string): Promise<void> {
    await this.usernameInput.fill(username);
    await this.passwordInput.fill(password);
    await this.loginButton.click();

    // Wait for login to complete (either success or error)
    await Promise.race([
      this.page.waitForURL('/', { timeout: 15000 }),
      this.errorMessage.waitFor({ state: 'visible', timeout: 15000 }),
    ]).catch(() => {
      // Ignore timeout - we'll check the result in the test
    });
  }

  async waitForRedirectToRecent(): Promise<void> {
    // Wait for URL change AND React to render home page content
    await this.page.waitForURL('/', { timeout: 15000 });
    // Ensure home page content is actually visible (not just URL changed)
    await expect(
      this.page.getByRole('heading', { name: /Recently Played|Discover|Albums/i }).first()
    ).toBeVisible({ timeout: 10000 });
  }

  async expectError(errorText?: string): Promise<void> {
    await expect(this.errorMessage).toBeVisible();
    if (errorText) {
      await expect(this.errorMessage).toContainText(errorText);
    }
  }

  async isLoginFormVisible(): Promise<boolean> {
    return await this.usernameInput.isVisible();
  }

  async expectStillOnLoginPage(): Promise<void> {
    await expect(this.page).toHaveURL('/login');
  }

  async reload(): Promise<void> {
    await this.page.reload();
  }

  async navigateTo(path: string): Promise<void> {
    await this.page.goto(path);
  }
}
