import { useEffect, useRef } from 'react';
import { Loader2, Music, Play } from 'lucide-react';
import type { AudioItem } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { useInView } from '@/shared/hooks/useInView';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
  useQueueItems,
  useQueueIndex,
} from '../stores/player.store';
import {
  useSessionStore,
  useActiveSession,
  useIsSessionStarting,
  useIsSessionRefreshing,
  getSavedSessionId,
} from '../stores/session-store';

const SCROLL_LOAD_THRESHOLD = 10;

function formatDuration(ticks: number): string {
  const totalSeconds = Math.floor(ticks / 10_000_000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

function QueueTrackItem({
  track,
  index,
  isCurrent,
  isPlaying,
  onPlay,
}: {
  track: AudioItem;
  index: number;
  isCurrent: boolean;
  isPlaying: boolean;
  onPlay: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onPlay}
      className={cn(
        'flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left transition-colors',
        'focus-visible:ring-accent cursor-pointer focus-visible:ring-2 focus-visible:outline-none',
        isCurrent ? 'bg-accent/10' : 'hover:bg-bg-hover'
      )}
    >
      <div className="flex h-7 w-7 shrink-0 items-center justify-center">
        {isCurrent && isPlaying ? (
          <Music className="text-accent h-3.5 w-3.5 animate-pulse" />
        ) : isCurrent ? (
          <Play className="text-accent h-3.5 w-3.5" />
        ) : (
          <span className="text-text-tertiary text-xs">{index + 1}</span>
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div
          className={cn(
            'truncate text-sm',
            isCurrent ? 'text-accent font-medium' : 'text-text-primary'
          )}
        >
          {track.Name}
        </div>
        <div className="text-text-secondary truncate text-xs">
          {track.Artists?.join(', ') ?? 'Unknown Artist'}
        </div>
      </div>

      {track.RunTimeTicks && (
        <span className="text-text-tertiary shrink-0 text-xs">
          {formatDuration(track.RunTimeTicks)}
        </span>
      )}
    </button>
  );
}

export function QueueView() {
  const queueItems = useQueueItems();
  const queueIndex = useQueueIndex();
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const jumpToQueueTrack = usePlayerStore(state => state.jumpToQueueTrack);
  const pause = usePlayerStore(state => state.pause);
  const resume = usePlayerStore(state => state.resume);
  const activeSession = useActiveSession();
  const isStarting = useIsSessionStarting();
  const isRefreshing = useIsSessionRefreshing();
  const initAttemptedRef = useRef(false);

  useEffect(() => {
    if (activeSession || isStarting) return;
    if (initAttemptedRef.current) return;
    initAttemptedRef.current = true;
    if (getSavedSessionId()) {
      void useSessionStore.getState().restoreSession();
    } else if (queueItems.length === 0) {
      void useSessionStore.getState().startSession();
    }
  }, [activeSession, isStarting, queueItems.length]);

  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    listRef.current?.scrollTo({ top: 0 });
  }, [currentTrack?.Id]);

  const canLoadMore = activeSession != null && !isRefreshing;
  const { ref: sentinelRef, isInView } = useInView({
    rootMargin: '200px',
    enabled: canLoadMore,
  });

  useEffect(() => {
    if (!isInView || !canLoadMore) return;
    const baseIndex = queueIndex >= 0 ? queueIndex : queueItems.length;
    const remaining = queueItems.length - baseIndex - 1;
    if (remaining < SCROLL_LOAD_THRESHOLD) {
      void useSessionStore.getState().refreshQueue();
    }
  }, [isInView, canLoadMore, queueItems.length, queueIndex]);

  if (queueItems.length === 0) {
    return (
      <div className="flex flex-col items-center gap-3 py-8">
        <p className="text-text-tertiary text-sm">No tracks in queue</p>
      </div>
    );
  }

  const handleTrackClick = (track: AudioItem) => {
    if (currentTrack?.Id === track.Id) {
      if (isPlaying) pause();
      else void resume();
      return;
    }
    void jumpToQueueTrack(track.Id);
  };

  return (
    <div className="flex flex-col">
      <div ref={listRef} className="flex-1 overflow-y-auto px-1 py-1">
        {queueItems.map((track, index) => (
          <QueueTrackItem
            key={`${track.Id}-${index}`}
            track={track}
            index={index}
            isCurrent={index === queueIndex}
            isPlaying={isPlaying && index === queueIndex}
            onPlay={() => handleTrackClick(track)}
          />
        ))}
        <div ref={sentinelRef} className="h-1" />
        {isRefreshing && (
          <div className="flex items-center justify-center py-4">
            <Loader2 className="text-text-tertiary h-5 w-5 animate-spin" />
          </div>
        )}
      </div>
    </div>
  );
}
