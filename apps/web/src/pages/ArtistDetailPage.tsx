import { useParams } from 'react-router-dom';
import { getIsFavorite } from '@yay-tsa/core';
import { useArtist, useArtistAlbums } from '@/features/library/hooks';
import { AlbumGrid } from '@/features/library/components';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { FavoriteButton } from '@/features/library/components/FavoriteButton';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { NotFound } from '@/shared/ui/NotFound';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { BackLink } from '@/shared/ui/BackLink';
import { usePlayerStore } from '@/features/player/stores/player.store';

export function ArtistDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { getImageUrl } = useImageUrl();
  const playAlbum = usePlayerStore(state => state.playAlbum);

  const {
    data: artist,
    isLoading: artistLoading,
    isError: artistError,
    refetch: refetchArtist,
  } = useArtist(id);
  const {
    data: albums = [],
    isLoading: albumsLoading,
    isError: albumsError,
    refetch: refetchAlbums,
  } = useArtistAlbums(id);
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    artist?.Id ?? '',
    artist?.ImageTags?.Primary
  );

  const handlePlayAlbum = (album: { Id: string }) => {
    playAlbum(album.Id);
  };

  const isLoading = artistLoading || albumsLoading;

  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (artistError || albumsError) {
    return (
      <div className="space-y-6 p-6">
        <BackLink to="/artists" label="Back to Artists" />
        <LoadErrorState
          message="Couldn't load artist"
          onRetry={() => {
            if (artistError) void refetchArtist();
            if (albumsError) void refetchAlbums();
          }}
        />
      </div>
    );
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
            <div className="flex items-center gap-2">
              <h1 data-testid="artist-detail-name" className="text-text-primary text-3xl font-bold">
                {artist.Name?.trim() || 'Unknown Artist'}
              </h1>
              <FavoriteButton
                itemId={artist.Id}
                itemType="artist"
                isFavorite={getIsFavorite(artist)}
                size="md"
              />
            </div>
            <p className="text-text-tertiary text-sm">
              {albumCount} {albumCount === 1 ? 'album' : 'albums'}
            </p>
          </div>

          {artist.Overview && (
            <p className="text-text-secondary max-w-2xl text-sm">{artist.Overview}</p>
          )}
        </div>
      </div>

      <section>
        <h2 className="mb-4 text-xl font-semibold">Albums</h2>
        {albums.length > 0 ? (
          <AlbumGrid albums={albums} onPlayAlbum={handlePlayAlbum} />
        ) : (
          <p className="text-text-tertiary text-sm">No albums for this artist yet.</p>
        )}
      </section>
    </div>
  );
}
