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
          <button
            key={track.Id}
            onClick={() => onPlayTrack?.(track, index)}
            className={cn(
              'flex w-full items-center gap-md rounded-sm p-sm text-left',
              'group transition-colors hover:bg-bg-secondary',
              isCurrentTrack && 'bg-bg-secondary'
            )}
          >
            <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center">
              {isCurrentTrack ? (
                isPlaying ? (
                  <Pause className="h-4 w-4 text-accent" fill="currentColor" />
                ) : (
                  <Play className="h-4 w-4 text-accent" fill="currentColor" />
                )
              ) : (
                <>
                  <span className="text-sm text-text-tertiary group-hover:hidden">
                    {track.IndexNumber || index + 1}
                  </span>
                  <Play
                    className="hidden h-4 w-4 text-text-primary group-hover:block"
                    fill="currentColor"
                  />
                </>
              )}
            </div>

            <div className="min-w-0 flex-1">
              <p
                className={cn(
                  'truncate font-medium',
                  isCurrentTrack ? 'text-accent' : 'text-text-primary'
                )}
              >
                {track.Name}
              </p>
              {(showArtist || showAlbum) && (
                <p className="truncate text-sm text-text-secondary">
                  {showArtist && artistName}
                  {showArtist && showAlbum && ' • '}
                  {showAlbum && track.Album}
                </p>
              )}
            </div>

            <span className="flex-shrink-0 text-sm text-text-tertiary">{duration}</span>

            <button
              onClick={e => {
                e.stopPropagation();
              }}
              className="p-1 opacity-0 transition-opacity group-hover:opacity-100"
              aria-label="More options"
            >
              <MoreHorizontal className="h-4 w-4 text-text-secondary" />
            </button>
          </button>
        );
      })}
    </div>
  );
}
