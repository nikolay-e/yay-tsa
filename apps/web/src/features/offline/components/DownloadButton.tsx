import { type MouseEvent } from 'react';
import { Download, CheckCircle2, Loader2, AlertCircle } from 'lucide-react';
import { type AudioItem } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
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
  const progress = entry?.progress ?? 0;
  const percent = Math.round(progress * 100);

  const handleClick = (e: MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (status === 'ready') {
      remove(track.Id).catch(() => {
        toast.add('error', `Could not remove the download for ${track.Name}. Try again.`);
      });
    } else if (status !== 'downloading') {
      download(track).catch(() => {
        toast.add('error', `Could not download ${track.Name}. Check your connection.`);
      });
    }
  };

  const label =
    status === 'ready'
      ? 'Remove download'
      : status === 'downloading'
        ? progress > 0
          ? `Downloading… ${percent}%`
          : 'Downloading…'
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
        progress > 0 ? (
          <span className={cn('relative inline-flex items-center justify-center', iconSize)}>
            <svg viewBox="0 0 36 36" className={iconSize} aria-hidden="true">
              <circle
                cx="18"
                cy="18"
                r="16"
                fill="none"
                strokeWidth="4"
                className="stroke-border"
              />
              <circle
                cx="18"
                cy="18"
                r="16"
                fill="none"
                strokeWidth="4"
                strokeLinecap="round"
                pathLength={100}
                strokeDasharray={100}
                strokeDashoffset={100 - percent}
                transform="rotate(-90 18 18)"
                className="stroke-accent transition-[stroke-dashoffset] duration-200"
              />
            </svg>
            <span className="absolute text-[0.5rem] leading-none font-medium tabular-nums">
              {percent}
            </span>
          </span>
        ) : (
          <Loader2 className={cn(iconSize, 'animate-spin')} />
        )
      ) : status === 'ready' ? (
        <CheckCircle2 className={iconSize} />
      ) : status === 'error' ? (
        <AlertCircle className={cn(iconSize, 'text-error')} />
      ) : (
        <Download className={iconSize} />
      )}
    </button>
  );
}
