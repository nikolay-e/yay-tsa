import { useMemo } from 'react';
import { Download } from 'lucide-react';
import { type AudioItem } from '@yay-tsa/core';
import { TrackList } from '@/features/library/components';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { useOfflineStore } from '@/features/offline/stores/offline.store';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';

export function OfflineLibraryPage() {
  const initialized = useOfflineStore(state => state.initialized);
  const items = useOfflineStore(state => state.items);
  const entries = useOfflineStore(state => state.entries);
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  // Only fully-downloaded tracks are playable offline. Sorted by name for a
  // stable, browsable list that renders entirely from local storage.
  const tracks = useMemo<AudioItem[]>(() => {
    return Object.values(items)
      .filter(item => entries[item.Id]?.status === 'ready')
      .sort((a, b) => a.Name.localeCompare(b.Name));
  }, [items, entries]);

  const handlePlayTrack = (_: unknown, index: number) => {
    playTracks(tracks, index);
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-center gap-3">
        <Download className="text-accent h-6 w-6" />
        <div>
          <h1 className="text-text-primary text-2xl font-bold">Downloads</h1>
          <p className="text-text-secondary text-sm">
            {tracks.length} {tracks.length === 1 ? 'track' : 'tracks'} available offline
          </p>
        </div>
      </div>

      {!initialized ? (
        <LoadingSpinner className="h-48" />
      ) : tracks.length === 0 ? (
        <div className="flex h-48 flex-col items-center justify-center gap-2 text-center">
          <p className="text-text-secondary">No downloads yet.</p>
          <p className="text-text-tertiary text-sm">
            Tap the download icon on any track or album to make it available offline.
          </p>
        </div>
      ) : (
        <TrackList
          tracks={tracks}
          virtualized
          showArtist
          showAlbum
          currentTrackId={currentTrack?.Id}
          isPlaying={isPlaying}
          onPlayTrack={handlePlayTrack}
          onPauseTrack={pause}
        />
      )}
    </div>
  );
}
