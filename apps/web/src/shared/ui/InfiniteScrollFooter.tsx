import { useEffect } from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { useInView } from '@/shared/hooks/useInView';

interface InfiniteScrollFooterProps {
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  onLoadMore: () => void;
  currentCount: number;
  totalCount: number;
  itemLabel: string;
}

export function InfiniteScrollFooter({
  hasNextPage,
  isFetchingNextPage,
  onLoadMore,
  currentCount,
  totalCount,
  itemLabel,
}: InfiniteScrollFooterProps) {
  const { ref, isInView } = useInView({
    rootMargin: '200px', // Start loading before reaching bottom
    enabled: hasNextPage && !isFetchingNextPage,
  });

  useEffect(() => {
    if (isInView && hasNextPage && !isFetchingNextPage) {
      onLoadMore();
    }
  }, [isInView, hasNextPage, isFetchingNextPage, onLoadMore]);

  return (
    <>
      {hasNextPage && (
        <div ref={ref} className="flex justify-center pt-4">
          <button
            onClick={onLoadMore}
            disabled={isFetchingNextPage}
            className={cn(
              'bg-bg-secondary text-text-primary rounded-md px-6 py-2',
              'hover:bg-bg-tertiary transition-colors',
              'disabled:opacity-50'
            )}
          >
            {isFetchingNextPage ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Load More'}
          </button>
        </div>
      )}

      {totalCount > 0 && (
        <p className="text-text-tertiary text-center text-sm">
          Showing {currentCount} of {totalCount} {itemLabel}
        </p>
      )}
    </>
  );
}
