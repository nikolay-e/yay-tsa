import { getOrCreateDeviceId, type DeviceInfo } from '@yay-tsa/core';
import { useDeviceStore } from '../stores/device-store';
import { usePlayerStore } from '../stores/player.store';

const currentDeviceId = getOrCreateDeviceId();

export function useActiveRemotePlayback(): DeviceInfo | null {
  const devices = useDeviceStore(s => s.devices);
  const isLocalPlaying = usePlayerStore(s => s.isPlaying);

  if (isLocalPlaying) return null;

  const candidates = devices.filter(
    d => d.isOnline && d.nowPlayingItemId && !d.isPaused && d.deviceId !== currentDeviceId
  );

  if (candidates.length === 0) return null;

  candidates.sort((a, b) => new Date(b.lastUpdate).getTime() - new Date(a.lastUpdate).getTime());
  return candidates[0] ?? null;
}
