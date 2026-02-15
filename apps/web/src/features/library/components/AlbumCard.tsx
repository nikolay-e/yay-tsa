import { Link } from 'react-router-dom';
import { Play } from 'lucide-react';
import { type MusicAlbum } from '@yay-tsa/core';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { cn } from '@/shared/utils/cn';

interface AlbumCardProps {
  album: MusicAlbum;
  onPlay?: () => void;
}

export function AlbumCard({ album, onPlay }: AlbumCardProps) {
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    album.Id,
    album.ImageTags?.Primary
  );
  const { getImageUrl } = useImageUrl();
  const imageUrl = album.ImageTags?.Primary
    ? getImageUrl(album.Id, 'Primary', {
        maxWidth: 300,
        maxHeight: 300,
        tag: album.ImageTags.Primary,
      })
    : getImagePlaceholder();

  const artistName = album.Artists?.[0] ?? 'Unknown Artist';
  const artistId = album.ArtistItems?.[0]?.Id;

  return (
    <div
      data-testid="album-card"
      className={cn(
        'group relative rounded-md p-2',
        'bg-bg-secondary hover:bg-bg-tertiary transition-colors'
      )}
    >
      <div className="bg-bg-tertiary relative mb-2 aspect-square overflow-hidden rounded-sm">
        <img
          data-testid="album-cover"
          src={hasImageError ? getImagePlaceholder() : imageUrl}
          alt={album.Name}
          className="h-full w-full object-cover"
          loading="lazy"
          onError={onImageError}
        />
        <button
          type="button"
          onClick={e => {
            e.stopPropagation();
            onPlay?.();
          }}
          className={cn(
            'absolute top-1/2 left-1/2 z-[2] h-12 w-12 -translate-x-1/2 -translate-y-1/2',
            'flex items-center justify-center',
            'bg-accent rounded-full text-black shadow-lg',
            'scale-90 opacity-0 group-focus-within:scale-100 group-focus-within:opacity-100 group-hover:scale-100 group-hover:opacity-100',
            'transition-all duration-200',
            'hover:bg-accent-hover hover:scale-110',
            'focus-visible:ring-accent focus-visible:scale-100 focus-visible:opacity-100 focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none'
          )}
          aria-label={`Play ${album.Name || 'album'}`}
        >
          <Play className="ml-0.5 h-6 w-6" fill="currentColor" />
        </button>
      </div>
      <h3 data-testid="album-title" className="text-text-primary truncate font-medium">
        <Link
          to={`/albums/${album.Id}`}
          className="focus-visible:after:ring-accent after:absolute after:inset-0 after:z-[1] focus-visible:outline-none focus-visible:after:ring-2 focus-visible:after:ring-offset-2"
        >
          {album.Name}
        </Link>
      </h3>
      {artistId ? (
        <Link
          to={`/artists/${artistId}`}
          className="text-text-secondary hover:text-text-primary relative z-[2] block truncate text-sm hover:underline"
        >
          {artistName}
        </Link>
      ) : (
        <p className="text-text-secondary truncate text-sm">{artistName}</p>
      )}
    </div>
  );
}
