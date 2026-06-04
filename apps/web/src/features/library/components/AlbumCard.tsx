import { memo } from 'react';
import { Link } from 'react-router-dom';
import { Play, Pause } from 'lucide-react';
import { type MusicAlbum } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { MediaCard } from './MediaCard';

type AlbumCardProps = Readonly<{
  album: MusicAlbum;
  isPlaying?: boolean;
  onPlay?: () => void;
  onPause?: () => void;
  priority?: boolean;
}>;

function AlbumCardImpl({ album, isPlaying, onPlay, onPause, priority }: AlbumCardProps) {
  const artistName = album.Artists?.[0] ?? 'Unknown Artist';
  const artistId = album.ArtistItems?.[0]?.Id;
  const isIncomplete =
    album.IsComplete === false && (album.ChildCount ?? 0) > 0 && (album.TotalTracks ?? 0) > 0;

  const overlay = (
    <>
      {isIncomplete && (
        <div
          className="absolute top-1.5 right-1.5 z-[3] rounded-full bg-amber-500 px-1.5 py-0.5 text-[10px] leading-none font-bold text-white shadow"
          title="Upload remaining tracks from Settings"
        >
          {album.ChildCount}/{album.TotalTracks}
        </div>
      )}
      <button
        type="button"
        onClick={e => {
          e.stopPropagation();
          if (isPlaying) {
            onPause?.();
          } else {
            onPlay?.();
          }
        }}
        className={cn(
          'absolute top-1/2 left-1/2 z-[2] h-12 w-12 -translate-x-1/2 -translate-y-1/2',
          'flex items-center justify-center',
          'bg-accent text-text-on-accent rounded-full shadow-lg',
          isPlaying
            ? 'scale-100 opacity-100'
            : 'scale-90 opacity-0 group-focus-within:scale-100 group-focus-within:opacity-100 group-hover:scale-100 group-hover:opacity-100',
          'transition-all duration-200',
          'hover:bg-accent-hover hover:scale-110',
          'focus-visible:ring-accent focus-visible:scale-100 focus-visible:opacity-100 focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none'
        )}
        aria-label={isPlaying ? `Pause ${album.Name || 'album'}` : `Play ${album.Name || 'album'}`}
      >
        {isPlaying ? (
          <Pause className="h-6 w-6" fill="currentColor" />
        ) : (
          <Play className="ml-0.5 h-6 w-6" fill="currentColor" />
        )}
      </button>
    </>
  );

  return (
    <MediaCard
      itemId={album.Id}
      imageTag={album.ImageTags?.Primary}
      imageAlt={album.Name}
      imageShape="square"
      imageOverlay={overlay}
      imageTestId="album-cover"
      testId="album-card"
      priority={priority}
    >
      <h2 data-testid="album-title" className="text-text-primary truncate font-medium">
        <Link
          to={`/albums/${album.Id}`}
          className="focus-visible:after:ring-accent after:absolute after:inset-0 after:z-[1] focus-visible:outline-none focus-visible:after:ring-2 focus-visible:after:ring-offset-2"
        >
          {album.Name}
        </Link>
      </h2>
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
    </MediaCard>
  );
}

// Grids re-render whenever the parent does (e.g. a sibling fetches a page). Album objects keep
// their identity across non-refetch renders, so comparing by reference + isPlaying lets unchanged
// cards skip work; the inline onPlay/onPause closures from the grid are intentionally ignored.
export const AlbumCard = memo(
  AlbumCardImpl,
  (prev, next) => prev.album === next.album && prev.isPlaying === next.isPlaying
);
