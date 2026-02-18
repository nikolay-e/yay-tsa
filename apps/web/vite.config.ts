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
        globPatterns: ['**/*.{js,css,ico,png,svg,woff,woff2}'],
        navigateFallback: null,
        runtimeCaching: [
          {
            urlPattern: ({ request }) => request.mode === 'navigate',
            handler: 'NetworkFirst',
            options: {
              cacheName: `yaytsa-navigation-${process.env.VITE_APP_VERSION || 'dev'}`,
              networkTimeoutSeconds: 10,
            },
          },
          {
            urlPattern: ({ url }) =>
              url.pathname.includes('/Items/') && url.pathname.includes('/Images/'),
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: `yaytsa-images-${process.env.VITE_APP_VERSION || 'dev'}`,
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
            handler: 'NetworkOnly',
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
      '@app': path.resolve(__dirname, './src/app'),
      '@pages': path.resolve(__dirname, './src/pages'),
      '@features': path.resolve(__dirname, './src/features'),
      '@shared': path.resolve(__dirname, './src/shared'),
    },
  },
  server: {
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
        rewrite: urlPath => urlPath.replace(/^\/api/, ''),
      },
    },
    ...(httpsEnabled
      ? {
          https: {
            key: fs.readFileSync(keyPath),
            cert: fs.readFileSync(certPath),
          },
          hmr: {
            protocol: 'wss',
          },
        }
      : {}),
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

          if (id.includes('@yay-tsa/core')) {
            return 'vendor-core';
          }

          if (id.includes('@yay-tsa/platform')) {
            return 'vendor-platform';
          }
        },
      },
    },
  },
  optimizeDeps: {
    exclude: ['@yay-tsa/core', '@yay-tsa/platform'],
  },
});
