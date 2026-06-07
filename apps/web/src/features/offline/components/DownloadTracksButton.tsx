import { Download, Check, Loader2 } from 'lucide-react';
import { type AudioItem } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { useOfflineStore } from '../stores/offline.store';

type DownloadTracksButtonProps = Readonly<{
  tracks: AudioItem[];
  label?: string;
  className?: string;
}>;

// Aggregate download control for a whole album / list. Reflects "all downloaded"
// vs "download all" and shows progress while the batch runs.
export function DownloadTracksButton({
  tracks,
  label = 'Download',
  className,
}: DownloadTracksButtonProps) {
  const downloadMany = useOfflineStore(state => state.downloadMany);
  const entries = useOfflineStore(state => state.entries);

  if (tracks.length === 0) return null;

  const ready = tracks.filter(t => entries[t.Id]?.status === 'ready').length;
  const downloading = tracks.some(t => entries[t.Id]?.status === 'downloading');
  const allReady = ready === tracks.length;

  const handleClick = () => {
    if (allReady || downloading) return;
    downloadMany(tracks).catch(() => {});
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={allReady || downloading}
      className={cn(
        'border-border hover:bg-bg-hover flex items-center gap-2 rounded-full border px-4 py-2 text-sm font-medium transition-colors disabled:opacity-70',
        allReady && 'text-accent',
        className
      )}
      data-testid="download-album-button"
    >
      {downloading ? (
        <Loader2 className="h-4 w-4 animate-spin" />
      ) : allReady ? (
        <Check className="h-4 w-4" />
      ) : (
        <Download className="h-4 w-4" />
      )}
      {allReady ? 'Downloaded' : downloading ? `Downloading ${ready}/${tracks.length}` : label}
    </button>
  );
}
