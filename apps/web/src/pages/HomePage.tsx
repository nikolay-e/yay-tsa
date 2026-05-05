import { DailyMix } from '@/features/library/components';

export function HomePage() {
  return (
    <div className="p-4">
      <h1 className="sr-only">Home</h1>
      <DailyMix />
    </div>
  );
}
