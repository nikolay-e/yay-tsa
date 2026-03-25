import { QueueView } from '@/features/player/components/QueueView';
import { RadioSeeds } from '@/features/radio';

export function HomePage() {
  return (
    <div className="p-4">
      <RadioSeeds />
      <QueueView />
    </div>
  );
}
