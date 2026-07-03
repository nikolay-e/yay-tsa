import { useState, type FormEvent } from 'react';
import { X } from 'lucide-react';
import type { Playlist } from '@yay-tsa/core';
import { Modal } from '@/shared/ui/Modal';
import { toast } from '@/shared/ui/Toast';
import { useCreatePlaylist } from '../hooks/usePlaylists';

type CreatePlaylistModalProps = Readonly<{
  isOpen: boolean;
  onClose: () => void;
  itemIds?: string[];
  onCreated?: (playlist: Playlist) => void;
}>;

export function CreatePlaylistModal({
  isOpen,
  onClose,
  itemIds,
  onCreated,
}: CreatePlaylistModalProps) {
  const [name, setName] = useState('');
  const createPlaylist = useCreatePlaylist();

  const handleClose = () => {
    setName('');
    createPlaylist.reset();
    onClose();
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    const trimmedName = name.trim();
    if (!trimmedName || createPlaylist.isPending) return;
    createPlaylist.mutate(
      { name: trimmedName, itemIds },
      {
        onSuccess: playlist => {
          toast.add('success', `Created playlist "${trimmedName}"`);
          onCreated?.(playlist);
          handleClose();
        },
        onError: () => {
          toast.add('error', 'Failed to create playlist — please try again');
        },
      }
    );
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      ariaLabelledBy="create-playlist-title"
      className="bg-bg-secondary w-80 rounded-lg p-6 shadow-lg"
    >
      <div className="mb-4 flex items-center justify-between">
        <h3 id="create-playlist-title" className="text-text-primary text-lg font-semibold">
          New Playlist
        </h3>
        <button
          onClick={handleClose}
          className="text-text-secondary hover:text-text-primary p-1"
          aria-label="Close"
        >
          <X className="h-5 w-5" />
        </button>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label
            htmlFor="playlist-name"
            className="text-text-secondary mb-1 block text-sm font-medium"
          >
            Name
          </label>
          <input
            id="playlist-name"
            data-testid="playlist-name-input"
            type="text"
            value={name}
            onChange={e => setName(e.target.value)}
            required
            maxLength={200}
            // eslint-disable-next-line jsx-a11y/no-autofocus
            autoFocus
            className="bg-bg-tertiary border-border text-text-primary focus:border-accent min-h-11 w-full rounded-md border px-3 py-2 text-sm focus:outline-none"
          />
        </div>
        <button
          type="submit"
          data-testid="playlist-create-submit"
          disabled={!name.trim() || createPlaylist.isPending}
          className="bg-accent text-text-on-accent hover:bg-accent-hover flex min-h-11 w-full items-center justify-center rounded-md px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50"
        >
          {createPlaylist.isPending ? 'Creating…' : 'Create playlist'}
        </button>
      </form>
    </Modal>
  );
}
