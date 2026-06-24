import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MoreVertical, ListStart, ListPlus, Radio } from 'lucide-react';
import type { AudioItem } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { useIsOnline } from '@/features/offline/stores/offline.store';
import { usePlayerStore } from '../stores/player.store';
import { useSessionStore } from '../stores/session-store';

const ESTIMATED_MENU_HEIGHT_PX = 150;

type MenuPosition = { top?: number; bottom?: number; right: number };

export function TrackRowMenu({
  track,
  className,
}: Readonly<{ track: AudioItem; className?: string }>) {
  const [menuPosition, setMenuPosition] = useState<MenuPosition | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const isOpen = menuPosition !== null;
  const isOnline = useIsOnline();

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
            style={menuPosition}
            className="border-border bg-bg-secondary z-modal fixed w-44 rounded-lg border py-1 shadow-xl"
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
              data-testid="track-menu-start-radio"
              onClick={handleStartRadio}
              disabled={!isOnline}
              className="text-text-primary hover:bg-bg-tertiary flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Radio className="h-4 w-4" />
              Start radio
            </button>
          </div>,
          document.body
        )}
    </div>
  );
}
