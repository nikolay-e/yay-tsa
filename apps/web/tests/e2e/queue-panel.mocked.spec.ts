import { test, expect, type Page, type Route } from '@playwright/test';

// Backend-free queue-panel suite (chromium-mocked project): every /api/* call is stubbed and audio
// streams are served as generated silent WAVs, so this runs without a live backend. It exercises the
// real queue UI in a browser: starting a queue from the Songs list, opening the queue panel from the
// player bar, jumping to an upcoming track, removing a track, and the Play next / Add to queue row
// actions.
//
//   npx playwright test --project=chromium-mocked queue-panel.mocked.spec.ts

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };

const CATALOG = [
  { Id: 't1', Name: 'Aurora' },
  { Id: 't2', Name: 'Borealis' },
  { Id: 't3', Name: 'Cascade' },
  { Id: 't4', Name: 'Drift' },
];

function buildTrack(id: string, name: string) {
  return {
    Id: id,
    Name: name,
    Type: 'Audio',
    RunTimeTicks: 300_000_000,
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

function installMock(page: Page): void {
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
    const items = CATALOG.map(t => buildTrack(t.Id, t.Name));
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

async function startQueueFromSongs(page: Page): Promise<void> {
  await page.goto('/songs');
  await page.getByTestId('track-title').filter({ hasText: 'Aurora' }).first().click();
  await expect(page.getByTestId('player-bar')).toBeVisible({ timeout: 10000 });
  await expect(page.locator('[data-testid="current-track-title"]:visible')).toHaveText('Aurora', {
    timeout: 10000,
  });
}

test.describe('Queue panel (mocked backend)', () => {
  test('opens from the player bar, jumps to a track and removes one', async ({ page }) => {
    installMock(page);
    await login(page);
    await startQueueFromSongs(page);

    await page.getByTestId('queue-button').click();
    await expect(page.getByTestId('queue-panel')).toBeVisible();
    await expect(page.getByTestId('queue-item')).toHaveCount(4);

    const currentItem = page.getByTestId('queue-item').filter({ hasText: 'Aurora' });
    await expect(currentItem).toHaveAttribute('aria-current', 'true');

    await page
      .getByTestId('queue-item')
      .filter({ hasText: 'Cascade' })
      .getByRole('button', { name: 'Play Cascade' })
      .click();
    await expect(page.locator('[data-testid="current-track-title"]:visible')).toHaveText(
      'Cascade',
      { timeout: 10000 }
    );
    await expect(page.getByTestId('queue-item').filter({ hasText: 'Cascade' })).toHaveAttribute(
      'aria-current',
      'true'
    );

    await page
      .getByTestId('queue-item')
      .filter({ hasText: 'Borealis' })
      .getByTestId('queue-item-remove')
      .click();
    await expect(page.getByTestId('queue-item')).toHaveCount(3);
    await expect(page.getByTestId('queue-item').filter({ hasText: 'Borealis' })).toHaveCount(0);
  });

  test('Play next and Add to queue from track rows reorder the queue', async ({ page }) => {
    installMock(page);
    await login(page);
    await startQueueFromSongs(page);

    const rowMenu = (trackName: string) =>
      page.getByTestId('track-row').filter({ hasText: trackName }).getByTestId('track-menu-button');

    await rowMenu('Cascade').click();
    await page.getByTestId('track-menu-play-next').click();
    await expect(page.getByText('Playing next')).toBeVisible();

    await rowMenu('Drift').click();
    await page.getByTestId('track-menu-add-queue').click();
    await expect(page.getByText('Added to queue')).toBeVisible();

    await page.getByTestId('queue-button').click();
    await expect(page.getByTestId('queue-panel')).toBeVisible();
    await expect(page.getByTestId('queue-item')).toHaveCount(6);
    await expect(page.getByTestId('queue-item-title').nth(1)).toHaveText('Cascade');
    await expect(page.getByTestId('queue-item-title').last()).toHaveText('Drift');
  });
});
