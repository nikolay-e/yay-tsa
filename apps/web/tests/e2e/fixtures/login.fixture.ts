import { test as base } from '@playwright/test';
import { LoginPage } from '../page-objects/LoginPage';

type LoginFixtures = {
  loginPage: LoginPage;
};

export const test = base.extend<LoginFixtures>({
  loginPage: async ({ page }, use) => {
    const loginPage = new LoginPage(page);
    await use(loginPage);
  },
});

export { expect } from '@playwright/test';
