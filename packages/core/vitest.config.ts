import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  // Load env file from project root (2 levels up from packages/core)
  const env = loadEnv(mode, '../../', '');

  return {
    test: {
      // E2E tests against real server - run serially to avoid rate limiting
      sequence: {
        concurrent: false,
        shuffle: false,
      },
      // Single thread to prevent Jellyfin SQLite concurrency issues
      pool: 'forks',
      poolOptions: {
        forks: {
          singleFork: true,
        },
      },
      // Longer timeout for network requests
      testTimeout: 30000,
      hookTimeout: 30000,
      // Environment variables - merge loaded .env with test-specific vars
      env: {
        ...env,
        NODE_ENV: 'test',
      },
      // Show test output
      reporters: ['verbose'],
    },
  };
});
