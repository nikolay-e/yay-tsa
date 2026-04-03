import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Loader2, Music, Play, ThumbsUp, ThumbsDown } from 'lucide-react';
import type { AudioItem } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { useInView } from '@/shared/hooks/useInView';
import { formatTicks, currentTimeOfDay } from '@/shared/utils/time';
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
  useSessionError,
  getSavedSessionId,
} from '../stores/session-store';

function DjFeedbackButtons({
  trackId,
  onThumbsDown,
}: Readonly<{
  trackId: string;
  onThumbsDown: (trackId: string) => void;
}>) {
  const [liked, setLiked] = useState(false);

  const sendFeedback = (type: 'THUMBS_UP' | 'THUMBS_DOWN') => {
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
          timeOfDay: currentTimeOfDay(),
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
    <div
      className={cn(
        'flex w-full items-center rounded-lg px-3 py-2 transition-colors',
        isCurrent ? 'bg-accent/10' : 'hover:bg-bg-hover'
      )}
    >
      <button
        type="button"
        onClick={onPlay}
        className={cn(
          'flex min-w-0 flex-1 cursor-pointer items-center gap-3 text-left',
          'focus-visible:ring-accent rounded focus-visible:ring-2 focus-visible:outline-none'
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
      </button>

      {feedbackSlot}

      {track.RunTimeTicks && (
        <span className="text-text-tertiary shrink-0 pl-3 text-xs">
          {formatTicks(track.RunTimeTicks)}
        </span>
      )}
    </div>
  );
}

function SessionExpiredBanner() {
  return (
    <div className="mx-2 mb-2 flex items-center gap-3 rounded-lg bg-yellow-500/10 px-4 py-3">
      <AlertTriangle className="h-4 w-4 shrink-0 text-yellow-500" />
      <div className="min-w-0 flex-1">
        <p className="text-text-primary text-sm font-medium">DJ session ended</p>
        <p className="text-text-secondary text-xs">
          Start a new session to keep getting recommendations
        </p>
      </div>
      <button
        type="button"
        onClick={() => useSessionStore.getState().startSession()}
        className="bg-accent text-text-on-accent hover:bg-accent-hover shrink-0 rounded-md px-3 py-1.5 text-xs font-medium transition-colors"
      >
        Restart
      </button>
    </div>
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
  const sessionError = useSessionError();
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

  const isSessionExpired = !activeSession && sessionError != null;

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
        {isSessionExpired && <SessionExpiredBanner />}
        <p className="text-text-tertiary text-sm">No tracks in queue</p>
        {!activeSession && !isSessionExpired && (
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
      {isSessionExpired && <SessionExpiredBanner />}
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
