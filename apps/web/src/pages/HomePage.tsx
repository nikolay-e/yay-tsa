import { DailyMix } from '@/features/library/components';
import { RadioSeeds } from '@/features/radio';

export function HomePage() {
  return (
    <div className="p-4">
      <h1 className="sr-only">Home</h1>
      <RadioSeeds />
      <DailyMix />
    </div>
  );
}
