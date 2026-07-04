import { test, expect } from './fixtures/console-guard.fixture';
import { installBaseMock, login } from './helpers/media-fixtures';

// Backend-free verification (chromium-mocked project) that a stale-chunk fetch
// failure (Vite's `vite:preloadError`, fired when a lazy route's hashed JS chunk
// 404s after a deploy replaces it) triggers exactly one full page reload instead
// of leaving the user stuck on the ErrorBoundary's "Something went wrong" screen.
test.describe('Chunk-load recovery (mocked backend)', () => {
  test('vite:preloadError triggers a single page reload, not a reload loop', async ({ page }) => {
    installBaseMock(page);
    await login(page);

    const reloadedOnce = page.waitForEvent('load');
    await page.evaluate(() => {
      window.dispatchEvent(new Event('vite:preloadError', { cancelable: true }));
    });
    await reloadedOnce;

    // A second preloadError right after the reload must NOT trigger another
    // reload within the same guard window — assert the page stays put.
    let reloadedAgain = false;
    page.once('load', () => {
      reloadedAgain = true;
    });
    await page.evaluate(() => {
      window.dispatchEvent(new Event('vite:preloadError', { cancelable: true }));
    });
    await page.waitForTimeout(500);
    expect(reloadedAgain).toBe(false);
  });

  test('a third preloadError after the guard window elapses triggers another reload', async ({
    page,
  }) => {
    installBaseMock(page);
    await login(page);

    const firstReload = page.waitForEvent('load');
    await page.evaluate(() => {
      window.dispatchEvent(new Event('vite:preloadError', { cancelable: true }));
    });
    await firstReload;

    // Backdate the stored reload timestamp past the guard window instead of
    // waiting 5 real seconds — proves the guard self-expires by elapsed time,
    // not by a boot-anchored timer.
    await page.evaluate(() => {
      sessionStorage.setItem('yaytsa_chunk_preload_reloaded_at', String(Date.now() - 6000));
    });

    const secondReload = page.waitForEvent('load');
    await page.evaluate(() => {
      window.dispatchEvent(new Event('vite:preloadError', { cancelable: true }));
    });
    await secondReload;
    expect(
      await page.evaluate(() => sessionStorage.getItem('yaytsa_chunk_preload_reloaded_at'))
    ).not.toBeNull();
  });
});
