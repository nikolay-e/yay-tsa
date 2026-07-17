import { memo } from 'react';
import { type MusicArtist } from '@yay-tsa/core';
import { MediaCard } from './MediaCard';

type ArtistCardProps = Readonly<{
  artist: MusicArtist;
}>;

function ArtistCardImpl({ artist }: ArtistCardProps) {
  const albumCount = artist.ChildCount ?? 0;

  return (
    <MediaCard
      itemId={artist.Id}
      imageTag={artist.ImageTags?.Primary}
      imageAlt={artist.Name}
      imageShape="circle"
      href={`/artists/${artist.Id}`}
      testId="artist-card"
    >
      <h2
        data-testid="artist-name"
        title={artist.Name}
        className="text-text-primary truncate text-center font-medium"
      >
        {artist.Name}
      </h2>
      <p className="text-text-secondary truncate text-center text-sm">
        {albumCount} {albumCount === 1 ? 'album' : 'albums'}
      </p>
    </MediaCard>
  );
}

// Artist is the only prop and keeps a stable identity across non-refetch renders, so the default
// shallow comparison lets unchanged cards skip re-rendering in large grids.
export const ArtistCard = memo(ArtistCardImpl);
