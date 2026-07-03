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

function resolveHotkeyAction(event: KeyboardEvent): (() => void) | null {
  const { isPlaying, pause, resume, skipBy, next, previous } = usePlayerStore.getState();

  if (event.shiftKey) {
    const key = event.key.toLowerCase();
    if (key === 'n') return () => void next();
    if (key === 'p') return () => void previous();
    return null;
  }

  if (hasNativeActivation(event.target)) return null;
  if (event.key === ' ') return isPlaying ? pause : () => void resume();
  if (event.key === 'ArrowLeft') return () => skipBy(-SEEK_STEP_SECONDS);
  if (event.key === 'ArrowRight') return () => skipBy(SEEK_STEP_SECONDS);
  return null;
}

export function usePlaybackHotkeys(): void {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      if (isTypingTarget(event.target) || isModalOpen()) return;

      const action = resolveHotkeyAction(event);
      if (!action) return;
      event.preventDefault();
      action();
    };

    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, []);
}
