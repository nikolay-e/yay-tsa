import { test, expect, type Page, type Route } from '@playwright/test';
import { installBaseMock, login, silentWav } from './helpers/media-fixtures';

// Backend-free mobile audiobook-controls suite (chromium-mocked project, mobile viewport): all
// /api/* calls are stubbed and audio streams are generated WAVs. It proves the full-screen mobile
// player swaps shuffle/repeat for ±15/30s skip buttons and exposes the speed control when an
// audiobook is playing — and keeps the music controls untouched for a regular track.
//
//   npx playwright test --project=chromium-mocked audiobook-mobile.mocked.spec.ts

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true });

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

function installMock(page: Page, opts: { audiobook: boolean }): void {
  installBaseMock(page);

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
