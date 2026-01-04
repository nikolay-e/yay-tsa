import { useState, memo } from 'react';
import { Play, Pause } from 'lucide-react';
import { type AudioItem } from '@yaytsa/core';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { formatTicksDuration } from '@/shared/utils/time';
import { cn } from '@/shared/utils/cn';

const UNKNOWN_ARTIST = 'Unknown Artist';

interface TrackListProps {
  tracks: AudioItem[];
  currentTrackId?: string;
  isPlaying?: boolean;
  onPlayTrack?: (track: AudioItem, index: number) => void;
  showAlbum?: boolean;
  showArtist?: boolean;
  showImage?: boolean;
}

const TrackImage = memo(function TrackImage({
  track,
  isCurrentTrack,
  isPlaying,
}: {
  track: AudioItem;
  isCurrentTrack: boolean;
  isPlaying: boolean;
}) {
  const [hasError, setHasError] = useState(false);
  const { getImageUrl } = useImageUrl();

  const imageUrl = track.AlbumPrimaryImageTag
    ? getImageUrl(track.AlbumId || track.Id, 'Primary', {
        tag: track.AlbumPrimaryImageTag,
        maxWidth: 48,
        maxHeight: 48,
      })
    : getImagePlaceholder();

  return (
    <div className="group/img relative h-10 w-10 shrink-0 overflow-hidden rounded-sm">
      <img
        src={hasError ? getImagePlaceholder() : imageUrl}
        alt={track.Name}
        className="h-full w-full object-cover"
        onError={() => setHasError(true)}
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
});

export function TrackList({
  tracks,
  currentTrackId,
  isPlaying = false,
  onPlayTrack,
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
        const duration = formatTicksDuration(track.RunTimeTicks);
        const artistName = track.Artists?.[0] ?? UNKNOWN_ARTIST;

        return (
          <div
            data-testid="track-row"
            key={track.Id}
            role="button"
            tabIndex={0}
            onClick={() => onPlayTrack?.(track, index)}
            onKeyDown={e => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onPlayTrack?.(track, index);
              }
            }}
            className={cn(
              'flex w-full cursor-pointer items-center gap-4 rounded-sm p-2 text-left',
              'group hover:bg-bg-secondary transition-colors',
              isCurrentTrack && 'bg-bg-secondary'
            )}
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
                    <span className="text-text-tertiary text-sm group-hover:hidden">
                      {track.IndexNumber ?? index + 1}
                    </span>
                    <Play
                      className="text-text-primary hidden h-4 w-4 group-hover:block"
                      fill="currentColor"
                    />
                  </>
                )}
              </div>
            )}

            <div className="min-w-0 flex-1">
              <p
                data-testid="track-title"
                className={cn(
                  'truncate font-medium',
                  isCurrentTrack ? 'text-accent' : 'text-text-primary'
                )}
              >
                {track.Name}
              </p>
              {(showArtist || showAlbum) && (
                <p className="text-text-secondary truncate text-sm">
                  {showArtist && artistName}
                  {showArtist && showAlbum && ' • '}
                  {showAlbum && track.Album}
                </p>
              )}
            </div>

            <span className="text-text-tertiary shrink-0 text-sm">{duration}</span>
          </div>
        );
      })}
    </div>
  );
}
