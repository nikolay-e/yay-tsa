import { Link } from 'react-router-dom';
import { type MusicAlbum } from '@yay-tsa/core';
import { useRecentlyPlayedAlbums } from '@/features/library/hooks/useAlbums';
import { AlbumCard } from '@/features/library/components/AlbumCard';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { AdaptiveQueueView } from '@/features/player/components/AdaptiveQueueView';
import { DjPreferencesPanel } from '@/features/player/components/DjPreferencesPanel';

export function HomePage() {
  const { data: recentlyPlayed } = useRecentlyPlayedAlbums(6);
  const playAlbum = usePlayerStore(state => state.playAlbum);

  const handlePlayAlbum = (album: MusicAlbum) => {
    void playAlbum(album.Id);
  };

  const hasRecentlyPlayed = recentlyPlayed?.Items && recentlyPlayed.Items.length > 0;

  return (
    <div className="flex h-full flex-col">
      <div className="flex-1">
        <AdaptiveQueueView />
      </div>

      <DjPreferencesPanel />

      {hasRecentlyPlayed && (
        <div className="border-border border-t px-4 py-4">
          <div className="mb-3 flex items-center justify-between">
            <h3 className="text-text-secondary text-xs font-medium tracking-wide uppercase">
              Recently Played
            </h3>
            <Link to="/albums" className="text-accent text-xs hover:underline">
              Browse
            </Link>
          </div>
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-4 md:grid-cols-6">
            {recentlyPlayed.Items.map(album => (
              <AlbumCard key={album.Id} album={album} onPlay={() => handlePlayAlbum(album)} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
