import { Link } from 'react-router-dom';
import { useRecentlyPlayedAlbums, useRecentAlbums } from '@/features/library/hooks/useAlbums';
import { AlbumCard } from '@/features/library/components/AlbumCard';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { type MusicAlbum } from '@yaytsa/core';

export function HomePage() {
  const { data: recentlyPlayed, isLoading: loadingPlayed } = useRecentlyPlayedAlbums(10);
  const { data: recentlyAdded, isLoading: loadingAdded } = useRecentAlbums(10);
  const playAlbum = usePlayerStore(state => state.playAlbum);

  const handlePlayAlbum = (album: MusicAlbum) => {
    void playAlbum(album.Id);
  };

  const hasRecentlyPlayed = recentlyPlayed?.Items && recentlyPlayed.Items.length > 0;
  const hasRecentlyAdded = recentlyAdded?.Items && recentlyAdded.Items.length > 0;

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

      {hasRecentlyAdded && (
        <section>
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-xl font-semibold">Recently Added</h2>
            <Link to="/albums" className="text-accent text-sm hover:underline">
              View all
            </Link>
          </div>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
            {recentlyAdded.Items.map(album => (
              <AlbumCard key={album.Id} album={album} onPlay={() => handlePlayAlbum(album)} />
            ))}
          </div>
        </section>
      )}

      {!loadingPlayed && !loadingAdded && !hasRecentlyPlayed && !hasRecentlyAdded && (
        <div className="text-text-secondary py-12 text-center">
          <p>Your library is empty. Add some music to get started!</p>
        </div>
      )}

      {(loadingPlayed || loadingAdded) && (
        <div className="text-text-secondary py-12 text-center">
          <p>Loading...</p>
        </div>
      )}
    </div>
  );
}
