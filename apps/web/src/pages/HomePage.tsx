import { QueueView } from '@/features/player/components/QueueView';
import { RadioSeeds } from '@/features/radio';

export function HomePage() {
  return (
    <div className="p-4">
      <h1 className="sr-only">Home</h1>
      <RadioSeeds />
      <QueueView />
    </div>
  );
}
