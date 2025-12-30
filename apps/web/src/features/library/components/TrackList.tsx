import { Play, Pause, MoreHorizontal } from 'lucide-react';
import { type AudioItem } from '@yaytsa/core';
import { formatTicksDuration } from '@/shared/utils/time';
import { cn } from '@/shared/utils/cn';

interface TrackListProps {
  tracks: AudioItem[];
  currentTrackId?: string;
  isPlaying?: boolean;
  onPlayTrack?: (track: AudioItem, index: number) => void;
  showAlbum?: boolean;
  showArtist?: boolean;
}

export function TrackList({
  tracks,
  currentTrackId,
  isPlaying = false,
  onPlayTrack,
  showAlbum = false,
  showArtist = true,
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
        const artistName = track.Artists?.[0] || 'Unknown Artist';

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
                    {track.IndexNumber || index + 1}
                  </span>
                  <Play
                    className="text-text-primary hidden h-4 w-4 group-hover:block"
                    fill="currentColor"
                  />
                </>
              )}
            </div>

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

            <button
              onClick={e => {
                e.stopPropagation();
              }}
              className="p-1 opacity-0 transition-opacity group-hover:opacity-100"
              aria-label="More options"
            >
              <MoreHorizontal className="text-text-secondary h-4 w-4" />
            </button>
          </div>
        );
      })}
    </div>
  );
}
