import { DailyMix, ExploreNew } from '@/features/library/components';
import { EmptyLibraryGuidance } from '@/features/library/components/EmptyLibraryGuidance';
import { useLibraryTotalCount } from '@/features/library/hooks/useLibraryScan';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';

export function HomePage() {
  const { data: libraryCount, isLoading, isError, refetch } = useLibraryTotalCount();
  const isLibraryEmpty = libraryCount?.TotalRecordCount === 0;

  let content;
  if (isLoading) {
    // Avoid flashing DailyMix/ExploreNew before swapping to EmptyLibraryGuidance
    // once the count query resolves — wait for a definitive answer first.
    content = <LoadingSpinner />;
  } else if (isError) {
    content = (
      <LoadErrorState
        message="Couldn't load your library"
        onRetry={() => {
          void refetch();
        }}
      />
    );
  } else if (isLibraryEmpty) {
    content = <EmptyLibraryGuidance />;
  } else {
    content = (
      <>
        <DailyMix />
        <ExploreNew />
      </>
    );
  }

  return (
    <div className="p-4">
      <h1 className="sr-only">Home</h1>
      {content}
    </div>
  );
}
