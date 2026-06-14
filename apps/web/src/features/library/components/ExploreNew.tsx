import { Compass, RefreshCw } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { isAudiobook } from '@yay-tsa/core';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { useDiscover, DISCOVER_QUERY_KEY } from '../hooks/useDiscover';
import { TrackList } from './TrackList';

const DISCOVER_LIMIT = 30;

function ExploreNewRefreshButton() {
  const queryClient = useQueryClient();
  const [isRefreshing, setIsRefreshing] = useState(false);
  const handleClick = async () => {
    setIsRefreshing(true);
    try {
      await queryClient.invalidateQueries({ queryKey: DISCOVER_QUERY_KEY });
    } finally {
      setIsRefreshing(false);
    }
  };
  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isRefreshing}
      aria-label="Refresh Explore new"
      className="text-text-secondary hover:text-text-primary hover:bg-bg-secondary ml-1 rounded p-1 transition-colors disabled:opacity-50"
    >
      <RefreshCw
        className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`}
        aria-hidden="true"
      />
    </button>
  );
}

export function ExploreNew() {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data, isLoading, isError, refetch } = useDiscover(DISCOVER_LIMIT);

  const tracks = (data ?? []).filter(track => !isAudiobook(track));

  const handlePlayTrack = (_track: unknown, index: number) => {
    playTracks(tracks, index);
  };

  if (!isLoading && !isError && tracks.length === 0) {
    return null;
  }

  return (
    <section className="mb-4">
      <div className="mb-2 flex items-center gap-2">
        <Compass className="text-accent h-4 w-4" />
        <h2 className="text-text-primary text-base font-semibold">Explore new</h2>
        <ExploreNewRefreshButton />
      </div>
      {(() => {
        if (isLoading) return <LoadingSpinner />;
        if (isError) {
          return (
            <LoadErrorState
              message="Couldn't load new recommendations"
              onRetry={() => {
                void refetch();
              }}
            />
          );
        }
        return (
          <TrackList
            tracks={tracks}
            currentTrackId={currentTrack?.Id}
            isPlaying={isPlaying}
            onPlayTrack={handlePlayTrack}
            onPauseTrack={pause}
            showAlbum
            showArtist
            showImage
          />
        );
      })()}
    </section>
  );
}
