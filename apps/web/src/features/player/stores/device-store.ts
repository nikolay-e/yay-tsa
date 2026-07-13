import { create } from 'zustand';
import {
  DeviceService,
  ItemsService,
  TransferUnavailableError,
  type DeviceInfo,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { toError } from '@/shared/utils/to-error';
import { getEffectiveDeviceId } from '../device-identity';

interface DeviceStoreState {
  devices: DeviceInfo[];
  isLoading: boolean;
  commandPending: string | null;
}

interface DeviceStoreActions {
  fetchDevices: () => Promise<void>;
  sendCommand: (
    sessionId: string,
    type: string,
    payload?: Record<string, unknown>
  ) => Promise<void>;
  transferHere: (sourceSessionId: string) => Promise<void>;
  updateDeviceState: (deviceId: string, update: Partial<DeviceInfo>) => void;
  setDeviceOffline: (deviceId: string) => void;
}

type DeviceStore = DeviceStoreState & DeviceStoreActions;

function getService(): DeviceService | null {
  const client = useAuthStore.getState().client;
  if (!client) return null;
  return new DeviceService(client);
}

export const useDeviceStore = create<DeviceStore>()(set => ({
  devices: [],
  isLoading: false,
  commandPending: null,

  fetchDevices: async () => {
    const service = getService();
    if (!service) return;
    set({ isLoading: true });
    try {
      const devices = await service.listDevices();
      set({ devices, isLoading: false });
    } catch (error) {
      log.player.warn('Failed to fetch devices', { error: String(error) });
      set({ isLoading: false });
    }
  },

  sendCommand: async (sessionId, type, payload) => {
    const service = getService();
    if (!service) throw new Error('Not authenticated');
    set({ commandPending: sessionId });
    try {
      await service.sendCommand(sessionId, type, payload);
    } catch (error) {
      log.player.warn('Failed to send command', { error: String(error) });
      throw toError(error);
    } finally {
      setTimeout(() => set({ commandPending: null }), 300);
    }
  },

  transferHere: async sourceSessionId => {
    const service = getService();
    if (!service) throw new Error('Not authenticated');
    try {
      const result = await service.transferLease(sourceSessionId, getEffectiveDeviceId());
      const { useSessionStore } = await import('./session-store');
      const { usePlayerStore } = await import('./player.store');
      // Repopulate the adaptive/DJ queue tail if the transferred session had one
      // (no-op for a plain now-playing transfer — restoreSession early-returns).
      await useSessionStore.getState().restoreSession();
      // restoreSession only rebuilds the queue; it never starts the *current* entry.
      // The backend hands back the live track + position, so play it here — otherwise
      // a plain transfer lands on an empty audio engine and the seek hits nothing.
      // playTrack advances within the restored DJ queue when a session is active and
      // sets a single-track queue otherwise.
      if (result.currentEntryId) {
        const client = useAuthStore.getState().client;
        if (client) {
          const [track] = await new ItemsService(client).getItemsByIds([result.currentEntryId]);
          if (track) {
            await usePlayerStore.getState().playTrack(track);
            if (result.positionMs > 0) {
              usePlayerStore.getState().seek(result.positionMs / 1000);
            }
          }
        }
      }
    } catch (error) {
      // Web devices have no server-side playback session yet, so the backend 404s the transfer
      // by design. Treat it as an expected "unavailable" condition — debug-level (never forwarded
      // to telemetry), not a MediaServerError/API:error — and let the UI show a calm message.
      if (error instanceof TransferUnavailableError) {
        log.player.debug('Transfer unavailable for target device', {
          sessionId: sourceSessionId,
        });
        throw error;
      }
      log.player.error('Transfer failed', error);
      throw toError(error);
    }
  },

  updateDeviceState: (deviceId, update) => {
    set(state => ({
      devices: state.devices.map(d => (d.deviceId === deviceId ? { ...d, ...update } : d)),
    }));
  },

  setDeviceOffline: deviceId => {
    set(state => ({
      devices: state.devices.map(d => (d.deviceId === deviceId ? { ...d, isOnline: false } : d)),
    }));
  },
}));
