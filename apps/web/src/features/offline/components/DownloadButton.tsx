import { type MouseEvent } from 'react';
import { Download, Check, Loader2, AlertCircle } from 'lucide-react';
import { type AudioItem } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { useOfflineStore, useOfflineEntry } from '../stores/offline.store';

type DownloadButtonProps = Readonly<{
  track: AudioItem;
  size?: 'sm' | 'md';
  className?: string;
}>;

export function DownloadButton({ track, size = 'sm', className }: DownloadButtonProps) {
  const entry = useOfflineEntry(track.Id);
  const download = useOfflineStore(state => state.download);
  const remove = useOfflineStore(state => state.remove);

  const iconSize = size === 'sm' ? 'h-4 w-4' : 'h-5 w-5';
  const status = entry?.status ?? 'idle';

  const handleClick = (e: MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (status === 'ready') {
      remove(track.Id).catch(() => {});
    } else if (status !== 'downloading') {
      download(track).catch(() => {});
    }
  };

  const label =
    status === 'ready'
      ? 'Remove download'
      : status === 'downloading'
        ? 'Downloading…'
        : status === 'error'
          ? 'Download failed — retry'
          : 'Download for offline';

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={status === 'downloading'}
      className={cn(
        'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
        status === 'ready' ? 'text-accent' : 'text-text-secondary hover:text-text-primary',
        className,
        // A downloaded / in-progress track stays visible even when the row isn't
        // hovered, so offline availability is always discoverable.
        (status === 'ready' || status === 'downloading' || status === 'error') && 'opacity-100'
      )}
      aria-label={label}
      title={label}
      data-testid="download-button"
    >
      {status === 'downloading' ? (
        <Loader2 className={cn(iconSize, 'animate-spin')} />
      ) : status === 'ready' ? (
        <Check className={iconSize} />
      ) : status === 'error' ? (
        <AlertCircle className={cn(iconSize, 'text-error')} />
      ) : (
        <Download className={iconSize} />
      )}
    </button>
  );
}
