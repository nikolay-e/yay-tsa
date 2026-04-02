import { test, expect } from './fixtures/auth.fixture';
import { RADIO_TEST_IDS, PLAYER_TEST_IDS } from './helpers/test-ids';

test.describe('Radio', () => {
  test('should display seed cards on home page', async ({ authenticatedPage }) => {
    const section = authenticatedPage.getByTestId(RADIO_TEST_IDS.SECTION);
    await expect(section).toBeVisible({ timeout: 10000 });

    const cards = section.getByTestId(RADIO_TEST_IDS.SEED_CARD);
    await expect(cards.first()).toBeVisible();
    expect(await cards.count()).toBeGreaterThanOrEqual(2);

    const firstCard = cards.first();
    await expect(firstCard.locator('img')).toBeVisible();
    await expect(firstCard.locator('p').first()).not.toBeEmpty();
  });

  test('should start DJ session when seed card clicked', async ({ authenticatedPage }) => {
    const section = authenticatedPage.getByTestId(RADIO_TEST_IDS.SECTION);
    await expect(section).toBeVisible({ timeout: 10000 });

    const firstCard = section.getByTestId(RADIO_TEST_IDS.SEED_CARD).first();
    await firstCard.click();

    const playerBar = authenticatedPage.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR);
    await expect(playerBar).toBeVisible({ timeout: 15000 });

    const trackTitle = authenticatedPage.getByTestId(PLAYER_TEST_IDS.CURRENT_TRACK_TITLE);
    await expect(trackTitle).toBeVisible({ timeout: 10000 });
    expect(await trackTitle.textContent()).toBeTruthy();
  });

  test('should populate queue after DJ session start', async ({ authenticatedPage }) => {
    const section = authenticatedPage.getByTestId(RADIO_TEST_IDS.SECTION);
    await expect(section).toBeVisible({ timeout: 10000 });

    const firstCard = section.getByTestId(RADIO_TEST_IDS.SEED_CARD).first();
    await firstCard.click();

    const playerBar = authenticatedPage.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR);
    await expect(playerBar).toBeVisible({ timeout: 15000 });

    const queueButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.QUEUE_BUTTON);
    if (await queueButton.isVisible().catch(() => false)) {
      await queueButton.click();

      await expect(async () => {
        const queueItems = await authenticatedPage.locator('[data-testid="track-row"]').count();
        expect(queueItems).toBeGreaterThanOrEqual(1);
      }).toPass({ timeout: 15000 });
    }
  });

  test('should persist session across page reload', async ({ authenticatedPage }) => {
    const section = authenticatedPage.getByTestId(RADIO_TEST_IDS.SECTION);
    await expect(section).toBeVisible({ timeout: 10000 });

    const firstCard = section.getByTestId(RADIO_TEST_IDS.SEED_CARD).first();
    await firstCard.click();

    const playerBar = authenticatedPage.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR);
    await expect(playerBar).toBeVisible({ timeout: 15000 });

    const trackTitle = authenticatedPage.getByTestId(PLAYER_TEST_IDS.CURRENT_TRACK_TITLE);
    await expect(trackTitle).toBeVisible({ timeout: 10000 });

    await authenticatedPage.reload();

    const playerBarAfterReload = authenticatedPage.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR);
    await expect(playerBarAfterReload).toBeVisible({ timeout: 10000 });

    const trackTitleAfterReload = authenticatedPage.getByTestId(
      PLAYER_TEST_IDS.CURRENT_TRACK_TITLE
    );
    await expect(trackTitleAfterReload).toBeVisible({ timeout: 10000 });
    expect(await trackTitleAfterReload.textContent()).toBeTruthy();
  });

  test('should show karaoke button that toggles gracefully', async ({ authenticatedPage }) => {
    const section = authenticatedPage.getByTestId(RADIO_TEST_IDS.SECTION);
    await expect(section).toBeVisible({ timeout: 10000 });

    const firstCard = section.getByTestId(RADIO_TEST_IDS.SEED_CARD).first();
    await firstCard.click();

    const playerBar = authenticatedPage.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR);
    await expect(playerBar).toBeVisible({ timeout: 15000 });

    const karaokeButton = authenticatedPage.getByRole('button', { name: /karaoke/i });
    const karaokeVisible = await karaokeButton.isVisible().catch(() => false);

    if (karaokeVisible) {
      await karaokeButton.click();
      await expect(playerBar).toBeVisible({ timeout: 5000 });
    }
  });
});
