import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Heart, ChevronRight } from 'lucide-react';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { useInfiniteTracks } from '../hooks/useTracks';
import { TrackList } from './TrackList';

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

  if (!isLoading && !isError && tracks.length === 0) return null;

  const handlePlayTrack = (_track: unknown, index: number) => {
    playTracks(tracks, index);
  };

  return (
    <section className="mb-4">
      <div className="mb-2 flex items-center gap-2">
        <Heart className="text-accent h-4 w-4" />
        <h2 className="text-text-primary text-base font-semibold">Favorite songs</h2>
        <Link
          to="/favorites"
          className="text-text-secondary hover:text-text-primary ml-auto flex items-center gap-0.5 text-sm transition-colors"
        >
          See all
          <ChevronRight className="h-4 w-4" aria-hidden="true" />
        </Link>
      </div>
      {(() => {
        if (isLoading) return <LoadingSpinner />;
        if (isError) {
          return (
            <LoadErrorState
              message="Couldn't load your favorite songs"
              onRetry={() => {
                refetch().catch(() => undefined);
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
