import type { RadioSeed } from '@yay-tsa/core';
import { Loader2, Play } from 'lucide-react';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useIsSessionStarting } from '@/features/player/stores/session-store';
import { useSessionStore } from '@/features/player/stores/session-store';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { RADIO_TEST_IDS } from '@/shared/testing/test-ids';
import { cn } from '@/shared/utils/cn';

function resolveImageSrc(
  hasError: boolean,
  seed: RadioSeed,
  getImageUrl: ReturnType<typeof useImageUrl>['getImageUrl']
): string {
  if (hasError || !seed.albumId) return getImagePlaceholder();
  const opts = { maxWidth: 300, maxHeight: 300 };
  if (seed.imageTag) return getImageUrl(seed.albumId, 'Primary', { ...opts, tag: seed.imageTag });
  return getImageUrl(seed.albumId, 'Primary', opts);
}

export function RadioSeedCard({ seed }: Readonly<{ seed: RadioSeed }>) {
  const { getImageUrl } = useImageUrl();
  const { hasError, onError } = useImageErrorTracking(seed.trackId, seed.imageTag, seed.albumId);
  const isStarting = useIsSessionStarting();

  const imgSrc = resolveImageSrc(hasError, seed, getImageUrl);

  const handleClick = () => {
    useSessionStore.getState().startSession(seed.trackId);
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isStarting}
      aria-label={`Play ${seed.name}`}
      data-testid={RADIO_TEST_IDS.SEED_CARD}
      className={cn(
        'group bg-bg-secondary flex w-40 shrink-0 flex-col rounded-md p-1.5 transition-colors',
        'hover:bg-bg-secondary/80',
        'disabled:opacity-50'
      )}
    >
      <div className="relative aspect-square overflow-hidden rounded-sm">
        <img
          src={imgSrc}
          alt={seed.name}
          className="h-full w-full object-cover"
          onError={onError}
        />
        <div className="absolute inset-0 flex items-center justify-center">
          <div
            className={cn(
              'flex h-10 w-10 items-center justify-center',
              'bg-accent text-text-on-accent rounded-full shadow-lg',
              'scale-90 opacity-0 group-hover:scale-100 group-hover:opacity-100',
              'transition-all duration-200'
            )}
          >
            {isStarting ? (
              <Loader2 className="h-5 w-5 animate-spin" />
            ) : (
              <Play className="h-5 w-5 fill-current" />
            )}
          </div>
        </div>
      </div>
      <div className="w-full min-w-0 pt-1.5">
        <p className="text-text-primary truncate text-sm font-medium">{seed.name}</p>
        <p className="text-text-secondary truncate text-xs">{seed.artistName}</p>
      </div>
    </button>
  );
}
