import { useState } from 'react';
import { Radio, Play, Pause, SlidersHorizontal, RefreshCw } from 'lucide-react';
import { type AudioItem, type RadioFilters } from '@yay-tsa/core';
import { useMyWave, useRefreshRadio } from '../hooks/useRadio';
import { usePlayerStore, useCurrentTrack, useIsPlaying } from '@/features/player/stores/player.store';
import { RadioFilterPanel } from './RadioFilterPanel';

export function MyWaveCard() {
  const [filters, setFilters] = useState<RadioFilters>({});
  const [showFilters, setShowFilters] = useState(false);
  const { data: tracks, isLoading } = useMyWave(filters, 20);
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const resume = usePlayerStore(state => state.resume);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const refreshRadio = useRefreshRadio();

  const isRadioPlaying = isPlaying && tracks?.some((t: AudioItem) => t.Id === currentTrack?.Id);
  const hasAnalyzedTracks = tracks && tracks.length > 0;

  const handlePlay = () => {
    if (isRadioPlaying) {
      pause();
    } else if (tracks && tracks.length > 0) {
      void playTracks(tracks);
    }
  };

  const handleRefresh = () => {
    refreshRadio();
  };

  if (!hasAnalyzedTracks && !isLoading) return null;

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
                {tracks?.length ?? 0} tracks selected for you
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleRefresh}
              className="hover:bg-bg-hover rounded-full p-2 transition-colors"
              title="Refresh recommendations"
            >
              <RefreshCw className="text-text-secondary h-4 w-4" />
            </button>
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`rounded-full p-2 transition-colors ${
                showFilters ? 'bg-accent/20 text-accent' : 'hover:bg-bg-hover text-text-secondary'
              }`}
              title="Filters"
            >
              <SlidersHorizontal className="h-4 w-4" />
            </button>
            <button
              onClick={handlePlay}
              disabled={isLoading || !hasAnalyzedTracks}
              className="bg-accent text-text-on-accent hover:bg-accent-hover flex h-10 w-10 items-center justify-center rounded-full shadow-lg transition-all disabled:opacity-50"
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

        {isLoading && (
          <div className="text-text-secondary py-2 text-center text-sm">
            Loading recommendations...
          </div>
        )}
      </div>
    </section>
  );
}
