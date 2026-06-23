import { Sparkles } from 'lucide-react';
import { isAudiobook } from '@yay-tsa/core';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { useDailyMix, DAILY_MIX_QUERY_KEY } from '../hooks/useDailyMix';
import { TrackSection, SectionRefreshButton } from './TrackSection';

const DAILY_MIX_LIMIT = 30;

export function DailyMix() {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data, isLoading, isError, refetch } = useDailyMix(DAILY_MIX_LIMIT);

  const tracks = (data ?? []).filter(track => !isAudiobook(track));

  return (
    <TrackSection
      title="Daily Mix"
      icon={Sparkles}
      headerAction={<SectionRefreshButton queryKey={DAILY_MIX_QUERY_KEY} title="Daily Mix" />}
      isLoading={isLoading}
      isError={isError}
      errorMessage="Couldn't load your daily mix"
      onRetry={() => {
        void refetch();
      }}
      tracks={tracks}
      emptyState={
        <p className="text-text-tertiary py-8 text-center text-sm">
          Play a few tracks and your Daily Mix will appear here.
        </p>
      }
      currentTrackId={currentTrack?.Id}
      isPlaying={isPlaying}
      onPlayTrack={index => playTracks(tracks, index)}
      onPause={pause}
      variant="grid"
    />
  );
}
