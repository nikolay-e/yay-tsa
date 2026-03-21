import { useRef, useEffect } from 'react';

export function useAutoScrollToActiveLine(activeLineIndex: number, isTimeSynced: boolean) {
  const activeLineRef = useRef<HTMLDivElement>(null);
  const lastScrolledIndex = useRef(-1);

  useEffect(() => {
    if (!isTimeSynced || activeLineIndex < 0) return;
    if (activeLineIndex === lastScrolledIndex.current) return;

    lastScrolledIndex.current = activeLineIndex;

    const timeoutId = setTimeout(() => {
      try {
        activeLineRef.current?.scrollIntoView({
          behavior: 'smooth',
          block: 'center',
        });
      } catch {
        // Element may be detached during unmount
      }
    }, 100);

    return () => clearTimeout(timeoutId);
  }, [activeLineIndex, isTimeSynced]);

  return activeLineRef;
}
