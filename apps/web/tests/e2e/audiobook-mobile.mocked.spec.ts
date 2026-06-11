import { test, expect, type Page, type Route } from '@playwright/test';

// Backend-free mobile audiobook-controls suite (chromium-mocked project, mobile viewport): all
// /api/* calls are stubbed and audio streams are generated WAVs. It proves the full-screen mobile
// player swaps shuffle/repeat for ±15/30s skip buttons and exposes the speed control when an
// audiobook is playing — and keeps the music controls untouched for a regular track.
//
//   npx playwright test --project=chromium-mocked audiobook-mobile.mocked.spec.ts

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true });

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };

function buildTrack(id: string, name: string, genres: string[]) {
  return {
    Id: id,
    Name: name,
    Type: 'Audio',
    Genres: genres,
    RunTimeTicks: 600_000_000,
    Album: 'Skyline',
    AlbumId: 'al1',
    AlbumPrimaryImageTag: 'tag1',
    Artists: ['Nova'],
    ArtistItems: [{ Id: 'ar1', Name: 'Nova' }],
    IndexNumber: 1,
    UserData: { PlaybackPositionTicks: 0, PlayCount: 0, IsFavorite: false, Played: false },
  };
}

function silentWav(seconds = 30): Buffer {
  const sampleRate = 8000;
  const numSamples = sampleRate * seconds;
  const dataSize = numSamples * 2;
  const buf = Buffer.alloc(44 + dataSize);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + dataSize, 4);
  buf.write('WAVE', 8);
  buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(dataSize, 40);
  return buf;
}

function installMock(page: Page, opts: { audiobook: boolean }): void {
  void page.route('**/api/**', (route: Route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: [], TotalRecordCount: 0, StartIndex: 0 }),
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  void page.route(/\/Users\/AuthenticateByName/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ AccessToken: VALID_TOKEN, User: USER, SessionInfo: {} }),
    })
  );
  void page.route(/\/Users\/Me(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(USER) })
  );

  void page.route(/\/Items(\?|$)/, (route: Route) => {
    const items = [buildTrack('b1', 'Chapter One', opts.audiobook ? ['Audiobook'] : ['Rock'])];
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: items, TotalRecordCount: items.length, StartIndex: 0 }),
    });
  });

  void page.route(/\/Audio\/[^/?]+\/stream/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'audio/wav', body: silentWav() })
  );

  void page.route(/\/Images\//, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
        'base64'
      ),
    })
  );

  void page.route(/\/v1\/me\/devices(\?|$)/, (route: Route) =>
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

async function openFullPlayer(page: Page): Promise<void> {
  await page.goto('/songs');
  await page.getByTestId('track-title').filter({ hasText: 'Chapter One' }).first().click();
  await expect(page.getByTestId('player-bar')).toBeVisible({ timeout: 10000 });
  await page.getByRole('button', { name: 'Open player' }).click();
}

test.describe('Mobile full player audiobook controls (mocked backend)', () => {
  test('audiobook shows skip and speed controls instead of shuffle/repeat', async ({ page }) => {
    installMock(page, { audiobook: true });
    await login(page);
    await openFullPlayer(page);

    await expect(page.locator('[data-testid="audiobook-back-15"]:visible')).toBeVisible();
    await expect(page.locator('[data-testid="audiobook-forward-30"]:visible')).toBeVisible();

    const speed = page.locator('[data-testid="audiobook-speed"]:visible');
    await expect(speed).toBeVisible();
    await expect(speed).toHaveText('1x');
    await speed.click();
    await expect(speed).toHaveText('1.25x');

    await expect(page.locator('button[aria-label="Shuffle"]:visible')).toHaveCount(0);
    await expect(page.locator('button[aria-label^="Repeat"]:visible')).toHaveCount(0);
  });

  test('music track keeps shuffle/repeat and hides audiobook controls', async ({ page }) => {
    installMock(page, { audiobook: false });
    await login(page);
    await openFullPlayer(page);

    await expect(page.locator('button[aria-label="Shuffle"]:visible')).toBeVisible();
    await expect(page.locator('button[aria-label^="Repeat"]:visible')).toBeVisible();
    await expect(page.locator('[data-testid="audiobook-back-15"]:visible')).toHaveCount(0);
    await expect(page.locator('[data-testid="audiobook-speed"]:visible')).toHaveCount(0);
  });
});
