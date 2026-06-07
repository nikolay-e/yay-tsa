import { WifiOff } from 'lucide-react';
import { useIsOnline } from '../stores/offline.store';

export function OfflineIndicator() {
  const isOnline = useIsOnline();
  if (isOnline) return null;

  return (
    <div
      data-testid="offline-indicator"
      role="status"
      className="bg-bg-tertiary text-text-secondary pt-safe fixed top-0 right-0 left-0 z-50 flex items-center justify-center gap-2 px-4 py-1.5 text-xs font-medium md:left-sidebar"
    >
      <WifiOff className="h-3.5 w-3.5" />
      <span>Offline — playing downloaded tracks</span>
    </div>
  );
}
