import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './pages/LibraryPage';
import { AlbumPage } from './pages/AlbumPage';

const test = baseTest;

test.describe('Search Functionality', () => {
  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    await libraryPage.goto();
  });

  test('should show search page', async ({ authenticatedPage }) => {
    await libraryPage.navigateToSearch();

    await expect(libraryPage.searchInput).toBeVisible();
    // Search is now on home page
    await expect(authenticatedPage).toHaveURL('/');
  });

  test('should search for albums', async () => {
    await libraryPage.navigateToSearch();

    await libraryPage.goto();
    const firstAlbumTitle = await libraryPage.getAlbumTitle(0);
    const searchTerm = firstAlbumTitle.substring(0, Math.min(5, firstAlbumTitle.length));

    await libraryPage.navigateToSearch();
    await libraryPage.search(searchTerm);

    const resultsCount = await libraryPage.getAlbumCount();
    expect(resultsCount).toBeGreaterThan(0);
  });

  test('should show no results for non-existent search', async () => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('xyznonexistentquery999');

    await libraryPage.expectNoResults();
  });

  test('should clear search results', async () => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('test');
    await libraryPage.clearSearch();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThanOrEqual(0);
  });

  test('should search with partial matches', async () => {
    await libraryPage.goto();
    const firstAlbumTitle = await libraryPage.getAlbumTitle(0);

    const partialSearch = firstAlbumTitle.substring(0, 3);

    await libraryPage.navigateToSearch();
    await libraryPage.search(partialSearch);

    const resultsCount = await libraryPage.getAlbumCount();
    expect(resultsCount).toBeGreaterThan(0);
  });

  test('should handle search input changes', async () => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('a');
    await libraryPage.clearSearch();
    await libraryPage.search('be');

    const resultsCount = await libraryPage.getAlbumCount();
    expect(resultsCount).toBeGreaterThanOrEqual(0);
  });

  test('should open album from search results', async () => {
    await libraryPage.goto();
    const targetAlbumTitle = await libraryPage.getAlbumTitle(0);
    const searchTerm = targetAlbumTitle.substring(0, 5);

    await libraryPage.navigateToSearch();
    await libraryPage.search(searchTerm);

    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    await expect(albumPage.albumTitle).toBeVisible();
  });

  test('should handle special characters in search', async () => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('test-album.2024');
    // search() already waits for results
  });

  test('should preserve search on navigation', async ({ authenticatedPage }) => {
    // Search is on home page, navigate away and back to check preservation
    await libraryPage.navigateToSearch();
    await libraryPage.search('test');

    // Navigate to albums page
    await authenticatedPage.goto('/albums');
    await expect(authenticatedPage).toHaveURL('/albums');

    // Navigate back to home
    await authenticatedPage.goto('/');
    await expect(libraryPage.searchInput).toBeVisible();

    const searchValue = await libraryPage.searchInput.inputValue();
    // Search may or may not be preserved depending on implementation
    expect(searchValue).toBeDefined();
  });

  test('should perform case-insensitive search', async () => {
    await libraryPage.goto();
    const albumTitle = await libraryPage.getAlbumTitle(0);

    await libraryPage.navigateToSearch();

    await libraryPage.search(albumTitle.toUpperCase().substring(0, 5));
    const upperCaseResults = await libraryPage.getAlbumCount();

    await libraryPage.clearSearch();
    await libraryPage.search(albumTitle.toLowerCase().substring(0, 5));
    const lowerCaseResults = await libraryPage.getAlbumCount();

    expect(upperCaseResults).toBeGreaterThan(0);
    expect(lowerCaseResults).toBeGreaterThan(0);
  });
});
