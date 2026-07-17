import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useInfiniteArtists } from '@/features/library/hooks';
import { ArtistCard } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { InfiniteScrollHeader } from '@/shared/ui/InfiniteScrollHeader';
import { SortMenu, useSortPreference } from '@/shared/ui/SortMenu';
import { SearchButton } from '@/shared/ui/SearchButton';

export function ArtistsPage() {
  const { selectedId, activeOption, select } = useSortPreference('artists');

  const {
    data,
    isLoading,
    isError,
    error,
    refetch,
    isFetchingNextPage,
    isFetchingPreviousPage,
    isFetchNextPageError,
    hasNextPage,
    hasPreviousPage,
    fetchNextPage,
    fetchPreviousPage,
  } = useInfiniteArtists({
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const artists = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleLoadMore = () => {
    fetchNextPage();
  };

  const emptyState = (
    <div className="flex h-64 flex-col items-center justify-center gap-3 text-center">
      <p className="text-text-secondary">No artists found</p>
      <p className="text-text-tertiary text-sm">
        Add music to your server&rsquo;s library folder, then run a scan from Settings.
      </p>
      <Link
        to="/settings"
        className="text-accent text-sm font-medium underline-offset-4 hover:underline focus-visible:underline"
      >
        Open Settings
      </Link>
    </div>
  );

  const artistList = (
    <div>
      <InfiniteScrollHeader
        hasPreviousPage={hasPreviousPage}
        isFetchingPreviousPage={isFetchingPreviousPage}
        onLoadPrevious={() => {
          fetchPreviousPage();
        }}
      />
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
        {artists.map(artist => (
          <ArtistCard key={artist.Id} artist={artist} />
        ))}
      </div>
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        isFetchNextPageError={isFetchNextPageError}
        onLoadMore={handleLoadMore}
        currentCount={artists.length}
        totalCount={totalCount}
        itemLabel="artists"
      />
    </div>
  );

  let content;
  if (isLoading) {
    content = <LoadingSpinner />;
  } else if (isError && artists.length === 0) {
    content = (
      <LoadErrorState
        message={error instanceof Error ? error.message : 'Failed to load artists'}
        onRetry={() => {
          void refetch();
        }}
      />
    );
  } else if (artists.length === 0) {
    content = emptyState;
  } else {
    content = artistList;
  }

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Artists</h1>
        <div className="flex items-center gap-2">
          <SearchButton />
          <SortMenu selectedId={selectedId} onSelect={select} />
        </div>
      </div>

      {content}
    </div>
  );
}
