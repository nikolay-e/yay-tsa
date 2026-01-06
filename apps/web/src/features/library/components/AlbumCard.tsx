import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Play } from 'lucide-react';
import { type MusicAlbum } from '@yaytsa/core';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { cn } from '@/shared/utils/cn';

interface AlbumCardProps {
  album: MusicAlbum;
  onPlay?: () => void;
}

export function AlbumCard({ album, onPlay }: AlbumCardProps) {
  const imageKey = `${album.Id}-${album.ImageTags?.Primary ?? 'none'}`;
  const [errorKey, setErrorKey] = useState<string | null>(null);
  const hasImageError = errorKey === imageKey;

  const { getImageUrl } = useImageUrl();
  const imageUrl = album.ImageTags?.Primary
    ? getImageUrl(album.Id, 'Primary', {
        maxWidth: 300,
        maxHeight: 300,
        tag: album.ImageTags.Primary,
      })
    : getImagePlaceholder();

  const artistName = album.Artists?.[0] ?? 'Unknown Artist';

  return (
    <div
      data-testid="album-card"
      className={cn(
        'group relative rounded-md p-2',
        'bg-bg-secondary hover:bg-bg-tertiary transition-colors'
      )}
    >
      <Link to={`/albums/${album.Id}`} className="block">
        <div className="bg-bg-tertiary relative mb-2 aspect-square overflow-hidden rounded-sm">
          <img
            src={hasImageError ? getImagePlaceholder() : imageUrl}
            alt={album.Name}
            className="h-full w-full object-cover"
            loading="lazy"
            onError={() => setErrorKey(imageKey)}
          />
        </div>
        <h3 data-testid="album-title" className="text-text-primary truncate font-medium">
          {album.Name}
        </h3>
        <p className="text-text-secondary truncate text-sm">{artistName}</p>
      </Link>
      <button
        onClick={onPlay}
        className={cn(
          'absolute top-[calc(50%-1.25rem-0.5rem)] right-4 h-10 w-10',
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
  );
}
