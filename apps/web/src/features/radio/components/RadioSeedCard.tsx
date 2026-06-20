import type { RadioSeed } from '@yay-tsa/core';
import { Loader2, Play } from 'lucide-react';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useIsSessionStarting, useSessionStore } from '@/features/player/stores/session-store';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { RADIO_TEST_IDS } from '@/shared/testing/test-ids';
import { cn } from '@/shared/utils/cn';

// Compact seed rows display a 40px thumbnail; request 48px capped at 2× DPR for crispness.
const SEED_THUMB_PX = 48;
function thumbPx(): number {
  const dpr =
    globalThis.window === undefined ? 1 : Math.min(globalThis.window.devicePixelRatio || 1, 2);
  return Math.round(SEED_THUMB_PX * dpr);
}

function resolveImageSrc(
  hasError: boolean,
  seed: RadioSeed,
  getImageUrl: ReturnType<typeof useImageUrl>['getImageUrl']
): string {
  // Only request a cover the seed actually advertises (imageTag present). Without it the server has
  // no cover and would 404 (forwarded as a ResourceError), so render the placeholder and skip the
  // request, mirroring MediaCard's tag-gated rendering.
  if (hasError || !seed.albumId || !seed.imageTag) return getImagePlaceholder();
  const px = thumbPx();
  return getImageUrl(seed.albumId, 'Primary', { maxWidth: px, maxHeight: px, tag: seed.imageTag });
}

export function RadioSeedCard({
  seed,
  priority = false,
}: Readonly<{ seed: RadioSeed; priority?: boolean }>) {
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
      aria-label={`Start radio from ${seed.name}`}
      data-testid={RADIO_TEST_IDS.SEED_CARD}
      className={cn(
        'group flex min-h-[44px] w-full items-center gap-3 rounded-sm p-2 text-left',
        'hover:bg-bg-secondary focus-visible:ring-accent transition-colors',
        'focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none',
        'disabled:opacity-50'
      )}
    >
      <div className="relative h-10 w-10 shrink-0 overflow-hidden rounded-sm">
        <img
          src={imgSrc}
          alt={seed.name}
          width={SEED_THUMB_PX}
          height={SEED_THUMB_PX}
          className="h-full w-full object-cover"
          loading={priority ? 'eager' : 'lazy'}
          fetchPriority={priority ? 'high' : undefined}
          onError={onError}
        />
        <div
          className={cn(
            'absolute inset-0 flex items-center justify-center bg-black/50',
            'opacity-0 group-focus-within:opacity-100 group-hover:opacity-100',
            'transition-opacity'
          )}
          aria-hidden="true"
        >
          {isStarting ? (
            <Loader2 className="text-text-primary h-4 w-4 animate-spin" />
          ) : (
            <Play className="text-text-primary h-4 w-4 fill-current" />
          )}
        </div>
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-text-primary truncate text-sm font-medium">{seed.name}</p>
        <p className="text-text-secondary truncate text-xs">{seed.artistName}</p>
      </div>
    </button>
  );
}
