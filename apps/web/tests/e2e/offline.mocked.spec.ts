import { test, expect, type Page, type Route } from '@playwright/test';

// Backend-free offline-audio suite (chromium-mocked project): every /api/* call
// is stubbed, so it runs without a live backend. It proves the behavioural
// guarantee that matters for "works offline":
//
//   download an album → the track survives a full reload (offline library is
//   hydrated from IndexedDB) → with the network offline it still plays from the
//   local blob, currentTime advances, and no request hits the stream endpoint.
//
// It deliberately uses the album page + /offline page (both non-virtualised
// lists) rather than the virtualised /songs list, which does not render reliably
// under the headless mocked harness.
//
// The service-worker app-shell fallback (offline route loads after a cold start)
// is validated against the production build separately — the dev server used
// here ships no service worker.
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

// Minimal valid 8-bit mono PCM WAV (~3s of silence) — small but decodable, so
// Chromium can actually play it and advance currentTime.
function buildWav(): Buffer {
  const sampleRate = 8000;
  const seconds = 3;
  const numSamples = sampleRate * seconds;
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + numSamples, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate, 28); // byte rate (1 byte/sample)
  header.writeUInt16LE(1, 32); // block align
  header.writeUInt16LE(8, 34); // bits per sample
  header.write('data', 36);
  header.writeUInt32LE(numSamples, 40);
  const data = Buffer.alloc(numSamples, 128); // 8-bit silence is 128
  return Buffer.concat([header, data]);
}

const WAV = buildWav();
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64'
);

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
}

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill('mock-user');
  await page.getByLabel('Password').fill('mock-pass');
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL('/', { timeout: 15000 });
}

// NOTE: marked test.fixme until validated on a machine with a browser. The
// offline data layer (IndexedDb store + outbox collapse) is covered by the
// vitest suites; this is the ready browser-level proof. It could not be
// validated from the build sandbox — the Chromium download and the CI artifact
// (screenshot/trace) are both network-blocked there, so the track-list page
// rendering under the mocked harness needs a real run to debug (the same reason
// favorites-sync.mocked.spec.ts is left as fixme). Un-fixme and run:
//   npx playwright test --project=chromium-mocked offline.mocked.spec.ts
test.describe('Offline audio (mocked backend)', () => {
  test('download → survives reload from IndexedDB → plays offline from local blob', async ({
    page,
    context,
  }) => {
    await mockApi(page);
    await login(page);

    // 1. Download the album from the (non-virtualised) album page.
    await page.goto('/albums/album-1');
    await expect(page.getByTestId('track-row')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('download-album-button').click();
    await expect(page.getByTestId('download-album-button')).toContainText('Downloaded', {
      timeout: 15000,
    });

    // 2. Full reload onto the offline library. It reads only IndexedDB, so the
    //    track appearing here proves it was persisted, not re-fetched.
    await page.goto('/offline');
    await expect(page.getByTestId('track-row')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('track-title')).toHaveText(TRACK.Name);

    // 3. Go offline for real and record any hit to the stream endpoint.
    const streamRequests: string[] = [];
    let offline = false;
    page.on('request', req => {
      if (offline && /\/Audio\/.*\/stream/.test(req.url())) streamRequests.push(req.url());
    });
    await context.setOffline(true);
    offline = true;

    // 4. Play from the offline library.
    await page.getByTestId('track-title').click();

    // The engine must be playing a local blob: URL, not a network stream.
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
      .toContain('blob:');

    // currentTime advances → audio genuinely decodes and plays offline.
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

    // No request ever reached the stream endpoint while offline.
    expect(streamRequests).toEqual([]);
  });

  test('liking a track auto-downloads it (no manual download click)', async ({ page }) => {
    await mockApi(page);
    await login(page);

    await page.goto('/albums/album-1');
    await expect(page.getByTestId('track-row')).toBeVisible({ timeout: 15000 });

    // Like the track — with "auto-download liked songs" on by default this alone
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
