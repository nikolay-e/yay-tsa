import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { buildWav, TRANSPARENT_PNG, stubSse } from './helpers/media-fixtures';

// Backend-free offline-audio suite (chromium-mocked project): every /api/* call
// is stubbed, so it runs without a live backend. It proves the behavioural
// guarantee that matters for "works offline":
//
//   download an album → the track survives a full reload (offline library is
//   hydrated from IndexedDB) → with the network offline the audio-offline service
//   worker serves the downloaded bytes from IndexedDB, currentTime advances, and the
//   player points at the same-origin /Audio/{id}/stream URL (never a blob:, so a
//   strict `media-src 'self'` CSP is satisfied).
//
// It deliberately uses the album page + /offline page (both non-virtualised
// lists) rather than the virtualised /search track list, which does not render reliably
// under the headless mocked harness.
//
// In production the audio handler is importScripts'd into the generated Workbox
// service worker; the Vite dev server ships no service worker, so the test
// registers public/audio-offline-sw.js directly to exercise the real offline path.
//
//   npx playwright test --project=chromium-mocked offline.mocked.spec.ts

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };

const ALBUM = {
  Id: 'album-1',
  Name: 'Test Album',
  Type: 'MusicAlbum',
  ServerId: 'server-1',
  Artists: ['Tester'],
  ProductionYear: 2020,
  ImageTags: {},
  UserData: { IsFavorite: false, PlaybackPositionTicks: 0, PlayCount: 0, Played: false },
};

const TRACK = {
  Id: 'track-1',
  Name: 'Offline Test Track',
  Type: 'Audio',
  ServerId: 'server-1',
  RunTimeTicks: 30_000_000,
  Album: 'Test Album',
  AlbumId: 'album-1',
  Artists: ['Tester'],
  ArtistItems: [{ Id: 'artist-1', Name: 'Tester' }],
  IndexNumber: 1,
  UserData: { IsFavorite: false, PlaybackPositionTicks: 0, PlayCount: 0, Played: false },
};

const WAV = buildWav();
const PNG = TRANSPARENT_PNG;

async function mockApi(page: Page): Promise<void> {
  // Benign catch-all first; specific routes registered after take precedence.
  await page.route('**/api/**', (route: Route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: [], TotalRecordCount: 0 }),
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  // Track list (album tracks query hits /Items?ParentId=...).
  await page.route(/\/Items(\?|$)/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: [TRACK], TotalRecordCount: 1 }),
    })
  );

  // Favorites queries (reconcile + favorites page) return nothing by default, so
  // the auto-favorite reconcile is a no-op unless a test explicitly likes a track.
  // Registered after the generic /Items route so it wins for IsFavorite requests.
  await page.route(/IsFavorite=true/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: [], TotalRecordCount: 0 }),
    })
  );

  // getItem(track-1) — used by auto-download-on-like to resolve the full track.
  await page.route(/\/Items\/track-1/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TRACK) })
  );

  // Single album fetch (getItem).
  await page.route(/\/Items\/album-1/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ALBUM) })
  );

  await page.route(/\/Audio\/.*\/stream/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'audio/wav', body: WAV })
  );

  await page.route(/\/Images\//, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: PNG })
  );

  await page.route(/\/Users\/AuthenticateByName/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ AccessToken: VALID_TOKEN, User: USER, SessionInfo: {} }),
    })
  );

  await page.route(/\/Users\/Me(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(USER) })
  );

  // Device list returns a bare array (not a Jellyfin {Items} envelope); without
  // this the catch-all shape crashes useActiveRemotePlayback's devices.filter.
  await page.route(/\/v1\/me\/devices(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  );
  stubSse(page);
}

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill('mock-user');
  await page.getByLabel('Password').fill('mock-pass');
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL('/', { timeout: 15000 });
}

test.describe('Offline audio (mocked backend)', () => {
  test('download → survives reload from IndexedDB → plays offline via the service worker', async ({
    page,
    context,
  }) => {
    await mockApi(page);
    await login(page);

    // 1. Download the album from the (non-virtualised) album page.
    await page.goto('/albums/album-1');
    await expect(page.getByTestId('track-row')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('download-album-button').click();
    await expect(page.getByTestId('download-album-button')).toHaveAttribute(
      'aria-label',
      'Downloaded',
      {
        timeout: 15000,
      }
    );

    // 2. Full reload onto the offline library. It reads only IndexedDB, so the
    //    track appearing here proves it was persisted, not re-fetched.
    await page.goto('/offline');
    await expect(page.getByTestId('track-row')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('track-title')).toHaveText(TRACK.Name);

    // 3. Register the audio-offline service worker (in production it is importScripts'd
    //    into the Workbox SW; the dev server ships none) and reload so it controls the
    //    page — it owns /Audio/{id}/stream and serves downloaded bytes from IndexedDB.
    await page.evaluate(async () => {
      await navigator.serviceWorker.register('/audio-offline-sw.js');
      await navigator.serviceWorker.ready;
    });
    await page.reload();
    await expect(page.getByTestId('track-title')).toHaveText(TRACK.Name, { timeout: 15000 });
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, {
      timeout: 15000,
    });

    // 4. Drop the stream mock and cut the network: from here the only path to audio bytes
    //    is the service worker reading them out of IndexedDB.
    await page.unroute(/\/Audio\/.*\/stream/);
    await context.setOffline(true);

    // 5. Play from the offline library.
    await page.getByTestId('track-title').click();

    // The engine plays the same-origin stream URL (never blob:), satisfying media-src 'self'.
    await expect
      .poll(
        async () =>
          page.evaluate(() => {
            const handle = (
              window as unknown as {
                __playerStore__?: {
                  audioEngine?: { getAudioElement?: () => { src?: string } | null };
                };
              }
            ).__playerStore__;
            return handle?.audioEngine?.getAudioElement?.()?.src ?? '';
          }),
        { timeout: 15000 }
      )
      .toContain('/Audio/track-1/stream');

    // currentTime advances → the worker decoded real bytes from IndexedDB with the
    // network down and the stream mock removed, proving genuine offline playback.
    await expect
      .poll(
        async () =>
          page.evaluate(() => {
            const handle = (
              window as unknown as {
                __playerStore__?: { audioEngine?: { getCurrentTime?: () => number } };
              }
            ).__playerStore__;
            return handle?.audioEngine?.getCurrentTime?.() ?? 0;
          }),
        { timeout: 15000 }
      )
      .toBeGreaterThan(0.1);
  });

  test('liking a track auto-downloads it (no manual download click)', async ({ page }) => {
    await mockApi(page);
    await login(page);

    // Auto-download of liked songs is opt-in; enable it through the real Settings toggle.
    await page.goto('/settings');
    const autoDownloadToggle = page.getByRole('switch', { name: 'Auto-download liked songs' });
    await expect(autoDownloadToggle).toHaveAttribute('aria-checked', 'false');
    await autoDownloadToggle.click();
    await expect(autoDownloadToggle).toHaveAttribute('aria-checked', 'true');

    await page.goto('/albums/album-1');
    await expect(page.getByTestId('track-row')).toBeVisible({ timeout: 15000 });

    // Like the track — with "auto-download liked songs" enabled this alone
    // must persist it offline.
    await page.getByRole('button', { name: 'Add track to favorites' }).click();

    // The per-track download control reaches the downloaded state on its own.
    await expect(page.getByTestId('download-button')).toHaveAttribute(
      'aria-label',
      'Remove download',
      { timeout: 15000 }
    );

    // And it shows up in the offline library.
    await page.goto('/offline');
    await expect(page.getByTestId('track-title')).toHaveText(TRACK.Name, { timeout: 15000 });
  });

  test('playing a track caches it for offline (no manual download click)', async ({ page }) => {
    await mockApi(page);
    await login(page);

    await page.goto('/albums/album-1');
    await expect(page.getByTestId('track-row')).toBeVisible({ timeout: 15000 });

    // Play the track — with "cache songs I play" on by default it is downloaded
    // in the background.
    await page.getByTestId('track-title').click();

    await expect(page.getByTestId('download-button')).toHaveAttribute(
      'aria-label',
      'Remove download',
      { timeout: 15000 }
    );

    await page.goto('/offline');
    await expect(page.getByTestId('track-title')).toHaveText(TRACK.Name, { timeout: 15000 });
  });
});
