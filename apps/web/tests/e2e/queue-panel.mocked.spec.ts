import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { installBaseMock, login, silentWav } from './helpers/media-fixtures';

// Backend-free queue-panel suite (chromium-mocked project): every /api/* call is stubbed and audio
// streams are served as generated silent WAVs, so this runs without a live backend. It exercises the
// real queue UI in a browser: starting a queue from the Search track list, opening the queue panel from the
// player bar, jumping to an upcoming track, removing a track, and the Play next / Add to queue row
// actions.
//
//   npx playwright test --project=chromium-mocked queue-panel.mocked.spec.ts

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

function installMock(page: Page): void {
  installBaseMock(page);

  void page.route(/\/Items(\?|$)/, (route: Route) => {
    const items = CATALOG.map(t => buildTrack(t.Id, t.Name));
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: items, TotalRecordCount: items.length, StartIndex: 0 }),
    });
  });

  // Range-aware stream mock: without Accept-Ranges/206 support Chromium marks the
  // media element non-seekable (el.seekable stays empty) and every seek snaps back
  // to 0, which would falsify the arrow-key seek coverage below.
  const wav = silentWav();
  void page.route(/\/Audio\/[^/?]+\/stream/, (route: Route) => {
    const range = route.request().headers()['range'];
    const match = range ? /bytes=(\d+)-(\d*)/.exec(range) : null;
    if (!match) {
      return route.fulfill({
        status: 200,
        body: wav,
        headers: {
          'Content-Type': 'audio/wav',
          'Accept-Ranges': 'bytes',
          'Content-Length': String(wav.length),
        },
      });
    }
    const start = Number(match[1]);
    const end = match[2] ? Math.min(Number(match[2]), wav.length - 1) : wav.length - 1;
    return route.fulfill({
      status: 206,
      body: wav.subarray(start, end + 1),
      headers: {
        'Content-Type': 'audio/wav',
        'Accept-Ranges': 'bytes',
        'Content-Range': `bytes ${start}-${end}/${wav.length}`,
        'Content-Length': String(end - start + 1),
      },
    });
  });
}

async function startQueueFromSongs(page: Page): Promise<void> {
  await page.goto('/search');
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

  test('drag handle reorders the queue without interrupting playback', async ({ page }) => {
    installMock(page);
    await login(page);
    await startQueueFromSongs(page);

    await page.getByTestId('queue-button').click();
    await expect(page.getByTestId('queue-panel')).toBeVisible();
    await expect(page.getByTestId('queue-item')).toHaveCount(4);

    // exact: true skips dnd-kit's wrapper (role="button" whose accessible name
    // contains the handle label as a substring) and matches only the handle itself.
    const handles = page.getByRole('button', { name: 'Drag to reorder', exact: true });
    await expect(handles).toHaveCount(4);
    const sourceBox = await handles.nth(3).boundingBox();
    const targetBox = await page.getByTestId('queue-item').nth(1).boundingBox();
    if (!sourceBox || !targetBox) throw new Error('Drag geometry unavailable');

    await page.mouse.move(sourceBox.x + sourceBox.width / 2, sourceBox.y + sourceBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(targetBox.x + targetBox.width / 2, targetBox.y + targetBox.height / 2, {
      steps: 12,
    });
    await page.mouse.up();

    await expect(page.getByTestId('queue-item-title')).toHaveText([
      'Aurora',
      'Drift',
      'Borealis',
      'Cascade',
    ]);
    await expect(page.getByTestId('queue-item').filter({ hasText: 'Aurora' })).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(page.locator('[data-testid="current-track-title"]:visible')).toHaveText('Aurora');
    const isPlaying = await page.evaluate(
      () =>
        (window as unknown as { __playerStore__?: { isPlaying: boolean } }).__playerStore__
          ?.isPlaying ?? false
    );
    expect(isPlaying).toBe(true);
  });

  test('global hotkeys control playback without hijacking focused controls', async ({ page }) => {
    installMock(page);
    await login(page);
    await startQueueFromSongs(page);

    const playPause = page.locator('[data-testid="play-pause-button"]:visible');
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());

    // Key presses only after the engine holds real metadata — a seek issued before
    // the media is ready is dropped by the browser, like any pre-load UI seek.
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
        { timeout: 10000 }
      )
      .toBeGreaterThan(0.2);

    await page.keyboard.press('Space');
    await expect(playPause).toHaveAttribute('aria-label', 'Play');
    await page.keyboard.press('Space');
    await expect(playPause).toHaveAttribute('aria-label', 'Pause');

    await page.keyboard.press('ArrowRight');
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
        { timeout: 5000 }
      )
      .toBeGreaterThan(8);

    await page.keyboard.press('Shift+N');
    await expect(page.locator('[data-testid="current-track-title"]:visible')).toHaveText(
      'Borealis',
      { timeout: 10000 }
    );

    await page.getByTestId('queue-button').focus();
    await page.keyboard.press('Space');
    await expect(page.getByTestId('queue-panel')).toBeVisible();
    const stillPlaying = await page.evaluate(
      () =>
        (window as unknown as { __playerStore__?: { isPlaying: boolean } }).__playerStore__
          ?.isPlaying ?? false
    );
    expect(stillPlaying).toBe(true);
  });
});
