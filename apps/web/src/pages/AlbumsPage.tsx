import { useMemo } from 'react';
import { useInfiniteAlbums } from '@/features/library/hooks';
import { AlbumGrid } from '@/features/library/components';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { InfiniteScrollHeader } from '@/shared/ui/InfiniteScrollHeader';
import { SortMenu, useSortPreference } from '@/shared/ui/SortMenu';
import { SearchButton } from '@/shared/ui/SearchButton';

export function AlbumsPage() {
  const playAlbum = usePlayerStore(state => state.playAlbum);
  const { selectedId, activeOption, select } = useSortPreference('albums');

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
  } = useInfiniteAlbums({
    sortBy: activeOption.sortBy,
    sortOrder: activeOption.sortOrder,
  });

  const albums = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleLoadMore = () => {
    fetchNextPage();
  };

  const handlePlayAlbum = (album: { Id: string }) => {
    playAlbum(album.Id);
  };

  const emptyState = (
    <div className="flex h-64 items-center justify-center">
      <p className="text-text-secondary">No albums found</p>
    </div>
  );

  const albumList = (
    <div data-testid="albums-content">
      <InfiniteScrollHeader
        hasPreviousPage={hasPreviousPage}
        isFetchingPreviousPage={isFetchingPreviousPage}
        onLoadPrevious={() => {
          fetchPreviousPage();
        }}
      />
      <AlbumGrid albums={albums} onPlayAlbum={handlePlayAlbum} />
      <InfiniteScrollFooter
        hasNextPage={hasNextPage}
        isFetchingNextPage={isFetchingNextPage}
        isFetchNextPageError={isFetchNextPageError}
        onLoadMore={handleLoadMore}
        currentCount={albums.length}
        totalCount={totalCount}
        itemLabel="albums"
      />
    </div>
  );

  let content;
  if (isLoading) {
    content = <LoadingSpinner />;
  } else if (isError && albums.length === 0) {
    content = (
      <LoadErrorState
        message={error instanceof Error ? error.message : 'Failed to load albums'}
        onRetry={() => {
          void refetch();
        }}
      />
    );
  } else if (albums.length === 0) {
    content = emptyState;
  } else {
    content = albumList;
  }

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Albums</h1>
        <div className="flex items-center gap-2">
          <SearchButton />
          <SortMenu selectedId={selectedId} onSelect={select} />
        </div>
      </div>

      {content}
    </div>
  );
}
