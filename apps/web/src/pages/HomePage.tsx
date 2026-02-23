import { Link } from 'react-router-dom';
import { useRecentlyPlayedAlbums } from '@/features/library/hooks/useAlbums';
import { AlbumCard } from '@/features/library/components/AlbumCard';
import { usePlayerStore, useCurrentTrack, useIsPlaying } from '@/features/player/stores/player.store';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';

export function HomePage() {
  const { data: recentlyPlayed, isLoading } = useRecentlyPlayedAlbums(10);
  const playAlbum = usePlayerStore(state => state.playAlbum);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const playingAlbumId = isPlaying ? currentTrack?.AlbumId : undefined;

  const hasRecentlyPlayed = recentlyPlayed?.Items && recentlyPlayed.Items.length > 0;

  return (
    <div className="space-y-8 p-6">
      <h1 className="text-2xl font-bold">Home</h1>

      {hasRecentlyPlayed && (
        <section>
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-xl font-semibold">Recently Played</h2>
            <Link to="/albums" className="text-accent text-sm hover:underline">
              View all
            </Link>
          </div>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
            {recentlyPlayed.Items.map(album => (
              <AlbumCard
                key={album.Id}
                album={album}
                isPlaying={playingAlbumId === album.Id}
                onPlay={() => void playAlbum(album.Id)}
                onPause={pause}
              />
            ))}
          </div>
        </section>
      )}

      {!isLoading && !hasRecentlyPlayed && (
        <div className="text-text-secondary py-12 text-center">
          <p>No recently played albums. Start listening to see your history here!</p>
        </div>
      )}

      {isLoading && <LoadingSpinner />}
    </div>
  );
}
