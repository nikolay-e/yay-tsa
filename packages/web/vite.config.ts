import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';
import fs from 'fs';
import path from 'path';

// Check if SSL certificates exist
const certPath = path.resolve(__dirname, '../../.certs/cert.pem');
const keyPath = path.resolve(__dirname, '../../.certs/key.pem');
const httpsEnabled = fs.existsSync(certPath) && fs.existsSync(keyPath);

export default defineConfig({
  plugins: [sveltekit()],
  server: httpsEnabled
    ? {
        https: {
          key: fs.readFileSync(keyPath),
          cert: fs.readFileSync(certPath),
        },
        strictPort: true,
        port: 5173,
        hmr: {
          protocol: 'wss',
        },
      }
    : {
        strictPort: true,
        port: 5173,
      },
  build: {
    target: 'es2020',
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: ['log', 'debug'],
        drop_debugger: true,
      },
    },
  },
  optimizeDeps: {
    include: ['@yaytsa/core', '@yaytsa/platform'],
  },
});
