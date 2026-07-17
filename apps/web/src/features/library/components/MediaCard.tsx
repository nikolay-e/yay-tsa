import { type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { cn } from '@/shared/utils/cn';

type MediaCardProps = Readonly<{
  itemId: string;
  imageTag?: string;
  imageAlt?: string;
  imageShape: 'square' | 'circle';
  imageOverlay?: ReactNode;
  imageTestId?: string;
  children: ReactNode;
  href?: string;
  testId?: string;
  /** Requested cover size in px. Grid cards display ~150–180px, so the default is a 160px thumbnail
   * (a 2× DPR cap of 320 is requested so retina stays crisp without pulling the full-res original). */
  imageSize?: number;
  /** Set on the single above-the-fold LCP cover only: eager load + fetchpriority=high. */
  priority?: boolean;
}>;

// Display slot is ~150–180px; request 160 CSS px, capped at 2× DPR for retina.
const GRID_THUMB_PX = 160;
function thumbPx(cssPx: number): number {
  const dpr =
    globalThis.window === undefined ? 1 : Math.min(globalThis.window.devicePixelRatio || 1, 2);
  return Math.round(cssPx * dpr);
}

const WRAPPER_CLASSES =
  'group relative isolate rounded-md p-2 bg-bg-secondary hover:bg-bg-tertiary transition-colors';

export function MediaCard({
  itemId,
  imageTag,
  imageAlt,
  imageShape,
  imageOverlay,
  imageTestId,
  children,
  href,
  testId,
  imageSize = GRID_THUMB_PX,
  priority = false,
}: MediaCardProps) {
  const { hasError, onError } = useImageErrorTracking(itemId, imageTag);
  const { getImageUrl } = useImageUrl();
  const px = thumbPx(imageSize);
  const imageUrl = imageTag
    ? getImageUrl(itemId, 'Primary', { maxWidth: px, maxHeight: px, tag: imageTag })
    : getImagePlaceholder();

  const imageContainerClasses = cn(
    'bg-bg-tertiary relative mb-2 aspect-square overflow-hidden',
    imageShape === 'circle' ? 'rounded-full' : 'rounded-sm'
  );

  const body = (
    <>
      <div className={imageContainerClasses}>
        <img
          data-testid={imageTestId}
          src={hasError ? getImagePlaceholder() : imageUrl}
          alt={imageAlt ?? ''}
          width={imageSize}
          height={imageSize}
          className="h-full w-full object-cover"
          loading={priority ? 'eager' : 'lazy'}
          fetchPriority={priority ? 'high' : undefined}
          onError={onError}
        />
        {imageOverlay}
      </div>
      {children}
    </>
  );

  if (href) {
    return (
      <Link to={href} data-testid={testId} className={cn('block', WRAPPER_CLASSES)}>
        {body}
      </Link>
    );
  }

  return (
    <div data-testid={testId} className={WRAPPER_CLASSES}>
      {body}
    </div>
  );
}
