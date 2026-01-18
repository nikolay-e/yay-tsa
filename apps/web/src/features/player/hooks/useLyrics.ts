import { useMemo } from 'react';
import { parseLyrics, findActiveLineIndex, type ParsedLyrics } from '@yaytsa/core';
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
  const currentTime = useTimingStore(s => s.currentTime);

  const parsedLyrics = useMemo(() => {
    return parseLyrics(currentTrack?.Lyrics);
  }, [currentTrack?.Lyrics]);

  const activeLineIndex = useMemo(() => {
    if (!parsedLyrics?.isTimeSynced) {
      return -1;
    }
    return findActiveLineIndex(parsedLyrics.lines, currentTime);
  }, [parsedLyrics, currentTime]);

  return {
    parsedLyrics,
    activeLineIndex,
    isTimeSynced: parsedLyrics?.isTimeSynced ?? false,
    hasLyrics: parsedLyrics !== null && parsedLyrics.lines.length > 0,
  };
}
