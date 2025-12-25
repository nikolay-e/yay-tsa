import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['tests/unit/**/*.test.ts'],
    // Unit tests run in parallel for speed
    fileParallelism: true,
    testTimeout: 5000,
    reporters: ['verbose'],
  },
});
