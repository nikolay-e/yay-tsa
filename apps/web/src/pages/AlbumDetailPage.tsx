import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Pause, Play, Shuffle } from 'lucide-react';
import { ItemsService, type MusicAlbum } from '@yay-tsa/core';
import { FavoriteButton } from '@/features/library/components/FavoriteButton';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useAlbumTracks } from '@/features/library/hooks';
import { TrackList } from '@/features/library/components';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { NotFound } from '@/shared/ui/NotFound';
import { BackLink } from '@/shared/ui/BackLink';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { cn } from '@/shared/utils/cn';

export function AlbumDetailPage() {
  const { id } = useParams<{ id: string }>();
  const client = useAuthStore(state => state.client);
  const { getImageUrl } = useImageUrl();

  const playAlbum = usePlayerStore(state => state.playAlbum);
  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const setShuffle = usePlayerStore(state => state.setShuffle);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const { data: album, isLoading: albumLoading } = useQuery({
    queryKey: ['album', id],
    queryFn: async () => {
      if (!client || !id) throw new Error('Not authenticated or missing ID');
      const itemsService = new ItemsService(client);
      return itemsService.getItem(id) as Promise<MusicAlbum>;
    },
    enabled: !!client && !!id,
  });

  const { data: tracks = [], isLoading: tracksLoading } = useAlbumTracks(id);

  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    album?.Id ?? '',
    album?.ImageTags?.Primary
  );

  const isLoading = albumLoading || tracksLoading;

  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (!album) {
    return <NotFound message="Album not found" />;
  }

  const imageUrl = album.ImageTags?.Primary
    ? getImageUrl(album.Id, 'Primary', {
        maxWidth: 400,
        maxHeight: 400,
        tag: album.ImageTags.Primary,
      })
    : getImagePlaceholder();

  const artistName = album.Artists?.[0] ?? 'Unknown Artist';
  const year = album.ProductionYear;
  const trackCount = tracks.length;
  const isIncomplete = album.IsComplete === false && (album.TotalTracks ?? 0) > 0;

  return (
    <div className="space-y-6 p-6">
      <BackLink to="/albums" label="Back to Albums" data-testid="album-back-button" />

      <div className="flex flex-col gap-6 sm:flex-row">
        <div className="shrink-0">
          <img
            src={hasImageError ? getImagePlaceholder() : imageUrl}
            alt={album.Name}
            className="h-48 w-48 rounded-md object-cover shadow-lg sm:h-56 sm:w-56"
            onError={onImageError}
          />
        </div>

        <div className="flex flex-col justify-end space-y-4">
          <div>
            <h1 data-testid="album-detail-title" className="text-text-primary text-3xl font-bold">
              {album.Name}
            </h1>
            <p className="text-text-secondary text-lg">{artistName}</p>
            <p className="text-text-tertiary text-sm">
              {year && `${year} • `}
              {trackCount} {trackCount === 1 ? 'track' : 'tracks'}
            </p>
            {isIncomplete && (
              <div className="mt-1 inline-flex items-center gap-1.5 rounded-full bg-amber-500/15 px-2.5 py-1 text-xs font-medium text-amber-400">
                <span className="h-1.5 w-1.5 rounded-full bg-amber-400" />
                Альбом не завершён — загружено {trackCount} из {album.TotalTracks} треков
              </div>
            )}
          </div>

          <div className="flex items-center gap-2">
            <button
              data-testid="album-play-button"
              onClick={() => {
                if (isPlaying && currentTrack?.AlbumId === id) {
                  pause();
                } else if (id) {
                  void playAlbum(id);
                }
              }}
              className={cn(
                'flex items-center gap-2 px-6 py-2',
                'bg-accent rounded-full text-black',
                'hover:bg-accent-hover transition-colors'
              )}
            >
              {isPlaying && currentTrack?.AlbumId === id ? (
                <>
                  <Pause className="h-5 w-5" fill="currentColor" />
                  Pause
                </>
              ) : (
                <>
                  <Play className="h-5 w-5" fill="currentColor" />
                  Play
                </>
              )}
            </button>
            <button
              data-testid="album-shuffle-button"
              onClick={() => {
                setShuffle(true);
                if (id) void playAlbum(id);
              }}
              className={cn(
                'flex items-center gap-2 px-6 py-2',
                'bg-bg-secondary text-text-primary rounded-full',
                'hover:bg-bg-tertiary transition-colors'
              )}
            >
              <Shuffle className="h-5 w-5" />
              Shuffle
            </button>
            <div data-testid="album-favorite-button">
              <FavoriteButton
                itemId={album.Id}
                isFavorite={album.UserData?.IsFavorite ?? false}
                size="md"
              />
            </div>
          </div>
        </div>
      </div>

      <TrackList
        tracks={tracks}
        showArtist={false}
        showImage={false}
        currentTrackId={currentTrack?.Id}
        isPlaying={isPlaying}
        onPlayTrack={(_, index) => void playTracks(tracks, index)}
        onPauseTrack={pause}
      />
    </div>
  );
}
