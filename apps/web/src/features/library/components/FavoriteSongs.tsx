import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Heart, ChevronRight } from 'lucide-react';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { useInfiniteTracks } from '../hooks/useTracks';
import { TrackSection } from './TrackSection';

const FAVORITE_SONGS_PREVIEW_LIMIT = 12;

export function FavoriteSongs() {
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data, isLoading, isError, refetch } = useInfiniteTracks({
    isFavorite: true,
    limit: FAVORITE_SONGS_PREVIEW_LIMIT,
  });

  const tracks = useMemo(
    () => (data?.pages.flatMap(page => page.Items) ?? []).slice(0, FAVORITE_SONGS_PREVIEW_LIMIT),
    [data]
  );

  return (
    <TrackSection
      title="Favorite songs"
      icon={Heart}
      headerAction={
        <Link
          to="/favorites"
          className="text-text-secondary hover:text-text-primary ml-auto flex items-center gap-0.5 text-sm transition-colors"
        >
          See all
          <ChevronRight className="h-4 w-4" aria-hidden="true" />
        </Link>
      }
      isLoading={isLoading}
      isError={isError}
      errorMessage="Couldn't load your favorite songs"
      onRetry={() => {
        refetch().catch(() => undefined);
      }}
      tracks={tracks}
      currentTrackId={currentTrack?.Id}
      isPlaying={isPlaying}
      onPlayTrack={index => playTracks(tracks, index)}
      onPause={pause}
    />
  );
}
