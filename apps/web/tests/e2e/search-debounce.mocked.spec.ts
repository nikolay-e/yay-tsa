import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { installBaseMock, login } from './helpers/media-fixtures';

// Backend-free search-UX suite (chromium-mocked project): /api/* is stubbed, so this runs without
// a live backend. It proves the single global search (top bar -> /search) debounces: the results
// section stays mounted while typing (placeholderData) instead of swapping in a full-page spinner,
// and keystrokes are coalesced into a single trailing request.
//
//   npx playwright test --project=chromium-mocked

const TRACKS = Array.from({ length: 6 }, (_, i) => ({
  Id: `tr${i + 1}`,
  Name: `Song ${i + 1}`,
  Type: 'Audio',
  Artists: ['Nova'],
  AlbumArtist: 'Nova',
  Album: 'Nova Album',
  AlbumId: 'al1',
  RunTimeTicks: 2_000_000_000,
  ImageTags: {},
  UserData: { PlaybackPositionTicks: 0, PlayCount: 0, IsFavorite: false, Played: false },
}));

function installMock(page: Page, searchTermsSeen: string[]): void {
  installBaseMock(page);

  void page.route(/\/v1\/me\/(devices|audiobooks)(\?|$)/, (route: Route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  void page.route(/\/Items(\?|$)/, (route: Route) => {
    const url = new URL(route.request().url());
    const includeTypes = url.searchParams.get('IncludeItemTypes') ?? '';
    // Only the tracks section (Audio) returns data; album/artist sections stay empty so the
    // assertions isolate the debounced tracks request.
    if (!includeTypes.includes('Audio')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: [], TotalRecordCount: 0, StartIndex: 0 }),
      });
    }
    const searchTerm = url.searchParams.get('SearchTerm');
    if (searchTerm) searchTermsSeen.push(searchTerm);
    const items = searchTerm
      ? TRACKS.filter(t => t.Name.toLowerCase().includes(searchTerm.toLowerCase()))
      : TRACKS;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: items, TotalRecordCount: items.length, StartIndex: 0 }),
    });
  });
}

test.describe('Global search debounce (mocked backend)', () => {
  test('typing in the global bar keeps results mounted and coalesces requests', async ({
    page,
  }) => {
    const searchTermsSeen: string[] = [];
    installMock(page, searchTermsSeen);
    await login(page);

    // Seed an initial populated result set so the placeholderData behaviour is observable.
    await page.goto('/search?q=Song');
    const section = page.getByTestId('search-section-tracks');
    await expect(section).toBeVisible();
    await expect(page.getByText('Song 1')).toBeVisible();

    await page.evaluate(() => {
      const el = document.querySelector('[data-testid="search-section-tracks"]');
      (window as unknown as { __sectionDetached: boolean }).__sectionDetached = false;
      const observer = new MutationObserver(() => {
        if (el && !document.contains(el)) {
          (window as unknown as { __sectionDetached: boolean }).__sectionDetached = true;
        }
      });
      observer.observe(document.body, { childList: true, subtree: true });
    });

    const input = page.getByTestId('global-search-input');
    await input.fill('');
    await input.pressSequentially('Song 3', { delay: 60 });

    await expect(section).toBeVisible();
    await expect(page.getByText('Song 3')).toBeVisible();
    await expect(page.getByText('Song 1')).toHaveCount(0);

    const detached = await page.evaluate(
      () => (window as unknown as { __sectionDetached: boolean }).__sectionDetached
    );
    expect(detached).toBe(false);

    // Debounced: far fewer requests than keystrokes, and the last one carries the full term.
    expect(searchTermsSeen.length).toBeLessThan('Song 3'.length);
    expect(searchTermsSeen[searchTermsSeen.length - 1]).toBe('Song 3');
  });
});
