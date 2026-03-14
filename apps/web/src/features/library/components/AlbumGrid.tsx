import { type MusicAlbum } from '@yay-tsa/core';
import { AlbumCard } from './AlbumCard';

type AlbumGridProps = Readonly<{
  albums: MusicAlbum[];
  playingAlbumId?: string;
  onPlayAlbum?: (album: MusicAlbum) => void;
  onPause?: () => void;
}>;

export function AlbumGrid({ albums, playingAlbumId, onPlayAlbum, onPause }: AlbumGridProps) {
  if (albums.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center">
        <p className="text-text-secondary">No albums found</p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
      {albums.map(album => (
        <AlbumCard
          key={album.Id}
          album={album}
          isPlaying={playingAlbumId === album.Id}
          onPlay={() => onPlayAlbum?.(album)}
          onPause={onPause}
        />
      ))}
    </div>
  );
}
