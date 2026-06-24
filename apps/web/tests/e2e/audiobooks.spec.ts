import { type Page } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { test as playbackTest } from './fixtures/playback.fixture';

// Live-backend Audiobooks acceptance suite (chromium / mobile / webkit projects). Unlike the
// audiobooks.mocked.spec.ts UI suite (which fully passes), these exercise real audio playback +
// durable resume across a reload, which needs:
//   1. a running yay-tsa-v2 backend with a seeded library, AND
//   2. at least one track whose genre is "Audiobook" (the marker that drives audiobook mode and
//      the /v1/me/audiobooks tab), AND
//   3. a machine with a real browser + audio output for the engine to load/seek/play.
//
// They are marked test.fixme until that seeded fixture lands in CI — mirroring the repository's
// existing offline e2e deferral (commit "defer offline e2e to a browser-validated run"). The
// resume merge + status lifecycle they assert is already proven deterministically by the backend
// integration suites (ResumePositionServiceTest, ResumeHttpIntegrationTest). Un-fixme once a
// genre=Audiobook track exists in the test library.

async function hasSeededAudiobook(page: Page): Promise<boolean> {
  await page.goto('/audiobooks');
  const cards = page.getByTestId('audiobook-card');
  return (await cards.count()) > 0;
}

const audioCurrentTime = (page: Page) =>
  page.evaluate(() => document.querySelector('audio')?.currentTime ?? 0);

test.describe('Audiobooks — durable resume (live backend)', () => {
  // Acceptance #10 / #1: play → pause mid-way → reload app → position restored.
  test.fixme('play audiobook → pause → reload → resume exact position', async ({ page }) => {
    test.skip(
      !(await hasSeededAudiobook(page)),
      'No genre=Audiobook track seeded in the test library'
    );

    await page.getByTestId('audiobook-resume').first().click();
    await expect(page.getByTestId('play-pause-button')).toBeVisible({ timeout: 15000 });
    await page.waitForTimeout(5000);
    await page.getByTestId('play-pause-button').click(); // pause
    const before = await audioCurrentTime(page);
    expect(before).toBeGreaterThan(2);

    await page.reload();
    await page.getByTestId('audiobook-resume').first().click();
    await page.waitForTimeout(3000);
    const after = await audioCurrentTime(page);
    expect(Math.abs(after - before)).toBeLessThan(20);
  });

  // Acceptance #2: offline cold start still shows the downloaded book.
  test.fixme('offline cold start still shows the audiobooks tab', async ({ page, context }) => {
    test.skip(
      !(await hasSeededAudiobook(page)),
      'No genre=Audiobook track seeded in the test library'
    );

    await context.setOffline(true);
    await page.reload();
    await expect(page.getByTestId('audiobooks-page')).toBeVisible();
    await context.setOffline(false);
  });
});

// Acceptance #4: music behaviour is untouched — audiobook controls never leak into music playback.
playbackTest.describe('Music playback unaffected by audiobook mode (live backend)', () => {
  playbackTest.fixme(
    'music track shows no audiobook controls',
    async ({ playAlbumFromLibrary, authenticatedPage }) => {
      await playAlbumFromLibrary();
      await expect(authenticatedPage.getByTestId('audiobook-back-15')).toHaveCount(0);
      await expect(authenticatedPage.getByTestId('audiobook-forward-30')).toHaveCount(0);
      await expect(authenticatedPage.getByTestId('audiobook-speed')).toHaveCount(0);
    }
  );
});
