import { test, expect } from './fixtures/library.fixture';
import { LIBRARY_TEST_IDS, FAVORITES_TEST_IDS } from './helpers/test-ids';

test.describe('Favorites', () => {
  test('should toggle album favorite via button', async ({ libraryPage, albumPage }) => {
    await libraryPage.goto();
    const firstAlbum = libraryPage.page.getByTestId(LIBRARY_TEST_IDS.ALBUM_CARD).first();
    await expect(firstAlbum).toBeVisible({ timeout: 10000 });
    await firstAlbum.click();

    await expect(albumPage.albumTitle).toBeVisible({ timeout: 10000 });

    const favButton = albumPage.page.getByTestId(LIBRARY_TEST_IDS.ALBUM_FAVORITE_BUTTON);
    await expect(favButton).toBeVisible();

    const wasFavorite = await albumPage.isFavorite();
    await albumPage.toggleFavorite();

    await expect(async () => {
      const isFavNow = await albumPage.isFavorite();
      expect(isFavNow).toBe(!wasFavorite);
    }).toPass({ timeout: 5000 });

    await albumPage.toggleFavorite();
    await expect(async () => {
      const restored = await albumPage.isFavorite();
      expect(restored).toBe(wasFavorite);
    }).toPass({ timeout: 5000 });
  });

  test('should display favorites page with tabs', async ({ libraryPage }) => {
    const navFavorites = libraryPage.page.getByRole('link', { name: /favorites/i });
    const navExists = await navFavorites.isVisible().catch(() => false);

    if (navExists) {
      await navFavorites.click();
    } else {
      await libraryPage.page.goto('/favorites');
    }

    const favoritesPage = libraryPage.page.getByTestId(FAVORITES_TEST_IDS.PAGE);
    await expect(favoritesPage).toBeVisible({ timeout: 10000 });

    const tracksTab = libraryPage.page.getByRole('button', { name: 'Tracks' });
    const albumsTab = libraryPage.page.getByRole('button', { name: 'Albums' });
    const artistsTab = libraryPage.page.getByRole('button', { name: 'Artists' });

    await expect(tracksTab).toBeVisible();
    await expect(albumsTab).toBeVisible();
    await expect(artistsTab).toBeVisible();
  });

  test('should navigate between favorites tabs', async ({ libraryPage }) => {
    await libraryPage.page.goto('/favorites');

    const favoritesPage = libraryPage.page.getByTestId(FAVORITES_TEST_IDS.PAGE);
    await expect(favoritesPage).toBeVisible({ timeout: 10000 });

    const albumsTab = libraryPage.page.getByRole('button', { name: 'Albums' });
    await albumsTab.click();

    await expect(async () => {
      const hasAlbums =
        (await libraryPage.page.getByTestId(LIBRARY_TEST_IDS.ALBUM_CARD).count()) > 0;
      const hasEmpty = await libraryPage.page
        .getByText('No favorite albums yet')
        .isVisible()
        .catch(() => false);
      expect(hasAlbums || hasEmpty).toBe(true);
    }).toPass({ timeout: 10000 });

    const artistsTab = libraryPage.page.getByRole('button', { name: 'Artists' });
    await artistsTab.click();

    await expect(async () => {
      const hasArtists =
        (await libraryPage.page.getByTestId(LIBRARY_TEST_IDS.ARTIST_CARD).count()) > 0;
      const hasEmpty = await libraryPage.page
        .getByText('No favorite artists yet')
        .isVisible()
        .catch(() => false);
      expect(hasArtists || hasEmpty).toBe(true);
    }).toPass({ timeout: 10000 });
  });

  test('should persist favorite state across navigation', async ({ libraryPage, albumPage }) => {
    await libraryPage.goto();
    const firstAlbum = libraryPage.page.getByTestId(LIBRARY_TEST_IDS.ALBUM_CARD).first();
    await expect(firstAlbum).toBeVisible({ timeout: 10000 });
    await firstAlbum.click();
    await expect(albumPage.albumTitle).toBeVisible({ timeout: 10000 });

    const initialFavState = await albumPage.isFavorite();
    if (!initialFavState) {
      await albumPage.toggleFavorite();
      await expect(async () => {
        expect(await albumPage.isFavorite()).toBe(true);
      }).toPass({ timeout: 5000 });
    }

    await albumPage.goBack();
    await expect(firstAlbum).toBeVisible({ timeout: 10000 });
    await firstAlbum.click();
    await expect(albumPage.albumTitle).toBeVisible({ timeout: 10000 });

    await expect(async () => {
      expect(await albumPage.isFavorite()).toBe(true);
    }).toPass({ timeout: 5000 });

    if (!initialFavState) {
      await albumPage.toggleFavorite();
    }
  });
});
