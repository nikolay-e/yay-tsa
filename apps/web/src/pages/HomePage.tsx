import { Link } from 'react-router-dom';
import { type MusicAlbum } from '@yaytsa/core';
import { useRecentlyPlayedAlbums } from '@/features/library/hooks/useAlbums';
import { AlbumCard } from '@/features/library/components/AlbumCard';
import { usePlayerStore } from '@/features/player/stores/player.store';

export function HomePage() {
  const { data: recentlyPlayed, isLoading } = useRecentlyPlayedAlbums(10);
  const playAlbum = usePlayerStore(state => state.playAlbum);

  const handlePlayAlbum = (album: MusicAlbum) => {
    void playAlbum(album.Id);
  };

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
              <AlbumCard key={album.Id} album={album} onPlay={() => handlePlayAlbum(album)} />
            ))}
          </div>
        </section>
      )}

      {!isLoading && !hasRecentlyPlayed && (
        <div className="text-text-secondary py-12 text-center">
          <p>No recently played albums. Start listening to see your history here!</p>
        </div>
      )}

      {isLoading && (
        <div className="text-text-secondary py-12 text-center">
          <p>Loading...</p>
        </div>
      )}
    </div>
  );
}
