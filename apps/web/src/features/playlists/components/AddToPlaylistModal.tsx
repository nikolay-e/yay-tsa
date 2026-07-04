import { useState } from 'react';
import { ListMusic, Plus, X } from 'lucide-react';
import type { AudioItem } from '@yay-tsa/core';
import { Modal } from '@/shared/ui/Modal';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { toast } from '@/shared/ui/Toast';
import { usePlaylists, useAddToPlaylist } from '../hooks/usePlaylists';
import { CreatePlaylistModal } from './CreatePlaylistModal';

type AddToPlaylistModalProps = Readonly<{
  track: AudioItem;
  isOpen: boolean;
  onClose: () => void;
}>;

export function AddToPlaylistModal({ track, isOpen, onClose }: AddToPlaylistModalProps) {
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const { data, isLoading, isError } = usePlaylists();
  const addToPlaylist = useAddToPlaylist();
  const playlists = data?.Items ?? [];

  const handleSelect = (playlistId: string, playlistName: string) => {
    if (addToPlaylist.isPending) return;
    addToPlaylist.mutate(
      { playlistId, itemIds: [track.Id] },
      {
        onSuccess: () => {
          toast.add('success', `Added to ${playlistName}`);
          onClose();
        },
        onError: () => {
          toast.add('error', 'Failed to add to playlist — please try again');
        },
      }
    );
  };

  if (isCreateOpen) {
    return (
      <CreatePlaylistModal
        isOpen
        onClose={() => {
          setIsCreateOpen(false);
          onClose();
        }}
        itemIds={[track.Id]}
        onCreated={() => {
          toast.add('success', `Added "${track.Name}" to the new playlist`);
        }}
      />
    );
  }

  let content;
  if (isLoading) {
    content = <LoadingSpinner />;
  } else if (isError) {
    content = (
      <p className="text-text-secondary py-4 text-center text-sm">Couldn’t load playlists</p>
    );
  } else if (playlists.length === 0) {
    content = (
      <p className="text-text-secondary py-4 text-center text-sm">
        No playlists yet. Create your first one below.
      </p>
    );
  } else {
    content = (
      <div className="max-h-64 space-y-1 overflow-y-auto">
        {playlists.map(playlist => (
          <button
            key={playlist.Id}
            type="button"
            data-testid="add-to-playlist-option"
            onClick={() => handleSelect(playlist.Id, playlist.Name)}
            disabled={addToPlaylist.isPending}
            className="bg-bg-tertiary text-text-primary hover:bg-bg-hover flex w-full items-center gap-3 rounded-md p-3 text-left text-sm transition-colors disabled:opacity-50"
          >
            <ListMusic className="text-text-secondary h-4 w-4 shrink-0" />
            <span className="min-w-0 flex-1 truncate">{playlist.Name}</span>
            <span className="text-text-tertiary shrink-0 text-xs">
              {playlist.ChildCount ?? 0} tracks
            </span>
          </button>
        ))}
      </div>
    );
  }

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      ariaLabelledBy="add-to-playlist-title"
      className="bg-bg-secondary w-80 rounded-lg p-6 shadow-lg"
    >
      <div className="mb-4 flex items-center justify-between">
        <h3 id="add-to-playlist-title" className="text-text-primary text-lg font-semibold">
          Add to Playlist
        </h3>
        <button
          onClick={onClose}
          className="text-text-secondary hover:text-text-primary p-1"
          aria-label="Close"
        >
          <X className="h-5 w-5" />
        </button>
      </div>

      <div className="space-y-3">
        {content}
        <button
          type="button"
          data-testid="add-to-playlist-create-new"
          onClick={() => setIsCreateOpen(true)}
          className="border-border text-text-primary hover:bg-bg-tertiary flex min-h-11 w-full items-center justify-center gap-2 rounded-md border border-dashed px-4 py-2 text-sm font-medium transition-colors"
        >
          <Plus className="h-4 w-4" />
          New playlist
        </button>
      </div>
    </Modal>
  );
}
