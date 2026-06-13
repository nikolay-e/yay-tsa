import { CheckCircle2, ArrowDownCircle } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { useAlbumOfflineState } from '../stores/offline.store';

type AlbumOfflineBadgeProps = Readonly<{
  albumId: string | undefined;
  totalTracks?: number;
  className?: string;
}>;

export function AlbumOfflineBadge({ albumId, totalTracks, className }: AlbumOfflineBadgeProps) {
  const state = useAlbumOfflineState(albumId, totalTracks);
  if (state === 'none') return null;

  const isFull = state === 'full';
  const label = isFull ? 'Available offline' : 'Some tracks available offline';
  const Icon = isFull ? CheckCircle2 : ArrowDownCircle;

  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      data-testid="album-offline-badge"
      className={cn(
        'text-accent bg-bg-primary/80 inline-flex items-center justify-center rounded-full backdrop-blur-sm',
        className
      )}
    >
      <Icon className={cn('h-4 w-4', !isFull && 'opacity-70')} />
    </span>
  );
}
