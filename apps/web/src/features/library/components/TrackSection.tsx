import { useState, type ReactNode } from 'react';
import { RefreshCw, type LucideIcon } from 'lucide-react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';
import { type AudioItem } from '@yay-tsa/core';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { TrackList } from './TrackList';

type SectionRefreshButtonProps = Readonly<{
  queryKey: QueryKey;
  title: string;
}>;

export function SectionRefreshButton({ queryKey, title }: SectionRefreshButtonProps) {
  const queryClient = useQueryClient();
  const [isRefreshing, setIsRefreshing] = useState(false);
  const handleClick = async () => {
    setIsRefreshing(true);
    try {
      await queryClient.invalidateQueries({ queryKey });
    } finally {
      setIsRefreshing(false);
    }
  };
  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isRefreshing}
      aria-label={`Refresh ${title}`}
      className="text-text-secondary hover:text-text-primary hover:bg-bg-secondary ml-1 rounded p-1 transition-colors disabled:opacity-50"
    >
      <RefreshCw
        className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`}
        aria-hidden="true"
      />
    </button>
  );
}

type TrackSectionProps = Readonly<{
  title: string;
  icon: LucideIcon;
  headerAction?: ReactNode;
  isLoading: boolean;
  isError: boolean;
  errorMessage: string;
  onRetry: () => void;
  tracks: AudioItem[];
  emptyState?: ReactNode;
  currentTrackId?: string;
  isPlaying: boolean;
  onPlayTrack: (index: number) => void;
  onPause: () => void;
}>;

export function TrackSection({
  title,
  icon: Icon,
  headerAction,
  isLoading,
  isError,
  errorMessage,
  onRetry,
  tracks,
  emptyState,
  currentTrackId,
  isPlaying,
  onPlayTrack,
  onPause,
}: TrackSectionProps) {
  if (!isLoading && !isError && tracks.length === 0 && emptyState === undefined) {
    return null;
  }

  return (
    <section className="mb-4">
      <div className="mb-2 flex items-center gap-2">
        <Icon className="text-accent h-4 w-4" />
        <h2 className="text-text-primary text-base font-semibold">{title}</h2>
        {headerAction}
      </div>
      {(() => {
        if (isLoading) return <LoadingSpinner />;
        if (isError) {
          return <LoadErrorState message={errorMessage} onRetry={onRetry} />;
        }
        if (tracks.length === 0) {
          return emptyState ?? null;
        }
        return (
          <TrackList
            tracks={tracks}
            currentTrackId={currentTrackId}
            isPlaying={isPlaying}
            onPlayTrack={(_track, index) => onPlayTrack(index)}
            onPauseTrack={onPause}
            showAlbum
            showArtist
            showImage
          />
        );
      })()}
    </section>
  );
}
