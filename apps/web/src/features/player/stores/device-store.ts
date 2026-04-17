import { create } from 'zustand';
import { DeviceService, type DeviceInfo } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';

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
    if (!service) return;
    set({ commandPending: sessionId });
    try {
      await service.sendCommand(sessionId, type, payload);
    } catch (error) {
      log.player.warn('Failed to send command', { error: String(error) });
    } finally {
      setTimeout(() => set({ commandPending: null }), 300);
    }
  },

  transferHere: async sourceSessionId => {
    const service = getService();
    if (!service) return;
    try {
      const payload = await service.transferPlayback(sourceSessionId);
      if (payload.trackId && payload.listeningSessionId) {
        const { useSessionStore } = await import('./session-store');
        await useSessionStore.getState().restoreSession();
      } else if (payload.trackId) {
        const { usePlayerStore } = await import('./player.store');
        const { ItemsService } = await import('@yay-tsa/core');
        const client = useAuthStore.getState().client;
        if (!client) return;
        const items = await new ItemsService(client).getItemsByIds([payload.trackId]);
        if (items.length > 0) {
          const track = items[0];
          if (track) await usePlayerStore.getState().playTrack(track);
          usePlayerStore.getState().seek(payload.positionMs / 1000);
        }
      }
    } catch (error) {
      log.player.error('Transfer failed', error);
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
