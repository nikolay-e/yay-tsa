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
    return fulfillJson(
      route,
      itemsBody(playlist.itemIds.map((trackId, index) => buildTrack(trackId, index)))
    );
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

    await page.getByTestId('nav-playlists').click();
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
    await expect(page.getByText('Created playlist "Fresh Finds"')).toBeVisible();

    await page.goto('/playlists');
    const newCard = page.getByTestId('playlist-card').filter({ hasText: 'Fresh Finds' });
    await expect(newCard).toContainText('1 track');
  });
});
