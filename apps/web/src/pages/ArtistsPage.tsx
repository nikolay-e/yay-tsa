import React, { useState, useMemo, useDeferredValue } from 'react';
import { Search, Loader2 } from 'lucide-react';
import { useInfiniteArtists } from '@/features/library/hooks';
import { ArtistCard } from '@/features/library/components';
import { cn } from '@/shared/utils/cn';

export function ArtistsPage() {
  const [searchTerm, setSearchTerm] = useState('');
  const deferredSearchTerm = useDeferredValue(searchTerm);

  const { data, isLoading, isFetchingNextPage, error, hasNextPage, fetchNextPage } =
    useInfiniteArtists({
      searchTerm: deferredSearchTerm || undefined,
    });

  const artists = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleLoadMore = () => {
    void fetchNextPage();
  };

  const handleSearch = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchTerm(e.target.value);
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Artists</h1>

        <div className="relative w-full sm:w-64">
          <Search className="text-text-tertiary absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2" />
          <input
            type="text"
            value={searchTerm}
            onChange={handleSearch}
            placeholder="Search artists..."
            className={cn(
              'border-border bg-bg-secondary w-full rounded-sm border py-2 pr-4 pl-9',
              'text-text-primary placeholder:text-text-tertiary',
              'focus:border-accent transition-colors'
            )}
          />
        </div>
      </div>

      {error && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {error instanceof Error ? error.message : 'Failed to load artists'}
        </div>
      )}

      {isLoading ? (
        <div className="flex h-64 items-center justify-center">
          <Loader2 className="text-accent h-8 w-8 animate-spin" />
        </div>
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

          {hasNextPage && (
            <div className="flex justify-center pt-4">
              <button
                onClick={handleLoadMore}
                disabled={isFetchingNextPage}
                className={cn(
                  'bg-bg-secondary text-text-primary rounded-md px-6 py-2',
                  'hover:bg-bg-tertiary transition-colors',
                  'disabled:opacity-50'
                )}
              >
                {isFetchingNextPage ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Load More'}
              </button>
            </div>
          )}

          {totalCount > 0 && (
            <p className="text-text-tertiary text-center text-sm">
              Showing {artists.length} of {totalCount} artists
            </p>
          )}
        </>
      )}
    </div>
  );
}
