import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Play, Shuffle, Loader2 } from 'lucide-react';
import { ItemsService, type MusicAlbum } from '@yaytsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useAlbumTracks } from '@/features/library/hooks';
import { TrackList } from '@/features/library/components';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { cn } from '@/shared/utils/cn';

export function AlbumDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [hasImageError, setHasImageError] = useState(false);

  useEffect(() => {
    setHasImageError(false);
  }, [id]);
  const client = useAuthStore(state => state.client);
  const { getImageUrl } = useImageUrl();

  const playAlbum = usePlayerStore(state => state.playAlbum);
  const playTracks = usePlayerStore(state => state.playTracks);
  const toggleShuffle = usePlayerStore(state => state.toggleShuffle);
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

  const isLoading = albumLoading || tracksLoading;

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Loader2 className="text-accent h-8 w-8 animate-spin" />
      </div>
    );
  }

  if (!album) {
    return (
      <div className="p-6">
        <p className="text-text-secondary">Album not found</p>
      </div>
    );
  }

  const imageUrl = album.ImageTags?.Primary
    ? getImageUrl(album.Id, 'Primary', {
        maxWidth: 400,
        maxHeight: 400,
        tag: album.ImageTags.Primary,
      })
    : getImagePlaceholder();

  const artistName = album.Artists?.[0] || 'Unknown Artist';
  const year = album.ProductionYear;
  const trackCount = tracks.length;

  return (
    <div className="space-y-6 p-6">
      <Link
        data-testid="album-back-button"
        to="/albums"
        className="text-text-secondary hover:text-text-primary inline-flex items-center gap-2 transition-colors"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to Albums
      </Link>

      <div className="flex flex-col gap-6 sm:flex-row">
        <div className="shrink-0">
          <img
            src={hasImageError ? getImagePlaceholder() : imageUrl}
            alt={album.Name}
            className="h-48 w-48 rounded-md object-cover shadow-lg sm:h-56 sm:w-56"
            onError={() => setHasImageError(true)}
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
          </div>

          <div className="flex gap-2">
            <button
              data-testid="album-play-button"
              onClick={() => id && playAlbum(id)}
              className={cn(
                'flex items-center gap-2 px-6 py-2',
                'bg-accent rounded-full text-white',
                'hover:bg-accent-hover transition-colors'
              )}
            >
              <Play className="h-5 w-5" fill="currentColor" />
              Play
            </button>
            <button
              data-testid="album-shuffle-button"
              onClick={() => {
                toggleShuffle();
                if (id) playAlbum(id);
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
          </div>
        </div>
      </div>

      <TrackList
        tracks={tracks}
        showArtist={false}
        currentTrackId={currentTrack?.Id}
        isPlaying={isPlaying}
        onPlayTrack={(_, index) => playTracks(tracks, index)}
      />
    </div>
  );
}
