import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { VitePWA } from 'vite-plugin-pwa';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const certPath = path.resolve(__dirname, '../../.certs/cert.pem');
const keyPath = path.resolve(__dirname, '../../.certs/key.pem');
const httpsEnabled = fs.existsSync(certPath) && fs.existsSync(keyPath);

export default defineConfig({
  envPrefix: ['VITE_', 'YAYTSA_'],
  plugins: [
    tailwindcss(),
    react(),
    VitePWA({
      strategies: 'generateSW',
      registerType: 'autoUpdate',
      workbox: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff,woff2}'],
        runtimeCaching: [
          {
            urlPattern: ({ url }) =>
              url.pathname.includes('/Items/') && url.pathname.includes('/Images/'),
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: 'yaytsa-images-0.0.0-placeholder',
              expiration: {
                maxEntries: 500,
                maxAgeSeconds: 30 * 24 * 60 * 60,
              },
              cacheableResponse: {
                statuses: [0, 200],
              },
            },
          },
          {
            urlPattern: ({ url }) =>
              url.pathname.includes('/Audio/') && url.pathname.includes('/stream'),
            handler: 'CacheFirst',
            options: {
              cacheName: 'yaytsa-audio-0.0.0-placeholder',
              plugins: [
                {
                  cacheKeyWillBeUsed: async ({ request }: { request: Request }) => {
                    const url = new URL(request.url);
                    return url.origin + url.pathname;
                  },
                },
              ],
              expiration: {
                maxEntries: 50,
                maxAgeSeconds: 30 * 24 * 60 * 60,
                purgeOnQuotaError: true,
              },
              cacheableResponse: {
                statuses: [200, 206],
              },
              rangeRequests: true,
            },
          },
        ],
        cleanupOutdatedCaches: true,
        skipWaiting: true,
        clientsClaim: true,
      },
      manifest: false,
      devOptions: {
        enabled: false,
        type: 'module',
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
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
        allowedHosts: [
          'localhost',
          '127.0.0.1',
          'vite-server',
          'app-dev',
          'host.docker.internal',
          'frontend',
        ],
        proxy: {
          '/api': {
            target: process.env.YAYTSA_BACKEND_URL || 'http://localhost:8096',
            changeOrigin: true,
          },
        },
      }
    : {
        strictPort: true,
        port: 5173,
        allowedHosts: [
          'localhost',
          '127.0.0.1',
          'vite-server',
          'app-dev',
          'host.docker.internal',
          'frontend',
        ],
        proxy: {
          '/api': {
            target: process.env.YAYTSA_BACKEND_URL || 'http://localhost:8096',
            changeOrigin: true,
          },
        },
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
    rollupOptions: {
      output: {
        manualChunks: id => {
          if (id.includes('node_modules')) {
            const modulePath = id.split('node_modules/')[1];
            const packageName = modulePath.split('/')[0];

            if (packageName === 'lucide-react') {
              return 'vendor-icons';
            }

            if (['react', 'react-dom', 'react-router'].some(pkg => packageName.startsWith(pkg))) {
              return 'vendor-react';
            }

            if (packageName === 'zustand' || packageName.startsWith('@tanstack')) {
              return 'vendor-state';
            }

            return 'vendor-utils';
          }

          if (id.includes('@yaytsa/core')) {
            return 'vendor-core';
          }

          if (id.includes('@yaytsa/platform')) {
            return 'vendor-platform';
          }
        },
      },
    },
  },
  optimizeDeps: {
    include: ['@yaytsa/core', '@yaytsa/platform'],
  },
});
