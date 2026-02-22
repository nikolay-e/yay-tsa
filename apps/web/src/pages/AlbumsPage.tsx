import { useState, useMemo, useDeferredValue } from 'react';
import { useInfiniteAlbums } from '@/features/library/hooks';
import { AlbumGrid } from '@/features/library/components';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { SearchInput } from '@/shared/ui/SearchInput';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';

export function AlbumsPage() {
  const [searchTerm, setSearchTerm] = useState('');
  const deferredSearchTerm = useDeferredValue(searchTerm);
  const playAlbum = usePlayerStore(state => state.playAlbum);

  const { data, isLoading, isFetchingNextPage, error, hasNextPage, fetchNextPage } =
    useInfiniteAlbums({
      searchTerm: deferredSearchTerm.trim() || undefined,
    });

  const albums = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleLoadMore = () => {
    void fetchNextPage();
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Albums</h1>
        <SearchInput value={searchTerm} onChange={setSearchTerm} placeholder="Search albums..." />
      </div>

      {error && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {error instanceof Error ? error.message : 'Failed to load albums'}
        </div>
      )}

      {isLoading ? (
        <LoadingSpinner />
      ) : albums.length === 0 ? (
        <div className="flex h-64 items-center justify-center">
          <p className="text-text-secondary">No albums found</p>
        </div>
      ) : (
        <>
          <AlbumGrid albums={albums} onPlayAlbum={album => void playAlbum(album.Id)} />
          <InfiniteScrollFooter
            hasNextPage={hasNextPage}
            isFetchingNextPage={isFetchingNextPage}
            onLoadMore={handleLoadMore}
            currentCount={albums.length}
            totalCount={totalCount}
            itemLabel="albums"
          />
        </>
      )}
    </div>
  );
}
