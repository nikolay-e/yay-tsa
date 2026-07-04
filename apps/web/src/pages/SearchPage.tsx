import { useState, useMemo, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  useInfiniteAlbums,
  useInfiniteArtists,
  useInfiniteTracks,
  useSemanticSearch,
} from '@/features/library/hooks';
import { AlbumGrid, ArtistCard, TrackList } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
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

export function SearchPage() {
  const [searchParams] = useSearchParams();
  // The query lives in the URL (?q=), driven by the global top search bar; the page
  // reflects it reactively so typing in the bar updates results without a duplicate input.
  const searchTerm = searchParams.get('q') ?? '';
  const [searchMode, setSearchMode] = useState<SearchMode>('text');
  const debouncedSearchTerm = useDebouncedValue(searchTerm);
  const query = debouncedSearchTerm.trim();
  const hasQuery = query.length > 0;
  const isSearchPending = searchTerm !== debouncedSearchTerm;
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const { selectedId, activeOption, select } = useSortPreference('songs');

  const isSemanticActive = searchMode === 'semantic' && hasQuery;

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
    searchTerm: isSemanticActive ? undefined : query || undefined,
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
        {hasQuery ? 'No matching tracks found' : 'Search across tracks, albums, and artists'}
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
        refetchSemantic().catch(() => undefined);
      }}
    />
  );

  let trackContent;
  if (isSemanticActive && isSemanticError && tracks.length === 0) {
    trackContent = semanticErrorState;
  } else if (tracks.length === 0) {
    trackContent = emptyState;
  } else {
    trackContent = trackList;
  }
  const tracksSection = showLoading ? <LoadingSpinner /> : trackContent;

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Search</h1>
        <div className="flex w-full items-center gap-2 sm:w-auto">
          <fieldset className="border-border m-0 flex min-w-0 overflow-hidden rounded-md border p-0">
            <legend className="sr-only">Search mode</legend>
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
              title="Describe the vibe — semantic search"
              className={cn(
                'px-2.5 py-1.5 text-xs font-medium transition-colors',
                searchMode === 'semantic'
                  ? 'bg-accent text-text-on-accent'
                  : 'text-text-secondary hover:bg-bg-secondary'
              )}
            >
              AI
            </button>
          </fieldset>
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

      {hasQuery && !isSemanticActive && <SearchAlbums query={query} />}
      {hasQuery && !isSemanticActive && <SearchArtists query={query} />}

      <SearchSection title="Tracks">{tracksSection}</SearchSection>
    </div>
  );
}

type SearchSectionProps = Readonly<{
  title: string;
  children: ReactNode;
}>;

function SearchSection({ title, children }: SearchSectionProps) {
  return (
    <section className="space-y-3" data-testid={`search-section-${title.toLowerCase()}`}>
      <h2 className="text-text-secondary text-sm font-medium tracking-wide uppercase">{title}</h2>
      {children}
    </section>
  );
}

function SearchAlbums({ query }: Readonly<{ query: string }>) {
  const playAlbum = usePlayerStore(state => state.playAlbum);
  const { data, isLoading } = useInfiniteAlbums({ searchTerm: query, limit: 12 });
  const albums = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);

  if (isLoading) {
    return (
      <SearchSection title="Albums">
        <LoadingSpinner />
      </SearchSection>
    );
  }
  if (albums.length === 0) return null;

  return (
    <SearchSection title="Albums">
      <AlbumGrid albums={albums} onPlayAlbum={album => playAlbum(album.Id)} />
    </SearchSection>
  );
}

function SearchArtists({ query }: Readonly<{ query: string }>) {
  const { data, isLoading } = useInfiniteArtists({ searchTerm: query, limit: 12 });
  const artists = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);

  if (isLoading) {
    return (
      <SearchSection title="Artists">
        <LoadingSpinner />
      </SearchSection>
    );
  }
  if (artists.length === 0) return null;

  return (
    <SearchSection title="Artists">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
        {artists.map(artist => (
          <ArtistCard key={artist.Id} artist={artist} />
        ))}
      </div>
    </SearchSection>
  );
}
