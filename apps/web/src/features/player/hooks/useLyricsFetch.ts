import { useState, useEffect, useCallback, useRef } from 'react';
import type { MediaServerClient } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useCurrentTrack, usePlayerStore } from '../stores/player.store';
import { useLyrics } from './useLyrics';

interface UseLyricsFetchResult {
  isFetching: boolean;
  fetchError: string | null;
  handleFetch: () => void;
}

function isStaleTrack(trackId: string): boolean {
  return usePlayerStore.getState().currentTrack?.Id !== trackId;
}

export function useLyricsFetch(): UseLyricsFetchResult {
  const currentTrack = useCurrentTrack();
  const client = useAuthStore(state => state.client);
  const { hasLyrics } = useLyrics();
  const [isFetching, setIsFetching] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const clientRef = useRef(client);
  clientRef.current = client;

  useEffect(() => {
    setIsFetching(false);
    setFetchError(null);
  }, [currentTrack?.Id]);

  const doFetch = useCallback(async (trackId: string, apiClient: MediaServerClient) => {
    setIsFetching(true);
    setFetchError(null);
    try {
      const result = await apiClient.fetchLyrics(trackId);
      if (isStaleTrack(trackId)) return;
      if (result.found && result.lyrics) {
        usePlayerStore.getState().updateCurrentTrackLyrics(result.lyrics);
      } else {
        setFetchError('not_found');
      }
    } catch {
      if (isStaleTrack(trackId)) return;
      setFetchError('Lyrics service unavailable');
    } finally {
      if (!isStaleTrack(trackId)) {
        setIsFetching(false);
      }
    }
  }, []);

  useEffect(() => {
    if (!currentTrack || !client || hasLyrics || currentTrack.Lyrics) return;

    const trackId = currentTrack.Id;
    const timer = setTimeout(() => {
      void doFetch(trackId, client);
    }, 300);

    return () => clearTimeout(timer);
  }, [currentTrack?.Id, currentTrack?.Lyrics, client, hasLyrics, doFetch]);

  const handleFetch = useCallback(() => {
    const c = clientRef.current;
    if (!c || !currentTrack) return;
    void doFetch(currentTrack.Id, c);
  }, [currentTrack, doFetch]);

  return { isFetching, fetchError, handleFetch };
}
