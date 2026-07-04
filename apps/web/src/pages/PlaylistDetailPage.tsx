import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ListMusic, Pause, Play, Shuffle, Trash2, X } from 'lucide-react';
import type { AudioItem } from '@yay-tsa/core';
import { TrackListRow } from '@/features/library/components';
import {
  useInfinitePlaylistItems,
  usePlaylist,
  useDeletePlaylist,
  useRemoveFromPlaylist,
  useMovePlaylistItem,
} from '@/features/playlists/hooks/usePlaylists';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { NotFound } from '@/shared/ui/NotFound';
import { BackLink } from '@/shared/ui/BackLink';
import { InfiniteScrollFooter } from '@/shared/ui/InfiniteScrollFooter';
import { SortableList } from '@/shared/ui/SortableList';
import { toast } from '@/shared/ui/Toast';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
} from '@/features/player/stores/player.store';
import { cn } from '@/shared/utils/cn';

type PlaylistEntry = Readonly<{ Id: string; track: AudioItem }>;

export function PlaylistDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [confirmDelete, setConfirmDelete] = useState(false);

  const playTracks = usePlayerStore(state => state.playTracks);
  const pause = usePlayerStore(state => state.pause);
  const setShuffle = usePlayerStore(state => state.setShuffle);
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();

  const {
    data: playlist,
    isLoading: playlistLoading,
    isError: playlistError,
    refetch: refetchPlaylist,
  } = usePlaylist(id);

  const {
    data: itemsData,
    isLoading: itemsLoading,
    isError: itemsError,
    isFetching: itemsFetching,
    refetch: refetchItems,
    isFetchingNextPage,
    isFetchNextPageError,
    hasNextPage,
    fetchNextPage,
  } = useInfinitePlaylistItems(id);

  const deletePlaylist = useDeletePlaylist();
  const removeFromPlaylist = useRemoveFromPlaylist();
  const movePlaylistItem = useMovePlaylistItem(id);

  // Entry `Id` is a raw array position echoed back by the backend (see
  // JellyfinPlaylistsController), not a stable per-track identity. Any positional action
  // is unsafe the moment the displayed list can be stale relative to the server, so every
  // such action stays blocked until both the mutation settles AND the refetch it triggers
  // has actually landed — not just the mutation's own in-flight window.
  const positionalActionsLocked =
    removeFromPlaylist.isPending || movePlaylistItem.isPending || itemsFetching;

  const tracks = useMemo(() => itemsData?.pages.flatMap(page => page.Items) ?? [], [itemsData]);
  const totalCount = itemsData?.pages[0]?.TotalRecordCount ?? 0;

  const entries: PlaylistEntry[] = useMemo(
    () => tracks.map((track, index) => ({ Id: track.PlaylistItemId ?? String(index), track })),
    [tracks]
  );

  if (playlistLoading || itemsLoading) {
    return (
      <>
        <h1 className="sr-only">Playlist</h1>
        <LoadingSpinner />
      </>
    );
  }

  if (playlistError || itemsError) {
    return (
      <div className="space-y-6 p-6">
        <BackLink to="/playlists" label="Back to Playlists" data-testid="playlist-back-button" />
        <LoadErrorState
          message="Couldn't load playlist"
          onRetry={() => {
            if (playlistError) void refetchPlaylist();
            if (itemsError) void refetchItems();
          }}
        />
      </div>
    );
  }

  if (!playlist) {
    return <NotFound message="Playlist not found" />;
  }

  const isPlayingThisList = isPlaying && tracks.some(track => track.Id === currentTrack?.Id);

  const handlePlayAll = () => {
    if (isPlayingThisList) {
      pause();
    } else if (tracks.length > 0) {
      setShuffle(false);
      playTracks(tracks, 0);
    }
  };

  const handleShuffle = () => {
    if (tracks.length === 0) return;
    setShuffle(true);
    playTracks(tracks, 0);
  };

  const handleDelete = () => {
    deletePlaylist.mutate(playlist.Id, {
      onSuccess: () => {
        toast.add('success', `Deleted playlist "${playlist.Name}"`);
        void navigate('/playlists');
      },
      onError: () => {
        toast.add('error', 'Failed to delete playlist — please try again');
      },
    });
  };

  const handleRemoveEntry = (entry: PlaylistEntry, index: number) => {
    removeFromPlaylist.mutate(
      { playlistId: playlist.Id, entryIds: [entry.track.PlaylistItemId ?? String(index)] },
      {
        onSuccess: () => {
          toast.add('success', `Removed "${entry.track.Name}" from playlist`);
        },
        onError: () => {
          toast.add('error', 'Failed to remove track — please try again');
        },
      }
    );
  };

  const handleReorder = (_reordered: PlaylistEntry[], fromIndex: number, toIndex: number) => {
    movePlaylistItem.mutate({ fromIndex, toIndex });
  };

  return (
    <div className="space-y-6 p-6" data-testid="playlist-detail-page">
      <BackLink to="/playlists" label="Back to Playlists" data-testid="playlist-back-button" />

      <div className="flex flex-col gap-6 sm:flex-row">
        <div className="bg-bg-tertiary flex h-48 w-48 shrink-0 items-center justify-center rounded-md shadow-lg sm:h-56 sm:w-56">
          <ListMusic className="text-text-tertiary h-20 w-20" />
        </div>

        <div className="flex flex-col justify-end space-y-4">
          <div>
            <h1
              data-testid="playlist-detail-title"
              className="text-text-primary text-3xl font-bold"
            >
              {playlist.Name?.trim() || 'Unknown Playlist'}
            </h1>
            <p className="text-text-tertiary text-sm">
              {totalCount} {totalCount === 1 ? 'track' : 'tracks'}
            </p>
          </div>

          <div className="flex items-center gap-2">
            <button
              data-testid="playlist-play-button"
              onClick={handlePlayAll}
              disabled={tracks.length === 0}
              className={cn(
                'flex min-w-[7.5rem] items-center justify-center gap-2 px-6 py-2',
                'bg-accent text-text-on-accent rounded-full',
                'hover:bg-accent-hover transition-colors disabled:opacity-50'
              )}
            >
              {isPlayingThisList ? (
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
              data-testid="playlist-shuffle-button"
              onClick={handleShuffle}
              disabled={tracks.length === 0}
              className={cn(
                'flex items-center gap-2 px-6 py-2',
                'bg-bg-secondary text-text-primary rounded-full',
                'hover:bg-bg-tertiary transition-colors disabled:opacity-50'
              )}
            >
              <Shuffle className="h-5 w-5" />
              Shuffle
            </button>
            {confirmDelete ? (
              <div className="flex items-center gap-2">
                <button
                  data-testid="playlist-delete-confirm"
                  onClick={handleDelete}
                  disabled={deletePlaylist.isPending}
                  className="bg-error/20 text-error hover:bg-error/30 rounded-full px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50"
                >
                  Delete
                </button>
                <button
                  onClick={() => setConfirmDelete(false)}
                  disabled={deletePlaylist.isPending}
                  className="bg-bg-secondary text-text-primary hover:bg-bg-tertiary rounded-full px-4 py-2 text-sm transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
              </div>
            ) : (
              <button
                data-testid="playlist-delete-button"
                onClick={() => setConfirmDelete(true)}
                aria-label="Delete playlist"
                className="text-text-secondary hover:text-error rounded-full p-2 transition-colors"
              >
                <Trash2 className="h-5 w-5" />
              </button>
            )}
          </div>
        </div>
      </div>

      {tracks.length === 0 ? (
        <div className="flex h-32 flex-col items-center justify-center gap-2 text-center">
          <p className="text-text-secondary">This playlist is empty</p>
          <p className="text-text-tertiary text-sm">
            Use "Add to playlist" from any track menu to fill it.
          </p>
        </div>
      ) : (
        <>
          <SortableList
            items={entries}
            onReorder={handleReorder}
            disabled={positionalActionsLocked}
            renderItem={(entry, index) => (
              <div className="flex items-center gap-1">
                <div className="min-w-0 flex-1">
                  <TrackListRow
                    track={entry.track}
                    index={index}
                    isCurrentTrack={entry.track.Id === currentTrack?.Id}
                    isPlaying={isPlaying}
                    onPlay={() => {
                      playTracks(tracks, index);
                    }}
                    onPause={pause}
                    showAlbum={false}
                    showArtist={false}
                    showImage
                  />
                </div>
                <button
                  type="button"
                  data-testid="playlist-remove-track"
                  onClick={() => handleRemoveEntry(entry, index)}
                  disabled={positionalActionsLocked}
                  aria-label={`Remove ${entry.track.Name} from playlist`}
                  className="text-text-secondary hover:text-error shrink-0 rounded-full p-2 transition-colors disabled:opacity-50"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            )}
          />
          <InfiniteScrollFooter
            hasNextPage={hasNextPage}
            isFetchingNextPage={isFetchingNextPage}
            isFetchNextPageError={isFetchNextPageError}
            onLoadMore={() => {
              fetchNextPage();
            }}
            currentCount={tracks.length}
            totalCount={totalCount}
            itemLabel="tracks"
          />
        </>
      )}
    </div>
  );
}
