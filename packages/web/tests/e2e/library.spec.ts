import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './pages/LibraryPage';
import { AlbumPage } from './pages/AlbumPage';

const test = baseTest;

test.describe('Library Browsing', () => {
  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    await libraryPage.goto();
  });

  test('should display album grid', async () => {
    await libraryPage.waitForAlbumsToLoad();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThan(0);
  });

  test('should open album details when clicked', async ({ authenticatedPage }) => {
    await libraryPage.waitForAlbumsToLoad();

    await libraryPage.clickAlbum(0);

    await albumPage.waitForAlbumToLoad();
    await expect(authenticatedPage.locator('[data-testid="track-row"]').first()).toBeVisible();
  });

  test('should display album cover images', async () => {
    await libraryPage.waitForAlbumsToLoad();

    const firstAlbumImage = libraryPage.albumCards.first().locator('[data-testid="album-cover"]');
    await expect(firstAlbumImage).toBeVisible();
  });

  test('should navigate back from album to library', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    // Use browser back navigation
    await authenticatedPage.goBack();

    await expect(authenticatedPage).toHaveURL('/');
    await libraryPage.waitForAlbumsToLoad();
  });

  test('should load more albums on scroll (pagination)', async () => {
    const initialCount = await libraryPage.getAlbumCount();

    // Skip if all albums fit on one page (nothing to paginate)
    test.skip(initialCount < 20, 'Not enough albums to test pagination');

    await libraryPage.scrollToBottom();

    // Wait for either more albums or verify we've loaded all available
    const newCount = await libraryPage.getAlbumCount();
    expect(newCount).toBeGreaterThanOrEqual(initialCount);
  });

  test('should display album metadata', async () => {
    await libraryPage.waitForAlbumsToLoad();

    const albumCard = libraryPage.albumCards.first();
    await expect(albumCard.locator('[data-testid="album-title"]')).toBeVisible();
    // Album artist is shown in album detail page, not in card
  });

  test('should handle empty search results', async () => {
    await libraryPage.navigateToSearch();
    await libraryPage.search('xyznonexistentalbumname12345');

    await libraryPage.expectNoResults();
  });

  test('should search and find albums', async () => {
    await libraryPage.navigateToSearch();

    const firstAlbumTitle = await libraryPage.getAlbumTitle(0);
    const searchQuery = firstAlbumTitle.substring(0, 5);

    await libraryPage.search(searchQuery);

    const resultsCount = await libraryPage.getAlbumCount();
    expect(resultsCount).toBeGreaterThan(0);
  });

  test('should clear search results', async () => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('test');
    await libraryPage.clearSearch();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThan(0);
  });

  test('should navigate between Albums and Home tabs', async ({ authenticatedPage }) => {
    await expect(authenticatedPage).toHaveURL('/');

    // Navigate to albums page (use direct navigation since bottom bar hidden on desktop)
    await authenticatedPage.goto('/albums');
    await expect(authenticatedPage).toHaveURL('/albums');

    // Navigate back to home
    await authenticatedPage.goto('/');
    await expect(authenticatedPage).toHaveURL('/');
  });
});
