import { type MusicAlbum } from '@yaytsa/core';
import { AlbumCard } from './AlbumCard';

interface AlbumGridProps {
  albums: MusicAlbum[];
  onPlayAlbum?: (album: MusicAlbum) => void;
}

export function AlbumGrid({ albums, onPlayAlbum }: AlbumGridProps) {
  if (albums.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center">
        <p className="text-text-secondary">No albums found</p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-2 gap-md sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
      {albums.map(album => (
        <AlbumCard key={album.Id} album={album} onPlay={() => onPlayAlbum?.(album)} />
      ))}
    </div>
  );
}
