import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';

dotenv.config();

const baseURL = process.env.BASE_URL || 'http://localhost:5173';
const STORAGE_STATE = 'tests/e2e/.auth/user.json';

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 1,
  workers: process.env.CI ? 2 : 4,
  reporter: 'html',
  timeout: 45000,
  expect: {
    timeout: 5000,
  },
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
      use: {
        launchOptions: {
          args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
        },
      },
    },
    {
      name: 'chromium',
      testIgnore: /\.mocked\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        hasTouch: false, // Explicitly disable touch for desktop
        isMobile: false,
        storageState: STORAGE_STATE,
        launchOptions: {
          args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
        },
      },
      dependencies: ['setup'],
    },
    {
      name: 'mobile',
      testIgnore: /\.mocked\.spec\.ts/,
      use: {
        ...devices['Pixel 7'],
        hasTouch: true,
        storageState: STORAGE_STATE,
        launchOptions: {
          args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
        },
      },
      dependencies: ['setup'],
    },
    // WebKit covers Safari MediaSession / Audio quirks that Chromium misses on iOS PWAs.
    {
      name: 'webkit',
      testIgnore: /\.mocked\.spec\.ts/,
      use: {
        ...devices['Desktop Safari'],
        hasTouch: false,
        isMobile: false,
        storageState: STORAGE_STATE,
      },
      dependencies: ['setup'],
    },
    // Backend-free auth-persistence suite: all /api/* calls are stubbed via
    // Playwright route mocking, so it needs neither a live backend nor the
    // login `setup` dependency. Run: npx playwright test --project=chromium-mocked
    {
      name: 'chromium-mocked',
      testMatch: /\.mocked\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        hasTouch: false,
        isMobile: false,
        storageState: { cookies: [], origins: [] },
        launchOptions: {
          args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
        },
      },
    },
  ],

  // Skip webServer when using an external server (docker compose, remote)
  webServer:
    process.env.BASE_URL && !process.env.BASE_URL.includes('localhost')
      ? undefined
      : {
          command: 'npm run dev',
          url: baseURL,
          reuseExistingServer: !process.env.CI,
          timeout: 120000,
        },
});
