/**
 * Service Worker for Jellyfin Mini Client PWA
 * Implements caching strategies for offline functionality
 */

/* eslint-disable no-console */
// Console logging is essential for service worker debugging

const CACHE_VERSION = 'v1';
const CACHE_NAME = `yaytsa-${CACHE_VERSION}`;
const API_CACHE_NAME = `jellyfin-api-${CACHE_VERSION}`;

// Files to cache immediately on install
const PRECACHE_URLS = ['/', '/manifest.json', '/favicon.png'];

// Install event - precache app shell
self.addEventListener('install', event => {
  console.info('[Service Worker] Installing...');

  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then(cache => {
        console.info('[Service Worker] Precaching app shell');
        return cache.addAll(PRECACHE_URLS);
      })
      .then(() => {
        // Force activation of new service worker
        return self.skipWaiting();
      })
  );
});

// Activate event - clean up old caches
self.addEventListener('activate', event => {
  console.info('[Service Worker] Activating...');

  event.waitUntil(
    caches
      .keys()
      .then(cacheNames => {
        return Promise.all(
          cacheNames.map(cacheName => {
            // Delete old caches
            if (cacheName !== CACHE_NAME && cacheName !== API_CACHE_NAME) {
              console.info('[Service Worker] Deleting old cache:', cacheName);
              return caches.delete(cacheName);
            }
          })
        );
      })
      .then(() => {
        // Take control of all pages immediately
        return self.clients.claim();
      })
  );
});

// Sensitive endpoints that should NEVER be cached (security)
const NEVER_CACHE_PATTERNS = [
  '/Users/AuthenticateByName', // Authentication endpoint
  '/Users/AuthenticateWithQuickConnect', // Quick connect auth
  '/Users/AuthenticateByID', // Auth by ID
  '/Sessions/', // Active sessions
  '/System/Ping', // System ping
  '/Startup/', // Startup endpoints
];

// Fetch event - implement caching strategies
self.addEventListener('fetch', event => {
  const { request } = event;
  const url = new URL(request.url);

  // Skip localhost requests in development (Vite HMR, dev server)
  if (url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]') {
    return;
  }

  // Skip cross-origin requests
  if (url.origin !== location.origin && !url.pathname.includes('/Items/')) {
    return;
  }

  // SECURITY: Never cache sensitive endpoints
  const isSensitive = NEVER_CACHE_PATTERNS.some(pattern =>
    url.pathname.includes(pattern.replace('{userId}', ''))
  );
  if (isSensitive) {
    // Always fetch from network, never cache
    event.respondWith(fetch(request));
    return;
  }

  // Network-first for API requests (Jellyfin server)
  // These are cached but with network-first strategy (always try network)
  if (
    url.pathname.includes('/Items/') ||
    url.pathname.includes('/Audio/') ||
    (url.pathname.includes('/Users/') && !isSensitive)
  ) {
    event.respondWith(networkFirst(request, API_CACHE_NAME));
    return;
  }

  // Cache-first for static assets
  if (
    request.destination === 'image' ||
    request.destination === 'style' ||
    request.destination === 'script' ||
    request.destination === 'font'
  ) {
    event.respondWith(cacheFirst(request, CACHE_NAME));
    return;
  }

  // Network-first for HTML pages (to get updates)
  if (request.destination === 'document' || request.mode === 'navigate') {
    event.respondWith(networkFirst(request, CACHE_NAME));
    return;
  }

  // Default: network-first
  event.respondWith(networkFirst(request, CACHE_NAME));
});

/**
 * Cache-first strategy
 * Use cached version if available, otherwise fetch and cache
 */
async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);

  if (cached) {
    return cached;
  }

  try {
    const response = await fetch(request);

    // Cache successful responses (200 OK only, not redirects or other 2xx)
    if (response.status === 200) {
      cache.put(request, response.clone());
    }

    return response;
  } catch (error) {
    console.error('[Service Worker] Fetch failed:', error);
    throw error;
  }
}

/**
 * Network-first strategy
 * Try network first, fall back to cache if offline
 */
async function networkFirst(request, cacheName) {
  const cache = await caches.open(cacheName);

  try {
    const response = await fetch(request);

    // Cache successful GET requests (200 OK only, not redirects or other 2xx)
    if (response.status === 200 && request.method === 'GET') {
      cache.put(request, response.clone());
    }

    return response;
  } catch (error) {
    console.warn('[Service Worker] Network failed, trying cache:', request.url);

    const cached = await cache.match(request);

    if (cached) {
      return cached;
    }

    // If no cache and this is a navigation request, return offline page
    if (request.mode === 'navigate') {
      const offlineResponse = new Response(
        `<!DOCTYPE html>
        <html>
          <head>
            <title>Offline - Jellyfin Mini Client</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                display: flex;
                align-items: center;
                justify-content: center;
                height: 100vh;
                margin: 0;
                background: #1a1a1a;
                color: #fff;
                text-align: center;
                padding: 20px;
              }
              h1 { color: #00a4dc; }
            </style>
          </head>
          <body>
            <div>
              <h1>You're Offline</h1>
              <p>Please check your internet connection and try again.</p>
            </div>
          </body>
        </html>`,
        {
          headers: { 'Content-Type': 'text/html' },
        }
      );
      return offlineResponse;
    }

    throw error;
  }
}

// Handle messages from clients
self.addEventListener('message', event => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }

  if (event.data && event.data.type === 'CLEAR_CACHE') {
    event.waitUntil(
      caches.keys().then(cacheNames => {
        return Promise.all(cacheNames.map(cacheName => caches.delete(cacheName)));
      })
    );
  }
});
