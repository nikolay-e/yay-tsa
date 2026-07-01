import { useMemo } from 'react';
import { useInfiniteArtists } from '@/features/library/hooks';
import { ArtistCard } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { InfiniteScrollHeader } from '@/shared/ui/InfiniteScrollHeader';
import { SortMenu, useSortPreference } from '@/shared/ui/SortMenu';

export function ArtistsPage() {
  const { selectedId, activeOption, select } = useSortPreference('artists');

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
    <div className="flex h-64 items-center justify-center">
      <p className="text-text-secondary">No artists found</p>
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

  const loadedContent = artists.length === 0 ? emptyState : artistList;
  const content = isLoading ? <LoadingSpinner /> : loadedContent;

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Artists</h1>
        <SortMenu selectedId={selectedId} onSelect={select} />
      </div>

      {error && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {error instanceof Error ? error.message : 'Failed to load artists'}
        </div>
      )}

      {content}
    </div>
  );
}
