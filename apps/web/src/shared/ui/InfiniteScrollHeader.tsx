import { useEffect, useRef } from 'react';
import { Loader2 } from 'lucide-react';
import { useInView } from '@/shared/hooks/useInView';

type InfiniteScrollHeaderProps = Readonly<{
  hasPreviousPage: boolean;
  isFetchingPreviousPage: boolean;
  onLoadPrevious: () => void;
}>;

export function InfiniteScrollHeader({
  hasPreviousPage,
  isFetchingPreviousPage,
  onLoadPrevious,
}: InfiniteScrollHeaderProps) {
  const { ref, isInView } = useInView({
    rootMargin: '200px',
    enabled: hasPreviousPage && !isFetchingPreviousPage,
  });

  const onLoadPreviousRef = useRef(onLoadPrevious);
  onLoadPreviousRef.current = onLoadPrevious;

  useEffect(() => {
    if (isInView && hasPreviousPage && !isFetchingPreviousPage) {
      onLoadPreviousRef.current();
    }
  }, [isInView, hasPreviousPage, isFetchingPreviousPage]);

  if (!hasPreviousPage && !isFetchingPreviousPage) return null;

  return (
    <div ref={ref} className="flex justify-center pb-4" data-testid="infinite-scroll-header">
      <Loader2
        className={
          isFetchingPreviousPage ? 'text-accent h-4 w-4 animate-spin' : 'h-4 w-4 opacity-0'
        }
      />
    </div>
  );
}
