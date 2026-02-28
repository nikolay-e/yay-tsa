import { AdaptiveQueueView } from '@/features/player/components/AdaptiveQueueView';
import { DjPreferencesPanel } from '@/features/player/components/DjPreferencesPanel';

export function HomePage() {
  return (
    <div className="space-y-4 p-4">
      <AdaptiveQueueView />

      <DjPreferencesPanel />
    </div>
  );
}
