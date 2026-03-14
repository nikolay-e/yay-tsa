import { useState, useMemo, useDeferredValue } from 'react';
import { useInfiniteTracks } from '@/features/library/hooks';
import { TrackList } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { SearchInput } from '@/shared/ui/SearchInput';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { SortMenu, useSortPreference } from '@/shared/ui/SortMenu';
import { cn } from '@/shared/utils/cn';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';

export function SongsPage() {
  const [searchTerm, setSearchTerm] = useState('');
  const deferredSearchTerm = useDeferredValue(searchTerm);
  const isSearchPending = searchTerm !== deferredSearchTerm;
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const { selectedId, activeOption, select } = useSortPreference('songs');

  const { data, isLoading, isFetchingNextPage, error, hasNextPage, fetchNextPage } =
    useInfiniteTracks({
      searchTerm: deferredSearchTerm.trim() || undefined,
      sortBy: activeOption.sortBy,
      sortOrder: activeOption.sortOrder,
    });

  const tracks = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleLoadMore = () => {
    fetchNextPage();
  };

  const handlePlayTrack = (_track: unknown, index: number) => {
    playTracks(tracks, index);
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Songs</h1>
        <div className="flex items-center gap-2">
          <SearchInput value={searchTerm} onChange={setSearchTerm} placeholder="Search songs..." />
          <SortMenu selectedId={selectedId} onSelect={select} />
        </div>
      </div>

      {error && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {error instanceof Error ? error.message : 'Failed to load songs'}
        </div>
      )}

      {isLoading ? (
        <LoadingSpinner />
      ) : tracks.length === 0 ? (
        <div className="flex h-64 items-center justify-center">
          <p className="text-text-secondary">No songs found</p>
        </div>
      ) : (
        <div className={cn(isSearchPending && 'opacity-60 transition-opacity')}>
          <TrackList
            tracks={tracks}
            currentTrackId={currentTrack?.Id}
            isPlaying={isPlaying}
            onPlayTrack={handlePlayTrack}
            onPauseTrack={pause}
            showAlbum={true}
            showArtist={true}
            showImage={true}
            virtualized
          />
          <InfiniteScrollFooter
            hasNextPage={hasNextPage}
            isFetchingNextPage={isFetchingNextPage}
            onLoadMore={handleLoadMore}
            currentCount={tracks.length}
            totalCount={totalCount}
            itemLabel="songs"
          />
        </div>
      )}
    </div>
  );
}
