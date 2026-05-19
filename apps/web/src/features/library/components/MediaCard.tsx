import { type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { cn } from '@/shared/utils/cn';

type MediaCardProps = Readonly<{
  itemId: string;
  imageTag?: string;
  imageAlt: string;
  imageShape: 'square' | 'circle';
  imageOverlay?: ReactNode;
  imageTestId?: string;
  children: ReactNode;
  href?: string;
  testId?: string;
}>;

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
}: MediaCardProps) {
  const { hasError, onError } = useImageErrorTracking(itemId, imageTag);
  const { getImageUrl } = useImageUrl();
  const imageUrl = imageTag
    ? getImageUrl(itemId, 'Primary', { maxWidth: 300, maxHeight: 300, tag: imageTag })
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
          alt={imageAlt}
          className="h-full w-full object-cover"
          loading="lazy"
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
