import { memo, useRef, useEffect, useState, useCallback, type CSSProperties } from 'react';
import {
  ChevronDown,
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Shuffle,
  Repeat,
  Repeat1,
  Mic,
  MicOff,
  Timer,
  AlignLeft,
  Loader2,
  Search,
} from 'lucide-react';
import type { AudioItem } from '@yay-tsa/core';
import type { RepeatMode } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { formatSeconds } from '@/shared/utils/time';
import { FavoriteButton } from '@/features/library/components/FavoriteButton';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useTimingStore } from '../stores/playback-timing.store';
import { useLyrics } from '../hooks/useLyrics';
import { useCurrentTrack, usePlayerStore } from '../stores/player.store';
import { SeekBar } from './SeekBar';
import { LyricLine } from './LyricLine';

type MobileFullPlayerProps = Readonly<{
  track: AudioItem;
  imageUrl: string;
  hasImageError: boolean;
  isPlaying: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
  isKaraokeMode: boolean;
  isKaraokeTransitioning: boolean;
  karaokeStatus: { state: string } | null;
  hasSleepTimer: boolean;
  sleepMinutesLeft: number;
  onClose: () => void;
  onPlayPause: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onToggleShuffle: () => void;
  onToggleRepeat: () => void;
  onToggleKaraoke: () => void;
  onOpenSleepTimer: () => void;
  onSeek: (seconds: number) => void;
}>;

function MobileTimeRow() {
  const currentRef = useRef<HTMLSpanElement>(null);
  const totalRef = useRef<HTMLSpanElement>(null);
  const lastSec = useRef(-1);
  const lastDur = useRef(-1);

  useEffect(() => {
    const { currentTime: ct, duration: dur } = useTimingStore.getState();
    if (currentRef.current) currentRef.current.textContent = formatSeconds(ct);
    if (totalRef.current) totalRef.current.textContent = formatSeconds(dur);
    lastSec.current = Math.floor(ct);
    lastDur.current = Math.floor(dur);

    return useTimingStore.subscribe(({ currentTime, duration }) => {
      const s = Math.floor(currentTime);
      const d = Math.floor(duration);
      if (s !== lastSec.current) {
        lastSec.current = s;
        if (currentRef.current) currentRef.current.textContent = formatSeconds(currentTime);
      }
      if (d !== lastDur.current) {
        lastDur.current = d;
        if (totalRef.current) totalRef.current.textContent = formatSeconds(duration);
      }
    });
  }, []);

  return (
    <div className="text-text-tertiary mt-1 flex justify-between text-xs tabular-nums">
      <span ref={currentRef}>0:00</span>
      <span ref={totalRef}>0:00</span>
    </div>
  );
}

function InlineLyricsPanel() {
  const { parsedLyrics, activeLineIndex, isTimeSynced, hasLyrics } = useLyrics();
  const currentTrack = useCurrentTrack();
  const client = useAuthStore(state => state.client);
  const activeLineRef = useRef<HTMLDivElement>(null);
  const lastScrolledIndex = useRef(-1);
  const [isFetching, setIsFetching] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);

  useEffect(() => {
    setIsFetching(false);
    setFetchError(null);
  }, [currentTrack?.Id]);

  useEffect(() => {
    if (!isTimeSynced || activeLineIndex < 0) return;
    if (activeLineIndex === lastScrolledIndex.current) return;
    lastScrolledIndex.current = activeLineIndex;
    const id = setTimeout(() => {
      try {
        activeLineRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      } catch {
        // Element may be detached during unmount
      }
    }, 100);
    return () => clearTimeout(id);
  }, [activeLineIndex, isTimeSynced]);

  useEffect(() => {
    if (!currentTrack || !client || hasLyrics || currentTrack.Lyrics) return;
    const trackId = currentTrack.Id;
    const timer = setTimeout(async () => {
      setIsFetching(true);
      try {
        const result = await client.fetchLyrics(trackId);
        if (usePlayerStore.getState().currentTrack?.Id !== trackId) return;
        if (result.found && result.lyrics) {
          usePlayerStore.getState().updateCurrentTrackLyrics(result.lyrics);
        } else {
          setFetchError('not_found');
        }
      } catch {
        if (usePlayerStore.getState().currentTrack?.Id !== trackId) return;
        setFetchError('Lyrics service unavailable');
      } finally {
        if (usePlayerStore.getState().currentTrack?.Id === trackId) setIsFetching(false);
      }
    }, 300);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentTrack?.Id, client, hasLyrics, currentTrack?.Lyrics]);

  const handleFetch = useCallback(async () => {
    if (!client || !currentTrack) return;
    const trackId = currentTrack.Id;
    setIsFetching(true);
    setFetchError(null);
    try {
      const result = await client.fetchLyrics(trackId);
      if (usePlayerStore.getState().currentTrack?.Id !== trackId) return;
      if (result.found && result.lyrics) {
        usePlayerStore.getState().updateCurrentTrackLyrics(result.lyrics);
      } else {
        setFetchError('not_found');
      }
    } catch {
      if (usePlayerStore.getState().currentTrack?.Id !== trackId) return;
      setFetchError('Lyrics service unavailable');
    } finally {
      if (usePlayerStore.getState().currentTrack?.Id === trackId) setIsFetching(false);
    }
  }, [client, currentTrack]);

  const getLineState = (index: number): 'active' | 'past' | 'future' => {
    if (!isTimeSynced) return 'future';
    if (index === activeLineIndex) return 'active';
    if (index < activeLineIndex) return 'past';
    return 'future';
  };

  return (
    <div className="h-full w-full overflow-y-auto">
      {hasLyrics ? (
        <div className="space-y-1 py-4">
          {!isTimeSynced && (
            <p className="text-text-tertiary mb-3 text-center text-xs">Not time-synced</p>
          )}
          {parsedLyrics?.lines.map((line, index) => (
            <LyricLine
              key={`${index}-${line.time}`}
              ref={index === activeLineIndex ? activeLineRef : undefined}
              text={line.text}
              state={getLineState(index)}
            />
          ))}
        </div>
      ) : (
        <div className="flex h-full flex-col items-center justify-center gap-4">
          {isFetching ? (
            <>
              <Loader2 className="text-accent h-8 w-8 animate-spin" />
              <span className="text-text-secondary text-sm">Searching...</span>
            </>
          ) : fetchError === 'not_found' ? (
            <span className="text-text-tertiary text-sm">No lyrics found</span>
          ) : fetchError ? (
            <div className="flex flex-col items-center gap-3">
              <span className="text-text-tertiary text-sm">{fetchError}</span>
              <button
                type="button"
                onClick={() => {
                  void handleFetch();
                }}
                className="bg-bg-secondary hover:bg-bg-tertiary text-text-primary flex items-center gap-2 rounded-full px-5 py-2 text-sm transition-colors"
              >
                <Search className="h-4 w-4" /> Try Again
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => {
                void handleFetch();
              }}
              className="bg-accent hover:bg-accent-hover text-text-on-accent flex items-center gap-2 rounded-full px-6 py-2.5 transition-colors"
            >
              <Search className="h-4 w-4" /> Search Lyrics
            </button>
          )}
        </div>
      )}
    </div>
  );
}

export const MobileFullPlayer = memo(function MobileFullPlayer({
  track,
  imageUrl,
  hasImageError,
  isPlaying,
  isShuffle,
  repeatMode,
  isKaraokeMode,
  isKaraokeTransitioning,
  karaokeStatus,
  hasSleepTimer,
  sleepMinutesLeft,
  onClose,
  onPlayPause,
  onNext,
  onPrevious,
  onToggleShuffle,
  onToggleRepeat,
  onToggleKaraoke,
  onOpenSleepTimer,
  onSeek,
}: MobileFullPlayerProps) {
  const artistName = track.Artists?.[0] ?? 'Unknown Artist';
  const albumName = track.Album ?? '';
  const [showLyrics, setShowLyrics] = useState(false);

  return (
    <div className="bg-bg-primary fixed inset-0 z-[140] flex flex-col md:hidden">
      {/* Header */}
      <div className="pt-safe flex items-center justify-between px-4 py-3">
        <button
          type="button"
          onClick={onClose}
          className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
          aria-label="Close player"
        >
          <ChevronDown className="h-6 w-6" />
        </button>
        <p className="text-text-secondary max-w-[60%] truncate text-sm">{albumName}</p>
        <div className="w-10" />
      </div>

      {/* Album art or lyrics */}
      <div className="flex flex-1 items-center justify-center overflow-hidden px-8 py-2">
        {showLyrics ? (
          <InlineLyricsPanel />
        ) : (
          <img
            src={hasImageError ? '/placeholder-album.svg' : imageUrl}
            alt={track.Name}
            className="aspect-square w-full max-w-xs rounded-xl object-cover shadow-2xl"
            draggable={false}
            onContextMenu={e => e.preventDefault()}
            style={{ WebkitTouchCallout: 'none', userSelect: 'none' } as CSSProperties}
          />
        )}
      </div>

      {/* Track info + like */}
      <div className="flex items-center justify-between px-8 py-3">
        <div className="min-w-0 flex-1">
          <p className="text-text-primary truncate text-xl font-semibold">{track.Name}</p>
          <p className="text-text-secondary truncate text-base">{artistName}</p>
        </div>
        <FavoriteButton
          itemId={track.Id}
          isFavorite={track.UserData?.IsFavorite ?? false}
          size="md"
        />
      </div>

      {/* Seek bar + time */}
      <div className="px-8">
        <SeekBar onSeek={onSeek} />
        <MobileTimeRow />
      </div>

      {/* Main controls */}
      <div className="flex items-center justify-between px-8 py-4">
        <button
          type="button"
          onClick={onToggleShuffle}
          className={cn(
            'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
            isShuffle ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
          )}
          aria-label="Shuffle"
          aria-pressed={isShuffle}
        >
          <Shuffle className="h-5 w-5" />
        </button>

        <button
          type="button"
          onClick={onPrevious}
          className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
          aria-label="Previous"
        >
          <SkipBack className="h-7 w-7" fill="currentColor" />
        </button>

        <button
          type="button"
          onClick={onPlayPause}
          className="bg-accent text-text-on-accent hover:bg-accent-hover focus-visible:ring-accent rounded-full p-4 transition-colors focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none"
          aria-label={isPlaying ? 'Pause' : 'Play'}
        >
          {isPlaying ? (
            <Pause className="h-7 w-7" fill="currentColor" />
          ) : (
            <Play className="ml-0.5 h-7 w-7" fill="currentColor" />
          )}
        </button>

        <button
          type="button"
          onClick={onNext}
          className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
          aria-label="Next"
        >
          <SkipForward className="h-7 w-7" fill="currentColor" />
        </button>

        <button
          type="button"
          onClick={onToggleRepeat}
          className={cn(
            'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
            repeatMode === 'off' ? 'text-text-secondary hover:text-text-primary' : 'text-accent'
          )}
          aria-label={`Repeat: ${repeatMode}`}
          aria-pressed={repeatMode !== 'off'}
        >
          {repeatMode === 'one' ? <Repeat1 className="h-5 w-5" /> : <Repeat className="h-5 w-5" />}
        </button>
      </div>

      {/* Secondary pill controls */}
      <div
        className="flex justify-center gap-3 px-8 pt-4"
        style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 2rem)' }}
      >
        <button
          type="button"
          onClick={onToggleKaraoke}
          className={cn(
            'focus-visible:ring-accent flex items-center gap-2 rounded-full px-4 py-2 text-sm transition-colors focus-visible:ring-2 focus-visible:outline-none',
            isKaraokeMode || karaokeStatus?.state === 'PROCESSING'
              ? 'bg-accent/20 text-accent'
              : 'bg-bg-tertiary text-text-secondary hover:text-text-primary',
            (isKaraokeTransitioning || karaokeStatus?.state === 'PROCESSING') && 'animate-pulse'
          )}
          aria-label={isKaraokeMode ? 'Disable karaoke' : 'Enable karaoke'}
          aria-pressed={isKaraokeMode}
        >
          {isKaraokeMode ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
        </button>

        <button
          type="button"
          onClick={() => setShowLyrics(v => !v)}
          className={cn(
            'focus-visible:ring-accent flex items-center gap-2 rounded-full px-4 py-2 text-sm transition-colors focus-visible:ring-2 focus-visible:outline-none',
            showLyrics
              ? 'bg-accent/20 text-accent'
              : 'bg-bg-tertiary text-text-secondary hover:text-text-primary'
          )}
          aria-label={showLyrics ? 'Hide lyrics' : 'Show lyrics'}
          aria-pressed={showLyrics}
        >
          <AlignLeft className="h-4 w-4" />
        </button>

        <button
          type="button"
          onClick={onOpenSleepTimer}
          className={cn(
            'focus-visible:ring-accent relative flex items-center gap-2 rounded-full px-4 py-2 text-sm transition-colors focus-visible:ring-2 focus-visible:outline-none',
            hasSleepTimer
              ? 'bg-accent/20 text-accent'
              : 'bg-bg-tertiary text-text-secondary hover:text-text-primary'
          )}
          aria-label="Sleep timer"
        >
          <Timer className="h-4 w-4" />
          {hasSleepTimer && sleepMinutesLeft > 0 && (
            <span className="text-xs">{sleepMinutesLeft}m</span>
          )}
        </button>
      </div>
    </div>
  );
});
