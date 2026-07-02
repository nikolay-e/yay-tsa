import { useEffect } from 'react';
import { usePlayerStore } from '../stores/player.store';

const SEEK_STEP_SECONDS = 10;
const EDITABLE_SELECTOR = 'input, textarea, select, [contenteditable="true"]';
// Space and arrows keep their native meaning on focused controls (button activation,
// slider stepping, link scrolling), so global handling applies only off-control.
const NATIVE_ACTIVATION_SELECTOR = 'button, a, [role="button"], [role="menuitem"]';

function isTypingTarget(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable || !!target.closest(EDITABLE_SELECTOR))
  );
}

function isModalOpen(): boolean {
  return document.querySelector('[role="dialog"][aria-modal="true"]') !== null;
}

function hasNativeActivation(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && !!target.closest(NATIVE_ACTIVATION_SELECTOR);
}

export function usePlaybackHotkeys(): void {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      if (isTypingTarget(event.target) || isModalOpen()) return;

      const { isPlaying, pause, resume, skipBy, next, previous } = usePlayerStore.getState();

      if (event.shiftKey) {
        if (event.key.toLowerCase() === 'n') {
          event.preventDefault();
          void next();
        } else if (event.key.toLowerCase() === 'p') {
          event.preventDefault();
          void previous();
        }
        return;
      }

      if (event.key === ' ') {
        if (hasNativeActivation(event.target)) return;
        event.preventDefault();
        if (isPlaying) pause();
        else void resume();
        return;
      }

      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        if (hasNativeActivation(event.target)) return;
        event.preventDefault();
        skipBy(event.key === 'ArrowLeft' ? -SEEK_STEP_SECONDS : SEEK_STEP_SECONDS);
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, []);
}
