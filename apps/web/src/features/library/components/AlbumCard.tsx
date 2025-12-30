import { useState, useEffect, type MouseEvent } from 'react';
import { Link } from 'react-router-dom';
import { Play } from 'lucide-react';
import { type MusicAlbum } from '@yaytsa/core';
import { useImageUrl, getImagePlaceholder } from '@/shared/utils/image';
import { cn } from '@/shared/utils/cn';

interface AlbumCardProps {
  album: MusicAlbum;
  onPlay?: () => void;
}

export function AlbumCard({ album, onPlay }: AlbumCardProps) {
  const [hasImageError, setHasImageError] = useState(false);

  useEffect(() => {
    setHasImageError(false);
  }, [album.Id]);
  const { getImageUrl } = useImageUrl();
  const imageUrl = album.ImageTags?.Primary
    ? getImageUrl(album.Id, 'Primary', {
        maxWidth: 300,
        maxHeight: 300,
        tag: album.ImageTags.Primary,
      })
    : getImagePlaceholder();

  const artistName = album.Artists?.[0] || 'Unknown Artist';

  const handlePlayClick = (e: MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    onPlay?.();
  };

  return (
    <Link
      data-testid="album-card"
      to={`/albums/${album.Id}`}
      className={cn(
        'group block rounded-md p-2',
        'bg-bg-secondary hover:bg-bg-tertiary transition-colors'
      )}
    >
      <div className="bg-bg-tertiary relative mb-2 aspect-square overflow-hidden rounded-sm">
        <img
          src={hasImageError ? getImagePlaceholder() : imageUrl}
          alt={album.Name}
          className="h-full w-full object-cover"
          loading="lazy"
          onError={() => setHasImageError(true)}
        />
        <button
          onClick={handlePlayClick}
          className={cn(
            'absolute right-2 bottom-2 h-10 w-10',
            'flex items-center justify-center',
            'bg-accent rounded-full text-white shadow-lg',
            'translate-y-2 opacity-0 group-hover:translate-y-0 group-hover:opacity-100',
            'transition-all duration-200',
            'hover:bg-accent-hover hover:scale-105'
          )}
          aria-label={`Play ${album.Name}`}
        >
          <Play className="ml-0.5 h-5 w-5" fill="currentColor" />
        </button>
      </div>
      <h3 data-testid="album-title" className="text-text-primary truncate font-medium">
        {album.Name}
      </h3>
      <p className="text-text-secondary truncate text-sm">{artistName}</p>
    </Link>
  );
}
