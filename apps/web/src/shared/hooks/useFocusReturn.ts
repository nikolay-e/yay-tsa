import { useEffect, useRef } from 'react';

export function useFocusReturn(isActive: boolean): void {
  const previouslyFocusedRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!isActive) return;
    previouslyFocusedRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    return () => {
      previouslyFocusedRef.current?.focus();
    };
  }, [isActive]);
}
