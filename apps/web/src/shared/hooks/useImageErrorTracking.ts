import { useState } from 'react';

export function useImageErrorTracking(id: string, tag?: string, albumId?: string) {
  const imageKey = `${id}-${tag ?? albumId ?? 'none'}`;
  const [errorKey, setErrorKey] = useState<string | null>(null);

  const onError = () => {
    setErrorKey(imageKey);
  };

  return {
    hasError: errorKey === imageKey,
    onError,
  };
}
