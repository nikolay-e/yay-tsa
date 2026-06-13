import { useRef, useEffect, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import { Link, useLocation } from 'react-router-dom';
import {
  ChevronDown,
  Play,
  Pause,
  Loader2,
  SkipBack,
  SkipForward,
  Shuffle,
  Repeat,
  Repeat1,
  RotateCcw,
  RotateCw,
  Mic,
  MicOff,
  Timer,
  AlignLeft,
  ListMusic,
  ThumbsUp,
  ThumbsDown,
} from 'lucide-react';
import { type AudioItem, type RepeatMode, getIsFavorite } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { formatSeconds } from '@/shared/utils/time';
import { getImagePlaceholder } from '@/shared/utils/image-placeholder';
import { FavoriteButton } from '@/features/library/components/FavoriteButton';
import { useTimingStore } from '../stores/playback-timing.store';
import { usePlayerStore } from '../stores/player.store';
import { nextAudiobookSpeed } from '../playback-speed';
import { SeekBar } from './SeekBar';
import { InlineLyricsPanel } from './InlineLyricsPanel';

type MobileFullPlayerProps = Readonly<{
  track: AudioItem;
  imageUrl: string;
  hasImageError: boolean;
  isPlaying: boolean;
  isLoading: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
  isKaraokeMode: boolean;
  isKaraokeTransitioning: boolean;
  karaokeStatus: { state: string } | null;
  hasSleepTimer: boolean;
  sleepMinutesLeft: number;
  showThumbs: boolean;
  onClose: () => void;
  onNavigate: () => void;
  onPlayPause: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onToggleShuffle: () => void;
  onToggleRepeat: () => void;
  onToggleKaraoke: () => void;
  onOpenSleepTimer: () => void;
  onOpenQueue: () => void;
  onSeek: (seconds: number) => void;
  onThumbsUp: () => void;
  onThumbsDown: () => void;
}>;

type SecondaryPillControlsProps = Readonly<{
  isKaraokeMode: boolean;
  isKaraokeTransitioning: boolean;
  karaokeStatus: { state: string } | null | undefined;
  showLyrics: boolean;
  hasSleepTimer: boolean;
  sleepMinutesLeft: number;
  isAudiobook: boolean;
  playbackRate: number;
  onCycleSpeed: () => void;
  onToggleKaraoke: () => void;
  onToggleLyrics: () => void;
  onOpenSleepTimer: () => void;
}>;

function SecondaryPillControls({
  isKaraokeMode,
  isKaraokeTransitioning,
  karaokeStatus,
  showLyrics,
  hasSleepTimer,
  sleepMinutesLeft,
  isAudiobook,
  playbackRate,
  onCycleSpeed,
  onToggleKaraoke,
  onToggleLyrics,
  onOpenSleepTimer,
}: SecondaryPillControlsProps) {
  return (
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

      {isAudiobook && (
        <button
          type="button"
          data-testid="audiobook-speed"
          onClick={onCycleSpeed}
          className="bg-bg-tertiary text-text-secondary hover:text-text-primary focus-visible:ring-accent flex items-center gap-2 rounded-full px-4 py-2 text-sm font-semibold transition-colors focus-visible:ring-2 focus-visible:outline-none"
          aria-label={`Playback speed ${playbackRate}x`}
        >
          {playbackRate}x
        </button>
      )}

      <button
        type="button"
        onClick={onToggleLyrics}
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
        data-testid="sleep-timer-button"
      >
        <Timer className="h-4 w-4" />
        {hasSleepTimer && (
          <span className="text-xs">{sleepMinutesLeft > 0 ? `${sleepMinutesLeft}m` : '<1m'}</span>
        )}
      </button>
    </div>
  );
}

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

export function MobileFullPlayer({
  track,
  imageUrl,
  hasImageError,
  isPlaying,
  isLoading,
  isShuffle,
  repeatMode,
  isKaraokeMode,
  isKaraokeTransitioning,
  karaokeStatus,
  hasSleepTimer,
  sleepMinutesLeft,
  showThumbs,
  onClose,
  onNavigate,
  onPlayPause,
  onNext,
  onPrevious,
  onToggleShuffle,
  onToggleRepeat,
  onToggleKaraoke,
  onOpenSleepTimer,
  onOpenQueue,
  onSeek,
  onThumbsUp,
  onThumbsDown,
}: MobileFullPlayerProps) {
  const artistName = track.Artists?.[0] ?? 'Unknown Artist';
  const artistId = track.ArtistItems?.[0]?.Id;
  const albumName = track.Album ?? '';
  const [showLyrics, setShowLyrics] = useState(false);
  const { pathname } = useLocation();
  const isInitialLocationRef = useRef(true);
  useEffect(() => {
    if (isInitialLocationRef.current) {
      isInitialLocationRef.current = false;
      return;
    }
    setShowLyrics(false);
  }, [pathname]);
  const playerMode = usePlayerStore(s => s.playerMode);
  const playbackRate = usePlayerStore(s => s.playbackRate);
  const setPlaybackRate = usePlayerStore(s => s.setPlaybackRate);
  const skipBy = usePlayerStore(s => s.skipBy);
  const isAudiobook = playerMode === 'audiobook';

  return createPortal(
    <div className="bg-bg-primary fixed inset-0 z-[140] overflow-hidden md:hidden">
      {!hasImageError && (
        <img
          src={imageUrl}
          alt=""
          aria-hidden
          draggable={false}
          className="pointer-events-none absolute inset-0 h-full w-full scale-110 object-cover opacity-50 blur-3xl select-none"
        />
      )}
      <div
        aria-hidden
        className="from-bg-primary/30 via-bg-primary/70 to-bg-primary pointer-events-none absolute inset-0 bg-gradient-to-b"
      />

      <div className="relative z-10 flex h-full flex-col">
        {/* Header */}
        <div className="pt-safe flex items-center justify-between px-4 py-3">
          <button
            type="button"
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Close player"
          >
            <ChevronDown className="h-6 w-6" />
          </button>
          <p className="text-text-secondary max-w-[60%] truncate text-sm">{albumName}</p>
          <button
            type="button"
            data-testid="full-player-queue-button"
            onClick={onOpenQueue}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Queue"
          >
            <ListMusic className="h-6 w-6" />
          </button>
        </div>

        {/* Album art or lyrics */}
        <div className="flex flex-1 items-center justify-center overflow-hidden px-8 py-2">
          {showLyrics ? (
            <InlineLyricsPanel />
          ) : (
            <img
              src={hasImageError ? getImagePlaceholder() : imageUrl}
              alt={track.Name}
              className="aspect-square w-full max-w-xs rounded-xl object-cover shadow-2xl"
              draggable={false}
              onContextMenu={e => e.preventDefault()}
              style={{ WebkitTouchCallout: 'none', userSelect: 'none' } as CSSProperties}
            />
          )}
        </div>

        {/* Track info + like + rating */}
        <div className="flex items-center justify-between px-8 py-3">
          <div className="min-w-0 flex-1">
            <p
              data-testid="current-track-title"
              className="text-text-primary truncate text-xl font-semibold"
            >
              {track.Name}
            </p>
            {artistId ? (
              <Link
                to={`/artists/${artistId}`}
                onClick={onNavigate}
                data-testid="current-track-artist"
                className="text-text-secondary hover:text-text-primary truncate text-base hover:underline"
              >
                {artistName}
              </Link>
            ) : (
              <p
                data-testid="current-track-artist"
                className="text-text-secondary truncate text-base"
              >
                {artistName}
              </p>
            )}
          </div>
          <div className="flex shrink-0 items-center gap-1">
            {showThumbs && (
              <>
                <button
                  type="button"
                  onClick={onThumbsUp}
                  className="text-text-secondary hover:text-success focus-visible:ring-accent inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
                  aria-label="Thumbs up"
                >
                  <ThumbsUp className="h-5 w-5" />
                </button>
                <button
                  type="button"
                  onClick={onThumbsDown}
                  className="text-text-secondary hover:text-error focus-visible:ring-accent inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
                  aria-label="Thumbs down"
                >
                  <ThumbsDown className="h-5 w-5" />
                </button>
              </>
            )}
            <FavoriteButton
              itemId={track.Id}
              itemType="track"
              isFavorite={getIsFavorite(track)}
              size="md"
            />
          </div>
        </div>

        {/* Seek bar + time */}
        <div className="px-8">
          <SeekBar onSeek={onSeek} />
          <MobileTimeRow />
        </div>

        {/* Main controls */}
        <div className="flex items-center justify-between px-8 py-4">
          {isAudiobook ? (
            <button
              type="button"
              data-testid="audiobook-back-15"
              onClick={() => skipBy(-15)}
              className="text-text-secondary hover:text-text-primary focus-visible:ring-accent relative inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
              aria-label="Back 15 seconds"
            >
              <RotateCcw className="h-6 w-6" />
              <span className="absolute inset-0 flex items-center justify-center text-[9px] font-bold">
                15
              </span>
            </button>
          ) : (
            <button
              type="button"
              onClick={onToggleShuffle}
              className={cn(
                'focus-visible:ring-accent inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
                isShuffle ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
              )}
              aria-label="Shuffle"
              aria-pressed={isShuffle}
            >
              <Shuffle className="h-5 w-5" />
            </button>
          )}

          <button
            type="button"
            data-testid="previous-button"
            onClick={onPrevious}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Previous"
          >
            <SkipBack className="h-7 w-7" fill="currentColor" />
          </button>

          <button
            type="button"
            data-testid="play-pause-button"
            onClick={onPlayPause}
            className="bg-accent text-text-on-accent hover:bg-accent-hover focus-visible:ring-accent rounded-full p-4 transition-colors focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none"
            aria-label={isPlaying ? 'Pause' : 'Play'}
            aria-busy={isLoading}
          >
            {(() => {
              if (isLoading) return <Loader2 className="h-7 w-7 animate-spin" />;
              if (isPlaying) return <Pause className="h-7 w-7" fill="currentColor" />;
              return <Play className="ml-0.5 h-7 w-7" fill="currentColor" />;
            })()}
          </button>

          <button
            type="button"
            data-testid="next-button"
            onClick={onNext}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Next"
          >
            <SkipForward className="h-7 w-7" fill="currentColor" />
          </button>

          {isAudiobook ? (
            <button
              type="button"
              data-testid="audiobook-forward-30"
              onClick={() => skipBy(30)}
              className="text-text-secondary hover:text-text-primary focus-visible:ring-accent relative inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
              aria-label="Forward 30 seconds"
            >
              <RotateCw className="h-6 w-6" />
              <span className="absolute inset-0 flex items-center justify-center text-[9px] font-bold">
                30
              </span>
            </button>
          ) : (
            <button
              type="button"
              onClick={onToggleRepeat}
              className={cn(
                'focus-visible:ring-accent inline-flex min-h-11 min-w-11 items-center justify-center rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
                repeatMode === 'off' ? 'text-text-secondary hover:text-text-primary' : 'text-accent'
              )}
              aria-label={`Repeat: ${repeatMode}`}
              aria-pressed={repeatMode !== 'off'}
            >
              {repeatMode === 'one' ? (
                <Repeat1 className="h-5 w-5" />
              ) : (
                <Repeat className="h-5 w-5" />
              )}
            </button>
          )}
        </div>

        {/* Secondary pill controls */}
        <SecondaryPillControls
          isKaraokeMode={isKaraokeMode}
          isKaraokeTransitioning={isKaraokeTransitioning}
          karaokeStatus={karaokeStatus}
          showLyrics={showLyrics}
          hasSleepTimer={hasSleepTimer}
          sleepMinutesLeft={sleepMinutesLeft}
          isAudiobook={isAudiobook}
          playbackRate={playbackRate}
          onCycleSpeed={() => setPlaybackRate(nextAudiobookSpeed(playbackRate), 'book')}
          onToggleKaraoke={onToggleKaraoke}
          onToggleLyrics={() => setShowLyrics(v => !v)}
          onOpenSleepTimer={onOpenSleepTimer}
        />
      </div>
    </div>,
    document.body
  );
}
