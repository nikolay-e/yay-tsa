// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type AudioItem } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useFavoriteToggle, useFavoritePendingStore } from './useFavorites';

const post = vi.fn<(url: string) => Promise<void>>();
const del = vi.fn<(url: string) => Promise<void>>();

const stubClient = {
  requireAuth: () => 'user1',
  isAuthenticated: () => true,
  post,
  delete: del,
};

function track(id: string, fav: boolean): AudioItem {
  return {
    Id: id,
    Name: id,
    Type: 'Audio',
    RunTimeTicks: 1,
    UserData: { PlaybackPositionTicks: 0, PlayCount: 0, IsFavorite: fav, Played: false },
  } as AudioItem;
}

// Query-key shapes mirroring the real hooks the patcher must reach.
const TRACKS_KEY = ['tracks', 'infinite', { limit: 100, isFavorite: false }];
const FAV_TRACKS_KEY = ['tracks', 'infinite', { limit: 100, isFavorite: true }];
const ALBUM_KEY = ['album', 'al1'];
const SEARCH_KEY = ['semantic-search', 'q'];
const DAILY_MIX_KEY = ['recommend', 'daily-mix', 30];

function seed(qc: QueryClient, fav: boolean) {
  qc.setQueryData(TRACKS_KEY, {
    pages: [{ Items: [track('t1', fav)], TotalRecordCount: 1, StartIndex: 0 }],
    pageParams: [0],
  });
  qc.setQueryData(ALBUM_KEY, { Id: 'al1', Items: [track('t1', fav)] });
  qc.setQueryData(SEARCH_KEY, [track('t1', fav)]);
  qc.setQueryData(DAILY_MIX_KEY, [track('t1', fav)]);
}

function favIn(qc: QueryClient, key: unknown[]): boolean | undefined {
  const data = qc.getQueryData(key);
  if (Array.isArray(data)) return (data[0] as AudioItem | undefined)?.UserData?.IsFavorite;
  const record = data as { Items?: AudioItem[]; pages?: { Items: AudioItem[] }[] } | undefined;
  const item = record?.pages?.[0]?.Items?.[0] ?? record?.Items?.[0];
  return item?.UserData?.IsFavorite;
}

function Toggle({ itemId, isFavorite }: Readonly<{ itemId: string; isFavorite: boolean }>) {
  const { mutate } = useFavoriteToggle();
  return (
    <button type="button" onClick={() => mutate({ itemId, isFavorite })}>
      toggle
    </button>
  );
}

function renderToggle(qc: QueryClient, isFavorite: boolean) {
  return render(
    <QueryClientProvider client={qc}>
      <Toggle itemId="t1" isFavorite={isFavorite} />
    </QueryClientProvider>
  );
}

let qc: QueryClient;

beforeEach(() => {
  cleanup();
  post.mockReset().mockResolvedValue(undefined);
  del.mockReset().mockResolvedValue(undefined);
  useAuthStore.setState({ client: stubClient as never });
  useFavoritePendingStore.setState({ pending: new Set() });
  qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
});

describe('useFavoriteToggle cache patching', () => {
  it('optimistically patches the item in EVERY relevant cache from one click', async () => {
    seed(qc, false);
    renderToggle(qc, false);

    fireEvent.click(screen.getByText('toggle'));

    await waitFor(() => expect(favIn(qc, TRACKS_KEY)).toBe(true));
    expect(favIn(qc, ALBUM_KEY)).toBe(true); // single-item query + nested Items
    expect(favIn(qc, SEARCH_KEY)).toBe(true); // search results array
    expect(favIn(qc, DAILY_MIX_KEY)).toBe(true); // daily mix array
    expect(post).toHaveBeenCalledWith('/UserFavoriteItems/t1');
  });

  it('rolls every cache back to the previous state when the request fails', async () => {
    post.mockRejectedValue(new Error('network'));
    seed(qc, false);
    renderToggle(qc, false);

    fireEvent.click(screen.getByText('toggle'));

    // After the failed request settles, every cache is restored to its pre-click value.
    await waitFor(() => expect(post).toHaveBeenCalled());
    await waitFor(() => expect(favIn(qc, TRACKS_KEY)).toBe(false));
    expect(favIn(qc, SEARCH_KEY)).toBe(false);
    expect(favIn(qc, DAILY_MIX_KEY)).toBe(false);
  });

  it('unfavorite calls DELETE and empties the heart in place (remain-until-refetch policy)', async () => {
    qc.setQueryData(FAV_TRACKS_KEY, {
      pages: [{ Items: [track('t1', true)], TotalRecordCount: 1, StartIndex: 0 }],
      pageParams: [0],
    });
    renderToggle(qc, true);

    fireEvent.click(screen.getByText('toggle'));

    await waitFor(() => expect(del).toHaveBeenCalledWith('/UserFavoriteItems/t1'));
    // Item stays in the favorites list with an empty heart; the settle refetch removes it later.
    const data = qc.getQueryData(FAV_TRACKS_KEY) as { pages: { Items: AudioItem[] }[] };
    expect(data.pages[0]!.Items).toHaveLength(1);
    expect(data.pages[0]!.Items[0]!.UserData?.IsFavorite).toBe(false);
  });
});
