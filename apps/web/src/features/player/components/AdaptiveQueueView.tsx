import { useState, useCallback } from 'react';
import { RefreshCw, Sparkles, Loader2, Music, Pause } from 'lucide-react';
import {
  ItemsService,
  type SessionState,
  type AdaptiveQueueTrack,
  type AudioItem,
} from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import {
  useActiveSession,
  useSessionQueue,
  useIsSessionRefreshing,
  useIsSessionStarting,
  useSessionActions,
} from '../stores/session-store';
import { usePlayerStore, useCurrentTrack, useIsPlaying } from '../stores/player.store';
import { useSignalEmitter } from '../hooks/useSignalEmitter';
import { SessionMoodSelector } from './SessionMoodSelector';

function formatDuration(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

const INTENT_COLORS: Record<string, string> = {
  energy: 'bg-orange-500/20 text-orange-400',
  calm: 'bg-blue-500/20 text-blue-400',
  transition: 'bg-purple-500/20 text-purple-400',
  peak: 'bg-red-500/20 text-red-400',
  anchor: 'bg-green-500/20 text-green-400',
  discovery: 'bg-yellow-500/20 text-yellow-400',
};

function IntentBadge({ label }: { label: string }) {
  const colorClass = INTENT_COLORS[label.toLowerCase()] ?? 'bg-bg-tertiary text-text-secondary';
  return (
    <span className={cn('rounded-full px-2 py-0.5 text-[10px] font-medium', colorClass)}>
      {label}
    </span>
  );
}

function EnergySparkline({ tracks }: { tracks: AdaptiveQueueTrack[] }) {
  const upcomingTracks = tracks.filter(t => t.status === 'QUEUED');
  if (upcomingTracks.length < 2) return null;

  const values = upcomingTracks.slice(0, 12).map(t => t.features?.energy ?? 0.5);

  const width = 120;
  const height = 24;
  const maxVal = Math.max(...values, 1);
  const step = width / (values.length - 1);

  const points = values.map((v, i) => `${i * step},${height - (v / maxVal) * height}`).join(' ');

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="h-6 w-full max-w-[120px]"
      preserveAspectRatio="none"
    >
      <polyline
        points={points}
        fill="none"
        stroke="var(--color-accent)"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

interface QueueTrackItemProps {
  track: AdaptiveQueueTrack;
  isCurrentTrack: boolean;
  isPlaying: boolean;
  isLoading: boolean;
  onClick: () => void;
}

function QueueTrackItem({
  track,
  isCurrentTrack,
  isPlaying,
  isLoading,
  onClick,
}: QueueTrackItemProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors',
        'focus-visible:ring-accent cursor-pointer focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none',
        isCurrentTrack ? 'bg-accent/10' : 'hover:bg-bg-hover'
      )}
    >
      <div className="group/icon flex h-8 w-8 shrink-0 items-center justify-center">
        {isLoading ? (
          <Loader2 className="text-accent h-4 w-4 animate-spin" />
        ) : isCurrentTrack && isPlaying ? (
          <Music className="text-accent h-4 w-4 animate-pulse" />
        ) : isCurrentTrack ? (
          <Pause className="text-accent h-4 w-4" />
        ) : (
          <span className="text-text-tertiary text-xs">{track.position}</span>
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span
            className={cn(
              'truncate text-sm font-medium',
              isCurrentTrack ? 'text-accent' : 'text-text-primary'
            )}
          >
            {track.name}
          </span>
          {track.intentLabel && <IntentBadge label={track.intentLabel} />}
        </div>
        <div className="text-text-secondary truncate text-xs">
          {track.artistName}
          {track.addedReason && <span className="text-text-tertiary"> — {track.addedReason}</span>}
        </div>
      </div>

      <span className="text-text-tertiary shrink-0 text-xs">
        {track.durationMs ? formatDuration(track.durationMs) : ''}
      </span>
    </button>
  );
}

export function AdaptiveQueueView() {
  const activeSession = useActiveSession();
  const queue = useSessionQueue();
  const isRefreshing = useIsSessionRefreshing();
  const isStarting = useIsSessionStarting();
  const { startSession, updateMood, refreshQueue, endSession, sendSignal } = useSessionActions();
  const [isMoodSelectorOpen, setMoodSelectorOpen] = useState(false);
  const [loadingTrackId, setLoadingTrackId] = useState<string | null>(null);
  useSignalEmitter();

  const client = useAuthStore(state => state.client);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const playTrack = usePlayerStore(state => state.playTrack);
  const pause = usePlayerStore(state => state.pause);
  const resume = usePlayerStore(state => state.resume);

  const handleTrackClick = useCallback(
    async (track: AdaptiveQueueTrack) => {
      const isCurrentlyPlaying = currentTrack?.Id === track.trackId;

      if (isCurrentlyPlaying) {
        if (isPlaying) {
          pause();
        } else {
          void resume();
        }
        return;
      }

      if (!client) return;

      setLoadingTrackId(track.trackId);
      try {
        const itemsService = new ItemsService(client);
        const audioItem = (await itemsService.getItem(track.trackId)) as AudioItem;
        await playTrack(audioItem);

        void sendSignal({
          signalType: 'QUEUE_JUMP',
          trackId: track.trackId,
          context: {
            positionPct: 0,
            elapsedSec: 0,
            autoplay: false,
            selectedByUser: true,
            timeOfDay:
              new Date().getHours() < 6
                ? 'night'
                : new Date().getHours() < 12
                  ? 'morning'
                  : new Date().getHours() < 18
                    ? 'afternoon'
                    : 'evening',
          },
        });
      } finally {
        setLoadingTrackId(null);
      }
    },
    [client, currentTrack, isPlaying, playTrack, pause, resume, sendSignal]
  );

  const handleStartSession = (state: SessionState) => {
    void startSession(state);
  };

  const handleUpdateMood = (state: SessionState) => {
    void updateMood(state);
  };

  if (!activeSession) {
    return (
      <div className="flex flex-col items-center gap-4 py-6">
        <Sparkles className="text-accent h-10 w-10" />
        <div className="text-center">
          <h1 className="text-text-primary mb-1 text-base font-semibold">Adaptive DJ</h1>
          <p className="text-text-secondary text-sm">
            Let the DJ curate your listening experience based on your mood.
          </p>
        </div>
        <button
          onClick={() => setMoodSelectorOpen(true)}
          disabled={isStarting}
          className="bg-accent text-text-on-accent hover:bg-accent-hover flex items-center gap-2 rounded-xl px-6 py-2.5 text-sm font-semibold transition-colors disabled:opacity-50"
        >
          {isStarting ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Sparkles className="h-4 w-4" />
          )}
          Start DJ Session
        </button>

        <SessionMoodSelector
          isOpen={isMoodSelectorOpen}
          onClose={() => setMoodSelectorOpen(false)}
          onSubmit={handleStartSession}
        />
      </div>
    );
  }

  const upcomingTracks = queue.filter(t => t.status === 'QUEUED' || t.status === 'PLAYING');

  return (
    <div className="flex flex-col">
      <div className="border-border flex items-center justify-between border-b px-4 py-3">
        <div className="flex items-center gap-2">
          <Sparkles className="text-accent h-4 w-4" />
          <h1 className="text-text-primary text-sm font-semibold">DJ Queue</h1>
          {activeSession.state?.moodTags?.length > 0 && (
            <div className="flex gap-1">
              {activeSession.state.moodTags.map(tag => (
                <span
                  key={tag}
                  className="bg-accent/15 text-accent rounded-full px-2 py-0.5 text-[10px] font-medium capitalize"
                >
                  {tag}
                </span>
              ))}
            </div>
          )}
        </div>
        <div className="flex items-center gap-1">
          <EnergySparkline tracks={queue} />
          <button
            onClick={() => void refreshQueue()}
            disabled={isRefreshing}
            className="text-text-secondary hover:text-text-primary rounded-lg p-1.5 transition-colors disabled:opacity-50"
            aria-label="Refresh queue"
          >
            <RefreshCw className={cn('h-4 w-4', isRefreshing && 'animate-spin')} />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-1 py-1">
        {upcomingTracks.length === 0 && !isRefreshing && (
          <div className="text-text-tertiary py-8 text-center text-sm">Queue is empty.</div>
        )}
        {isRefreshing && upcomingTracks.length === 0 && (
          <div className="flex items-center justify-center gap-2 py-8">
            <Loader2 className="text-accent h-5 w-5 animate-spin" />
            <span className="text-text-secondary text-sm">DJ is thinking...</span>
          </div>
        )}
        {upcomingTracks.map(track => (
          <QueueTrackItem
            key={`${track.trackId}-${track.position}`}
            track={track}
            isCurrentTrack={currentTrack?.Id === track.trackId}
            isPlaying={isPlaying && currentTrack?.Id === track.trackId}
            isLoading={loadingTrackId === track.trackId}
            onClick={() => void handleTrackClick(track)}
          />
        ))}
      </div>

      <div className="border-border flex gap-2 border-t px-4 py-3">
        <button
          onClick={() => setMoodSelectorOpen(true)}
          className="bg-bg-tertiary text-text-secondary hover:bg-bg-hover flex-1 rounded-lg py-2 text-xs font-medium transition-colors"
        >
          Change Mood
        </button>
        <button
          onClick={() => void endSession()}
          className="bg-error/10 text-error hover:bg-error/20 rounded-lg px-4 py-2 text-xs font-medium transition-colors"
        >
          End Session
        </button>
      </div>

      <SessionMoodSelector
        isOpen={isMoodSelectorOpen}
        onClose={() => setMoodSelectorOpen(false)}
        onSubmit={handleUpdateMood}
        initialState={activeSession.state}
      />
    </div>
  );
}
