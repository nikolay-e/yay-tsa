import { useState, useMemo } from 'react';
import { useInfiniteAlbums, useInfiniteArtists, useInfiniteTracks } from '@/features/library/hooks';
import { AlbumGrid, ArtistCard, TrackList } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { cn } from '@/shared/utils/cn';

type FavoriteTab = 'albums' | 'artists' | 'tracks';

const TABS: { key: FavoriteTab; label: string }[] = [
  { key: 'albums', label: 'Albums' },
  { key: 'artists', label: 'Artists' },
  { key: 'tracks', label: 'Tracks' },
];

export function FavoritesPage() {
  const [activeTab, setActiveTab] = useState<FavoriteTab>('albums');

  return (
    <div className="space-y-6 p-6">
      <h1 className="text-2xl font-bold">Favorites</h1>

      <div className="border-border flex gap-1 border-b">
        {TABS.map(tab => (
          <button
            key={tab.key}
            type="button"
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              'px-4 py-2 text-sm font-medium transition-colors',
              activeTab === tab.key
                ? 'text-accent border-accent border-b-2'
                : 'text-text-secondary hover:text-text-primary'
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'albums' && <FavoriteAlbums />}
      {activeTab === 'artists' && <FavoriteArtists />}
      {activeTab === 'tracks' && <FavoriteTracks />}
    </div>
  );
}

function FavoriteAlbums() {
  const playAlbum = usePlayerStore(state => state.playAlbum);

  const { data, isLoading, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteAlbums({
    isFavorite: true,
  });

  const albums = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

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
      <AlbumGrid albums={albums} onPlayAlbum={album => void playAlbum(album.Id)} />
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        onLoadMore={() => void fetchNextPage()}
        currentCount={albums.length}
        totalCount={totalCount}
        itemLabel="albums"
      />
    </>
  );
}

function FavoriteArtists() {
  const { data, isLoading, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteArtists({
    isFavorite: true,
  });

  const artists = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

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
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
        {artists.map(artist => (
          <ArtistCard key={artist.Id} artist={artist} />
        ))}
      </div>
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        onLoadMore={() => void fetchNextPage()}
        currentCount={artists.length}
        totalCount={totalCount}
        itemLabel="artists"
      />
    </>
  );
}

function FavoriteTracks() {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data, isLoading, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteTracks({
    isFavorite: true,
    sortBy: 'SortName',
  });

  const tracks = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

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
      <TrackList
        tracks={tracks}
        currentTrackId={currentTrack?.Id}
        isPlaying={isPlaying}
        onPlayTrack={(_, index) => void playTracks(tracks, index)}
        onPauseTrack={pause}
        showAlbum
        showArtist
        showImage
      />
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        onLoadMore={() => void fetchNextPage()}
        currentCount={tracks.length}
        totalCount={totalCount}
        itemLabel="tracks"
      />
    </>
  );
}
