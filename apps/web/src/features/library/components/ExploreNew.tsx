import { Compass } from 'lucide-react';
import { isAudiobook } from '@yay-tsa/core';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { useDiscover, DISCOVER_QUERY_KEY } from '../hooks/useDiscover';
import { TrackSection, SectionRefreshButton } from './TrackSection';

const DISCOVER_LIMIT = 30;

export function ExploreNew() {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data, isLoading, isError, refetch } = useDiscover(DISCOVER_LIMIT);

  const tracks = (data ?? []).filter(track => !isAudiobook(track));

  return (
    <TrackSection
      title="Explore new"
      icon={Compass}
      headerAction={<SectionRefreshButton queryKey={DISCOVER_QUERY_KEY} title="Explore new" />}
      isLoading={isLoading}
      isError={isError}
      errorMessage="Couldn't load new recommendations"
      onRetry={() => {
        void refetch();
      }}
      tracks={tracks}
      emptyState={
        <p className="text-text-tertiary py-8 text-center text-sm">
          Nothing new to discover right now — new additions to your library will appear here.
        </p>
      }
      currentTrackId={currentTrack?.Id}
      isPlaying={isPlaying}
      onPlayTrack={index => playTracks(tracks, index, 'discover')}
      onPause={pause}
      variant="grid"
    />
  );
}
