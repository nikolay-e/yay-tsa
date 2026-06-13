import { Sparkles, RefreshCw } from 'lucide-react';
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
import { useDailyMix, DAILY_MIX_QUERY_KEY } from '../hooks/useDailyMix';
import { TrackList } from './TrackList';

const DAILY_MIX_LIMIT = 30;

function DailyMixRefreshButton() {
  const queryClient = useQueryClient();
  const [isRefreshing, setIsRefreshing] = useState(false);
  const handleClick = async () => {
    setIsRefreshing(true);
    try {
      await queryClient.invalidateQueries({ queryKey: DAILY_MIX_QUERY_KEY });
    } finally {
      setIsRefreshing(false);
    }
  };
  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isRefreshing}
      aria-label="Refresh Daily Mix"
      className="text-text-secondary hover:text-text-primary hover:bg-bg-secondary ml-1 rounded p-1 transition-colors disabled:opacity-50"
    >
      <RefreshCw
        className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`}
        aria-hidden="true"
      />
    </button>
  );
}

export function DailyMix() {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data, isLoading, isError, refetch } = useDailyMix(DAILY_MIX_LIMIT);

  const tracks = (data ?? []).filter(track => !isAudiobook(track));

  const handlePlayTrack = (_track: unknown, index: number) => {
    playTracks(tracks, index);
  };

  return (
    <section className="mb-4">
      <div className="mb-2 flex items-center gap-2">
        <Sparkles className="text-accent h-4 w-4" />
        <h2 className="text-text-primary text-base font-semibold">Daily Mix</h2>
        <DailyMixRefreshButton />
      </div>
      {(() => {
        if (isLoading) return <LoadingSpinner />;
        if (isError) {
          return (
            <LoadErrorState
              message="Couldn't load your daily mix"
              onRetry={() => {
                void refetch();
              }}
            />
          );
        }
        if (tracks.length === 0) {
          return (
            <p className="text-text-tertiary py-8 text-center text-sm">
              Play a few tracks and your Daily Mix will appear here.
            </p>
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
