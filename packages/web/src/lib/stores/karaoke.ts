import { writable, derived, get } from 'svelte/store';
import { browser } from '$app/environment';
import { createLogger, type KaraokeStatus } from '@yaytsa/core';
import { client } from './auth.js';

const log = createLogger('Karaoke');

const KARAOKE_STORAGE_KEY = 'yaytsa_karaoke_enabled';
const KARAOKE_MODE_KEY = 'yaytsa_karaoke_mode';

export type KaraokeMode = 'off' | 'client' | 'server';

interface KaraokeState {
  enabled: boolean;
  mode: KaraokeMode;
  serverAvailable: boolean;
  currentTrackId: string | null;
  trackStatus: KaraokeStatus | null;
  processing: boolean;
  error: string | null;
}

type AudioEngineWithKaraoke = {
  setKaraokeMode?: (enabled: boolean) => void;
  isKaraokeModeEnabled?: () => boolean;
};

let audioEngineRef: AudioEngineWithKaraoke | null = null;
let eventSource: EventSource | null = null;

function loadPersistedState(): { enabled: boolean; mode: KaraokeMode } {
  if (!browser) return { enabled: false, mode: 'client' };
  try {
    const enabled = localStorage.getItem(KARAOKE_STORAGE_KEY) === 'true';
    const mode = (localStorage.getItem(KARAOKE_MODE_KEY) as KaraokeMode) || 'client';
    return { enabled, mode };
  } catch {
    return { enabled: false, mode: 'client' };
  }
}

function persistState(enabled: boolean, mode: KaraokeMode): void {
  if (!browser) return;
  try {
    localStorage.setItem(KARAOKE_STORAGE_KEY, String(enabled));
    localStorage.setItem(KARAOKE_MODE_KEY, mode);
  } catch {
    // localStorage unavailable
  }
}

const persisted = loadPersistedState();
const initialState: KaraokeState = {
  enabled: persisted.enabled,
  mode: persisted.mode,
  serverAvailable: false,
  currentTrackId: null,
  trackStatus: null,
  processing: false,
  error: null,
};

const karaokeStore = writable<KaraokeState>(initialState);

function setAudioEngine(engine: AudioEngineWithKaraoke | null): void {
  audioEngineRef = engine;

  const state = get(karaokeStore);
  if (engine?.setKaraokeMode && state.enabled && state.mode === 'client') {
    engine.setKaraokeMode(true);
  }
}

function stopStatusStream(): void {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
}

function startStatusStream(trackId: string): void {
  const currentClient = get(client);
  if (!currentClient || !browser) return;

  stopStatusStream();

  const serverUrl = currentClient.getServerUrl();
  const token = currentClient.getToken();
  const streamUrl = `${serverUrl}/Karaoke/${trackId}/status/stream?api_key=${token}`;

  log.info('Starting SSE status stream', { trackId });

  eventSource = new EventSource(streamUrl);

  eventSource.addEventListener('status', (event: MessageEvent<string>) => {
    try {
      const status = JSON.parse(event.data) as KaraokeStatus;
      karaokeStore.update(s => ({
        ...s,
        trackStatus: status,
        processing: status.state === 'PROCESSING',
      }));

      if (status.state === 'READY' || status.state === 'FAILED') {
        stopStatusStream();
        if (status.state === 'READY') {
          log.info('Karaoke processing completed', { trackId });
        } else {
          log.error('Karaoke processing failed', { trackId, message: status.message ?? 'Unknown' });
        }
      }
    } catch (error) {
      log.error('Failed to parse SSE status', { error: String(error) });
    }
  });

  eventSource.onerror = () => {
    log.warn('SSE connection error, will retry');
  };
}

async function checkServerAvailability(): Promise<void> {
  const currentClient = get(client);
  if (!currentClient) {
    karaokeStore.update(s => ({ ...s, serverAvailable: false }));
    return;
  }

  try {
    const available = await currentClient.getKaraokeEnabled();
    karaokeStore.update(s => ({ ...s, serverAvailable: available }));
    log.info('Karaoke server availability', { available });
  } catch {
    karaokeStore.update(s => ({ ...s, serverAvailable: false }));
  }
}

async function setCurrentTrack(trackId: string | null): Promise<void> {
  stopStatusStream();

  const targetTrackId = trackId;

  karaokeStore.update(s => ({
    ...s,
    currentTrackId: trackId,
    trackStatus: null,
    processing: false,
    error: null,
  }));

  if (!targetTrackId) return;

  const currentClient = get(client);
  const state = get(karaokeStore);

  if (!currentClient || !state.serverAvailable) return;

  try {
    const status = await currentClient.getKaraokeStatus(targetTrackId);

    // Verify track hasn't changed during async operation
    const currentState = get(karaokeStore);
    if (currentState.currentTrackId !== targetTrackId) return;

    karaokeStore.update(s => ({
      ...s,
      trackStatus: status,
      processing: status.state === 'PROCESSING',
    }));

    if (status.state === 'PROCESSING') {
      startStatusStream(targetTrackId);
    }
  } catch (error) {
    log.error('Failed to get karaoke status', { error: String(error) });
  }
}

async function requestProcessing(): Promise<void> {
  const state = get(karaokeStore);
  const currentClient = get(client);

  if (!state.currentTrackId || !currentClient) {
    log.warn('Cannot request processing: no track or client');
    return;
  }

  karaokeStore.update(s => ({ ...s, processing: true, error: null }));

  try {
    const status = await currentClient.requestKaraokeProcessing(state.currentTrackId);
    karaokeStore.update(s => ({ ...s, trackStatus: status }));

    if (status.state === 'PROCESSING') {
      startStatusStream(state.currentTrackId);
    }

    log.info('Karaoke processing requested', { trackId: state.currentTrackId });
  } catch (error) {
    log.error('Failed to request karaoke processing', { error: String(error) });
    karaokeStore.update(s => ({
      ...s,
      processing: false,
      error: 'Failed to start processing',
    }));
  }
}

function toggle(): void {
  karaokeStore.update(state => {
    const newEnabled = !state.enabled;

    if (state.mode === 'client' && audioEngineRef?.setKaraokeMode) {
      try {
        audioEngineRef.setKaraokeMode(newEnabled);
      } catch (error) {
        log.error('Failed to toggle karaoke mode', { error: String(error) });
        return { ...state, error: 'Failed to toggle karaoke mode' };
      }
    }

    persistState(newEnabled, state.mode);
    log.info(`Karaoke ${newEnabled ? 'enabled' : 'disabled'}`, { mode: state.mode });

    return { ...state, enabled: newEnabled, error: null };
  });
}

function setMode(mode: KaraokeMode): void {
  karaokeStore.update(state => {
    if (state.mode === mode) return state;

    // Disable client-side karaoke when switching away
    if (state.mode === 'client' && state.enabled && audioEngineRef?.setKaraokeMode) {
      audioEngineRef.setKaraokeMode(false);
    }

    // Enable client-side karaoke when switching to it
    if (mode === 'client' && state.enabled && audioEngineRef?.setKaraokeMode) {
      audioEngineRef.setKaraokeMode(true);
    }

    persistState(state.enabled, mode);
    log.info('Karaoke mode changed', { mode });

    return { ...state, mode };
  });
}

function reset(): void {
  stopStatusStream();
  audioEngineRef = null;
  karaokeStore.set(initialState);
}

// Subscribe to client changes
if (browser) {
  client.subscribe(c => {
    if (c) {
      void checkServerAvailability();
    } else {
      stopStatusStream();
      karaokeStore.update(s => ({
        ...s,
        serverAvailable: false,
        currentTrackId: null,
        trackStatus: null,
        processing: false,
      }));
    }
  });
}

export const isKaraokeEnabled = derived(karaokeStore, $state => $state.enabled);
export const karaokeMode = derived(karaokeStore, $state => $state.mode);
export const isServerAvailable = derived(karaokeStore, $state => $state.serverAvailable);
export const isProcessing = derived(karaokeStore, $state => $state.processing);
export const trackStatus = derived(karaokeStore, $state => $state.trackStatus);
export const karaokeError = derived(karaokeStore, $state => $state.error);

export const isTrackReady = derived(karaokeStore, $state => $state.trackStatus?.state === 'READY');

export const shouldUseInstrumental = derived(
  karaokeStore,
  $state => $state.enabled && $state.mode === 'server' && $state.trackStatus?.state === 'READY'
);

export const karaoke = {
  subscribe: karaokeStore.subscribe,
  setAudioEngine,
  setCurrentTrack,
  requestProcessing,
  checkServerAvailability,
  toggle,
  setMode,
  reset,
};
