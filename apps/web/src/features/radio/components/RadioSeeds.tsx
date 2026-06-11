import { Radio, RefreshCw } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { RADIO_TEST_IDS } from '@/shared/testing/test-ids';
import { LoadErrorState } from '@/shared/ui/LoadErrorState';
import { useRadioSeeds } from '../hooks/useRadioSeeds';
import { RadioSeedCard } from './RadioSeedCard';

const RADIO_QUERY_KEY = ['radio', 'seeds'] as const;

function RadioRefreshButton() {
  const queryClient = useQueryClient();
  const [isRefreshing, setIsRefreshing] = useState(false);
  const handleClick = async () => {
    setIsRefreshing(true);
    try {
      await queryClient.invalidateQueries({ queryKey: RADIO_QUERY_KEY });
    } finally {
      setIsRefreshing(false);
    }
  };
  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isRefreshing}
      aria-label="Refresh Radio"
      className="text-text-secondary hover:text-text-primary hover:bg-bg-secondary ml-1 rounded p-1 transition-colors disabled:opacity-50"
    >
      <RefreshCw
        className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`}
        aria-hidden="true"
      />
    </button>
  );
}

export function RadioSeeds() {
  const { data, isLoading, isError, refetch } = useRadioSeeds();

  if (isLoading) {
    return (
      <div className="mb-4" data-testid={RADIO_TEST_IDS.SECTION}>
        <div className="mb-2 flex items-center gap-2">
          <Radio className="text-accent h-4 w-4" />
          <h2 className="text-text-primary text-base font-semibold">Radio</h2>
          <RadioRefreshButton />
        </div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 xl:grid-cols-8">
          {Array.from({ length: 8 }, (_, i) => (
            <div key={i} className="bg-bg-secondary rounded-md p-1.5">
              <div className="skeleton-bg animate-skeleton aspect-square rounded-sm" />
              <div className="skeleton-bg animate-skeleton mt-1.5 h-4 w-3/4 rounded" />
              <div className="skeleton-bg animate-skeleton mt-1 h-3 w-1/2 rounded" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (isError) {
    return (
      <div className="mb-4" data-testid={RADIO_TEST_IDS.SECTION}>
        <div className="mb-2 flex items-center gap-2">
          <Radio className="text-accent h-4 w-4" />
          <h2 className="text-text-primary text-base font-semibold">Radio</h2>
        </div>
        <LoadErrorState
          message="Couldn't load radio stations"
          onRetry={() => {
            void refetch();
          }}
        />
      </div>
    );
  }

  if (!data?.seeds?.length) return null;

  return (
    <div className="mb-4" data-testid={RADIO_TEST_IDS.SECTION}>
      <div className="mb-2 flex items-center gap-2">
        <Radio className="text-accent h-4 w-4" />
        <h2 className="text-text-primary text-base font-semibold">Radio</h2>
        <RadioRefreshButton />
      </div>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 xl:grid-cols-8">
        {data.seeds.map((seed, i) => (
          <RadioSeedCard key={seed.trackId} seed={seed} priority={i === 0} />
        ))}
      </div>
    </div>
  );
}
