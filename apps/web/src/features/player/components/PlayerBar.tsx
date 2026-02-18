import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Mic, MicOff, Timer, AlignLeft } from 'lucide-react';
import { FavoriteButton } from '@/features/library/components/FavoriteButton';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { getTrackImageUrl } from '@/shared/utils/track-image';
import { formatSeconds } from '@/shared/utils/time';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { PLAYER_TEST_IDS } from '@/shared/testing/test-ids';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { useTimingStore } from '../stores/playback-timing.store';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
  useVolume,
  useIsShuffle,
  useRepeatMode,
  usePlayerError,
  useIsKaraokeMode,
  useIsKaraokeTransitioning,
  useKaraokeStatus,
  useVocalVolume,
  useSleepTimer,
} from '../stores/player.store';
import { LyricsView } from './LyricsView';
import { SleepTimerModal } from './SleepTimerModal';
import { VolumeControls } from './VolumeControls';
import { PlaybackControls } from './PlaybackControls';

function formatTimeText(time: number, total: number): string {
  const formatNum = (n: number) => {
    const mins = Math.floor(n / 60);
    const secs = Math.floor(n % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };
  return `${formatNum(time)} of ${formatNum(total)}`;
}

export function PlayerBar() {
  const [showSleepModal, setShowSleepModal] = useState(false);
  const [showLyricsView, setShowLyricsView] = useState(false);
  const [sleepMinutesLeft, setSleepMinutesLeft] = useState(0);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const volume = useVolume();
  const isShuffle = useIsShuffle();
  const repeatMode = useRepeatMode();
  const playerError = usePlayerError();
  const isKaraokeMode = useIsKaraokeMode();
  const isKaraokeTransitioning = useIsKaraokeTransitioning();
  const karaokeStatus = useKaraokeStatus();
  const vocalVolume = useVocalVolume();
  const sleepTimer = useSleepTimer();
  const currentTime = useTimingStore(s => s.currentTime);
  const duration = useTimingStore(s => s.duration);
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    currentTrack?.Id ?? '',
    currentTrack?.AlbumPrimaryImageTag,
    currentTrack?.AlbumId
  );

  const {
    pause,
    resume,
    next,
    previous,
    seek,
    setVolume,
    toggleShuffle,
    toggleRepeat,
    toggleKaraoke,
    refreshKaraokeStatus,
    setVocalVolume,
    setSleepTimer,
    clearSleepTimer,
  } = usePlayerStore();
  const { getImageUrl } = useImageUrl();

  useEffect(() => {
    if (playerError) {
      toast.add('error', `Playback error: ${playerError.message}`);
    }
  }, [playerError]);

  useEffect(() => {
    if (karaokeStatus?.state !== 'PROCESSING') return;

    const interval = setInterval(() => {
      void refreshKaraokeStatus();
    }, 3000);

    return () => clearInterval(interval);
  }, [karaokeStatus?.state, refreshKaraokeStatus]);

  useEffect(() => {
    if (!sleepTimer.endTime) {
      setSleepMinutesLeft(0);
      return;
    }

    const updateRemaining = () => {
      const remaining = Math.ceil((sleepTimer.endTime! - Date.now()) / 60000);
      setSleepMinutesLeft(Math.max(0, remaining));
    };

    updateRemaining();
    const interval = setInterval(updateRemaining, 10000);
    return () => clearInterval(interval);
  }, [sleepTimer.endTime]);

  if (!currentTrack) {
    return null;
  }

  const imageUrl = getTrackImageUrl(getImageUrl, {
    albumId: currentTrack.AlbumId,
    albumPrimaryImageTag: currentTrack.AlbumPrimaryImageTag,
    trackId: currentTrack.Id,
    maxWidth: 64,
    maxHeight: 64,
  });

  const artistName = currentTrack.Artists?.[0] ?? 'Unknown Artist';
  const artistId = currentTrack.ArtistItems?.[0]?.Id;
  const progress = duration > 0 ? (currentTime / duration) * 100 : 0;

  const handleSeekChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    seek(parseFloat(e.target.value));
  };

  return (
    <div
      data-testid="player-bar"
      className="z-player border-border bg-bg-secondary px-safe md:left-sidebar md:pb-safe fixed right-0 bottom-above-tab-bar left-0 border-t"
    >
      {/* Mobile only: track info above progress bar */}
      <div className="flex items-center gap-2 px-4 pt-2 md:hidden">
        {currentTrack.AlbumId ? (
          <Link to={`/albums/${currentTrack.AlbumId}`}>
            <img
              src={hasImageError ? getImagePlaceholder() : imageUrl}
              alt={currentTrack.Name}
              className="h-10 w-10 shrink-0 rounded-sm object-cover transition-opacity hover:opacity-80"
              onError={onImageError}
            />
          </Link>
        ) : (
          <img
            src={hasImageError ? getImagePlaceholder() : imageUrl}
            alt={currentTrack.Name}
            className="h-10 w-10 shrink-0 rounded-sm object-cover"
            onError={onImageError}
          />
        )}
        <div className="min-w-0 flex-1">
          {currentTrack.AlbumId ? (
            <Link
              to={`/albums/${currentTrack.AlbumId}`}
              className="text-text-primary block truncate font-medium hover:underline"
            >
              {currentTrack.Name}
            </Link>
          ) : (
            <p className="text-text-primary truncate font-medium">
              {currentTrack.Name}
            </p>
          )}
          {artistId ? (
            <Link
              to={`/artists/${artistId}`}
              className="text-text-secondary hover:text-text-primary block truncate text-sm hover:underline"
            >
              {artistName}
            </Link>
          ) : (
            <p className="text-text-secondary truncate text-sm">
              {artistName}
            </p>
          )}
        </div>
        <FavoriteButton
          itemId={currentTrack.Id}
          isFavorite={currentTrack.UserData?.IsFavorite ?? false}
        />
      </div>

      <input
        data-testid="seek-slider"
        type="range"
        min={0}
        max={duration || 1}
        step={0.1}
        value={currentTime}
        onChange={handleSeekChange}
        aria-label="Seek"
        aria-valuetext={formatTimeText(currentTime, duration)}
        className="bg-bg-tertiary accent-accent h-1 w-full cursor-pointer appearance-none"
        style={{
          background: `linear-gradient(to right, var(--color-accent) ${progress}%, var(--color-bg-tertiary) ${progress}%)`,
        }}
      />

      <div className="mx-auto flex max-w-7xl items-center gap-4 p-2 px-4">
        {/* Mobile: spacer to approximately center playback controls */}
        <div className="flex-1 md:hidden" aria-hidden="true" />

        {/* Desktop: track info */}
        <div className="hidden min-w-0 flex-1 items-center gap-2 md:flex">
          {currentTrack.AlbumId ? (
            <Link to={`/albums/${currentTrack.AlbumId}`}>
              <img
                src={hasImageError ? getImagePlaceholder() : imageUrl}
                alt={currentTrack.Name}
                className="h-12 w-12 shrink-0 rounded-sm object-cover transition-opacity hover:opacity-80"
                onError={onImageError}
              />
            </Link>
          ) : (
            <img
              src={hasImageError ? getImagePlaceholder() : imageUrl}
              alt={currentTrack.Name}
              className="h-12 w-12 shrink-0 rounded-sm object-cover"
              onError={onImageError}
            />
          )}
          <div className="min-w-0">
            {currentTrack.AlbumId ? (
              <Link
                to={`/albums/${currentTrack.AlbumId}`}
                data-testid="current-track-title"
                className="text-text-primary block truncate font-medium hover:underline"
              >
                {currentTrack.Name}
              </Link>
            ) : (
              <p
                data-testid="current-track-title"
                className="text-text-primary truncate font-medium"
              >
                {currentTrack.Name}
              </p>
            )}
            {artistId ? (
              <Link
                to={`/artists/${artistId}`}
                data-testid="current-track-artist"
                className="text-text-secondary hover:text-text-primary block truncate text-sm hover:underline"
              >
                {artistName}
              </Link>
            ) : (
              <p
                data-testid="current-track-artist"
                className="text-text-secondary truncate text-sm"
              >
                {artistName}
              </p>
            )}
          </div>
          <FavoriteButton
            itemId={currentTrack.Id}
            isFavorite={currentTrack.UserData?.IsFavorite ?? false}
          />
        </div>

        <PlaybackControls
          isPlaying={isPlaying}
          isShuffle={isShuffle}
          repeatMode={repeatMode}
          onPlayPause={() => (isPlaying ? pause() : resume())}
          onNext={() => next()}
          onPrevious={() => previous()}
          onToggleShuffle={toggleShuffle}
          onToggleRepeat={toggleRepeat}
        />

        <div className="flex flex-1 items-center justify-end gap-1 md:gap-2">
          <span className="text-text-tertiary hidden text-xs tabular-nums md:inline">
            <span data-testid="current-time">{formatSeconds(currentTime)}</span> /{' '}
            <span data-testid="total-time">{formatSeconds(duration)}</span>
          </span>

          <button
            type="button"
            onClick={() => void toggleKaraoke()}
            className={cn(
              'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
              isKaraokeMode || karaokeStatus?.state === 'PROCESSING'
                ? 'text-accent'
                : 'text-text-secondary hover:text-text-primary',
              (isKaraokeTransitioning || karaokeStatus?.state === 'PROCESSING') && 'animate-pulse'
            )}
            aria-label={isKaraokeMode ? 'Disable karaoke mode' : 'Enable karaoke mode'}
            aria-pressed={isKaraokeMode}
            title={
              karaokeStatus?.state === 'PROCESSING'
                ? 'Processing karaoke...'
                : isKaraokeMode
                  ? 'Karaoke mode on'
                  : 'Karaoke mode'
            }
          >
            {isKaraokeMode ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
          </button>

          {isKaraokeMode && (
            <div className="flex items-center gap-2 px-2">
              <span className="text-text-tertiary text-xs whitespace-nowrap">
                Vocal
              </span>
              <input
                type="range"
                min="0"
                max="100"
                value={vocalVolume * 100}
                onChange={(e) => void setVocalVolume(Number(e.target.value) / 100)}
                className="w-16 md:w-24 h-1 bg-surface-tertiary rounded-lg appearance-none cursor-pointer accent-accent"
                style={{
                  background: `linear-gradient(to right, rgb(var(--color-accent)) 0%, rgb(var(--color-accent)) ${vocalVolume * 100}%, rgb(var(--color-surface-tertiary)) ${vocalVolume * 100}%, rgb(var(--color-surface-tertiary)) 100%)`
                }}
                aria-label="Vocal volume"
                title={`Vocal volume: ${Math.round(vocalVolume * 100)}%`}
              />
              <span className="text-text-tertiary text-xs tabular-nums w-8">
                {Math.round(vocalVolume * 100)}%
              </span>
            </div>
          )}

          <button
            type="button"
            onClick={() => setShowLyricsView(true)}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Show lyrics"
            title="Show lyrics"
          >
            <AlignLeft className="h-4 w-4" />
          </button>

          <button
            type="button"
            onClick={() => setShowSleepModal(true)}
            className={cn(
              'focus-visible:ring-accent relative rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
              sleepTimer.endTime ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label="Sleep timer"
            title="Sleep timer"
            data-testid={PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON}
          >
            <Timer className="h-4 w-4" />
            {sleepTimer.endTime && sleepMinutesLeft > 0 && (
              <span className="bg-accent absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full text-[10px] text-black">
                {sleepMinutesLeft}
              </span>
            )}
          </button>

          <div className="hidden md:block">
            <VolumeControls volume={volume} onVolumeChange={setVolume} />
          </div>
        </div>
      </div>

      <SleepTimerModal
        isOpen={showSleepModal}
        onClose={() => setShowSleepModal(false)}
        currentMinutes={sleepTimer.minutes}
        hasActiveTimer={!!sleepTimer.endTime}
        onSetTimer={setSleepTimer}
        onClearTimer={clearSleepTimer}
      />

      {showLyricsView && <LyricsView onClose={() => setShowLyricsView(false)} />}
    </div>
  );
}
