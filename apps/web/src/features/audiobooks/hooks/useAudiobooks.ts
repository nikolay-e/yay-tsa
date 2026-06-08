import { useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { AudiobooksService, type AudiobookEntry } from '@yay-tsa/core';
import { useClient } from '@/features/auth/stores/auth.store';

const AUDIOBOOKS_QUERY_KEY = ['audiobooks'] as const;

export type BookStatus = 'not_started' | 'in_progress' | 'finished';

// A book groups its chapter tracks (all sharing an album) into a single shelf entry,
// so a 50-chapter audiobook is one card, not fifty.
export interface AudiobookBook {
  albumId: string;
  title: string;
  author: string;
  coverItemId: string;
  coverImageTag?: string;
  chapters: AudiobookEntry[];
  totalChapters: number;
  finishedChapters: number;
  status: BookStatus;
  // Index into `chapters` the primary action starts/resumes from.
  resumeChapterIndex: number;
  resumeChapterName: string;
  progressPercent: number;
  lastUpdatedAt: string;
}

export interface GroupedAudiobooks {
  continueListening: AudiobookBook | null;
  inProgress: AudiobookBook[];
  finished: AudiobookBook[];
  notStarted: AudiobookBook[];
}

const titleCollator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

function chapterIsFinished(entry: AudiobookEntry): boolean {
  return entry.resume.status === 'finished';
}

function chapterIsStarted(entry: AudiobookEntry): boolean {
  return (
    entry.resume.status === 'finished' ||
    entry.resume.status === 'relistening' ||
    (entry.resume.status === 'in_progress' && entry.resume.positionMs > 0)
  );
}

function buildBook(albumId: string, entries: AudiobookEntry[]): AudiobookBook {
  const chapters = [...entries].sort((a, b) =>
    titleCollator.compare(a.item.Name ?? '', b.item.Name ?? '')
  );
  const total = chapters.length;
  const finished = chapters.filter(chapterIsFinished).length;

  // Resume at the chapter actively in progress; otherwise the first unfinished one;
  // a fully finished book restarts from the top.
  let resumeChapterIndex = chapters.findIndex(
    c => c.resume.status === 'in_progress' || c.resume.status === 'relistening'
  );
  if (resumeChapterIndex < 0) resumeChapterIndex = chapters.findIndex(c => !chapterIsFinished(c));
  if (resumeChapterIndex < 0) resumeChapterIndex = 0;

  const status: BookStatus =
    total > 0 && finished === total
      ? 'finished'
      : chapters.some(chapterIsStarted)
        ? 'in_progress'
        : 'not_started';

  const current = chapters[resumeChapterIndex];
  const currentFraction = current ? current.resume.progressPercent / 100 : 0;
  const progressPercent =
    total > 0 ? Math.min(100, Math.floor(((finished + currentFraction) / total) * 100)) : 0;

  const lastUpdatedAt = chapters.reduce(
    (max, c) => (c.resume.updatedAt > max ? c.resume.updatedAt : max),
    chapters[0]?.resume.updatedAt ?? ''
  );

  const first = chapters[0]?.item;
  return {
    albumId,
    title: first?.Album ?? first?.Name ?? 'Unknown',
    author: (first?.Artists ?? []).join(', '),
    coverItemId: first?.Id ?? albumId,
    coverImageTag: first?.AlbumPrimaryImageTag ?? first?.ImageTags?.Primary,
    chapters,
    totalChapters: total,
    finishedChapters: finished,
    status,
    resumeChapterIndex,
    resumeChapterName: chapters[resumeChapterIndex]?.item.Name ?? '',
    progressPercent,
    lastUpdatedAt,
  };
}

function groupAudiobooks(entries: AudiobookEntry[]): GroupedAudiobooks {
  const byAlbum = new Map<string, AudiobookEntry[]>();
  for (const entry of entries) {
    // Chapters without an album are treated as their own single-chapter book.
    const key = entry.item.AlbumId ?? `track:${entry.item.Id}`;
    const bucket = byAlbum.get(key);
    if (bucket) bucket.push(entry);
    else byAlbum.set(key, [entry]);
  }

  const books = [...byAlbum.entries()].map(([albumId, group]) => buildBook(albumId, group));

  const byTitle = (a: AudiobookBook, b: AudiobookBook) => titleCollator.compare(a.title, b.title);
  const byRecency = (a: AudiobookBook, b: AudiobookBook) =>
    Date.parse(b.lastUpdatedAt) - Date.parse(a.lastUpdatedAt);

  const inProgress = books.filter(b => b.status === 'in_progress').sort(byRecency);
  const finished = books.filter(b => b.status === 'finished').sort(byRecency);
  const notStarted = books.filter(b => b.status === 'not_started').sort(byTitle);

  return {
    continueListening: inProgress[0] ?? null,
    inProgress,
    finished,
    notStarted,
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

  // Book-level actions fan out across every chapter so a whole book can be marked
  // finished or reset in one click.
  const markFinished = useMutation({
    mutationFn: async (itemIds: string[]) => {
      if (!client) throw new Error('Not authenticated');
      const service = new AudiobooksService(client);
      await Promise.all(itemIds.map(async id => service.markFinished(id)));
    },
    onSuccess: invalidate,
  });

  const restart = useMutation({
    mutationFn: async (itemIds: string[]) => {
      if (!client) throw new Error('Not authenticated');
      const service = new AudiobooksService(client);
      await Promise.all(itemIds.map(async id => service.restart(id)));
    },
    onSuccess: invalidate,
  });

  return { markFinished, restart };
}
