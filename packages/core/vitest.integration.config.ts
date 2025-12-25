import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '../../', '');

  return {
    test: {
      include: ['tests/integration/**/*.test.ts'],
      // Integration tests against real server - run serially to avoid rate limiting
      fileParallelism: false,
      sequence: {
        concurrent: false,
        shuffle: false,
      },
      // Single thread to prevent database concurrency issues
      pool: 'forks',
      poolOptions: {
        forks: {
          singleFork: true,
        },
      },
      // Longer timeout for network requests
      testTimeout: 30000,
      hookTimeout: 30000,
      // Environment variables - merge loaded .env with process.env (CI secrets)
      env: {
        ...env,
        ...process.env,
        NODE_ENV: 'test',
      },
      reporters: ['verbose'],
    },
  };
});
