import React, { useState } from 'react';
import { Search, Loader2 } from 'lucide-react';
import { useAlbums } from '@/features/library/hooks';
import { AlbumGrid } from '@/features/library/components';
import { cn } from '@/shared/utils/cn';

const PAGE_SIZE = 50;

export function AlbumsPage() {
  const [searchTerm, setSearchTerm] = useState('');
  const [startIndex, setStartIndex] = useState(0);

  const { data, isLoading, error } = useAlbums({
    startIndex,
    limit: PAGE_SIZE,
    searchTerm: searchTerm || undefined,
  });

  const albums = data?.Items || [];
  const totalCount = data?.TotalRecordCount || 0;
  const hasMore = startIndex + PAGE_SIZE < totalCount;

  const handleLoadMore = () => {
    setStartIndex(prev => prev + PAGE_SIZE);
  };

  const handleSearch = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchTerm(e.target.value);
    setStartIndex(0);
  };

  return (
    <div className="space-y-lg p-lg">
      <div className="flex flex-wrap items-center justify-between gap-md">
        <h1 className="text-2xl font-bold">Albums</h1>

        <div className="relative w-full sm:w-64">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-text-tertiary" />
          <input
            type="text"
            value={searchTerm}
            onChange={handleSearch}
            placeholder="Search albums..."
            className={cn(
              'w-full rounded-sm border border-border bg-bg-secondary py-sm pl-9 pr-md',
              'text-text-primary placeholder:text-text-tertiary',
              'transition-colors focus:border-accent'
            )}
          />
        </div>
      </div>

      {error && (
        <div className="bg-error/10 border-error/20 rounded-md border p-md text-error">
          {error instanceof Error ? error.message : 'Failed to load albums'}
        </div>
      )}

      {isLoading && startIndex === 0 ? (
        <div className="flex h-64 items-center justify-center">
          <Loader2 className="h-8 w-8 animate-spin text-accent" />
        </div>
      ) : (
        <>
          <AlbumGrid albums={albums} />

          {hasMore && (
            <div className="flex justify-center pt-md">
              <button
                onClick={handleLoadMore}
                disabled={isLoading}
                className={cn(
                  'rounded-md bg-bg-secondary px-lg py-sm text-text-primary',
                  'transition-colors hover:bg-bg-tertiary',
                  'disabled:opacity-50'
                )}
              >
                {isLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Load More'}
              </button>
            </div>
          )}

          {totalCount > 0 && (
            <p className="text-center text-sm text-text-tertiary">
              Showing {Math.min(startIndex + PAGE_SIZE, totalCount)} of {totalCount} albums
            </p>
          )}
        </>
      )}
    </div>
  );
}
