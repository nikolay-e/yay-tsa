import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import { Loader2, Music, Play, ThumbsUp, ThumbsDown } from 'lucide-react';
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

function formatDuration(ticks: number): string {
  const totalSeconds = Math.floor(ticks / 10_000_000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

function DjFeedbackButtons({
  trackId,
  onThumbsDown,
}: Readonly<{
  trackId: string;
  onThumbsDown: (trackId: string) => void;
}>) {
  const [liked, setLiked] = useState(false);

  const sendFeedback = (type: 'THUMBS_UP' | 'THUMBS_DOWN') => {
    const hour = new Date().getHours();
    let timeOfDay: string;
    if (hour < 6) timeOfDay = 'night';
    else if (hour < 12) timeOfDay = 'morning';
    else if (hour < 18) timeOfDay = 'afternoon';
    else timeOfDay = 'evening';
    useSessionStore
      .getState()
      .sendSignal({
        signalType: type,
        trackId,
        context: {
          positionPct: 0,
          elapsedSec: 0,
          autoplay: false,
          selectedByUser: true,
          timeOfDay,
        },
      })
      .catch(() => {});
  };

  return (
    <div className="flex shrink-0 items-center gap-0.5">
      <button
        type="button"
        aria-label="Good pick"
        aria-pressed={liked}
        onClick={e => {
          e.stopPropagation();
          if (liked) return;
          setLiked(true);
          sendFeedback('THUMBS_UP');
        }}
        className={cn(
          'cursor-pointer rounded p-1.5 transition-colors',
          'focus-visible:ring-accent focus-visible:ring-2 focus-visible:outline-none',
          liked ? 'text-accent' : 'text-text-tertiary hover:text-text-primary'
        )}
      >
        <ThumbsUp className="h-3.5 w-3.5" fill={liked ? 'currentColor' : 'none'} />
      </button>
      <button
        type="button"
        aria-label="Bad pick"
        onClick={e => {
          e.stopPropagation();
          sendFeedback('THUMBS_DOWN');
          onThumbsDown(trackId);
        }}
        className={cn(
          'text-text-tertiary cursor-pointer rounded p-1.5 transition-colors hover:text-red-400',
          'focus-visible:ring-accent focus-visible:ring-2 focus-visible:outline-none'
        )}
      >
        <ThumbsDown className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}

function QueueTrackItem({
  track,
  index,
  isCurrent,
  isPlaying,
  onPlay,
  feedbackSlot,
}: Readonly<{
  track: AudioItem;
  index: number;
  isCurrent: boolean;
  isPlaying: boolean;
  onPlay: () => void;
  feedbackSlot?: ReactNode;
}>) {
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
        {(() => {
          if (isCurrent && isPlaying)
            return <Music className="text-accent h-3.5 w-3.5 animate-pulse" />;
          if (isCurrent) return <Play className="text-accent h-3.5 w-3.5" />;
          return <span className="text-text-tertiary text-xs">{index + 1}</span>;
        })()}
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

      {feedbackSlot}

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
  const next = usePlayerStore(state => state.next);
  const activeSession = useActiveSession();
  const isStarting = useIsSessionStarting();
  const isRefreshing = useIsSessionRefreshing();
  const initAttemptedRef = useRef(false);

  useEffect(() => {
    if (activeSession || isStarting) return;
    if (initAttemptedRef.current) return;
    initAttemptedRef.current = true;
    if (getSavedSessionId()) {
      useSessionStore.getState().restoreSession();
    } else if (queueItems.length === 0) {
      useSessionStore.getState().startSession();
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
    useSessionStore.getState().refreshQueue();
  }, [isInView, canLoadMore]);

  if (queueItems.length === 0) {
    if (isStarting) {
      return (
        <div className="flex flex-col items-center gap-3 py-8">
          <Loader2 className="text-accent h-6 w-6 animate-spin" />
          <p className="text-text-tertiary text-sm">Starting DJ mode…</p>
        </div>
      );
    }
    return (
      <div className="flex flex-col items-center gap-3 py-8">
        <p className="text-text-tertiary text-sm">No tracks in queue</p>
        {!activeSession && (
          <button
            type="button"
            onClick={() => useSessionStore.getState().startSession()}
            className="bg-accent text-text-on-accent hover:bg-accent-hover rounded-lg px-4 py-2 text-sm font-medium transition-colors"
          >
            Start DJ Mode
          </button>
        )}
      </div>
    );
  }

  const handleTrackClick = (track: AudioItem) => {
    if (currentTrack?.Id === track.Id) {
      if (isPlaying) pause();
      else resume();
      return;
    }
    jumpToQueueTrack(track.Id);
  };

  const handleThumbsDown = (trackId: string) => {
    if (trackId === currentTrack?.Id) {
      next();
    }
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
            feedbackSlot={
              activeSession ? (
                <DjFeedbackButtons trackId={track.Id} onThumbsDown={handleThumbsDown} />
              ) : undefined
            }
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
