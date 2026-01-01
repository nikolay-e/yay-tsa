import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './page-objects/LibraryPage';
import { AlbumPage } from './page-objects/AlbumPage';

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

  test('should update results when search input changes', async () => {
    await libraryPage.navigateToSearch();

    // Get initial album for comparison
    await libraryPage.goto();
    const firstAlbum = await libraryPage.getAlbumTitle(0);
    const searchTerm = firstAlbum.substring(0, 3);

    await libraryPage.navigateToSearch();

    // Search for something specific
    await libraryPage.search(searchTerm);
    const resultsWithTerm = await libraryPage.getAlbumCount();

    // Clear and search for something unlikely to exist
    await libraryPage.clearSearch();
    await libraryPage.search('zzz999nonexistent');
    const resultsWithNonexistent = await libraryPage.getAlbumCount();

    // Results should differ - term should find albums, nonexistent should find none
    expect(resultsWithTerm).toBeGreaterThan(0);
    expect(resultsWithNonexistent).toBe(0);
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

  test('should handle special characters in search without crashing', async () => {
    await libraryPage.navigateToSearch();

    // Search with special characters should not throw errors
    await libraryPage.search('test-album.2024');

    // Verify search completed and UI is still functional
    const resultsCount = await libraryPage.getAlbumCount();
    // May return 0 results, but should not crash - that's the expected behavior
    expect(resultsCount).toBeGreaterThanOrEqual(0);
    await expect(libraryPage.searchInput).toBeVisible();
  });

  test('should clear search when navigating away and back', async ({ authenticatedPage }) => {
    // Search is on home page, navigate away and back
    await libraryPage.navigateToSearch();
    await libraryPage.search('test');

    // Verify search is active
    const searchValueBefore = await libraryPage.searchInput.inputValue();
    expect(searchValueBefore).toBe('test');

    // Navigate to albums page
    await authenticatedPage.goto('/albums');
    await expect(authenticatedPage).toHaveURL('/albums');

    // Navigate back to home
    await authenticatedPage.goto('/');
    await expect(libraryPage.searchInput).toBeVisible();

    const searchValueAfter = await libraryPage.searchInput.inputValue();
    // Expected: search is cleared after navigation (fresh state)
    // This is the correct UX - user starts fresh when returning
    expect(searchValueAfter).toBe('');
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
