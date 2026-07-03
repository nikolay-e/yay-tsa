import { DailyMix, ExploreNew } from '@/features/library/components';
import { EmptyLibraryGuidance } from '@/features/library/components/EmptyLibraryGuidance';
import { useLibraryTotalCount } from '@/features/library/hooks/useLibraryScan';

export function HomePage() {
  const { data: libraryCount } = useLibraryTotalCount();
  const isLibraryEmpty = libraryCount?.TotalRecordCount === 0;

  return (
    <div className="p-4">
      <h1 className="sr-only">Home</h1>
      {isLibraryEmpty ? (
        <EmptyLibraryGuidance />
      ) : (
        <>
          <DailyMix />
          <ExploreNew />
        </>
      )}
    </div>
  );
}
