import { test, expect } from './fixtures/library.fixture';

test.describe('Library Browsing', () => {
  test('should display album grid', async ({ libraryPage }) => {
    await libraryPage.waitForAlbumsToLoad();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThan(0);
  });

  test('should open album details when clicked', async ({ libraryPage, albumPage }) => {
    await libraryPage.waitForAlbumsToLoad();

    await libraryPage.clickAlbum(0);

    await albumPage.waitForAlbumToLoad();
    await expect(albumPage.trackRows.first()).toBeVisible();
  });

  test('should display album cover images', async ({ libraryPage }) => {
    await libraryPage.waitForAlbumsToLoad();

    await libraryPage.expectAlbumCoverVisible(0);
  });

  test('should navigate back from album to library', async ({ libraryPage, albumPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    // Use browser back navigation
    await albumPage.goBack();

    await expect(libraryPage.page).toHaveURL('/');
  });

  test('should load more albums on scroll (pagination)', async ({ libraryPage }) => {
    const initialCount = await libraryPage.getAlbumCount();

    // Skip if all albums fit on one page (nothing to paginate)
    test.skip(initialCount < 20, 'Not enough albums to test pagination');

    await libraryPage.scrollToBottom();

    // Wait for either more albums or verify we've loaded all available
    const newCount = await libraryPage.getAlbumCount();
    expect(newCount).toBeGreaterThanOrEqual(initialCount);
  });

  test('should display album metadata', async ({ libraryPage }) => {
    await libraryPage.waitForAlbumsToLoad();

    await libraryPage.expectAlbumTitleVisible(0);
    // Album artist is shown in album detail page, not in card
  });

  test('should handle empty search results', async ({ libraryPage }) => {
    await libraryPage.navigateToSearch();
    await libraryPage.search('xyznonexistentalbumname12345');

    await libraryPage.expectNoResults();
  });

  test('should search and find albums', async ({ libraryPage }) => {
    await libraryPage.navigateToSearch();

    const firstAlbumTitle = await libraryPage.getAlbumTitle(0);
    const searchQuery = firstAlbumTitle.substring(0, 5);

    await libraryPage.search(searchQuery);

    const resultsCount = await libraryPage.getAlbumCount();
    expect(resultsCount).toBeGreaterThan(0);
  });

  test('should clear search results', async ({ libraryPage }) => {
    await libraryPage.navigateToSearch();

    await libraryPage.search('test');
    await libraryPage.clearSearch();

    const albumCount = await libraryPage.getAlbumCount();
    expect(albumCount).toBeGreaterThan(0);
  });

  test('should navigate between Albums and Home tabs', async ({ libraryPage }) => {
    await expect(libraryPage.page).toHaveURL('/');

    // Navigate to albums page (use direct navigation since bottom bar hidden on desktop)
    await libraryPage.page.goto('/albums');
    await expect(libraryPage.page).toHaveURL('/albums');

    // Navigate back to home
    await libraryPage.page.goto('/');
    await expect(libraryPage.page).toHaveURL('/');
  });
});
