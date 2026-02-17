import { useState, useMemo, useDeferredValue } from 'react';
import { Upload } from 'lucide-react';
import { useInfiniteTracks } from '@/features/library/hooks';
import { TrackList, TrackUploadDialog } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { SearchInput } from '@/shared/ui/SearchInput';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';

export function SongsPage() {
  const [searchTerm, setSearchTerm] = useState('');
  const [isUploadDialogOpen, setIsUploadDialogOpen] = useState(false);
  const deferredSearchTerm = useDeferredValue(searchTerm);
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data, isLoading, isFetchingNextPage, error, hasNextPage, fetchNextPage, refetch } =
    useInfiniteTracks({
      searchTerm: deferredSearchTerm.trim() || undefined,
      sortBy: 'SortName',
    });

  const tracks = useMemo(() => data?.pages.flatMap(page => page.Items) ?? [], [data]);
  const totalCount = data?.pages[0]?.TotalRecordCount ?? 0;

  const handleLoadMore = () => {
    void fetchNextPage();
  };

  const handlePlayTrack = (_track: unknown, index: number) => {
    void playTracks(tracks, index);
  };

  const handleUploadSuccess = () => {
    // Refetch tracks after successful upload
    void refetch();
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Songs</h1>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setIsUploadDialogOpen(true)}
            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-white hover:bg-primary/90 transition-colors font-medium"
          >
            <Upload size={18} />
            Upload Track
          </button>
          <SearchInput value={searchTerm} onChange={setSearchTerm} placeholder="Search songs..." />
        </div>
      </div>

      {error && (
        <div className="bg-error/10 border-error/20 text-error rounded-md border p-4">
          {error instanceof Error ? error.message : 'Failed to load songs'}
        </div>
      )}

      {isLoading ? (
        <LoadingSpinner />
      ) : tracks.length === 0 ? (
        <div className="flex h-64 items-center justify-center">
          <p className="text-text-secondary">No songs found</p>
        </div>
      ) : (
        <>
          <TrackList
            tracks={tracks}
            currentTrackId={currentTrack?.Id}
            isPlaying={isPlaying}
            onPlayTrack={handlePlayTrack}
            onPauseTrack={pause}
            showAlbum={true}
            showArtist={true}
            showImage={true}
          />
          <InfiniteScrollFooter
            hasNextPage={hasNextPage}
            isFetchingNextPage={isFetchingNextPage}
            onLoadMore={handleLoadMore}
            currentCount={tracks.length}
            totalCount={totalCount}
            itemLabel="songs"
          />
        </>
      )}

      <TrackUploadDialog
        isOpen={isUploadDialogOpen}
        onClose={() => setIsUploadDialogOpen(false)}
        onUploadSuccess={handleUploadSuccess}
      />
    </div>
  );
}
