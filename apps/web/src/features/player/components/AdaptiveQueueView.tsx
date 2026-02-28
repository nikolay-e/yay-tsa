import { useState } from 'react';
import { RefreshCw, Sparkles, Loader2, Music } from 'lucide-react';
import type { SessionState, AdaptiveQueueTrack } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import {
  useActiveSession,
  useSessionQueue,
  useIsSessionRefreshing,
  useIsSessionStarting,
  useSessionActions,
} from '../stores/session-store';
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

function QueueTrackItem({ track }: { track: AdaptiveQueueTrack }) {
  return (
    <div
      className={cn(
        'flex items-center gap-3 rounded-lg px-3 py-2.5 transition-colors',
        track.status === 'PLAYING' ? 'bg-accent/10' : 'hover:bg-bg-hover'
      )}
    >
      <div className="flex h-8 w-8 shrink-0 items-center justify-center">
        {track.status === 'PLAYING' ? (
          <Music className="text-accent h-4 w-4 animate-pulse" />
        ) : (
          <span className="text-text-tertiary text-xs">{track.position}</span>
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span
            className={cn(
              'truncate text-sm font-medium',
              track.status === 'PLAYING' ? 'text-accent' : 'text-text-primary'
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
    </div>
  );
}

export function AdaptiveQueueView() {
  const activeSession = useActiveSession();
  const queue = useSessionQueue();
  const isRefreshing = useIsSessionRefreshing();
  const isStarting = useIsSessionStarting();
  const { startSession, updateMood, refreshQueue, endSession } = useSessionActions();
  const [isMoodSelectorOpen, setMoodSelectorOpen] = useState(false);
  useSignalEmitter();

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
          <QueueTrackItem key={`${track.trackId}-${track.position}`} track={track} />
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
