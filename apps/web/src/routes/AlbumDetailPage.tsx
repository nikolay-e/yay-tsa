import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Play, Shuffle, Loader2 } from 'lucide-react';
import { ItemsService, type MusicAlbum } from '@yaytsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useAlbumTracks } from '@/features/library/hooks';
import { TrackList } from '@/features/library/components';
import { useImageUrl, getImagePlaceholder } from '@/shared/utils/image';
import { cn } from '@/shared/utils/cn';

export function AlbumDetailPage() {
  const { id } = useParams<{ id: string }>();
  const client = useAuthStore(state => state.client);
  const { getImageUrl } = useImageUrl();

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
        <Loader2 className="h-8 w-8 animate-spin text-accent" />
      </div>
    );
  }

  if (!album) {
    return (
      <div className="p-lg">
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
    <div className="space-y-lg p-lg">
      <Link
        data-testid="album-back-button"
        to="/albums"
        className="inline-flex items-center gap-sm text-text-secondary transition-colors hover:text-text-primary"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to Albums
      </Link>

      <div className="flex flex-col gap-lg sm:flex-row">
        <div className="flex-shrink-0">
          <img
            src={imageUrl}
            alt={album.Name}
            className="h-48 w-48 rounded-md object-cover shadow-lg sm:h-56 sm:w-56"
          />
        </div>

        <div className="flex flex-col justify-end space-y-md">
          <div>
            <h1 data-testid="album-detail-title" className="text-3xl font-bold text-text-primary">
              {album.Name}
            </h1>
            <p className="text-lg text-text-secondary">{artistName}</p>
            <p className="text-sm text-text-tertiary">
              {year && `${year} • `}
              {trackCount} {trackCount === 1 ? 'track' : 'tracks'}
            </p>
          </div>

          <div className="flex gap-sm">
            <button
              data-testid="album-play-button"
              className={cn(
                'flex items-center gap-sm px-lg py-sm',
                'rounded-full bg-accent text-white',
                'transition-colors hover:bg-accent-hover'
              )}
            >
              <Play className="h-5 w-5" fill="currentColor" />
              Play
            </button>
            <button
              data-testid="album-shuffle-button"
              className={cn(
                'flex items-center gap-sm px-lg py-sm',
                'rounded-full bg-bg-secondary text-text-primary',
                'transition-colors hover:bg-bg-tertiary'
              )}
            >
              <Shuffle className="h-5 w-5" />
              Shuffle
            </button>
          </div>
        </div>
      </div>

      <TrackList tracks={tracks} showArtist={false} />
    </div>
  );
}
