import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './page-objects/LibraryPage';
import { AlbumPage } from './page-objects/AlbumPage';
import { PlayerBar } from './page-objects/PlayerBar';
import { PLAYER_TEST_IDS } from './helpers/test-ids';

const test = baseTest;

test.describe('Sleep Timer', () => {
  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;
  let playerBar: PlayerBar;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    playerBar = new PlayerBar(authenticatedPage);
    await libraryPage.goto();
  });

  test('should open sleep timer modal', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await expect(sleepTimerButton).toBeVisible({ timeout: 5000 });
    await sleepTimerButton.click();

    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });
  });

  test('should start sleep timer with preset', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await sleepTimerButton.click();

    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });

    const preset15min = authenticatedPage.getByRole('button', { name: '15 min' });
    await preset15min.click();

    await expect(sleepTimerButton).toHaveClass(/text-accent/, { timeout: 5000 });
    const badge = sleepTimerButton.locator('span');
    await expect(badge).toBeVisible({ timeout: 5000 });
  });

  test('should display remaining time when sleep timer is active', async ({
    authenticatedPage,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await sleepTimerButton.click();

    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });

    const preset15min = authenticatedPage.getByRole('button', { name: '15 min' });
    await preset15min.click();

    const badge = sleepTimerButton.locator('span');
    await expect(badge).toBeVisible({ timeout: 5000 });
    const badgeText = await badge.textContent();
    const remainingMinutes = Number(badgeText);
    expect(remainingMinutes).toBeGreaterThan(0);
    expect(remainingMinutes).toBeLessThanOrEqual(15);
  });

  test('should cancel sleep timer', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await sleepTimerButton.click();

    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });

    const preset15min = authenticatedPage.getByRole('button', { name: '15 min' });
    await preset15min.click();

    const badge = sleepTimerButton.locator('span');
    await expect(badge).toBeVisible({ timeout: 5000 });

    await sleepTimerButton.click();
    await expect(modal).toBeVisible({ timeout: 5000 });

    const cancelButton = authenticatedPage.getByRole('button', { name: /cancel/i });
    await cancelButton.click();

    await expect(badge).not.toBeVisible({ timeout: 5000 });
    await expect(sleepTimerButton).not.toHaveClass(/text-accent/, { timeout: 5000 });
  });

  test('should maintain playback during sleep timer music phase', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await sleepTimerButton.click();

    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });

    const preset15min = authenticatedPage.getByRole('button', { name: '15 min' });
    await preset15min.click();

    const badge = sleepTimerButton.locator('span');
    await expect(badge).toBeVisible({ timeout: 5000 });
    await playerBar.waitForPlayingState();
    expect(await playerBar.isPlaying()).toBe(true);
  });
});
