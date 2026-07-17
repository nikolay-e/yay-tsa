import { Link } from 'react-router-dom';
import { WifiOff } from 'lucide-react';
import { useIsOnline } from '../stores/offline.store';

export function OfflineIndicator() {
  const isOnline = useIsOnline();
  if (isOnline) return null;

  return (
    <Link
      to="/offline"
      data-testid="offline-indicator"
      role="status"
      className="bg-bg-tertiary text-text-secondary hover:text-text-primary pt-safe md:left-sidebar fixed top-0 right-0 left-0 z-50 flex items-center justify-center gap-2 px-4 py-1.5 text-xs font-medium transition-colors"
    >
      <WifiOff className="h-3.5 w-3.5" />
      <span>Offline — tap to open Downloads</span>
    </Link>
  );
}
