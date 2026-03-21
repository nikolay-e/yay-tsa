import { useState, useMemo, useEffect, useCallback } from 'react';
import { parseLyrics, findActiveLineIndex, type ParsedLyrics } from '@yay-tsa/core';
import type { LineState } from '../components/LyricLine';
import { useCurrentTrack } from '../stores/player.store';
import { useTimingStore } from '../stores/playback-timing.store';

interface UseLyricsResult {
  parsedLyrics: ParsedLyrics | null;
  activeLineIndex: number;
  isTimeSynced: boolean;
  hasLyrics: boolean;
  getLineState: (index: number) => LineState;
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

  const isTimeSynced = parsedLyrics?.isTimeSynced ?? false;

  const getLineState = useCallback(
    (index: number): LineState => {
      if (!isTimeSynced) return 'future';
      if (index === activeLineIndex) return 'active';
      if (index < activeLineIndex) return 'past';
      return 'future';
    },
    [isTimeSynced, activeLineIndex]
  );

  return {
    parsedLyrics,
    activeLineIndex,
    isTimeSynced,
    hasLyrics: parsedLyrics !== null && parsedLyrics.lines.length > 0,
    getLineState,
  };
}
