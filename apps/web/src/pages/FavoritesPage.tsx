import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { type AudioItem, type MusicAlbum, type MusicArtist } from '@yay-tsa/core';
import { useInfiniteAlbums, useInfiniteArtists, useInfiniteTracks } from '@/features/library/hooks';
import { AlbumGrid, ArtistCard, TrackList, TrackListRow } from '@/features/library/components';
import { useReorderFavorites } from '@/features/library/hooks/useReorderFavorites';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { SortMenu, FAVORITES_SORT_OPTIONS, useSortPreference } from '@/shared/ui/SortMenu';
import { SortableList } from '@/shared/ui/SortableList';
import { SearchInput } from '@/shared/ui/SearchInput';
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { cn } from '@/shared/utils/cn';
import { AlbumCard } from '@/features/library/components/AlbumCard';
import { FAVORITES_TEST_IDS } from '@/shared/testing/test-ids';

type FavoriteTab = 'albums' | 'artists' | 'tracks';

type SortState = ReturnType<typeof useSortPreference>;

const TABS: { key: FavoriteTab; label: string }[] = [
  { key: 'tracks', label: 'Songs' },
  { key: 'albums', label: 'Albums' },
  { key: 'artists', label: 'Artists' },
];

export function FavoritesPage() {
  const [activeTab, setActiveTab] = useState<FavoriteTab>('tracks');
  const [searchTerm, setSearchTerm] = useState('');
  const debouncedSearchTerm = useDebouncedValue(searchTerm);
  const search = debouncedSearchTerm.trim() || undefined;

  const trackSort = useSortPreference('songs', FAVORITES_SORT_OPTIONS, 'favorites');
  const albumSort = useSortPreference('albums', FAVORITES_SORT_OPTIONS, 'favorites');
  const artistSort = useSortPreference('artists', FAVORITES_SORT_OPTIONS, 'favorites');

  let activeSort: SortState;
  if (activeTab === 'tracks') activeSort = trackSort;
  else if (activeTab === 'albums') activeSort = albumSort;
  else activeSort = artistSort;

  return (
    <div className="space-y-6 p-6" data-testid={FAVORITES_TEST_IDS.PAGE}>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Favorites</h1>
        <SearchInput
          value={searchTerm}
          onChange={setSearchTerm}
          placeholder="Search favorites..."
        />
      </div>

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

      {activeTab === 'albums' && <FavoriteAlbums sortState={albumSort} searchTerm={search} />}
      {activeTab === 'artists' && <FavoriteArtists sortState={artistSort} searchTerm={search} />}
      {activeTab === 'tracks' && <FavoriteTracks sortState={trackSort} searchTerm={search} />}
    </div>
  );
}

function FavoriteAlbums({
  sortState,
  searchTerm,
}: Readonly<{ sortState: SortState; searchTerm?: string }>) {
  const playAlbum = usePlayerStore(state => state.playAlbum);
  const { activeOption } = sortState;
  const reorderMutation = useReorderFavorites();
  const isCustomOrder = activeOption.sortBy === 'FavoritePosition';

  const {
    data,
    isLoading,
    isError,
    error,
    refetch,
    isFetchingNextPage,
    isFetchNextPageError,
    hasNextPage,
    fetchNextPage,
  } = useInfiniteAlbums({
    isFavorite: true,
    searchTerm,
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const albums = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleReorder = (reordered: MusicAlbum[]) => {
    reorderMutation.mutate(reordered.map(a => a.Id));
  };

  const handlePlayAlbum = (album: { Id: string }) => {
    playAlbum(album.Id);
  };

  const handleLoadMore = () => {
    fetchNextPage();
  };

  if (isLoading) return <LoadingSpinner />;

  if (isError && albums.length === 0) {
    return (
      <LoadErrorState
        message={error instanceof Error ? error.message : 'Failed to load favorite albums'}
        onRetry={() => {
          void refetch();
        }}
      />
    );
  }

  if (albums.length === 0) {
    if (searchTerm) {
      return <FavoritesSearchEmptyState itemLabel="albums" />;
    }
    return (
      <FavoritesEmptyState
        message="No favorite albums yet"
        hint="Tap the heart on any album to add it here."
        ctaLabel="Browse all albums"
        ctaTo="/albums"
      />
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
        isFetchNextPageError={isFetchNextPageError}
        onLoadMore={handleLoadMore}
        currentCount={albums.length}
        totalCount={totalCount}
        itemLabel="albums"
      />
    </>
  );
}

function FavoriteArtists({
  sortState,
  searchTerm,
}: Readonly<{ sortState: SortState; searchTerm?: string }>) {
  const { activeOption } = sortState;
  const reorderMutation = useReorderFavorites();
  const isCustomOrder = activeOption.sortBy === 'FavoritePosition';

  const {
    data,
    isLoading,
    isError,
    error,
    refetch,
    isFetchingNextPage,
    isFetchNextPageError,
    hasNextPage,
    fetchNextPage,
  } = useInfiniteArtists({
    isFavorite: true,
    searchTerm,
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const artists = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleReorder = (reordered: MusicArtist[]) => {
    reorderMutation.mutate(reordered.map(a => a.Id));
  };

  const handleLoadMore = () => {
    fetchNextPage();
  };

  if (isLoading) return <LoadingSpinner />;

  if (isError && artists.length === 0) {
    return (
      <LoadErrorState
        message={error instanceof Error ? error.message : 'Failed to load favorite artists'}
        onRetry={() => {
          void refetch();
        }}
      />
    );
  }

  if (artists.length === 0) {
    if (searchTerm) {
      return <FavoritesSearchEmptyState itemLabel="artists" />;
    }
    return (
      <FavoritesEmptyState
        message="No favorite artists yet"
        hint="Tap the heart on any artist to add them here."
        ctaLabel="Browse all artists"
        ctaTo="/artists"
      />
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
        isFetchNextPageError={isFetchNextPageError}
        onLoadMore={handleLoadMore}
        currentCount={artists.length}
        totalCount={totalCount}
        itemLabel="artists"
      />
    </>
  );
}

function FavoriteTracks({
  sortState,
  searchTerm,
}: Readonly<{ sortState: SortState; searchTerm?: string }>) {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const { activeOption } = sortState;
  const reorderMutation = useReorderFavorites();
  const isCustomOrder = activeOption.sortBy === 'FavoritePosition';

  const {
    data,
    isLoading,
    isError,
    error,
    refetch,
    isFetchingNextPage,
    isFetchNextPageError,
    hasNextPage,
    fetchNextPage,
  } = useInfiniteTracks({
    isFavorite: true,
    searchTerm,
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const tracks = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleReorder = (reordered: AudioItem[]) => {
    reorderMutation.mutate(reordered.map(t => t.Id));
  };

  const handlePlayTrack = (_: unknown, index: number) => {
    playTracks(tracks, index);
  };

  const handleLoadMore = () => {
    fetchNextPage();
  };

  if (isLoading) return <LoadingSpinner />;

  if (isError && tracks.length === 0) {
    return (
      <LoadErrorState
        message={error instanceof Error ? error.message : 'Failed to load favorite songs'}
        onRetry={() => {
          void refetch();
        }}
      />
    );
  }

  if (tracks.length === 0) {
    if (searchTerm) {
      return <FavoritesSearchEmptyState itemLabel="songs" />;
    }
    return (
      <FavoritesEmptyState
        message="No favorite songs yet"
        hint="Tap the heart on any track to add it here."
        ctaLabel="Browse all songs"
        ctaTo="/search"
      />
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
        isFetchNextPageError={isFetchNextPageError}
        onLoadMore={handleLoadMore}
        currentCount={tracks.length}
        totalCount={totalCount}
        itemLabel="songs"
      />
    </>
  );
}

function FavoritesSearchEmptyState({ itemLabel }: Readonly<{ itemLabel: string }>) {
  return (
    <div className="flex h-64 items-center justify-center">
      <p className="text-text-secondary">No favorite {itemLabel} match your search</p>
    </div>
  );
}

function FavoritesEmptyState({
  message,
  hint,
  ctaLabel,
  ctaTo,
}: Readonly<{ message: string; hint: string; ctaLabel: string; ctaTo: string }>) {
  return (
    <div className="flex h-64 flex-col items-center justify-center gap-3 text-center">
      <p className="text-text-secondary">{message}</p>
      <p className="text-text-tertiary text-sm">{hint}</p>
      <Link
        to={ctaTo}
        className="text-accent text-sm font-medium underline-offset-4 hover:underline focus-visible:underline"
      >
        {ctaLabel}
      </Link>
    </div>
  );
}
