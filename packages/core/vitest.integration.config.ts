import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Integration tests run in sequence to avoid race conditions
    threads: false,
    // Longer timeout for network operations
    testTimeout: 30000,
    // Include only integration tests
    include: ['tests/integration/**/*.test.ts'],
    // Setup file to load environment variables
    setupFiles: ['./tests/integration/setup.ts'],
    // Fail fast on first error
    bail: 1,
  },
});
