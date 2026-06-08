import { useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { AudiobooksService, type AudiobookEntry } from '@yay-tsa/core';
import { useClient } from '@/features/auth/stores/auth.store';

const AUDIOBOOKS_QUERY_KEY = ['audiobooks'] as const;

export interface GroupedAudiobooks {
  continueListening: AudiobookEntry | null;
  inProgress: AudiobookEntry[];
  finished: AudiobookEntry[];
}

function groupAudiobooks(entries: AudiobookEntry[]): GroupedAudiobooks {
  const sorted = [...entries].sort(
    (a, b) => Date.parse(b.resume.updatedAt) - Date.parse(a.resume.updatedAt)
  );
  const inProgress = sorted.filter(
    e =>
      e.resume.status === 'relistening' ||
      (e.resume.status === 'in_progress' && e.resume.positionMs > 0)
  );
  const finished = sorted.filter(e => e.resume.status === 'finished');
  return {
    continueListening: inProgress[0] ?? null,
    inProgress,
    finished,
  };
}

export function useAudiobooks() {
  const client = useClient();
  const query = useQuery({
    queryKey: AUDIOBOOKS_QUERY_KEY,
    enabled: client !== null,
    staleTime: 30_000,
    queryFn: async () => {
      if (!client) return [];
      return new AudiobooksService(client).list();
    },
  });

  const grouped = useMemo(() => groupAudiobooks(query.data ?? []), [query.data]);

  return { ...query, grouped };
}

export function useAudiobookActions() {
  const client = useClient();
  const queryClient = useQueryClient();

  const invalidate = async () => queryClient.invalidateQueries({ queryKey: AUDIOBOOKS_QUERY_KEY });

  const markFinished = useMutation({
    mutationFn: async (itemId: string) => {
      if (!client) throw new Error('Not authenticated');
      return new AudiobooksService(client).markFinished(itemId);
    },
    onSuccess: invalidate,
  });

  const restart = useMutation({
    mutationFn: async (itemId: string) => {
      if (!client) throw new Error('Not authenticated');
      return new AudiobooksService(client).restart(itemId);
    },
    onSuccess: invalidate,
  });

  return { markFinished, restart };
}
