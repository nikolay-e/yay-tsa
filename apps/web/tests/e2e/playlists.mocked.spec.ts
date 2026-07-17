import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { installBaseMock, login } from './helpers/media-fixtures';

const TRACK_NAMES: Record<string, string> = {
  t1: 'Aurora',
  t2: 'Borealis',
  t3: 'Cascade',
};

type PlaylistState = { Id: string; Name: string; itemIds: string[] };

function buildTrack(id: string, playlistPosition?: number) {
  return {
    Id: id,
    Name: TRACK_NAMES[id] ?? id,
    Type: 'Audio',
    RunTimeTicks: 2_000_000_000,
    ...(playlistPosition === undefined ? {} : { PlaylistItemId: String(playlistPosition) }),
    UserData: { PlaybackPositionTicks: 0, PlayCount: 0, IsFavorite: false, Played: false },
  };
}

function itemsBody(items: unknown[]) {
  return JSON.stringify({ Items: items, TotalRecordCount: items.length, StartIndex: 0 });
}

function fulfillJson(route: Route, body: string, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body });
}

function installPlaylistsMock(page: Page): { playlists: PlaylistState[] } {
  const state = {
    playlists: [
      { Id: 'pl1', Name: 'Chill', itemIds: ['t2', 't3'] },
      { Id: 'pl2', Name: 'Workout', itemIds: ['t1'] },
    ],
  };
  let createdCount = 0;

  const playlistDto = (playlist: PlaylistState) => ({
    Id: playlist.Id,
    Name: playlist.Name,
    Type: 'Playlist',
    IsFolder: true,
    ChildCount: playlist.itemIds.length,
  });

  installBaseMock(page);

  void page.route(/\/Items(\?|$)/, (route: Route) => {
    const url = new URL(route.request().url());
    if (url.searchParams.get('IncludeItemTypes') === 'Playlist') {
      return fulfillJson(route, itemsBody(state.playlists.map(playlistDto)));
    }
    return fulfillJson(route, itemsBody(Object.keys(TRACK_NAMES).map(id => buildTrack(id))));
  });

  void page.route(/\/Items\/[^/?]+(\?|$)/, (route: Route) => {
    const id = route.request().url().split('/Items/')[1]!.split('?')[0]!;
    const playlist = state.playlists.find(p => p.Id === id);
    if (route.request().method() === 'DELETE') {
      state.playlists = state.playlists.filter(p => p.Id !== id);
      return fulfillJson(route, '{}');
    }
    if (!playlist) {
      return route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: 404, title: 'Not Found' }),
      });
    }
    return fulfillJson(route, JSON.stringify(playlistDto(playlist)));
  });

  void page.route(/\/Playlists\/[^/]+\/Items\/[^/]+\/Move\/\d+/, (route: Route) => {
    const [, tail] = route.request().url().split('/Playlists/');
    const [playlistId, , entryId, , newIndexRaw] = tail!.split('?')[0]!.split('/');
    const playlist = state.playlists.find(p => p.Id === playlistId);
    if (playlist) {
      const fromIndex = Number(entryId);
      const toIndex = Math.min(Number(newIndexRaw), playlist.itemIds.length - 1);
      const [moved] = playlist.itemIds.splice(fromIndex, 1);
      playlist.itemIds.splice(toIndex, 0, moved!);
    }
    return fulfillJson(route, '{}');
  });

  void page.route(/\/Playlists\/[^/]+\/Items(\?|$)/, (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const playlistId = url.pathname.split('/Playlists/')[1]!.split('/')[0]!;
    const playlist = state.playlists.find(p => p.Id === playlistId);
    if (!playlist) {
      return route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: 404, title: 'Not Found' }),
      });
    }
    if (request.method() === 'POST') {
      const ids = url.searchParams.get('Ids')?.split(',').filter(Boolean) ?? [];
      playlist.itemIds.push(...ids);
      return fulfillJson(route, '{}');
    }
    if (request.method() === 'DELETE') {
      const removed = new Set(
        (url.searchParams.get('EntryIds')?.split(',') ?? []).map(entry => Number(entry))
      );
      playlist.itemIds = playlist.itemIds.filter((_, index) => !removed.has(index));
      return fulfillJson(route, '{}');
    }
    const allItems = playlist.itemIds.map((trackId, index) => buildTrack(trackId, index));
    const startIndex = Number(url.searchParams.get('StartIndex') ?? 0);
    const limit = Number(url.searchParams.get('Limit') ?? allItems.length);
    return fulfillJson(
      route,
      JSON.stringify({
        Items: allItems.slice(startIndex, startIndex + limit),
        TotalRecordCount: allItems.length,
        StartIndex: startIndex,
      })
    );
  });

  void page.route(/\/Playlists\/[^/?]+(\?|$)/, (route: Route) => {
    const request = route.request();
    if (request.method() !== 'POST') return route.fallback();
    const playlistId = new URL(request.url()).pathname.split('/Playlists/')[1]!;
    const playlist = state.playlists.find(p => p.Id === playlistId);
    if (!playlist) {
      return route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: 404, title: 'Not Found' }),
      });
    }
    const body = request.postDataJSON() as { Name: string };
    playlist.Name = body.Name;
    return route.fulfill({ status: 204 });
  });

  void page.route(/\/Playlists(\?|$)/, (route: Route) => {
    if (route.request().method() !== 'POST') return fulfillJson(route, '{}');
    const body = route.request().postDataJSON() as { Name: string; Ids?: string[] };
    createdCount += 1;
    const id = `pl-new-${createdCount}`;
    state.playlists.push({ Id: id, Name: body.Name, itemIds: body.Ids ?? [] });
    return fulfillJson(route, JSON.stringify({ Id: id }));
  });

  return state;
}

function trackRow(page: Page, name: string) {
  return page.getByTestId('track-row').filter({ hasText: name });
}

test.describe('Playlists (mocked backend)', () => {
  test('list page shows playlists with counts and creates a new one', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    // Playlists now lives in both the (CSS-hidden-on-desktop) bottom tab bar and the sidebar, so
    // the bare test id is ambiguous — scope to the sidebar, which is what this desktop-viewport
    // test actually exercises.
    await page.getByTestId('sidebar').getByTestId('nav-playlists').click();
    await expect(page).toHaveURL('/playlists');

    await expect(page.getByTestId('playlist-card')).toHaveCount(2);
    await expect(page.getByTestId('playlist-card').filter({ hasText: 'Chill' })).toContainText(
      '2 tracks'
    );
    await expect(page.getByTestId('playlist-card').filter({ hasText: 'Workout' })).toContainText(
      '1 track'
    );

    await page.getByTestId('playlist-create-button').click();
    await page.getByTestId('playlist-name-input').fill('Road Trip');
    await page.getByTestId('playlist-create-submit').click();

    await expect(page.getByTestId('playlist-card')).toHaveCount(3);
    await expect(page.getByTestId('playlist-card').filter({ hasText: 'Road Trip' })).toContainText(
      '0 tracks'
    );
  });

  test('detail page lists tracks and removes one from the playlist', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/playlists');
    await page.getByTestId('playlist-card').filter({ hasText: 'Chill' }).click();
    await expect(page).toHaveURL('/playlists/pl1');

    await expect(page.getByTestId('playlist-detail-title')).toHaveText('Chill');
    await expect(page.getByTestId('track-row')).toHaveCount(2);
    await expect(trackRow(page, 'Borealis')).toBeVisible();
    await expect(trackRow(page, 'Cascade')).toBeVisible();

    await page.getByRole('button', { name: 'Remove Borealis from playlist', exact: true }).click();

    await expect(page.getByTestId('track-row')).toHaveCount(1);
    await expect(trackRow(page, 'Borealis')).toHaveCount(0);
    await expect(page.getByText('1 track', { exact: true })).toBeVisible();
  });

  test('renames a playlist inline from the detail page', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/playlists/pl1');
    await expect(page.getByTestId('playlist-detail-title')).toHaveText('Chill');

    await page.getByTestId('playlist-rename-button').click();
    await page.getByTestId('playlist-rename-input').fill('Chill Renamed');
    await page.keyboard.press('Enter');

    await expect(page.getByTestId('playlist-detail-title')).toHaveText('Chill Renamed');

    await page.goto('/playlists');
    await expect(
      page.getByTestId('playlist-card').filter({ hasText: 'Chill Renamed' })
    ).toBeVisible();
  });

  test('Escape cancels an inline rename without saving', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/playlists/pl1');
    await page.getByTestId('playlist-rename-button').click();
    await page.getByTestId('playlist-rename-input').fill('Discarded Name');
    await page.keyboard.press('Escape');

    await expect(page.getByTestId('playlist-rename-input')).toHaveCount(0);
    await expect(page.getByTestId('playlist-detail-title')).toHaveText('Chill');
  });

  test('undo on the removal toast restores the track at its position', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/playlists/pl1');
    await expect(page.getByTestId('track-title').first()).toHaveText('Borealis');

    await page.getByRole('button', { name: 'Remove Borealis from playlist', exact: true }).click();
    await expect(trackRow(page, 'Borealis')).toHaveCount(0);

    await page.getByRole('button', { name: 'Undo', exact: true }).click();

    await expect(page.getByTestId('track-row')).toHaveCount(2);
    await expect(page.getByTestId('track-title').first()).toHaveText('Borealis');
  });

  test.describe('with a failing Move endpoint', () => {
    test.use({
      allowConsole: [
        'Failed to load resource: the server responded with a status of 500',
        '\\[API\\] POST /Playlists/.+/Move/\\d+ failed',
      ],
      allowResponses: ['/Playlists/.+/Items/.+/Move/'],
    });

    test('a failed reorder rolls back and shows an error toast', async ({ page }) => {
      installPlaylistsMock(page);
      await login(page);

      await page.route(/\/Playlists\/[^/]+\/Items\/[^/]+\/Move\/\d+/, (route: Route) =>
        route.fulfill({
          status: 500,
          contentType: 'application/problem+json',
          body: JSON.stringify({ status: 500, title: 'Internal Server Error' }),
        })
      );

      await page.goto('/playlists/pl1');
      const firstHandle = page
        .getByRole('button', { name: 'Drag to reorder', exact: true })
        .first();
      await firstHandle.focus();
      await page.keyboard.press('Space');
      await expect(page.locator('body')).toHaveClass(/dragging/);
      await page.waitForTimeout(200);
      await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(200);
      await page.keyboard.press('Space');

      await expect(page.getByText("Couldn't reorder — try again")).toBeVisible();
      await expect(page.getByTestId('track-title').first()).toHaveText('Borealis');
    });
  });

  test('filters the add-to-playlist picker by name', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/search');
    await trackRow(page, 'Aurora').getByTestId('track-menu-button').click();
    await page.getByTestId('track-menu-add-playlist').click();
    await expect(page.getByTestId('add-to-playlist-option')).toHaveCount(2);

    await page.getByTestId('add-to-playlist-filter').fill('work');
    await expect(page.getByTestId('add-to-playlist-option')).toHaveCount(1);
    await expect(
      page.getByTestId('add-to-playlist-option').filter({ hasText: 'Workout' })
    ).toBeVisible();

    await page.getByTestId('add-to-playlist-filter').fill('zzz');
    await expect(page.getByTestId('add-to-playlist-option')).toHaveCount(0);
    await expect(page.getByText('No playlists match')).toBeVisible();
  });

  test('warns about a duplicate playlist name without blocking submit', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/playlists');
    await page.getByTestId('playlist-create-button').click();
    await page.getByTestId('playlist-name-input').fill('chill');
    await expect(page.getByTestId('playlist-duplicate-name-hint')).toBeVisible();
    await expect(page.getByTestId('playlist-create-submit')).toBeEnabled();

    await page.getByTestId('playlist-name-input').fill('Totally Unique');
    await expect(page.getByTestId('playlist-duplicate-name-hint')).toHaveCount(0);

    await page.getByTestId('playlist-create-submit').click();
    await expect(
      page.getByTestId('playlist-card').filter({ hasText: 'Totally Unique' })
    ).toBeVisible();
  });

  test('deleting a playlist returns to the list without it', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/playlists/pl2');
    await expect(page.getByTestId('playlist-detail-title')).toHaveText('Workout');

    await page.getByTestId('playlist-delete-button').click();
    await page.getByTestId('playlist-delete-confirm').click();

    await expect(page).toHaveURL('/playlists');
    await expect(page.getByTestId('playlist-card')).toHaveCount(1);
    await expect(page.getByTestId('playlist-card').filter({ hasText: 'Workout' })).toHaveCount(0);
  });

  test('reorder persists the new track order', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/playlists/pl1');
    await expect(page.getByTestId('track-title').first()).toHaveText('Borealis');

    const firstHandle = page.getByRole('button', { name: 'Drag to reorder', exact: true }).first();
    await firstHandle.focus();
    await page.keyboard.press('Space');
    await expect(page.locator('body')).toHaveClass(/dragging/);
    await page.waitForTimeout(200);
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(200);
    await page.keyboard.press('Space');
    await expect(page.locator('body')).not.toHaveClass(/dragging/);

    await expect(page.getByTestId('track-title').first()).toHaveText('Cascade');
    await page.reload();
    await expect(page.getByTestId('track-title').first()).toHaveText('Cascade');
  });

  test('adds a track to an existing playlist from the track menu', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/search');
    await trackRow(page, 'Aurora').getByTestId('track-menu-button').click();
    await page.getByTestId('track-menu-add-playlist').click();
    await page.getByTestId('add-to-playlist-option').filter({ hasText: 'Chill' }).click();

    await expect(page.getByText('Added to Chill')).toBeVisible();

    await page.goto('/playlists/pl1');
    await expect(page.getByTestId('track-row')).toHaveCount(3);
    await expect(trackRow(page, 'Aurora')).toBeVisible();
  });

  test('creates a new playlist with the track from the add-to-playlist modal', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/search');
    await trackRow(page, 'Cascade').getByTestId('track-menu-button').click();
    await page.getByTestId('track-menu-add-playlist').click();
    await page.getByTestId('add-to-playlist-create-new').click();
    await page.getByTestId('playlist-name-input').fill('Fresh Finds');
    await page.getByTestId('playlist-create-submit').click();

    // Only the "add track to new playlist" toast should show — not also the generic
    // "Created playlist" one, otherwise one user action reads as two confirmations.
    await expect(page.getByText('Added "Cascade" to the new playlist')).toBeVisible();
    await expect(page.getByText('Created playlist "Fresh Finds"')).toHaveCount(0);
    await expect(page.locator('[aria-live="polite"]').getByRole('status')).toHaveCount(1);

    await page.goto('/playlists');
    const newCard = page.getByTestId('playlist-card').filter({ hasText: 'Fresh Finds' });
    await expect(newCard).toContainText('1 track');
  });

  test('a bare "create playlist" (no track) still shows its own toast', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.goto('/playlists');
    await page.getByTestId('playlist-create-button').click();
    await page.getByTestId('playlist-name-input').fill('Solo Create');
    await page.getByTestId('playlist-create-submit').click();

    await expect(page.getByText('Created playlist "Solo Create"')).toBeVisible();
    await expect(page.locator('[aria-live="polite"]').getByRole('status')).toHaveCount(1);
  });

  test('blocks a second remove click during the gap between mutation success and refetch', async ({
    page,
  }) => {
    const state = installPlaylistsMock(page);
    await login(page);

    // The DELETE itself resolves fast — the bug is the window AFTER that, while the
    // invalidated items query is still refetching with the stale (pre-removal) positions
    // on screen. Only the refetch (2nd+ GET for this playlist's items) is delayed here so
    // the test actually exercises that gap rather than the mutation's own isPending window.
    let deleteRequests = 0;
    let itemsGetCount = 0;
    await page.route(/\/Playlists\/[^/]+\/Items(\?|$)/, async (route: Route) => {
      const request = route.request();
      if (request.method() === 'DELETE') {
        deleteRequests += 1;
        const url = new URL(request.url());
        const playlistId = url.pathname.split('/Playlists/')[1]!.split('/')[0]!;
        const playlist = state.playlists.find(p => p.Id === playlistId);
        if (playlist) {
          const removed = new Set(
            (url.searchParams.get('EntryIds')?.split(',') ?? []).map(entry => Number(entry))
          );
          playlist.itemIds = playlist.itemIds.filter((_, index) => !removed.has(index));
        }
        return fulfillJson(route, '{}');
      }
      if (request.method() === 'GET') {
        itemsGetCount += 1;
        if (itemsGetCount > 1) {
          await new Promise(resolve => setTimeout(resolve, 600));
        }
      }
      return route.fallback();
    });

    await page.goto('/playlists/pl1');
    const removeButton = page.getByRole('button', {
      name: 'Remove Borealis from playlist',
      exact: true,
    });

    await removeButton.click();
    await expect(removeButton).toBeDisabled();
    // force: true is deliberate — without it Playwright's own actionability check would
    // refuse to click a disabled button, so the test would never exercise whether the app's
    // disabled state (not just Playwright's UI gate) blocks the second mutation.
    await removeButton.click({ force: true }); // NOSONAR(typescript:S8783)

    await expect(page.getByTestId('track-row')).toHaveCount(1);
    expect(deleteRequests).toBe(1);
  });

  test('disables the drag handle while a reorder mutation is pending', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await page.route(/\/Playlists\/[^/]+\/Items\/[^/]+\/Move\/\d+/, async (route: Route) => {
      await new Promise(resolve => setTimeout(resolve, 500));
      return route.fallback();
    });

    await page.goto('/playlists/pl1');
    const firstHandle = page.getByRole('button', { name: 'Drag to reorder', exact: true }).first();
    await firstHandle.focus();
    await page.keyboard.press('Space');
    await expect(page.locator('body')).toHaveClass(/dragging/);
    await page.waitForTimeout(200);
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(200);
    await page.keyboard.press('Space');

    await expect(firstHandle).toBeDisabled();
  });
});

// Regression for the bug where BOTTOM_TAB_ITEMS filtered out /playlists, leaving mobile users
// (sidebar hidden below md:) with no way to reach a fully built, working feature.
test.describe('Playlists (mocked backend) — mobile bottom nav', () => {
  test.use({ viewport: { width: 412, height: 915 }, hasTouch: true });

  test('playlists is reachable via the bottom tab bar on a mobile viewport', async ({ page }) => {
    installPlaylistsMock(page);
    await login(page);

    await expect(page.getByTestId('sidebar')).toBeHidden();
    await page.getByTestId('bottom-tab-bar').getByTestId('nav-playlists').click();

    await expect(page).toHaveURL('/playlists');
    await expect(page.getByTestId('playlist-card')).toHaveCount(2);
  });
});
