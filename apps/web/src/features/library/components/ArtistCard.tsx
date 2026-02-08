import { Link } from 'react-router-dom';
import { type MusicArtist } from '@yay-tsa/core';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { cn } from '@/shared/utils/cn';

interface ArtistCardProps {
  artist: MusicArtist;
}

export function ArtistCard({ artist }: ArtistCardProps) {
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    artist.Id,
    artist.ImageTags?.Primary
  );
  const { getImageUrl } = useImageUrl();
  const imageUrl = artist.ImageTags?.Primary
    ? getImageUrl(artist.Id, 'Primary', {
        maxWidth: 300,
        maxHeight: 300,
        tag: artist.ImageTags.Primary,
      })
    : getImagePlaceholder();

  const albumCount = artist.ChildCount ?? 0;

  return (
    <Link
      data-testid="artist-card"
      to={`/artists/${artist.Id}`}
      className={cn(
        'group block rounded-md p-2',
        'bg-bg-secondary hover:bg-bg-tertiary transition-colors'
      )}
    >
      <div className="bg-bg-tertiary relative mb-2 aspect-square overflow-hidden rounded-full">
        <img
          src={hasImageError ? getImagePlaceholder() : imageUrl}
          alt={artist.Name}
          className="h-full w-full object-cover"
          loading="lazy"
          onError={onImageError}
        />
      </div>
      <h3 data-testid="artist-name" className="text-text-primary truncate text-center font-medium">
        {artist.Name}
      </h3>
      <p className="text-text-secondary truncate text-center text-sm">
        {albumCount} {albumCount === 1 ? 'album' : 'albums'}
      </p>
    </Link>
  );
}
