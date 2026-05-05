import { Sparkles } from 'lucide-react';
import { useInfiniteTracks } from '../hooks/useTracks';
import { TrackList } from './TrackList';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';

const DAILY_MIX_LIMIT = 30;

export function DailyMix() {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data, isLoading } = useInfiniteTracks({
    sortBy: 'DatePlayed',
    sortOrder: 'Descending',
    limit: DAILY_MIX_LIMIT,
  });

  const tracks = data?.pages[0]?.Items ?? [];

  const handlePlayTrack = (_track: unknown, index: number) => {
    playTracks(tracks, index);
  };

  return (
    <section className="mb-4">
      <div className="mb-2 flex items-center gap-2">
        <Sparkles className="text-accent h-4 w-4" />
        <h2 className="text-text-primary text-base font-semibold">Daily Mix</h2>
      </div>
      {(() => {
        if (isLoading) return <LoadingSpinner />;
        if (tracks.length === 0) {
          return (
            <p className="text-text-tertiary py-8 text-center text-sm">
              Listen to some tracks to build your daily mix
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
