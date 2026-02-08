import { memo } from 'react';
import { Link } from 'react-router-dom';
import { Play, Pause } from 'lucide-react';
import { type AudioItem } from '@yay-tsa/core';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { getTrackImageUrl } from '@/shared/utils/track-image';
import { formatTicks } from '@/shared/utils/time';
import { cn } from '@/shared/utils/cn';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { FavoriteButton } from './FavoriteButton';

const UNKNOWN_ARTIST = 'Unknown Artist';

interface TrackListProps {
  tracks: AudioItem[];
  currentTrackId?: string;
  isPlaying?: boolean;
  onPlayTrack?: (track: AudioItem, index: number) => void;
  onPauseTrack?: () => void;
  showAlbum?: boolean;
  showArtist?: boolean;
  showImage?: boolean;
}

const TrackImage = memo(
  function TrackImage({
    track,
    isCurrentTrack,
    isPlaying,
  }: {
    track: AudioItem;
    isCurrentTrack: boolean;
    isPlaying: boolean;
  }) {
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
            isCurrentTrack ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
          )}
        >
          {isCurrentTrack && isPlaying ? (
            <Pause className="text-accent h-4 w-4" fill="currentColor" />
          ) : (
            <Play className="h-4 w-4 text-white" fill="currentColor" />
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

export function TrackList({
  tracks,
  currentTrackId,
  isPlaying = false,
  onPlayTrack,
  onPauseTrack,
  showAlbum = false,
  showArtist = true,
  showImage = true,
}: TrackListProps) {
  if (tracks.length === 0) {
    return (
      <div className="flex h-32 items-center justify-center">
        <p className="text-text-secondary">No tracks found</p>
      </div>
    );
  }

  return (
    <div className="space-y-1">
      {tracks.map((track, index) => {
        const isCurrentTrack = track.Id === currentTrackId;
        const duration = formatTicks(track.RunTimeTicks);
        const artistName = track.Artists?.[0] ?? UNKNOWN_ARTIST;
        const artistId = track.ArtistItems?.[0]?.Id;

        return (
          <div
            data-testid="track-row"
            key={track.Id}
            aria-current={isCurrentTrack ? 'true' : undefined}
            className={cn(
              'flex w-full items-center gap-4 rounded-sm p-2 text-left',
              'group hover:bg-bg-secondary transition-colors',
              isCurrentTrack && 'bg-bg-secondary'
            )}
          >
            <button
              type="button"
              onClick={() => {
                if (isCurrentTrack && isPlaying) {
                  onPauseTrack?.();
                } else {
                  onPlayTrack?.(track, index);
                }
              }}
              className="focus-visible:ring-accent cursor-pointer focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none"
              aria-label={
                isCurrentTrack && isPlaying ? `Pause ${track.Name}` : `Play ${track.Name}`
              }
            >
              {showImage ? (
                <TrackImage track={track} isCurrentTrack={isCurrentTrack} isPlaying={isPlaying} />
              ) : (
                <div className="flex h-8 w-8 shrink-0 items-center justify-center">
                  {isCurrentTrack ? (
                    isPlaying ? (
                      <Pause className="text-accent h-4 w-4" fill="currentColor" />
                    ) : (
                      <Play className="text-accent h-4 w-4" fill="currentColor" />
                    )
                  ) : (
                    <>
                      <span className="text-text-tertiary text-sm group-focus-within:hidden group-hover:hidden">
                        {track.IndexNumber ?? index + 1}
                      </span>
                      <Play
                        className="text-text-primary hidden h-4 w-4 group-focus-within:block group-hover:block"
                        fill="currentColor"
                      />
                    </>
                  )}
                </div>
              )}
            </button>

            <div className="min-w-0 flex-1">
              <button
                type="button"
                data-testid="track-title"
                onClick={() => onPlayTrack?.(track, index)}
                className={cn(
                  'block w-full cursor-pointer truncate text-left font-medium hover:underline',
                  isCurrentTrack ? 'text-accent' : 'text-text-primary'
                )}
              >
                {track.Name}
              </button>
              {(showArtist || showAlbum) && (
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
                  {showArtist && showAlbum && track.Album && ' • '}
                  {showAlbum && track.AlbumId ? (
                    <Link
                      to={`/albums/${track.AlbumId}`}
                      onClick={e => e.stopPropagation()}
                      className="hover:text-text-primary hover:underline"
                    >
                      {track.Album}
                    </Link>
                  ) : (
                    showAlbum && track.Album
                  )}
                </p>
              )}
            </div>

            <FavoriteButton
              itemId={track.Id}
              isFavorite={track.UserData?.IsFavorite ?? false}
              className={cn(
                'shrink-0',
                !track.UserData?.IsFavorite && 'opacity-0 group-hover:opacity-100'
              )}
            />
            <span className="text-text-tertiary shrink-0 text-sm">{duration}</span>
          </div>
        );
      })}
    </div>
  );
}
