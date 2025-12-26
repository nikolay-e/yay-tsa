import { writable, derived, get } from 'svelte/store';
import { browser } from '$app/environment';
import { createLogger, type KaraokeStatus } from '@yaytsa/core';
import { client } from '../../auth/stores/auth.store.js';

const log = createLogger('Karaoke');

const KARAOKE_STORAGE_KEY = 'yaytsa_karaoke_enabled';
const KARAOKE_MODE_KEY = 'yaytsa_karaoke_mode';
const PROCESSING_TRACK_KEY = 'yaytsa_karaoke_processing_track';

export type KaraokeMode = 'off' | 'server';

interface KaraokeState {
  enabled: boolean;
  mode: KaraokeMode;
  serverAvailable: boolean;
  currentTrackId: string | null;
  trackStatus: KaraokeStatus | null;
  processing: boolean;
  error: string | null;
}

let eventSource: EventSource | null = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 5;
const RECONNECT_DELAY_MS = 2000;

function loadPersistedState(): { enabled: boolean; mode: KaraokeMode } {
  if (!browser) return { enabled: false, mode: 'server' };
  try {
    const enabled = localStorage.getItem(KARAOKE_STORAGE_KEY) === 'true';
    const storedMode = localStorage.getItem(KARAOKE_MODE_KEY);
    const mode: KaraokeMode = storedMode === 'server' ? 'server' : 'server';
    return { enabled, mode };
  } catch {
    return { enabled: false, mode: 'server' };
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

function persistProcessingTrack(trackId: string | null): void {
  if (!browser) return;
  try {
    if (trackId) {
      sessionStorage.setItem(PROCESSING_TRACK_KEY, trackId);
    } else {
      sessionStorage.removeItem(PROCESSING_TRACK_KEY);
    }
  } catch {
    // sessionStorage unavailable
  }
}

function loadProcessingTrack(): string | null {
  if (!browser) return null;
  try {
    return sessionStorage.getItem(PROCESSING_TRACK_KEY);
  } catch {
    return null;
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

function stopStatusStream(): void {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
  reconnectAttempts = 0;
}

function startStatusStream(trackId: string): void {
  const currentClient = get(client);
  if (!currentClient || !browser) return;

  stopStatusStream();

  const serverUrl = currentClient.getServerUrl();
  const token = currentClient.getToken();
  const streamUrl = `${serverUrl}/Karaoke/${trackId}/status/stream?api_key=${token}`;

  log.info('Starting SSE status stream', { trackId, attempt: reconnectAttempts + 1 });

  eventSource = new EventSource(streamUrl);

  const subscribedTrackId = trackId;

  eventSource.addEventListener('status', (event: MessageEvent<string>) => {
    try {
      reconnectAttempts = 0;

      const currentState = get(karaokeStore);
      if (currentState.currentTrackId !== subscribedTrackId) {
        log.debug('SSE status event for stale track, ignoring', {
          subscribedTrackId,
          currentTrackId: currentState.currentTrackId,
        });
        stopStatusStream();
        persistProcessingTrack(null);
        return;
      }

      const status = JSON.parse(event.data) as KaraokeStatus;
      karaokeStore.update(s => ({
        ...s,
        trackStatus: status,
        processing: status.state === 'PROCESSING',
      }));

      if (status.state === 'READY' || status.state === 'FAILED') {
        stopStatusStream();
        persistProcessingTrack(null);
        if (status.state === 'READY') {
          log.info('Karaoke processing completed', { trackId: subscribedTrackId });
        } else {
          log.error('Karaoke processing failed', {
            trackId: subscribedTrackId,
            message: status.message ?? 'Unknown',
          });
        }
      }
    } catch (error) {
      log.error('Failed to parse SSE status', { error: String(error) });
    }
  });

  eventSource.onerror = () => {
    stopStatusStream();

    const currentState = get(karaokeStore);
    if (currentState.currentTrackId !== subscribedTrackId) {
      persistProcessingTrack(null);
      return;
    }

    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
      reconnectAttempts++;
      log.warn('SSE connection error, reconnecting', {
        attempt: reconnectAttempts,
        maxAttempts: MAX_RECONNECT_ATTEMPTS,
        trackId: subscribedTrackId,
      });
      setTimeout(() => {
        if (get(karaokeStore).currentTrackId === subscribedTrackId) {
          startStatusStream(subscribedTrackId);
        }
      }, RECONNECT_DELAY_MS * reconnectAttempts);
    } else {
      log.error('SSE reconnection failed after max attempts, falling back to polling', {
        trackId: subscribedTrackId,
      });
      reconnectAttempts = 0;
      void pollStatusUntilComplete(subscribedTrackId);
    }
  };
}

async function pollStatusUntilComplete(trackId: string): Promise<void> {
  const currentClient = get(client);
  if (!currentClient) return;

  const pollInterval = 2000;
  const maxPolls = 300;
  let polls = 0;

  while (polls < maxPolls) {
    const currentState = get(karaokeStore);
    if (currentState.currentTrackId !== trackId) {
      persistProcessingTrack(null);
      return;
    }

    try {
      const status = await currentClient.getKaraokeStatus(trackId);
      karaokeStore.update(s => ({
        ...s,
        trackStatus: status,
        processing: status.state === 'PROCESSING',
      }));

      if (status.state === 'READY' || status.state === 'FAILED') {
        persistProcessingTrack(null);
        log.info('Polling completed', { trackId, state: status.state });
        return;
      }
    } catch (error) {
      log.warn('Poll failed', { trackId, error: String(error) });
    }

    await new Promise<void>(resolve => {
      setTimeout(resolve, pollInterval);
    });
    polls++;
  }

  log.error('Polling timed out', { trackId });
  persistProcessingTrack(null);
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

  const targetTrackId = state.currentTrackId;

  karaokeStore.update(s => ({ ...s, processing: true, error: null }));
  persistProcessingTrack(targetTrackId);

  try {
    const status = await currentClient.requestKaraokeProcessing(targetTrackId);

    const currentState = get(karaokeStore);
    if (currentState.currentTrackId !== targetTrackId) {
      log.debug('Track changed during karaoke processing request, ignoring result', {
        targetTrackId,
        currentTrackId: currentState.currentTrackId,
      });
      return;
    }

    karaokeStore.update(s => ({ ...s, trackStatus: status }));

    if (status.state === 'PROCESSING') {
      startStatusStream(targetTrackId);
    } else {
      persistProcessingTrack(null);
    }

    log.info('Karaoke processing requested', { trackId: targetTrackId });
  } catch (error) {
    const currentState = get(karaokeStore);
    if (currentState.currentTrackId === targetTrackId) {
      log.error('Failed to request karaoke processing', { error: String(error) });
      karaokeStore.update(s => ({
        ...s,
        processing: false,
        error: 'Failed to start processing',
      }));
      persistProcessingTrack(null);
    }
  }
}

function toggle(): void {
  karaokeStore.update(state => {
    const newEnabled = !state.enabled;

    persistState(newEnabled, state.mode);
    log.info(`Karaoke ${newEnabled ? 'enabled' : 'disabled'}`, { mode: state.mode });

    return { ...state, enabled: newEnabled, error: null };
  });
}

function setMode(mode: KaraokeMode): void {
  karaokeStore.update(state => {
    if (state.mode === mode) return state;

    persistState(state.enabled, mode);
    log.info('Karaoke mode changed', { mode });

    return { ...state, mode };
  });
}

function reset(): void {
  stopStatusStream();
  karaokeStore.set(initialState);
}

function clearTrackStatus(): void {
  const state = get(karaokeStore);
  if (state.currentTrackId) {
    log.info('Clearing karaoke track status (stream failure recovery)', {
      trackId: state.currentTrackId,
    });
    karaokeStore.update(s => ({
      ...s,
      trackStatus: null,
      processing: false,
      error: null,
    }));
  }
}

async function resumeProcessingFromStorage(): Promise<void> {
  const processingTrackId = loadProcessingTrack();
  if (!processingTrackId) return;

  const currentClient = get(client);
  if (!currentClient) return;

  log.info('Resuming processing from storage', { trackId: processingTrackId });

  try {
    const status = await currentClient.getKaraokeStatus(processingTrackId);

    if (status.state === 'PROCESSING') {
      karaokeStore.update(s => ({
        ...s,
        currentTrackId: processingTrackId,
        trackStatus: status,
        processing: true,
      }));
      startStatusStream(processingTrackId);
    } else if (status.state === 'READY' || status.state === 'FAILED') {
      log.info('Persisted processing already completed', {
        trackId: processingTrackId,
        state: status.state,
      });
      persistProcessingTrack(null);
    } else {
      persistProcessingTrack(null);
    }
  } catch (error) {
    log.warn('Failed to resume processing', { trackId: processingTrackId, error: String(error) });
    persistProcessingTrack(null);
  }
}

let clientUnsubscribe: (() => void) | null = null;

if (browser) {
  clientUnsubscribe = client.subscribe(c => {
    if (c) {
      void checkServerAvailability().then(() => {
        void resumeProcessingFromStorage();
      });
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

  if (import.meta.hot) {
    import.meta.hot.dispose(() => {
      if (clientUnsubscribe) {
        clientUnsubscribe();
        clientUnsubscribe = null;
      }
      stopStatusStream();
    });
  }
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
  setCurrentTrack,
  requestProcessing,
  checkServerAvailability,
  toggle,
  setMode,
  reset,
  clearTrackStatus,
};
