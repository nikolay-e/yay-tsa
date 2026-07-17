import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import {
  MoreVertical,
  ListStart,
  ListPlus,
  ListMusic,
  Radio,
  Disc3,
  User,
  Heart,
  Download,
} from 'lucide-react';
import { getIsFavorite, type AudioItem } from '@yay-tsa/core';
import { useFocusReturn } from '@/shared/hooks/useFocusReturn';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { AddToPlaylistModal } from '@/features/playlists/components/AddToPlaylistModal';
import {
  useIsOnline,
  useOfflineEntry,
  useOfflineStore,
} from '@/features/offline/stores/offline.store';
import {
  useFavoriteToggle,
  useFavoritePendingStore,
  useIsFavoritePending,
} from '@/features/library/hooks/useFavorites';
import { usePlayerStore } from '../stores/player.store';
import { useSessionStore } from '../stores/session-store';

const ESTIMATED_MENU_HEIGHT_PX = 360;

type MenuPosition = { top?: number; bottom?: number; right: number };

export function TrackRowMenu({
  track,
  className,
}: Readonly<{ track: AudioItem; className?: string }>) {
  const [menuPosition, setMenuPosition] = useState<MenuPosition | null>(null);
  const [isPlaylistModalOpen, setIsPlaylistModalOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const isOpen = menuPosition !== null;
  const isOnline = useIsOnline();
  const navigate = useNavigate();
  const { mutate: toggleFavorite } = useFavoriteToggle();
  const isFavoritePending = useIsFavoritePending(track.Id);
  const offlineEntry = useOfflineEntry(track.Id);
  const isFavorite = getIsFavorite(track);
  const artistId = track.ArtistItems?.[0]?.Id;
  const downloadStatus = offlineEntry?.status ?? 'idle';
  useFocusReturn(isOpen);

  useEffect(() => {
    if (!isOpen) return;
    requestAnimationFrame(() => {
      menuRef.current?.querySelector<HTMLElement>('[role="menuitem"]:not([disabled])')?.focus();
    });
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const close = () => setMenuPosition(null);
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as Node;
      if (!menuRef.current?.contains(target) && !buttonRef.current?.contains(target)) close();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('scroll', close, { capture: true, passive: true });
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('scroll', close, { capture: true });
    };
  }, [isOpen]);

  const handleMenuKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Tab') {
      e.preventDefault();
      setMenuPosition(null);
      return;
    }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    e.preventDefault();
    const items = Array.from(
      menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([disabled])') ?? []
    );
    if (items.length === 0) return;
    const currentIndex = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'ArrowDown') {
      items[(currentIndex + 1) % items.length]?.focus();
    } else {
      items[currentIndex <= 0 ? items.length - 1 : currentIndex - 1]?.focus();
    }
  };

  const toggleMenu = () => {
    if (isOpen) {
      setMenuPosition(null);
      return;
    }
    const rect = buttonRef.current?.getBoundingClientRect();
    if (!rect) return;
    const right = window.innerWidth - rect.right;
    if (rect.bottom + ESTIMATED_MENU_HEIGHT_PX > window.innerHeight) {
      setMenuPosition({ bottom: window.innerHeight - rect.top + 4, right });
    } else {
      setMenuPosition({ top: rect.bottom + 4, right });
    }
  };

  const handlePlayNext = () => {
    setMenuPosition(null);
    const { currentTrack, insertNextInQueue, playTrack } = usePlayerStore.getState();
    if (currentTrack) {
      insertNextInQueue([track]);
      toast.add('success', 'Playing next');
    } else {
      void playTrack(track);
    }
  };

  const handleAddToQueue = () => {
    setMenuPosition(null);
    const { currentTrack, appendToQueue, playTrack } = usePlayerStore.getState();
    if (currentTrack) {
      appendToQueue([track]);
      toast.add('success', 'Added to queue');
    } else {
      void playTrack(track);
    }
  };

  const handleStartRadio = () => {
    setMenuPosition(null);
    void useSessionStore.getState().startSession(track.Id);
  };

  const handleAddToPlaylist = () => {
    setMenuPosition(null);
    setIsPlaylistModalOpen(true);
  };

  const handleGoToAlbum = () => {
    setMenuPosition(null);
    if (track.AlbumId) void navigate(`/albums/${track.AlbumId}`);
  };

  const handleGoToArtist = () => {
    setMenuPosition(null);
    if (artistId) void navigate(`/artists/${artistId}`);
  };

  const handleToggleFavorite = () => {
    setMenuPosition(null);
    const pendingStore = useFavoritePendingStore.getState();
    if (pendingStore.pending.has(track.Id)) return;
    pendingStore.begin(track.Id);
    toggleFavorite({ itemId: track.Id, isFavorite });
  };

  const handleDownload = () => {
    setMenuPosition(null);
    const { download, remove } = useOfflineStore.getState();
    if (downloadStatus === 'ready') {
      remove(track.Id).catch(() => {
        toast.add('error', `Could not remove the download for ${track.Name}. Try again.`);
      });
    } else if (downloadStatus !== 'downloading') {
      download(track).catch(() => {
        toast.add('error', `Could not download ${track.Name}. Check your connection.`);
      });
    }
  };

  return (
    <div className={cn('shrink-0', className)}>
      <button
        ref={buttonRef}
        type="button"
        data-testid="track-menu-button"
        onClick={e => {
          e.stopPropagation();
          toggleMenu();
        }}
        className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
        aria-label={`More actions for ${track.Name}`}
        aria-haspopup="menu"
        aria-expanded={isOpen}
      >
        <MoreVertical className="h-4 w-4" />
      </button>
      {isOpen &&
        createPortal(
          <div
            ref={menuRef}
            role="menu"
            tabIndex={-1}
            onKeyDown={handleMenuKeyDown}
            style={menuPosition}
            className="border-border bg-bg-secondary z-modal fixed w-52 rounded-lg border py-1 shadow-xl"
          >
            <button
              type="button"
              role="menuitem"
              data-testid="track-menu-play-next"
              onClick={handlePlayNext}
              className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm"
            >
              <ListStart className="h-4 w-4" />
              Play next
            </button>
            <button
              type="button"
              role="menuitem"
              data-testid="track-menu-add-queue"
              onClick={handleAddToQueue}
              className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm"
            >
              <ListPlus className="h-4 w-4" />
              Add to queue
            </button>
            <button
              type="button"
              role="menuitem"
              data-testid="track-menu-add-playlist"
              onClick={handleAddToPlaylist}
              disabled={!isOnline}
              title={isOnline ? undefined : 'Needs a connection'}
              className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm disabled:cursor-not-allowed disabled:opacity-50"
            >
              <ListMusic className="h-4 w-4" />
              Add to playlist
            </button>
            <button
              type="button"
              role="menuitem"
              data-testid="track-menu-start-radio"
              onClick={handleStartRadio}
              disabled={!isOnline}
              title={isOnline ? undefined : 'Needs a connection'}
              className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Radio className="h-4 w-4" />
              Start radio
            </button>
            {track.AlbumId && (
              <button
                type="button"
                role="menuitem"
                data-testid="track-menu-go-album"
                onClick={handleGoToAlbum}
                className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm"
              >
                <Disc3 className="h-4 w-4" />
                Go to album
              </button>
            )}
            {artistId && (
              <button
                type="button"
                role="menuitem"
                data-testid="track-menu-go-artist"
                onClick={handleGoToArtist}
                className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm"
              >
                <User className="h-4 w-4" />
                Go to artist
              </button>
            )}
            <button
              type="button"
              role="menuitem"
              data-testid="track-menu-favorite"
              onClick={handleToggleFavorite}
              disabled={isFavoritePending}
              className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Heart className="h-4 w-4" fill={isFavorite ? 'currentColor' : 'none'} />
              {isFavorite ? 'Remove from favorites' : 'Add to favorites'}
            </button>
            <button
              type="button"
              role="menuitem"
              data-testid="track-menu-download"
              onClick={handleDownload}
              disabled={
                downloadStatus === 'downloading' || (!isOnline && downloadStatus !== 'ready')
              }
              className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Download className="h-4 w-4" />
              {(() => {
                if (downloadStatus === 'ready') return 'Remove download';
                if (downloadStatus === 'downloading') return 'Downloading…';
                if (downloadStatus === 'error') return 'Retry download';
                return 'Download';
              })()}
            </button>
          </div>,
          document.body
        )}
      {isPlaylistModalOpen && (
        <AddToPlaylistModal track={track} isOpen onClose={() => setIsPlaylistModalOpen(false)} />
      )}
    </div>
  );
}
