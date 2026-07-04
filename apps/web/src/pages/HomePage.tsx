import { DailyMix, ExploreNew } from '@/features/library/components';
import { EmptyLibraryGuidance } from '@/features/library/components/EmptyLibraryGuidance';
import { useLibraryTotalCount } from '@/features/library/hooks/useLibraryScan';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';

export function HomePage() {
  const { data: libraryCount, isLoading } = useLibraryTotalCount();
  const isLibraryEmpty = libraryCount?.TotalRecordCount === 0;

  let content;
  if (isLoading) {
    // Avoid flashing DailyMix/ExploreNew before swapping to EmptyLibraryGuidance
    // once the count query resolves — wait for a definitive answer first.
    content = <LoadingSpinner />;
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
