import { useState, useMemo, useCallback } from 'react';
import { type AudioItem, type MusicAlbum, type MusicArtist } from '@yay-tsa/core';
import { useInfiniteAlbums, useInfiniteArtists, useInfiniteTracks } from '@/features/library/hooks';
import { AlbumGrid, ArtistCard, TrackList, TrackListRow } from '@/features/library/components';
import { useReorderFavorites } from '@/features/library/hooks/useReorderFavorites';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { SortMenu, FAVORITES_SORT_OPTIONS, useSortPreference } from '@/shared/ui/SortMenu';
import { SortableList } from '@/shared/ui/SortableList';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { cn } from '@/shared/utils/cn';
import { AlbumCard } from '@/features/library/components/AlbumCard';

type FavoriteTab = 'albums' | 'artists' | 'tracks';

type SortState = ReturnType<typeof useSortPreference>;

const TABS: { key: FavoriteTab; label: string }[] = [
  { key: 'tracks', label: 'Tracks' },
  { key: 'albums', label: 'Albums' },
  { key: 'artists', label: 'Artists' },
];

export function FavoritesPage() {
  const [activeTab, setActiveTab] = useState<FavoriteTab>('tracks');

  const trackSort = useSortPreference('songs', FAVORITES_SORT_OPTIONS, 'favorites');
  const albumSort = useSortPreference('albums', FAVORITES_SORT_OPTIONS, 'favorites');
  const artistSort = useSortPreference('artists', FAVORITES_SORT_OPTIONS, 'favorites');

  let activeSort: SortState;
  if (activeTab === 'tracks') activeSort = trackSort;
  else if (activeTab === 'albums') activeSort = albumSort;
  else activeSort = artistSort;

  return (
    <div className="space-y-6 p-6">
      <h1 className="text-2xl font-bold">Favorites</h1>

      <div className="border-border flex items-end justify-between border-b">
        <div className="flex gap-1">
          {TABS.map(tab => (
            <button
              key={tab.key}
              type="button"
              onClick={() => setActiveTab(tab.key)}
              className={cn(
                '-mb-px px-4 py-2 text-sm font-medium transition-colors',
                activeTab === tab.key
                  ? 'text-accent border-accent border-b-2'
                  : 'text-text-secondary hover:text-text-primary'
              )}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <SortMenu
          selectedId={activeSort.selectedId}
          onSelect={activeSort.select}
          options={FAVORITES_SORT_OPTIONS}
          className="mb-1"
        />
      </div>

      {activeTab === 'albums' && <FavoriteAlbums sortState={albumSort} />}
      {activeTab === 'artists' && <FavoriteArtists sortState={artistSort} />}
      {activeTab === 'tracks' && <FavoriteTracks sortState={trackSort} />}
    </div>
  );
}

function FavoriteAlbums({ sortState }: Readonly<{ sortState: SortState }>) {
  const playAlbum = usePlayerStore(state => state.playAlbum);
  const { activeOption } = sortState;
  const reorderMutation = useReorderFavorites();
  const isCustomOrder = activeOption.sortBy === 'FavoritePosition';

  const { data, isLoading, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteAlbums({
    isFavorite: true,
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const albums = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleReorder = useCallback(
    (reordered: MusicAlbum[]) => {
      reorderMutation.mutate(reordered.map(a => a.Id));
    },
    [reorderMutation]
  );

  const handlePlayAlbum = useCallback(
    (album: { Id: string }) => {
      playAlbum(album.Id);
    },
    [playAlbum]
  );

  const handleLoadMore = useCallback(() => {
    fetchNextPage();
  }, [fetchNextPage]);

  if (isLoading) return <LoadingSpinner />;

  if (albums.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center">
        <p className="text-text-secondary">No favorite albums yet</p>
      </div>
    );
  }

  return (
    <>
      {isCustomOrder ? (
        <SortableList
          items={albums}
          onReorder={handleReorder}
          layout="grid"
          gridClassName="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6"
          renderItem={album => (
            <AlbumCard
              album={album}
              isPlaying={false}
              onPlay={() => {
                playAlbum(album.Id);
              }}
            />
          )}
        />
      ) : (
        <AlbumGrid albums={albums} onPlayAlbum={handlePlayAlbum} />
      )}
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        onLoadMore={handleLoadMore}
        currentCount={albums.length}
        totalCount={totalCount}
        itemLabel="albums"
      />
    </>
  );
}

function FavoriteArtists({ sortState }: Readonly<{ sortState: SortState }>) {
  const { activeOption } = sortState;
  const reorderMutation = useReorderFavorites();
  const isCustomOrder = activeOption.sortBy === 'FavoritePosition';

  const { data, isLoading, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteArtists({
    isFavorite: true,
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const artists = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleReorder = useCallback(
    (reordered: MusicArtist[]) => {
      reorderMutation.mutate(reordered.map(a => a.Id));
    },
    [reorderMutation]
  );

  const handleLoadMore = useCallback(() => {
    fetchNextPage();
  }, [fetchNextPage]);

  if (isLoading) return <LoadingSpinner />;

  if (artists.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center">
        <p className="text-text-secondary">No favorite artists yet</p>
      </div>
    );
  }

  return (
    <>
      {isCustomOrder ? (
        <SortableList
          items={artists}
          onReorder={handleReorder}
          layout="grid"
          gridClassName="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6"
          renderItem={artist => <ArtistCard artist={artist} />}
        />
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
          {artists.map(artist => (
            <ArtistCard key={artist.Id} artist={artist} />
          ))}
        </div>
      )}
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        onLoadMore={handleLoadMore}
        currentCount={artists.length}
        totalCount={totalCount}
        itemLabel="artists"
      />
    </>
  );
}

function FavoriteTracks({ sortState }: Readonly<{ sortState: SortState }>) {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const { activeOption } = sortState;
  const reorderMutation = useReorderFavorites();
  const isCustomOrder = activeOption.sortBy === 'FavoritePosition';

  const { data, isLoading, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteTracks({
    isFavorite: true,
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const tracks = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleReorder = useCallback(
    (reordered: AudioItem[]) => {
      reorderMutation.mutate(reordered.map(t => t.Id));
    },
    [reorderMutation]
  );

  const handlePlayTrack = useCallback(
    (_: unknown, index: number) => {
      playTracks(tracks, index);
    },
    [playTracks, tracks]
  );

  const handleLoadMore = useCallback(() => {
    fetchNextPage();
  }, [fetchNextPage]);

  if (isLoading) return <LoadingSpinner />;

  if (tracks.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center">
        <p className="text-text-secondary">No favorite tracks yet</p>
      </div>
    );
  }

  return (
    <>
      {isCustomOrder ? (
        <SortableList
          items={tracks}
          onReorder={handleReorder}
          renderItem={(track, index) => (
            <TrackListRow
              track={track}
              index={index}
              isCurrentTrack={track.Id === currentTrack?.Id}
              isPlaying={isPlaying}
              onPlay={() => {
                playTracks(tracks, index);
              }}
              onPause={pause}
              showAlbum
              showArtist
              showImage
            />
          )}
        />
      ) : (
        <TrackList
          tracks={tracks}
          currentTrackId={currentTrack?.Id}
          isPlaying={isPlaying}
          onPlayTrack={handlePlayTrack}
          onPauseTrack={pause}
          showAlbum
          showArtist
          showImage
          virtualized
        />
      )}
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        onLoadMore={handleLoadMore}
        currentCount={tracks.length}
        totalCount={totalCount}
        itemLabel="tracks"
      />
    </>
  );
}
