import { useState, useMemo } from 'react';
import { useInfiniteTracks, useSemanticSearch } from '@/features/library/hooks';
import { TrackList } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { SearchInput } from '@/shared/ui/SearchInput';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { InfiniteScrollHeader } from '@/shared/ui/InfiniteScrollHeader';
import { SortMenu, useSortPreference } from '@/shared/ui/SortMenu';
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue';
import { cn } from '@/shared/utils/cn';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';

type SearchMode = 'text' | 'semantic';

export function SongsPage() {
  const [searchTerm, setSearchTerm] = useState('');
  const [searchMode, setSearchMode] = useState<SearchMode>('text');
  const debouncedSearchTerm = useDebouncedValue(searchTerm);
  const isSearchPending = searchTerm !== debouncedSearchTerm;
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const { selectedId, activeOption, select } = useSortPreference('songs');

  const isSemanticActive = searchMode === 'semantic' && debouncedSearchTerm.trim().length > 0;

  const {
    data: semanticResults,
    isLoading: isSemanticLoading,
    isFetching: isSemanticFetching,
    isError: isSemanticError,
    error: semanticError,
    refetch: refetchSemantic,
  } = useSemanticSearch(debouncedSearchTerm, isSemanticActive);

  const {
    data,
    isLoading,
    isFetchingNextPage,
    isFetchingPreviousPage,
    isFetchNextPageError,
    error,
    hasNextPage,
    hasPreviousPage,
    fetchNextPage,
    fetchPreviousPage,
  } = useInfiniteTracks({
    searchTerm: isSemanticActive ? undefined : debouncedSearchTerm.trim() || undefined,
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const textTracks = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;
  const tracks = isSemanticActive ? (semanticResults ?? []) : textTracks;
  const showLoading = isSemanticActive ? isSemanticLoading : isLoading;

  const handleLoadMore = () => {
    fetchNextPage();
  };

  const handlePlayTrack = (_track: unknown, index: number) => {
    playTracks(tracks, index);
  };

  const emptyState = (
    <div className="flex h-64 items-center justify-center">
      <p className="text-text-secondary">
        {isSemanticActive ? 'No matching tracks found' : 'No songs found'}
      </p>
    </div>
  );

  const trackList = (
    <div className={cn((isSearchPending || isSemanticFetching) && 'opacity-60 transition-opacity')}>
      {!isSemanticActive && (
        <InfiniteScrollHeader
          hasPreviousPage={hasPreviousPage}
          isFetchingPreviousPage={isFetchingPreviousPage}
          onLoadPrevious={() => {
            fetchPreviousPage();
          }}
        />
      )}
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
      {!isSemanticActive && (
        <InfiniteScrollFooter
          hasNextPage={hasNextPage}
          isFetchingNextPage={isFetchingNextPage}
          isFetchNextPageError={isFetchNextPageError}
          onLoadMore={handleLoadMore}
          currentCount={textTracks.length}
          totalCount={totalCount}
          itemLabel="songs"
        />
      )}
    </div>
  );

  const semanticErrorState = (
    <LoadErrorState
      message={semanticError instanceof Error ? semanticError.message : 'Search failed. Try again.'}
      onRetry={() => {
        void refetchSemantic();
      }}
    />
  );

  let loadedContent;
  if (isSemanticActive && isSemanticError && tracks.length === 0) {
    loadedContent = semanticErrorState;
  } else if (tracks.length === 0) {
    loadedContent = emptyState;
  } else {
    loadedContent = trackList;
  }
  const content = showLoading ? <LoadingSpinner /> : loadedContent;

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Songs</h1>
        <div className="flex w-full items-center gap-2 sm:w-auto">
          <SearchInput
            value={searchTerm}
            onChange={setSearchTerm}
            placeholder={searchMode === 'semantic' ? 'Describe the vibe...' : 'Search songs...'}
          />
          <div className="border-border flex overflow-hidden rounded-md border">
            <button
              onClick={() => setSearchMode('text')}
              aria-pressed={searchMode === 'text'}
              className={cn(
                'px-2.5 py-1.5 text-xs font-medium transition-colors',
                searchMode === 'text'
                  ? 'bg-accent text-text-on-accent'
                  : 'text-text-secondary hover:bg-bg-secondary'
              )}
            >
              Text
            </button>
            <button
              onClick={() => setSearchMode('semantic')}
              aria-pressed={searchMode === 'semantic'}
              className={cn(
                'px-2.5 py-1.5 text-xs font-medium transition-colors',
                searchMode === 'semantic'
                  ? 'bg-accent text-text-on-accent'
                  : 'text-text-secondary hover:bg-bg-secondary'
              )}
            >
              AI
            </button>
          </div>
          {searchMode === 'text' && <SortMenu selectedId={selectedId} onSelect={select} />}
        </div>
      </div>

      {!isSemanticActive && error && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {error instanceof Error ? error.message : 'Failed to load songs'}
        </div>
      )}
      {isSemanticActive && isSemanticError && tracks.length > 0 && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {semanticError instanceof Error ? semanticError.message : 'Search failed. Try again.'}
        </div>
      )}

      {content}
    </div>
  );
}
