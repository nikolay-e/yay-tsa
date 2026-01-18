import { useParams } from 'react-router-dom';
import { useArtist, useArtistAlbums } from '@/features/library/hooks';
import { AlbumGrid } from '@/features/library/components';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { NotFound } from '@/shared/ui/NotFound';
import { BackLink } from '@/shared/ui/BackLink';
import { usePlayerStore } from '@/features/player/stores/player.store';

export function ArtistDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { getImageUrl } = useImageUrl();
  const playAlbum = usePlayerStore(state => state.playAlbum);

  const { data: artist, isLoading: artistLoading } = useArtist(id);
  const { data: albums = [], isLoading: albumsLoading } = useArtistAlbums(id);
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    artist?.Id ?? '',
    artist?.ImageTags?.Primary
  );

  const isLoading = artistLoading || albumsLoading;

  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (!artist) {
    return <NotFound message="Artist not found" />;
  }

  const imageUrl = artist.ImageTags?.Primary
    ? getImageUrl(artist.Id, 'Primary', {
        maxWidth: 400,
        maxHeight: 400,
        tag: artist.ImageTags.Primary,
      })
    : getImagePlaceholder();

  const albumCount = albums.length;

  return (
    <div className="space-y-6 p-6">
      <BackLink to="/artists" label="Back to Artists" />

      <div className="flex flex-col gap-6 sm:flex-row">
        <div className="shrink-0">
          <img
            src={hasImageError ? getImagePlaceholder() : imageUrl}
            alt={artist.Name}
            className="h-48 w-48 rounded-full object-cover shadow-lg sm:h-56 sm:w-56"
            onError={onImageError}
          />
        </div>

        <div className="flex flex-col justify-end space-y-4">
          <div>
            <h1 data-testid="artist-detail-name" className="text-text-primary text-3xl font-bold">
              {artist.Name}
            </h1>
            <p className="text-text-tertiary text-sm">
              {albumCount} {albumCount === 1 ? 'album' : 'albums'}
            </p>
          </div>

          {artist.Overview && (
            <p className="text-text-secondary max-w-2xl text-sm">{artist.Overview}</p>
          )}
        </div>
      </div>

      {albums.length > 0 && (
        <section>
          <h2 className="mb-4 text-xl font-semibold">Albums</h2>
          <AlbumGrid albums={albums} onPlayAlbum={album => void playAlbum(album.Id)} />
        </section>
      )}
    </div>
  );
}
