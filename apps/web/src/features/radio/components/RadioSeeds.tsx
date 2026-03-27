import { Radio } from 'lucide-react';
import { RADIO_TEST_IDS } from '@/shared/testing/test-ids';
import { useRadioSeeds } from '../hooks/useRadioSeeds';
import { RadioSeedCard } from './RadioSeedCard';

export function RadioSeeds() {
  const { data, isLoading, isError } = useRadioSeeds();

  if (isLoading) {
    return (
      <div className="mb-4" data-testid={RADIO_TEST_IDS.SECTION}>
        <div className="mb-2 flex items-center gap-2">
          <Radio className="text-accent h-4 w-4" />
          <h2 className="text-text-primary text-base font-semibold">Radio</h2>
        </div>
        <div className="flex gap-3 overflow-x-auto pb-2">
          {Array.from({ length: 4 }, (_, i) => (
            <div key={i} className="bg-bg-secondary w-40 shrink-0 rounded-md p-1.5">
              <div className="skeleton-bg animate-skeleton aspect-square rounded-sm" />
              <div className="skeleton-bg animate-skeleton mt-1.5 h-4 w-3/4 rounded" />
              <div className="skeleton-bg animate-skeleton mt-1 h-3 w-1/2 rounded" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (isError || !data?.seeds?.length) return null;

  return (
    <div className="mb-4" data-testid={RADIO_TEST_IDS.SECTION}>
      <div className="mb-2 flex items-center gap-2">
        <Radio className="text-accent h-4 w-4" />
        <h2 className="text-text-primary text-base font-semibold">Radio</h2>
      </div>
      <div className="flex gap-3 overflow-x-auto pb-2">
        {data.seeds.map(seed => (
          <RadioSeedCard key={seed.trackId} seed={seed} />
        ))}
      </div>
    </div>
  );
}
