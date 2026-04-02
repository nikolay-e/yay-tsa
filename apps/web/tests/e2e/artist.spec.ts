import { test, expect } from './fixtures/library.fixture';
import { LIBRARY_TEST_IDS, NAVIGATION_TEST_IDS } from './helpers/test-ids';

test.describe('Artist Browsing', () => {
  test('should display artist list', async ({ libraryPage }) => {
    const page = libraryPage.page;
    const sidebar = page.getByTestId(NAVIGATION_TEST_IDS.SIDEBAR);
    const bottomTab = page.getByTestId(NAVIGATION_TEST_IDS.BOTTOM_TAB_BAR);

    if (await sidebar.isVisible()) {
      await sidebar.getByTestId(NAVIGATION_TEST_IDS.NAV_ARTISTS).click();
    } else {
      await bottomTab.getByTestId(NAVIGATION_TEST_IDS.NAV_ARTISTS).click();
    }

    await expect(page).toHaveURL('/artists');
    await expect(page.getByRole('heading', { name: 'Artists' })).toBeVisible({ timeout: 10000 });

    const artistCards = page.getByTestId(LIBRARY_TEST_IDS.ARTIST_CARD);
    await expect(artistCards.first()).toBeVisible({ timeout: 10000 });

    const count = await artistCards.count();
    expect(count).toBeGreaterThan(0);
  });

  test('should open artist detail page showing their albums', async ({ libraryPage }) => {
    const page = libraryPage.page;

    await page.goto('/artists');
    const artistCards = page.getByTestId(LIBRARY_TEST_IDS.ARTIST_CARD);
    await expect(artistCards.first()).toBeVisible({ timeout: 10000 });

    const artistName = await artistCards
      .first()
      .getByTestId(LIBRARY_TEST_IDS.ARTIST_NAME)
      .textContent();
    expect(artistName).toBeTruthy();

    await artistCards.first().click();

    await expect(page.getByTestId(LIBRARY_TEST_IDS.ARTIST_DETAIL_NAME)).toBeVisible({
      timeout: 10000,
    });
    const detailName = await page.getByTestId(LIBRARY_TEST_IDS.ARTIST_DETAIL_NAME).textContent();
    expect(detailName).toBe(artistName);

    await expect(page).toHaveURL(/\/artists\/.+/);
  });

  test('should navigate back from artist to artists list', async ({ libraryPage }) => {
    const page = libraryPage.page;

    await page.goto('/artists');
    const artistCards = page.getByTestId(LIBRARY_TEST_IDS.ARTIST_CARD);
    await expect(artistCards.first()).toBeVisible({ timeout: 10000 });

    await artistCards.first().click();
    await expect(page.getByTestId(LIBRARY_TEST_IDS.ARTIST_DETAIL_NAME)).toBeVisible({
      timeout: 10000,
    });

    await page.goBack();

    await expect(page).toHaveURL('/artists');
    await expect(artistCards.first()).toBeVisible({ timeout: 10000 });
  });

  test('should show albums on artist detail page', async ({ libraryPage }) => {
    const page = libraryPage.page;

    await page.goto('/artists');
    const artistCards = page.getByTestId(LIBRARY_TEST_IDS.ARTIST_CARD);
    await expect(artistCards.first()).toBeVisible({ timeout: 10000 });

    await artistCards.first().click();
    await expect(page.getByTestId(LIBRARY_TEST_IDS.ARTIST_DETAIL_NAME)).toBeVisible({
      timeout: 10000,
    });

    const albumsHeading = page.getByRole('heading', { name: 'Albums' });
    const albumCards = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_CARD);

    const hasAlbums = await albumsHeading.isVisible().catch(() => false);
    if (hasAlbums) {
      await expect(albumCards.first()).toBeVisible({ timeout: 5000 });
      const albumCount = await albumCards.count();
      expect(albumCount).toBeGreaterThan(0);
    }
  });

  test('should navigate to album from artist page', async ({ libraryPage, albumPage }) => {
    const page = libraryPage.page;

    await page.goto('/artists');
    const artistCards = page.getByTestId(LIBRARY_TEST_IDS.ARTIST_CARD);
    await expect(artistCards.first()).toBeVisible({ timeout: 10000 });

    await artistCards.first().click();
    await expect(page.getByTestId(LIBRARY_TEST_IDS.ARTIST_DETAIL_NAME)).toBeVisible({
      timeout: 10000,
    });

    const albumCards = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_CARD);
    const hasAlbums = (await albumCards.count()) > 0;
    test.skip(!hasAlbums, 'Artist has no albums');

    await albumCards.first().getByTestId(LIBRARY_TEST_IDS.ALBUM_TITLE).click();

    await albumPage.waitForAlbumToLoad();
    await expect(page).toHaveURL(/\/albums\/.+/);
  });
});
