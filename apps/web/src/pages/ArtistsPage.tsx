import { useState, useMemo, useDeferredValue } from 'react';
import { useInfiniteArtists } from '@/features/library/hooks';
import { ArtistCard } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { SearchInput } from '@/shared/ui/SearchInput';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';

export function ArtistsPage() {
  const [searchTerm, setSearchTerm] = useState('');
  const deferredSearchTerm = useDeferredValue(searchTerm);

  const { data, isLoading, isFetchingNextPage, error, hasNextPage, fetchNextPage } =
    useInfiniteArtists({
      searchTerm: deferredSearchTerm.trim() || undefined,
    });

  const artists = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleLoadMore = () => {
    void fetchNextPage();
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Artists</h1>
        <SearchInput value={searchTerm} onChange={setSearchTerm} placeholder="Search artists..." />
      </div>

      {error && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {error instanceof Error ? error.message : 'Failed to load artists'}
        </div>
      )}

      {isLoading ? (
        <LoadingSpinner />
      ) : artists.length === 0 ? (
        <div className="flex h-64 items-center justify-center">
          <p className="text-text-secondary">No artists found</p>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
            {artists.map(artist => (
              <ArtistCard key={artist.Id} artist={artist} />
            ))}
          </div>
          <InfiniteScrollFooter
            hasNextPage={hasNextPage}
            isFetchingNextPage={isFetchingNextPage}
            onLoadMore={handleLoadMore}
            currentCount={artists.length}
            totalCount={totalCount}
            itemLabel="artists"
          />
        </>
      )}
    </div>
  );
}
