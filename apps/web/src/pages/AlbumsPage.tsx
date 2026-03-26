import { useState, useMemo, useCallback, useDeferredValue } from 'react';
import { useInfiniteAlbums } from '@/features/library/hooks';
import { AlbumGrid } from '@/features/library/components';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { SearchInput } from '@/shared/ui/SearchInput';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { SortMenu, useSortPreference } from '@/shared/ui/SortMenu';
import { cn } from '@/shared/utils/cn';

export function AlbumsPage() {
  const [searchTerm, setSearchTerm] = useState('');
  const deferredSearchTerm = useDeferredValue(searchTerm);
  const isSearchPending = searchTerm !== deferredSearchTerm;
  const playAlbum = usePlayerStore(state => state.playAlbum);
  const { selectedId, activeOption, select } = useSortPreference('albums');

  const { data, isLoading, isFetchingNextPage, error, hasNextPage, fetchNextPage } =
    useInfiniteAlbums({
      searchTerm: deferredSearchTerm.trim() || undefined,
      sortBy: activeOption.sortBy,
      sortOrder: activeOption.sortOrder,
    });

  const albums = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleLoadMore = useCallback(() => {
    fetchNextPage();
  }, [fetchNextPage]);

  const handlePlayAlbum = useCallback(
    (album: { Id: string }) => {
      playAlbum(album.Id);
    },
    [playAlbum]
  );

  const content = isLoading ? (
    <LoadingSpinner />
  ) : albums.length === 0 ? (
    <div className="flex h-64 items-center justify-center">
      <p className="text-text-secondary">No albums found</p>
    </div>
  ) : (
    <div className={cn(isSearchPending && 'opacity-60 transition-opacity')}>
      <AlbumGrid albums={albums} onPlayAlbum={handlePlayAlbum} />
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        onLoadMore={handleLoadMore}
        currentCount={albums.length}
        totalCount={totalCount}
        itemLabel="albums"
      />
    </div>
  );

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Albums</h1>
        <div className="flex w-full items-center gap-2 sm:w-auto">
          <SearchInput value={searchTerm} onChange={setSearchTerm} placeholder="Search albums..." />
          <SortMenu selectedId={selectedId} onSelect={select} />
        </div>
      </div>

      {error && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {error instanceof Error ? error.message : 'Failed to load albums'}
        </div>
      )}

      {content}
    </div>
  );
}
