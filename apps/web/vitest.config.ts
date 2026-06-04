import { defineConfig } from 'vitest/config';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.dirname(fileURLToPath(import.meta.url));

// Standalone unit-test config (does not load the app's Vite/PWA plugin stack).
// Covers deterministic, backend-free logic such as auth persistence.
export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(dir, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    globals: false,
    restoreMocks: true,
  },
});
