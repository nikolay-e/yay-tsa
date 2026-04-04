import { memo, useState, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Play, Pause, ThumbsUp, ThumbsDown } from 'lucide-react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { type AudioItem } from '@yay-tsa/core';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { getTrackImageUrl } from '@/shared/utils/track-image';
import { formatTicks } from '@/shared/utils/time';
import { cn } from '@/shared/utils/cn';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { useFavoriteToggle } from '@/features/library/hooks/useFavorites';

const UNKNOWN_ARTIST = 'Unknown Artist';

type TrackListProps = Readonly<{
  tracks: AudioItem[];
  currentTrackId?: string;
  isPlaying?: boolean;
  onPlayTrack?: (track: AudioItem, index: number) => void;
  onPauseTrack?: () => void;
  showAlbum?: boolean;
  showArtist?: boolean;
  showImage?: boolean;
  virtualized?: boolean;
}>;

export type TrackListRowProps = Readonly<{
  track: AudioItem;
  index: number;
  isCurrentTrack: boolean;
  isPlaying: boolean;
  onPlay: () => void;
  onPause: () => void;
  showAlbum?: boolean;
  showArtist?: boolean;
  showImage?: boolean;
}>;

const TrackImage = memo(
  function TrackImage({
    track,
    isCurrentTrack,
    isPlaying,
  }: Readonly<{
    track: AudioItem;
    isCurrentTrack: boolean;
    isPlaying: boolean;
  }>) {
    const { hasError, onError } = useImageErrorTracking(
      track.Id,
      track.AlbumPrimaryImageTag,
      track.AlbumId
    );
    const { getImageUrl } = useImageUrl();

    const imageUrl = getTrackImageUrl(getImageUrl, {
      albumId: track.AlbumId,
      albumPrimaryImageTag: track.AlbumPrimaryImageTag,
      trackId: track.Id,
      maxWidth: 48,
      maxHeight: 48,
    });

    return (
      <div className="group/img relative h-10 w-10 shrink-0 overflow-hidden rounded-sm">
        <img
          src={hasError ? getImagePlaceholder() : imageUrl}
          alt={track.Name}
          className="h-full w-full object-cover"
          onError={onError}
        />
        <div
          className={cn(
            'absolute inset-0 flex items-center justify-center bg-black/50',
            isCurrentTrack
              ? 'opacity-100'
              : 'opacity-0 group-focus-within:opacity-100 group-hover:opacity-100'
          )}
        >
          {isCurrentTrack && isPlaying ? (
            <Pause className="text-accent h-4 w-4" fill="currentColor" />
          ) : (
            <Play className="text-text-primary h-4 w-4" fill="currentColor" />
          )}
        </div>
      </div>
    );
  },
  (prev, next) =>
    prev.track.Id === next.track.Id &&
    prev.track.Name === next.track.Name &&
    prev.track.AlbumId === next.track.AlbumId &&
    prev.track.AlbumPrimaryImageTag === next.track.AlbumPrimaryImageTag &&
    prev.isCurrentTrack === next.isCurrentTrack &&
    prev.isPlaying === next.isPlaying
);

function RatingButtons({
  itemId,
  isFavorite,
  className,
}: Readonly<{
  itemId: string;
  isFavorite: boolean;
  className?: string;
}>) {
  const { mutate, isPending } = useFavoriteToggle();

  return (
    <div className={cn('flex items-center gap-0.5', className)}>
      <button
        type="button"
        onClick={e => {
          e.preventDefault();
          e.stopPropagation();
          if (!isFavorite) mutate({ itemId, isFavorite });
        }}
        disabled={isPending}
        className={cn(
          'focus-visible:ring-accent rounded-full p-1.5 transition-colors focus-visible:ring-2 focus-visible:outline-none',
          isFavorite ? 'text-accent' : 'text-text-secondary hover:text-text-primary',
          isPending && 'opacity-50'
        )}
        aria-label="Thumbs up"
        aria-pressed={isFavorite}
      >
        <ThumbsUp className="h-3.5 w-3.5" fill={isFavorite ? 'currentColor' : 'none'} />
      </button>
      <button
        type="button"
        onClick={e => {
          e.preventDefault();
          e.stopPropagation();
          if (isFavorite) mutate({ itemId, isFavorite });
        }}
        disabled={isPending}
        className={cn(
          'focus-visible:ring-accent rounded-full p-1.5 transition-colors focus-visible:ring-2 focus-visible:outline-none',
          'text-text-secondary hover:text-text-primary',
          isPending && 'opacity-50'
        )}
        aria-label="Thumbs down"
      >
        <ThumbsDown className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}

function TrackMetadata({
  showArtist,
  showAlbum,
  artistName,
  artistId,
  album,
  albumId,
}: Readonly<{
  showArtist: boolean;
  showAlbum: boolean;
  artistName: string;
  artistId?: string;
  album?: string;
  albumId?: string;
}>) {
  return (
    <p className="text-text-secondary truncate text-sm">
      {showArtist && artistId ? (
        <Link
          to={`/artists/${artistId}`}
          onClick={e => e.stopPropagation()}
          className="hover:text-text-primary hover:underline"
        >
          {artistName}
        </Link>
      ) : (
        showArtist && artistName
      )}
      {showArtist && showAlbum && album && ' • '}
      {showAlbum && albumId && album ? (
        <Link
          to={`/albums/${albumId}`}
          onClick={e => e.stopPropagation()}
          className="hover:text-text-primary hover:underline"
        >
          {album}
        </Link>
      ) : (
        showAlbum && album
      )}
    </p>
  );
}

export function TrackListRow({
  track,
  index,
  isCurrentTrack,
  isPlaying,
  onPlay,
  onPause,
  showAlbum = false,
  showArtist = true,
  showImage = true,
}: TrackListRowProps) {
  const duration = formatTicks(track.RunTimeTicks);
  const artistName = track.Artists?.[0] ?? UNKNOWN_ARTIST;
  const artistId = track.ArtistItems?.[0]?.Id;

  return (
    <div
      data-testid="track-row"
      aria-current={isCurrentTrack ? 'true' : undefined}
      className={cn(
        'flex w-full items-center gap-4 rounded-sm p-2 text-left',
        'group hover:bg-bg-secondary transition-colors',
        isCurrentTrack && 'bg-bg-secondary'
      )}
    >
      <button
        type="button"
        onClick={isCurrentTrack && isPlaying ? onPause : onPlay}
        className="focus-visible:ring-accent cursor-pointer focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none"
        aria-label={isCurrentTrack && isPlaying ? `Pause ${track.Name}` : `Play ${track.Name}`}
      >
        {showImage ? (
          <TrackImage track={track} isCurrentTrack={isCurrentTrack} isPlaying={isPlaying} />
        ) : (
          <div className="flex h-8 w-8 shrink-0 items-center justify-center">
            <TrackIndexIcon
              isCurrentTrack={isCurrentTrack}
              isPlaying={isPlaying}
              index={index}
              track={track}
            />
          </div>
        )}
      </button>

      <div className="min-w-0 flex-1">
        <button
          type="button"
          data-testid="track-title"
          onClick={isCurrentTrack && isPlaying ? onPause : onPlay}
          className={cn(
            'block w-full cursor-pointer truncate text-left font-medium hover:underline',
            isCurrentTrack ? 'text-accent' : 'text-text-primary'
          )}
        >
          {track.Name}
        </button>
        {(showArtist || showAlbum) && (
          <TrackMetadata
            showArtist={showArtist}
            showAlbum={showAlbum}
            artistName={artistName}
            artistId={artistId}
            album={track.Album}
            albumId={track.AlbumId}
          />
        )}
      </div>

      <RatingButtons
        itemId={track.Id}
        isFavorite={track.UserData?.IsFavorite ?? false}
        className={cn(
          'shrink-0',
          !track.UserData?.IsFavorite &&
            'opacity-0 group-focus-within:opacity-100 group-hover:opacity-100 max-md:opacity-60'
        )}
      />
      <span className="text-text-tertiary shrink-0 text-sm">{duration}</span>
    </div>
  );
}

function TrackIndexIcon({
  isCurrentTrack,
  isPlaying,
  index,
  track,
}: Readonly<{
  isCurrentTrack: boolean;
  isPlaying: boolean;
  index: number;
  track: AudioItem;
}>) {
  if (!isCurrentTrack) {
    return (
      <>
        <span className="text-text-tertiary text-sm group-focus-within:hidden group-hover:hidden">
          {track.IndexNumber ?? index + 1}
        </span>
        <Play
          className="text-text-primary hidden h-4 w-4 group-focus-within:block group-hover:block"
          fill="currentColor"
        />
      </>
    );
  }
  const CurrentIcon = isPlaying ? Pause : Play;
  return <CurrentIcon className="text-accent h-4 w-4" fill="currentColor" />;
}

const ROW_HEIGHT = 60;
const OVERSCAN = 10;

function VirtualizedTrackList({
  tracks,
  currentTrackId,
  isPlaying = false,
  onPlayTrack,
  onPauseTrack,
  showAlbum = false,
  showArtist = true,
  showImage = true,
}: Omit<TrackListProps, 'virtualized'>) {
  const [listNode, setListNode] = useState<HTMLDivElement | null>(null);

  const scrollParent = useMemo(
    () => (listNode?.closest('main') as HTMLElement | null) ?? null,
    [listNode]
  );

  const scrollMargin = useMemo(() => {
    if (!listNode || !scrollParent) return 0;
    return (
      listNode.getBoundingClientRect().top -
      scrollParent.getBoundingClientRect().top +
      scrollParent.scrollTop
    );
  }, [listNode, scrollParent]);

  const virtualizer = useVirtualizer({
    count: tracks.length,
    getScrollElement: () => scrollParent,
    estimateSize: () => ROW_HEIGHT,
    overscan: OVERSCAN,
    scrollMargin,
  });

  return (
    <div
      ref={setListNode}
      style={{ height: virtualizer.getTotalSize(), position: 'relative', width: '100%' }}
    >
      {virtualizer.getVirtualItems().map(virtualItem => {
        const track = tracks[virtualItem.index]!;
        return (
          <div
            key={track.Id}
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              transform: `translateY(${virtualItem.start - scrollMargin}px)`,
            }}
          >
            <TrackListRow
              track={track}
              index={virtualItem.index}
              isCurrentTrack={track.Id === currentTrackId}
              isPlaying={isPlaying}
              onPlay={() => onPlayTrack?.(track, virtualItem.index)}
              onPause={() => onPauseTrack?.()}
              showAlbum={showAlbum}
              showArtist={showArtist}
              showImage={showImage}
            />
          </div>
        );
      })}
    </div>
  );
}

export function TrackList({
  tracks,
  currentTrackId,
  isPlaying = false,
  onPlayTrack,
  onPauseTrack,
  showAlbum = false,
  showArtist = true,
  showImage = true,
  virtualized = false,
}: TrackListProps) {
  if (tracks.length === 0) {
    return (
      <div className="flex h-32 items-center justify-center">
        <p className="text-text-secondary">No tracks found</p>
      </div>
    );
  }

  if (virtualized) {
    return (
      <VirtualizedTrackList
        tracks={tracks}
        currentTrackId={currentTrackId}
        isPlaying={isPlaying}
        onPlayTrack={onPlayTrack}
        onPauseTrack={onPauseTrack}
        showAlbum={showAlbum}
        showArtist={showArtist}
        showImage={showImage}
      />
    );
  }

  return (
    <div className="space-y-1">
      {tracks.map((track, index) => (
        <TrackListRow
          key={track.Id}
          track={track}
          index={index}
          isCurrentTrack={track.Id === currentTrackId}
          isPlaying={isPlaying}
          onPlay={() => onPlayTrack?.(track, index)}
          onPause={() => onPauseTrack?.()}
          showAlbum={showAlbum}
          showArtist={showArtist}
          showImage={showImage}
        />
      ))}
    </div>
  );
}
