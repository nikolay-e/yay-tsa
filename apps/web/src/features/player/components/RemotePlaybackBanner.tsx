import { useState } from 'react';
import { Monitor, Smartphone, ArrowDownToLine, X } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { useDeviceStore } from '../stores/device-store';
import { useActiveRemotePlayback } from '../hooks/useActiveRemotePlayback';

function deviceIcon(clientName: string | null) {
  const name = (clientName ?? '').toLowerCase();
  if (name.includes('mobile') || name.includes('phone') || name.includes('android'))
    return Smartphone;
  return Monitor;
}

export function RemotePlaybackBanner({ hasLocalPlayer }: Readonly<{ hasLocalPlayer: boolean }>) {
  const activeDevice = useActiveRemotePlayback();
  const transferHere = useDeviceStore(s => s.transferHere);
  const [dismissedKey, setDismissedKey] = useState<string | null>(null);
  const [isTransferring, setIsTransferring] = useState(false);

  if (!activeDevice) return null;

  const currentKey = `${activeDevice.deviceId}:${activeDevice.nowPlayingItemId}`;
  if (dismissedKey === currentKey) return null;

  const Icon = deviceIcon(activeDevice.clientName);

  const handleTransfer = async () => {
    setIsTransferring(true);
    try {
      await transferHere(activeDevice.sessionId);
      toast.add('success', 'Playback transferred');
    } catch {
      toast.add('error', 'Transfer failed');
    } finally {
      setIsTransferring(false);
    }
  };

  return (
    <output
      className={cn(
        'z-remote-banner border-border bg-bg-secondary/95 px-safe fixed right-0 left-0 border-t backdrop-blur-sm',
        'md:left-sidebar',
        hasLocalPlayer ? 'bottom-above-player-and-tab' : 'bottom-above-tab-bar'
      )}
    >
      <div className="flex items-center gap-2 px-3 py-2">
        <Icon className="text-accent h-4 w-4 shrink-0" />
        <p className="text-text-secondary min-w-0 flex-1 truncate text-xs">
          <span className="text-text-primary font-medium">{activeDevice.deviceName}</span>
          {' · '}
          {activeDevice.nowPlayingItemName ?? 'Playing'}
        </p>
        <button
          type="button"
          onClick={() => void handleTransfer()}
          disabled={isTransferring}
          className="text-accent hover:text-accent-hover flex shrink-0 items-center gap-1 text-xs font-medium transition-colors disabled:opacity-50"
        >
          <ArrowDownToLine className="h-3 w-3" />
          <span className="hidden sm:inline">Listen here</span>
        </button>
        <button
          type="button"
          onClick={() => setDismissedKey(currentKey)}
          className="text-text-tertiary hover:text-text-secondary shrink-0 p-0.5 transition-colors"
          aria-label="Dismiss"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    </output>
  );
}
