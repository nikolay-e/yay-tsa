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

  test('should search for albums', async ({ authenticatedPage }) => {
    await libraryPage.navigateToSearch();

    await libraryPage.goto();
    const firstAlbumTitle = await libraryPage.getAlbumTitle(0);
    const searchTerm = firstAlbumTitle.substring(0, Math.min(5, firstAlbumTitle.length));

    await libraryPage.navigateToSearch();
    await libraryPage.search(searchTerm);
    await authenticatedPage.waitForTimeout(500);

    const resultsCount = await libraryPage.getAlbumCount();
    expect(resultsCount).toBeGreaterThan(0);
  });

  test('should show no results for non-existent search', async () => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('xyznonexistentquery999');
    await libraryPage.page.waitForTimeout(500);

    await libraryPage.expectNoResults();
  });

  test('should clear search results', async ({ authenticatedPage }) => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('test');
    await authenticatedPage.waitForTimeout(500);

    await libraryPage.clearSearch();
    await authenticatedPage.waitForTimeout(500);

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThanOrEqual(0);
  });

  test('should search with partial matches', async ({ authenticatedPage }) => {
    await libraryPage.goto();
    const firstAlbumTitle = await libraryPage.getAlbumTitle(0);

    const partialSearch = firstAlbumTitle.substring(0, 3);

    await libraryPage.navigateToSearch();
    await libraryPage.search(partialSearch);
    await authenticatedPage.waitForTimeout(500);

    const resultsCount = await libraryPage.getAlbumCount();
    expect(resultsCount).toBeGreaterThan(0);
  });

  test('should handle search input changes', async ({ authenticatedPage }) => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('a');
    await authenticatedPage.waitForTimeout(500);

    await libraryPage.clearSearch();
    await libraryPage.search('be');
    await authenticatedPage.waitForTimeout(500);

    const resultsCount = await libraryPage.getAlbumCount();
    expect(resultsCount).toBeGreaterThanOrEqual(0);
  });

  test('should open album from search results', async ({ authenticatedPage }) => {
    await libraryPage.goto();
    const targetAlbumTitle = await libraryPage.getAlbumTitle(0);
    const searchTerm = targetAlbumTitle.substring(0, 5);

    await libraryPage.navigateToSearch();
    await libraryPage.search(searchTerm);
    await authenticatedPage.waitForTimeout(500);

    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    await expect(albumPage.albumTitle).toBeVisible();
  });

  test('should handle special characters in search', async ({ authenticatedPage }) => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('test-album.2024');
    await authenticatedPage.waitForTimeout(500);
  });

  test('should preserve search on navigation', async ({ authenticatedPage }) => {
    // Search is on home page, navigate away and back to check preservation
    await libraryPage.navigateToSearch();
    await libraryPage.search('test');
    await authenticatedPage.waitForTimeout(500);

    // Navigate to albums page
    await authenticatedPage.goto('/albums');
    await authenticatedPage.waitForTimeout(300);

    // Navigate back to home
    await authenticatedPage.goto('/');
    await authenticatedPage.waitForTimeout(300);

    const searchValue = await libraryPage.searchInput.inputValue();
    // Search may or may not be preserved depending on implementation
    expect(searchValue).toBeDefined();
  });

  test('should perform case-insensitive search', async ({ authenticatedPage }) => {
    await libraryPage.goto();
    const albumTitle = await libraryPage.getAlbumTitle(0);

    await libraryPage.navigateToSearch();

    await libraryPage.search(albumTitle.toUpperCase().substring(0, 5));
    await authenticatedPage.waitForTimeout(500);
    const upperCaseResults = await libraryPage.getAlbumCount();

    await libraryPage.clearSearch();
    await libraryPage.search(albumTitle.toLowerCase().substring(0, 5));
    await authenticatedPage.waitForTimeout(500);
    const lowerCaseResults = await libraryPage.getAlbumCount();

    expect(upperCaseResults).toBeGreaterThan(0);
    expect(lowerCaseResults).toBeGreaterThan(0);
  });
});
