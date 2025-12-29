import React from 'react';
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
  const { getImageUrl } = useImageUrl();
  const imageUrl = album.ImageTags?.Primary
    ? getImageUrl(album.Id, 'Primary', {
        maxWidth: 300,
        maxHeight: 300,
        tag: album.ImageTags.Primary,
      })
    : getImagePlaceholder();

  const artistName = album.Artists?.[0] || 'Unknown Artist';

  const handlePlayClick = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    onPlay?.();
  };

  return (
    <Link
      data-testid="album-card"
      to={`/albums/${album.Id}`}
      className={cn(
        'group block rounded-md p-sm',
        'bg-bg-secondary transition-colors hover:bg-bg-tertiary'
      )}
    >
      <div className="relative mb-sm aspect-square overflow-hidden rounded-sm bg-bg-tertiary">
        <img
          src={imageUrl}
          alt={album.Name}
          className="h-full w-full object-cover"
          loading="lazy"
        />
        <button
          onClick={handlePlayClick}
          className={cn(
            'absolute bottom-2 right-2 h-10 w-10',
            'flex items-center justify-center',
            'rounded-full bg-accent text-white shadow-lg',
            'translate-y-2 opacity-0 group-hover:translate-y-0 group-hover:opacity-100',
            'transition-all duration-200',
            'hover:scale-105 hover:bg-accent-hover'
          )}
          aria-label={`Play ${album.Name}`}
        >
          <Play className="ml-0.5 h-5 w-5" fill="currentColor" />
        </button>
      </div>
      <h3 data-testid="album-title" className="truncate font-medium text-text-primary">
        {album.Name}
      </h3>
      <p className="truncate text-sm text-text-secondary">{artistName}</p>
    </Link>
  );
}
