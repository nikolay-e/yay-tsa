import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { ArrowLeft, Loader2 } from 'lucide-react';
import { useArtist, useArtistAlbums } from '@/features/library/hooks';
import { AlbumGrid } from '@/features/library/components';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { usePlayerStore } from '@/features/player/stores/player.store';

export function ArtistDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [errorKey, setErrorKey] = useState<string | null>(null);
  const { getImageUrl } = useImageUrl();
  const playAlbum = usePlayerStore(state => state.playAlbum);

  const { data: artist, isLoading: artistLoading } = useArtist(id);
  const { data: albums = [], isLoading: albumsLoading } = useArtistAlbums(id);

  const isLoading = artistLoading || albumsLoading;

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Loader2 className="text-accent h-8 w-8 animate-spin" />
      </div>
    );
  }

  if (!artist) {
    return (
      <div className="p-6">
        <p className="text-text-secondary">Artist not found</p>
      </div>
    );
  }

  const imageKey = `${artist.Id}-${artist.ImageTags?.Primary ?? 'none'}`;
  const hasImageError = errorKey === imageKey;

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
      <Link
        to="/artists"
        className="text-text-secondary hover:text-text-primary inline-flex items-center gap-2 transition-colors"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to Artists
      </Link>

      <div className="flex flex-col gap-6 sm:flex-row">
        <div className="shrink-0">
          <img
            src={hasImageError ? getImagePlaceholder() : imageUrl}
            alt={artist.Name}
            className="h-48 w-48 rounded-full object-cover shadow-lg sm:h-56 sm:w-56"
            onError={() => setErrorKey(imageKey)}
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
          <AlbumGrid albums={albums} onPlayAlbum={album => playAlbum(album.Id)} />
        </section>
      )}
    </div>
  );
}
