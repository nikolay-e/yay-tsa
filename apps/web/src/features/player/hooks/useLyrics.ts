import { useState, useMemo, useEffect } from 'react';
import { parseLyrics, findActiveLineIndex, type ParsedLyrics } from '@yay-tsa/core';
import { useCurrentTrack } from '../stores/player.store';
import { useTimingStore } from '../stores/playback-timing.store';

interface UseLyricsResult {
  parsedLyrics: ParsedLyrics | null;
  activeLineIndex: number;
  isTimeSynced: boolean;
  hasLyrics: boolean;
}

export function useLyrics(): UseLyricsResult {
  const currentTrack = useCurrentTrack();
  const [activeLineIndex, setActiveLineIndex] = useState(-1);

  const parsedLyrics = useMemo(() => {
    return parseLyrics(currentTrack?.Lyrics);
  }, [currentTrack?.Lyrics]);

  useEffect(() => {
    if (!parsedLyrics?.isTimeSynced) {
      setActiveLineIndex(-1);
      return;
    }

    const lines = parsedLyrics.lines;

    const unsubscribe = useTimingStore.subscribe(state => {
      const newIndex = findActiveLineIndex(lines, state.currentTime);
      setActiveLineIndex(prev => (prev === newIndex ? prev : newIndex));
    });

    return unsubscribe;
  }, [parsedLyrics]);

  return {
    parsedLyrics,
    activeLineIndex,
    isTimeSynced: parsedLyrics?.isTimeSynced ?? false,
    hasLyrics: parsedLyrics !== null && parsedLyrics.lines.length > 0,
  };
}
