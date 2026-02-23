import { useState, useEffect, useRef, useCallback } from 'react';
import { Radio, Play, Pause, SlidersHorizontal, RefreshCw } from 'lucide-react';
import { type AudioItem, type RadioFilters } from '@yay-tsa/core';
import { useMyWave, useRadioFilters, useRefreshRadio } from '../hooks/useRadio';
import { usePlayerStore, useCurrentTrack, useIsPlaying } from '@/features/player/stores/player.store';
import { RadioFilterPanel } from './RadioFilterPanel';

function trackIdsKey(tracks: AudioItem[] | undefined): string {
  if (!tracks || tracks.length === 0) return '';
  return tracks.map(t => t.Id).join(',');
}

export function MyWaveCard() {
  const [filters, setFilters] = useState<RadioFilters>({});
  const [showFilters, setShowFilters] = useState(false);
  const [radioActive, setRadioActive] = useState(false);
  const { data: tracks, isLoading, isError, isFetching } = useMyWave(filters, 20);
  const { data: availableFilters } = useRadioFilters();
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const refreshRadio = useRefreshRadio();

  // Track previous track IDs to detect actual data changes vs background refetches
  const prevTrackIdsRef = useRef('');

  const isRadioPlaying = isPlaying && radioActive &&
    tracks?.some((t: AudioItem) => t.Id === currentTrack?.Id);
  const hasFilteredTracks = tracks && tracks.length > 0;
  const hasActiveFilters = filters.mood || filters.language || filters.minEnergy || filters.maxEnergy;
  const hasAnalyzedTracks = availableFilters?.moods && availableFilters.moods.length > 0;

  // When user plays something else (not in radio tracks), deactivate radio mode
  useEffect(() => {
    if (!radioActive || !isPlaying || !currentTrack || !tracks) return;
    const inRadio = tracks.some((t: AudioItem) => t.Id === currentTrack.Id);
    if (!inRadio) {
      setRadioActive(false);
    }
  }, [currentTrack, isPlaying, tracks, radioActive]);

  // Auto-update playback when filters change and radio is active
  // Only fires when track IDs actually change (not on background refetch)
  useEffect(() => {
    if (!tracks || tracks.length === 0 || isLoading) return;
    const newKey = trackIdsKey(tracks);
    if (newKey === prevTrackIdsRef.current) return;
    prevTrackIdsRef.current = newKey;

    if (radioActive) {
      void playTracks(tracks);
    }
  }, [tracks, isLoading, playTracks, radioActive]);

  const handlePlay = useCallback(() => {
    if (isRadioPlaying) {
      pause();
      setRadioActive(false);
    } else if (tracks && tracks.length > 0) {
      setRadioActive(true);
      prevTrackIdsRef.current = trackIdsKey(tracks);
      void playTracks(tracks);
    }
  }, [isRadioPlaying, tracks, pause, playTracks]);

  // Don't show the card at all if no tracks have been analyzed
  if (!hasAnalyzedTracks && !hasFilteredTracks && !isLoading) return null;

  return (
    <section className="mb-8">
      <div className="from-accent/20 via-accent/10 to-bg-secondary overflow-hidden rounded-xl bg-gradient-to-br p-5">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="bg-accent/20 flex h-10 w-10 items-center justify-center rounded-full">
              <Radio className="text-accent h-5 w-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">My Wave</h2>
              <p className="text-text-secondary text-xs">
                {isLoading
                  ? 'Loading...'
                  : isError
                    ? 'Failed to load'
                    : hasFilteredTracks
                      ? `${tracks.length} tracks selected for you`
                      : hasActiveFilters
                        ? 'No tracks match filters'
                        : '0 tracks'}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => void refreshRadio()}
              className="hover:bg-bg-hover rounded-full p-2 transition-colors"
              aria-label="Refresh recommendations"
            >
              <RefreshCw className={`text-text-secondary h-4 w-4 ${isFetching ? 'animate-spin' : ''}`} />
            </button>
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`rounded-full p-2 transition-colors ${
                showFilters ? 'bg-accent/20 text-accent' : 'hover:bg-bg-hover text-text-secondary'
              }`}
              aria-label={showFilters ? 'Hide filters' : 'Show filters'}
            >
              <SlidersHorizontal className="h-4 w-4" />
            </button>
            <button
              onClick={handlePlay}
              disabled={isLoading || !hasFilteredTracks}
              className="bg-accent text-text-on-accent hover:bg-accent-hover flex h-10 w-10 items-center justify-center rounded-full shadow-lg transition-all disabled:opacity-50"
              aria-label={isRadioPlaying ? 'Pause My Wave' : 'Play My Wave'}
            >
              {isRadioPlaying ? (
                <Pause className="h-5 w-5" fill="currentColor" />
              ) : (
                <Play className="ml-0.5 h-5 w-5" fill="currentColor" />
              )}
            </button>
          </div>
        </div>

        {showFilters && (
          <RadioFilterPanel filters={filters} onChange={setFilters} />
        )}
      </div>
    </section>
  );
}
