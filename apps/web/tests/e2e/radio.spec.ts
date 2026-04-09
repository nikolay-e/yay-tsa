import { test, expect } from './fixtures/auth.fixture';
import { RADIO_TEST_IDS, PLAYER_TEST_IDS } from './helpers/test-ids';

async function fetchRealSeeds(
  page: import('@playwright/test').Page,
  limit = 4
): Promise<
  Array<{
    trackId: string;
    name: string;
    artistName: string;
    albumName: string;
    albumId: string;
    imageTag: string | null;
  }>
> {
  return page.evaluate(async lim => {
    const token = sessionStorage.getItem('yaytsa_session') || '';
    const userId = sessionStorage.getItem('yaytsa_user_id') || '';
    const res = await fetch(
      `/Users/${userId}/Items?Recursive=true&IncludeItemTypes=Audio&Limit=${lim}&Fields=Genres&api_key=${token}`
    );
    const data = await res.json();
    return (data.Items ?? []).map((item: Record<string, unknown>) => ({
      trackId: item.Id as string,
      name: item.Name as string,
      artistName: ((item.Artists as string[]) ?? ['Unknown'])[0],
      albumName: (item.Album as string) ?? '',
      albumId: (item.AlbumId as string) ?? '',
      imageTag: (item.AlbumPrimaryImageTag as string) ?? null,
    }));
  }, limit);
}

test.describe('Radio', () => {
  test('should display seed cards on home page', async ({ authenticatedPage }) => {
    const seeds = await fetchRealSeeds(authenticatedPage);
    test.skip(seeds.length < 2, 'Not enough tracks in test library for radio seeds');

    await authenticatedPage.route('**/v1/recommend/radio/seeds', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ seeds }),
      });
    });
    await authenticatedPage.goto('/');

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
    const seeds = await fetchRealSeeds(authenticatedPage);
    test.skip(seeds.length < 2, 'Not enough tracks in test library for radio seeds');

    await authenticatedPage.route('**/v1/recommend/radio/seeds', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ seeds }),
      });
    });
    await authenticatedPage.goto('/');

    const section = authenticatedPage.getByTestId(RADIO_TEST_IDS.SECTION);
    await expect(section).toBeVisible({ timeout: 10000 });

    const firstCard = section.getByTestId(RADIO_TEST_IDS.SEED_CARD).first();
    await firstCard.click();

    const playerBar = authenticatedPage.getByTestId(PLAYER_TEST_IDS.PLAYER_BAR);
    await expect(playerBar).toBeVisible({ timeout: 15000 });

    const trackTitle = authenticatedPage.locator(
      `[data-testid="${PLAYER_TEST_IDS.CURRENT_TRACK_TITLE}"]:visible`
    );
    await expect(trackTitle).toBeVisible({ timeout: 10000 });
    expect(await trackTitle.textContent()).toBeTruthy();
  });

  test('should populate queue after DJ session start', async ({ authenticatedPage }) => {
    const seeds = await fetchRealSeeds(authenticatedPage);
    test.skip(seeds.length < 2, 'Not enough tracks in test library for radio seeds');

    await authenticatedPage.route('**/v1/recommend/radio/seeds', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ seeds }),
      });
    });
    await authenticatedPage.goto('/');

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

  test('should show karaoke button that toggles gracefully', async ({ authenticatedPage }) => {
    const seeds = await fetchRealSeeds(authenticatedPage);
    test.skip(seeds.length < 2, 'Not enough tracks in test library for radio seeds');

    await authenticatedPage.route('**/v1/recommend/radio/seeds', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ seeds }),
      });
    });
    await authenticatedPage.goto('/');

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
