import { useState } from 'react';
import { Download, Check, Loader2, AlertCircle } from 'lucide-react';
import { type AudioItem } from '@yay-tsa/core';
import { type OfflineSource } from '@yay-tsa/platform';
import { cn } from '@/shared/utils/cn';
import { useOfflineStore } from '../stores/offline.store';

type DownloadTracksButtonProps = Readonly<{
  tracks: AudioItem[];
  label?: string;
  reason?: OfflineSource;
  className?: string;
  iconOnly?: boolean;
}>;

// Aggregate download control for a whole album / list. Reflects "all downloaded"
// vs "download all" and shows progress while the batch runs.
export function DownloadTracksButton({
  tracks,
  label = 'Download',
  reason = 'manual',
  className,
  iconOnly = false,
}: DownloadTracksButtonProps) {
  const downloadMany = useOfflineStore(state => state.downloadMany);
  const entries = useOfflineStore(state => state.entries);
  const [failedCount, setFailedCount] = useState(0);
  const [storageFull, setStorageFull] = useState(false);

  if (tracks.length === 0) return null;

  const ready = tracks.filter(t => entries[t.Id]?.status === 'ready').length;
  const downloading = tracks.some(t => entries[t.Id]?.status === 'downloading');
  const allDownloaded = ready === tracks.length;
  const failed = !allDownloaded && !downloading && failedCount > 0;

  const handleClick = () => {
    if (allDownloaded || downloading) return;
    setFailedCount(0);
    setStorageFull(false);
    downloadMany(tracks, reason)
      .then(result => {
        setFailedCount(result.failed);
        setStorageFull(result.storageFull);
      })
      .catch(() => setFailedCount(tracks.length));
  };

  let text = label;
  if (allDownloaded) text = 'Downloaded';
  else if (downloading) text = `Downloading ${ready}/${tracks.length}`;
  else if (failed)
    text = storageFull ? 'Not enough storage space — retry' : `${failedCount} failed — retry`;

  let icon = <Download className="h-4 w-4" />;
  if (downloading) icon = <Loader2 className="h-4 w-4 animate-spin" />;
  else if (allDownloaded) icon = <Check className="h-4 w-4" />;
  else if (failed) icon = <AlertCircle className="h-4 w-4" />;

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={allDownloaded || downloading}
      aria-label={iconOnly ? text : undefined}
      title={iconOnly ? text : undefined}
      className={cn(
        'border-border hover:bg-bg-hover flex items-center gap-2 rounded-full border text-sm font-medium transition-colors disabled:opacity-70',
        iconOnly ? 'p-2' : 'px-4 py-2',
        allDownloaded && 'text-accent',
        failed && 'text-error',
        className
      )}
      data-testid="download-album-button"
    >
      {icon}
      {!iconOnly && text}
    </button>
  );
}
