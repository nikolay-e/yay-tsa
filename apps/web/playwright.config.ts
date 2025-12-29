import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';

dotenv.config();

const baseURL = process.env.BASE_URL || 'http://localhost:5173';

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  timeout: 30000,
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
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        hasTouch: false, // Explicitly disable touch for desktop
        isMobile: false,
        launchOptions: {
          args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
        },
      },
    },
    {
      name: 'mobile',
      use: {
        ...devices['iPhone 13 Pro'],
        hasTouch: true, // Explicitly enable touch for mobile tests
        // isMobile: true is already set by iPhone 13 Pro preset
        launchOptions: {
          args: [],
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
