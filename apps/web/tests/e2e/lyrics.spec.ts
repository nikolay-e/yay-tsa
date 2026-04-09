import { test, expect } from './fixtures/playback.fixture';
import { LYRICS_TEST_IDS } from './helpers/test-ids';

async function openFullPlayerIfMobile(page: import('@playwright/test').Page): Promise<void> {
  const openButton = page.locator('[data-testid="player-bar"] button[aria-label="Open player"]');
  if (await openButton.isVisible().catch(() => false)) {
    await openButton.click();
    await expect(page.getByRole('button', { name: /lyrics/i })).toBeVisible({ timeout: 5000 });
  }
}

test.describe('Lyrics', () => {
  test.beforeEach(async ({ playAlbumFromLibrary }) => {
    await playAlbumFromLibrary();
  });

  test('should open lyrics overlay via button', async ({ authenticatedPage }) => {
    await openFullPlayerIfMobile(authenticatedPage);

    const lyricsButton = authenticatedPage.getByRole('button', { name: 'Show lyrics' });
    await expect(lyricsButton).toBeVisible({ timeout: 5000 });
    await lyricsButton.click();

    const overlay = authenticatedPage.getByTestId(LYRICS_TEST_IDS.OVERLAY);
    await expect(overlay).toBeVisible({ timeout: 5000 });
  });

  test('should close lyrics via button and Escape', async ({ authenticatedPage }) => {
    await openFullPlayerIfMobile(authenticatedPage);

    const lyricsButton = authenticatedPage.getByRole('button', { name: 'Show lyrics' });
    await lyricsButton.click();

    const overlay = authenticatedPage.getByTestId(LYRICS_TEST_IDS.OVERLAY);
    await expect(overlay).toBeVisible({ timeout: 5000 });

    const closeButton = authenticatedPage.getByTestId(LYRICS_TEST_IDS.CLOSE_BUTTON);
    await closeButton.click();
    await expect(overlay).not.toBeVisible();

    await lyricsButton.click();
    await expect(overlay).toBeVisible({ timeout: 5000 });

    await authenticatedPage.keyboard.press('Escape');
    await expect(overlay).not.toBeVisible();
  });

  test('should display lyrics text or no-lyrics state', async ({ authenticatedPage }) => {
    await openFullPlayerIfMobile(authenticatedPage);

    const lyricsButton = authenticatedPage.getByRole('button', { name: 'Show lyrics' });
    await lyricsButton.click();

    const content = authenticatedPage.getByTestId(LYRICS_TEST_IDS.CONTENT);
    await expect(content).toBeVisible({ timeout: 5000 });

    await expect(async () => {
      const hasSearchButton = await authenticatedPage
        .getByRole('button', { name: /Search Lyrics|Try Again/ })
        .isVisible()
        .catch(() => false);
      const hasNotFound = await authenticatedPage
        .getByTestId(LYRICS_TEST_IDS.NOT_FOUND)
        .isVisible()
        .catch(() => false);
      const hasLyricsText =
        (await content.getByTestId(LYRICS_TEST_IDS.LINE).count()) > 0 ||
        (await content
          .getByText(/Lyrics are not time-synced/)
          .isVisible()
          .catch(() => false));

      expect(hasSearchButton || hasNotFound || hasLyricsText).toBe(true);
    }).toPass({ timeout: 10000 });
  });

  test('should show loading state during fetch', async ({ authenticatedPage }) => {
    await openFullPlayerIfMobile(authenticatedPage);

    const lyricsButton = authenticatedPage.getByRole('button', { name: 'Show lyrics' });
    await lyricsButton.click();

    const overlay = authenticatedPage.getByTestId(LYRICS_TEST_IDS.OVERLAY);
    await expect(overlay).toBeVisible({ timeout: 5000 });

    const searchButton = authenticatedPage.getByRole('button', { name: 'Search Lyrics' });
    const searchVisible = await searchButton.isVisible().catch(() => false);
    if (searchVisible) {
      await searchButton.click();
      const loading = authenticatedPage.getByTestId(LYRICS_TEST_IDS.LOADING);
      await expect(async () => {
        const isLoading = await loading.isVisible().catch(() => false);
        const hasResult = await authenticatedPage
          .getByTestId(LYRICS_TEST_IDS.NOT_FOUND)
          .isVisible()
          .catch(() => false);
        expect(isLoading || hasResult).toBe(true);
      }).toPass({ timeout: 15000 });
    }
  });
});
