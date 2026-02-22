import { useState, useCallback } from 'react';

export function useImageErrorTracking(id: string, tag?: string, albumId?: string) {
  const imageKey = `${id}-${tag ?? albumId ?? 'none'}`;
  const [errorKey, setErrorKey] = useState<string | null>(null);

  const onError = useCallback(() => {
    setErrorKey(imageKey);
  }, [imageKey]);

  return {
    hasError: errorKey === imageKey,
    onError,
  };
}
