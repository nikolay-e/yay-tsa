import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ListMusic, Plus } from 'lucide-react';
import type { Playlist } from '@yay-tsa/core';
import { usePlaylists } from '@/features/playlists/hooks/usePlaylists';
import { CreatePlaylistModal } from '@/features/playlists/components/CreatePlaylistModal';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';

export function PlaylistsPage() {
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const { data, isLoading, isError, error, refetch } = usePlaylists();
  const playlists = data?.Items ?? [];

  let content;
  if (isLoading) {
    content = <LoadingSpinner />;
  } else if (isError) {
    content = (
      <LoadErrorState
        message={error instanceof Error ? error.message : 'Failed to load playlists'}
        onRetry={() => {
          void refetch();
        }}
      />
    );
  } else if (playlists.length === 0) {
    content = (
      <div className="flex h-64 flex-col items-center justify-center gap-3 text-center">
        <p className="text-text-secondary">No playlists yet</p>
        <p className="text-text-tertiary text-sm">
          Create a playlist to collect your favorite tracks in one place.
        </p>
        <button
          type="button"
          onClick={() => setIsCreateOpen(true)}
          className="text-accent text-sm font-medium underline-offset-4 hover:underline focus-visible:underline"
        >
          Create your first playlist
        </button>
      </div>
    );
  } else {
    content = (
      <div
        data-testid="playlists-content"
        className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6"
      >
        {playlists.map(playlist => (
          <PlaylistCard key={playlist.Id} playlist={playlist} />
        ))}
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6" data-testid="playlists-page">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Playlists</h1>
        <button
          type="button"
          data-testid="playlist-create-button"
          onClick={() => setIsCreateOpen(true)}
          className="bg-accent text-text-on-accent hover:bg-accent-hover flex items-center gap-2 rounded-full px-4 py-2 text-sm font-medium transition-colors"
        >
          <Plus className="h-4 w-4" />
          New playlist
        </button>
      </div>

      {content}

      <CreatePlaylistModal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)} />
    </div>
  );
}

function PlaylistCard({ playlist }: Readonly<{ playlist: Playlist }>) {
  const trackCount = playlist.ChildCount ?? 0;

  return (
    <Link
      to={`/playlists/${playlist.Id}`}
      data-testid="playlist-card"
      className="group bg-bg-secondary hover:bg-bg-tertiary block rounded-md p-2 transition-colors"
    >
      <div className="bg-bg-tertiary relative mb-2 flex aspect-square items-center justify-center overflow-hidden rounded-sm">
        <ListMusic className="text-text-tertiary h-1/3 w-1/3" />
      </div>
      <h2 data-testid="playlist-title" className="text-text-primary truncate font-medium">
        {playlist.Name}
      </h2>
      <p className="text-text-secondary truncate text-sm">
        {trackCount} {trackCount === 1 ? 'track' : 'tracks'}
      </p>
    </Link>
  );
}
