import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { DailyMix } from '@/features/library/components';
import { DAILY_MIX_QUERY_KEY } from '@/features/library/hooks/useDailyMix';
import { RadioSeeds } from '@/features/radio';

export function HomePage() {
  const queryClient = useQueryClient();
  const [isRegenerating, setIsRegenerating] = useState(false);

  const handleRegenerate = async () => {
    setIsRegenerating(true);
    try {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['radio', 'seeds'] }),
        queryClient.invalidateQueries({ queryKey: DAILY_MIX_QUERY_KEY }),
      ]);
    } finally {
      setIsRegenerating(false);
    }
  };

  return (
    <div className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <h1 className="sr-only">Home</h1>
        <button
          type="button"
          onClick={handleRegenerate}
          disabled={isRegenerating}
          aria-label="Regenerate recommendations"
          className="text-text-secondary hover:text-text-primary hover:bg-bg-secondary ml-auto flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm transition-colors disabled:opacity-50"
        >
          <RefreshCw
            className={`h-4 w-4 ${isRegenerating ? 'animate-spin' : ''}`}
            aria-hidden="true"
          />
          <span>Regenerate</span>
        </button>
      </div>
      <RadioSeeds />
      <DailyMix />
    </div>
  );
}
