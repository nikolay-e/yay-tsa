import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Mic, MicOff, Timer, AlignLeft } from 'lucide-react';
import { FavoriteButton } from '@/features/library/components/FavoriteButton';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { getTrackImageUrl } from '@/shared/utils/track-image';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { PLAYER_TEST_IDS } from '@/shared/testing/test-ids';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
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
  useSleepTimer,
} from '../stores/player.store';
import { useAlbumColors } from '../hooks/useAlbumColors';
import { SeekBar, TimeDisplay } from './SeekBar';
import { LyricsView } from './LyricsView';
import { SleepTimerModal } from './SleepTimerModal';
import { VolumeControls } from './VolumeControls';
import { PlaybackControls } from './PlaybackControls';

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
  const sleepTimer = useSleepTimer();
  useAlbumColors();
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    currentTrack?.Id ?? '',
    currentTrack?.AlbumPrimaryImageTag,
    currentTrack?.AlbumId
  );

  const { getImageUrl } = useImageUrl();

  const handleSeek = useCallback((seconds: number) => usePlayerStore.getState().seek(seconds), []);
  const handleSetVolume = useCallback((v: number) => usePlayerStore.getState().setVolume(v), []);
  const handleToggleShuffle = useCallback(() => usePlayerStore.getState().toggleShuffle(), []);
  const handleToggleRepeat = useCallback(() => usePlayerStore.getState().toggleRepeat(), []);
  const handlePlayPause = useCallback(
    () =>
      usePlayerStore.getState().isPlaying
        ? usePlayerStore.getState().pause()
        : void usePlayerStore.getState().resume(),
    []
  );
  const handleNext = useCallback(() => void usePlayerStore.getState().next(), []);
  const handlePrevious = useCallback(() => void usePlayerStore.getState().previous(), []);
  const handleToggleKaraoke = useCallback(() => void usePlayerStore.getState().toggleKaraoke(), []);
  const handleSetSleepTimer = useCallback(
    (m: number) => usePlayerStore.getState().setSleepTimer(m),
    []
  );
  const handleClearSleepTimer = useCallback(() => usePlayerStore.getState().clearSleepTimer(), []);

  useEffect(() => {
    if (playerError) {
      toast.add('error', `Playback error: ${playerError.message}`);
    }
  }, [playerError]);

  useEffect(() => {
    if (karaokeStatus?.state !== 'PROCESSING') return;

    const interval = setInterval(() => {
      void usePlayerStore.getState().refreshKaraokeStatus();
    }, 3000);

    return () => clearInterval(interval);
  }, [karaokeStatus?.state]);

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

  return (
    <div
      data-testid="player-bar"
      className="z-player border-border bg-bg-secondary pb-safe px-safe md:left-sidebar fixed right-0 bottom-0 left-0 border-t"
    >
      <SeekBar onSeek={handleSeek} />

      <div className="mx-auto flex max-w-7xl items-center gap-4 p-2 px-4">
        <div className="flex min-w-0 flex-1 items-center gap-2">
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
          onPlayPause={handlePlayPause}
          onNext={handleNext}
          onPrevious={handlePrevious}
          onToggleShuffle={handleToggleShuffle}
          onToggleRepeat={handleToggleRepeat}
        />

        <div className="flex shrink-0 items-center justify-end gap-1 md:flex-1 md:gap-2">
          <TimeDisplay />

          <button
            type="button"
            onClick={handleToggleKaraoke}
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
              <span className="bg-accent text-text-on-accent absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full text-[10px]">
                {sleepMinutesLeft}
              </span>
            )}
          </button>

          <div className="hidden md:block">
            <VolumeControls volume={volume} onVolumeChange={handleSetVolume} />
          </div>
        </div>
      </div>

      <SleepTimerModal
        isOpen={showSleepModal}
        onClose={() => setShowSleepModal(false)}
        currentMinutes={sleepTimer.minutes}
        hasActiveTimer={!!sleepTimer.endTime}
        onSetTimer={handleSetSleepTimer}
        onClearTimer={handleClearSleepTimer}
      />

      {showLyricsView && <LyricsView onClose={() => setShowLyricsView(false)} />}
    </div>
  );
}
