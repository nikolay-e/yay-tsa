import { Radio } from 'lucide-react';
import { useRadioSeeds } from '../hooks/useRadioSeeds';
import { RadioSeedCard } from './RadioSeedCard';

export function RadioSeeds() {
  const { data, isLoading, isError } = useRadioSeeds();

  if (isLoading || isError || !data?.seeds?.length) return null;

  return (
    <div className="mb-4">
      <div className="mb-2 flex items-center gap-2">
        <Radio className="text-accent h-4 w-4" />
        <h2 className="text-text-primary text-sm font-semibold">Radio</h2>
      </div>
      <div className="scrollbar-hide flex gap-3 overflow-x-auto pb-2">
        {data.seeds.map(seed => (
          <RadioSeedCard key={seed.trackId} seed={seed} />
        ))}
      </div>
    </div>
  );
}
