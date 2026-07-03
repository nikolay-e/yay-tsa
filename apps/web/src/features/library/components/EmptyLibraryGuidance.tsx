import { Link } from 'react-router-dom';
import { FolderOpen, Music, RefreshCw } from 'lucide-react';
import { useIsAdmin } from '@/features/auth/stores/auth.store';
import { useRescanLibrary, useScanStatus } from '../hooks/useLibraryScan';

export function EmptyLibraryGuidance() {
  const isAdmin = useIsAdmin();
  const rescan = useRescanLibrary();
  const { data: scanStatus } = useScanStatus();
  const isScanning = scanStatus?.scanning ?? false;

  return (
    <div
      data-testid="empty-library-guidance"
      className="mx-auto flex max-w-lg flex-col items-center gap-4 py-16 text-center"
    >
      <div className="bg-bg-secondary flex h-20 w-20 items-center justify-center rounded-full">
        <Music className="text-text-tertiary h-10 w-10" />
      </div>
      <h2 className="text-text-primary text-2xl font-bold">Your library is empty</h2>
      <p className="text-text-secondary text-sm">
        Yay-Tsa streams music from the media folder on your server. Put your music files into the
        configured library root (the folder mounted into the server), then run a library scan to
        index them.
      </p>
      <div className="bg-bg-secondary border-border flex w-full items-start gap-3 rounded-lg border p-4 text-left">
        <FolderOpen className="text-accent mt-0.5 h-5 w-5 shrink-0" />
        <p className="text-text-secondary text-sm">
          The server scans its library root for audio files. If you just added music, a rescan picks
          it up — new tracks appear here automatically once the scan finishes.
        </p>
      </div>
      {isAdmin ? (
        <button
          type="button"
          data-testid="empty-library-rescan-button"
          onClick={() => rescan.mutate()}
          disabled={rescan.isPending || isScanning}
          className="bg-accent text-text-on-accent hover:bg-accent-hover flex min-h-11 items-center gap-2 rounded-full px-6 py-2 text-sm font-medium transition-colors disabled:opacity-50"
        >
          <RefreshCw className={`h-4 w-4 ${isScanning ? 'animate-spin' : ''}`} />
          {isScanning ? 'Scan in progress…' : 'Rescan library'}
        </button>
      ) : (
        <p className="text-text-tertiary text-sm">
          Ask your server admin to add music and run a library scan.
        </p>
      )}
      <Link
        to="/settings"
        className="text-accent text-sm font-medium underline-offset-4 hover:underline focus-visible:underline"
      >
        Open Settings
      </Link>
    </div>
  );
}
