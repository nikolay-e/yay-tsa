import { useState, useEffect, useEffectEvent } from 'react';
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
  const trackId = currentTrack?.Id;
  const trackLyrics = currentTrack?.Lyrics;

  useEffect(() => {
    setIsFetching(false);
    setFetchError(null);
  }, [trackId]);

  const fetchLyricsForTrack = async (id: string, apiClient: MediaServerClient) => {
    setIsFetching(true);
    setFetchError(null);
    try {
      const result = await apiClient.fetchLyrics(id);
      if (isStaleTrack(id)) return;
      if (result.found && result.lyrics) {
        usePlayerStore.getState().updateCurrentTrackLyrics(result.lyrics);
      } else {
        setFetchError('not_found');
      }
    } catch {
      if (isStaleTrack(id)) return;
      setFetchError('Lyrics service unavailable');
    } finally {
      if (!isStaleTrack(id)) {
        setIsFetching(false);
      }
    }
  };

  const doFetch = useEffectEvent(fetchLyricsForTrack);

  useEffect(() => {
    if (!currentTrack || !client || hasLyrics || trackLyrics) return;

    const id = currentTrack.Id;
    const timer = setTimeout(() => {
      doFetch(id, client).catch(() => {});
    }, 300);

    return () => clearTimeout(timer);
  }, [trackId, trackLyrics, client, hasLyrics, currentTrack]);

  const handleFetch = () => {
    if (!client || !currentTrack) return;
    fetchLyricsForTrack(currentTrack.Id, client).catch(() => {});
  };

  return { isFetching, fetchError, handleFetch };
}
