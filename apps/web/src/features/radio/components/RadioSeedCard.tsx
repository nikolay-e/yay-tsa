import type { RadioSeed } from '@yay-tsa/core';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { useSessionStore } from '@/features/player/stores/session-store';

function resolveImageSrc(
  hasError: boolean,
  seed: RadioSeed,
  getImageUrl: ReturnType<typeof useImageUrl>['getImageUrl']
): string {
  if (hasError || !seed.albumId) return getImagePlaceholder();
  const opts = { maxWidth: 80, maxHeight: 80 };
  if (seed.imageTag) return getImageUrl(seed.albumId, 'Primary', { ...opts, tag: seed.imageTag });
  return getImageUrl(seed.albumId, 'Primary', opts);
}

export function RadioSeedCard({ seed }: Readonly<{ seed: RadioSeed }>) {
  const { getImageUrl } = useImageUrl();
  const { hasError, onError } = useImageErrorTracking(seed.trackId, seed.imageTag, seed.albumId);

  const imgSrc = resolveImageSrc(hasError, seed, getImageUrl);

  const handleClick = () => {
    useSessionStore.getState().startSession(seed.trackId);
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className="bg-bg-tertiary hover:bg-bg-tertiary/80 flex w-36 shrink-0 flex-col overflow-hidden rounded-lg transition-colors"
    >
      <img src={imgSrc} alt={seed.name} className="h-36 w-36 object-cover" onError={onError} />
      <div className="w-full min-w-0 p-2">
        <p className="text-text-primary truncate text-sm font-medium">{seed.name}</p>
        <p className="text-text-secondary truncate text-xs">{seed.artistName}</p>
      </div>
    </button>
  );
}
