import { useEffect } from 'react';
import {
  Monitor,
  Smartphone,
  Tablet,
  Wifi,
  WifiOff,
  Play,
  Pause,
  SkipForward,
  SkipBack,
  ArrowDownToLine,
  X,
  Loader2,
} from 'lucide-react';
import type { DeviceInfo } from '@yay-tsa/core';
import { getOrCreateDeviceId, TransferUnavailableError } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { Modal } from '@/shared/ui/Modal';
import { useDeviceStore } from '../stores/device-store';

function deviceIcon(clientName: string | null | undefined) {
  const name = (clientName ?? '').toLowerCase();
  if (name.includes('mobile') || name.includes('phone') || name.includes('android'))
    return Smartphone;
  if (name.includes('tablet') || name.includes('ipad')) return Tablet;
  return Monitor;
}

function formatPosition(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${sec.toString().padStart(2, '0')}`;
}

function DeviceItem({
  device,
  isCurrentDevice,
  commandPending,
  onCommand,
  onTransfer,
}: Readonly<{
  device: DeviceInfo;
  isCurrentDevice: boolean;
  commandPending: boolean;
  onCommand: (sessionId: string, type: string, payload?: Record<string, unknown>) => void;
  onTransfer: (sessionId: string) => void;
}>) {
  const Icon = deviceIcon(device.clientName);

  return (
    <div
      className={cn(
        'rounded-lg border p-3 transition-colors',
        isCurrentDevice && 'border-accent/30 bg-accent/5',
        !isCurrentDevice && device.isOnline && 'border-border bg-bg-tertiary',
        !isCurrentDevice && !device.isOnline && 'border-border/50 bg-bg-tertiary/50 opacity-60'
      )}
    >
      <div className="flex items-center gap-3">
        <div className="relative shrink-0">
          <Icon className="text-text-secondary h-5 w-5" />
          {device.isOnline ? (
            <Wifi className="text-success absolute -right-1 -bottom-1 h-2.5 w-2.5" />
          ) : (
            <WifiOff className="text-text-tertiary absolute -right-1 -bottom-1 h-2.5 w-2.5" />
          )}
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="text-text-primary truncate text-sm font-medium">
              {device.deviceName ?? 'Unknown Device'}
            </span>
            {isCurrentDevice && (
              <span className="text-accent bg-accent/10 rounded px-1.5 py-0.5 text-[10px] font-semibold">
                This device
              </span>
            )}
          </div>
          {device.nowPlayingItemName ? (
            <p className="text-text-secondary truncate text-xs">
              {device.nowPlayingItemName}
              {' · '}
              {formatPosition(device.positionMs ?? 0)}
            </p>
          ) : (
            <p className="text-text-tertiary text-xs">{device.isOnline ? 'Idle' : 'Offline'}</p>
          )}
        </div>
      </div>

      {device.isOnline && !isCurrentDevice && (
        <div className="mt-2 flex items-center gap-1">
          {device.nowPlayingItemId && (
            <>
              <button
                type="button"
                onClick={() => onCommand(device.sessionId, 'PREV')}
                disabled={commandPending}
                className="text-text-secondary hover:text-text-primary rounded p-1.5 transition-colors disabled:opacity-50"
                aria-label="Previous"
              >
                <SkipBack className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                onClick={() => onCommand(device.sessionId, device.isPaused ? 'PLAY' : 'PAUSE')}
                disabled={commandPending}
                className="bg-accent text-text-on-accent hover:bg-accent-hover rounded-full p-1.5 transition-colors disabled:opacity-50"
                aria-label={device.isPaused ? 'Play' : 'Pause'}
              >
                {commandPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                {!commandPending && device.isPaused && (
                  <Play className="h-3.5 w-3.5" fill="currentColor" />
                )}
                {!commandPending && !device.isPaused && (
                  <Pause className="h-3.5 w-3.5" fill="currentColor" />
                )}
              </button>
              <button
                type="button"
                onClick={() => onCommand(device.sessionId, 'NEXT')}
                disabled={commandPending}
                className="text-text-secondary hover:text-text-primary rounded p-1.5 transition-colors disabled:opacity-50"
                aria-label="Next"
              >
                <SkipForward className="h-3.5 w-3.5" />
              </button>
            </>
          )}
          <div className="flex-1" />
          <button
            type="button"
            onClick={() => onTransfer(device.sessionId)}
            className="text-text-secondary hover:text-accent flex items-center gap-1 rounded px-2 py-1 text-xs transition-colors"
            aria-label="Transfer playback here"
          >
            <ArrowDownToLine className="h-3 w-3" />
            Transfer here
          </button>
        </div>
      )}
    </div>
  );
}

export function DevicesPanel({
  isOpen,
  onClose,
}: Readonly<{ isOpen: boolean; onClose: () => void }>) {
  const devices = useDeviceStore(s => s.devices);
  const isLoading = useDeviceStore(s => s.isLoading);
  const commandPending = useDeviceStore(s => s.commandPending);
  const fetchDevices = useDeviceStore(s => s.fetchDevices);
  const sendCommand = useDeviceStore(s => s.sendCommand);
  const transferHere = useDeviceStore(s => s.transferHere);

  const currentDeviceId = getOrCreateDeviceId();

  useEffect(() => {
    if (isOpen) {
      fetchDevices();
    }
  }, [isOpen, fetchDevices]);

  const handleTransfer = async (sessionId: string) => {
    try {
      await transferHere(sessionId);
    } catch (error) {
      if (error instanceof TransferUnavailableError) {
        toast.add('info', "Can't transfer to that device");
      } else {
        toast.add('error', 'Transfer failed — try again');
      }
      return;
    }
    toast.add('success', 'Playback transferred');
    onClose();
  };

  const handleCommand = (sessionId: string, type: string, payload?: Record<string, unknown>) => {
    sendCommand(sessionId, type, payload).catch(() => {
      toast.add('error', 'Could not reach the device');
    });
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      ariaLabelledBy="devices-panel-title"
      backdropClassName="bg-bg-primary/80 flex items-end justify-center backdrop-blur-sm md:items-center"
      className="bg-bg-secondary border-border w-full max-w-md rounded-t-2xl border p-4 md:rounded-2xl"
    >
      <div className="mb-4 flex items-center justify-between">
        <h2 id="devices-panel-title" className="text-text-primary text-lg font-semibold">
          My Devices
        </h2>
        <button
          type="button"
          onClick={onClose}
          className="text-text-secondary hover:text-text-primary rounded-full p-1 transition-colors"
          aria-label="Close"
        >
          <X className="h-5 w-5" />
        </button>
      </div>

      {isLoading && devices.length === 0 && (
        <div className="flex justify-center py-8">
          <Loader2 className="text-accent h-6 w-6 animate-spin" />
        </div>
      )}
      {!isLoading && devices.length === 0 && (
        <p className="text-text-tertiary py-8 text-center text-sm">No devices found</p>
      )}
      {devices.length > 0 && (
        <div className="flex max-h-80 flex-col gap-2 overflow-y-auto">
          {devices.map(device => (
            <DeviceItem
              key={device.sessionId}
              device={device}
              isCurrentDevice={device.deviceId === currentDeviceId}
              commandPending={commandPending === device.sessionId}
              onCommand={handleCommand}
              onTransfer={handleTransfer}
            />
          ))}
        </div>
      )}
    </Modal>
  );
}
