import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    pool: 'forks',
    testTimeout: 10000,
    reporters: ['verbose'],
  },
});
