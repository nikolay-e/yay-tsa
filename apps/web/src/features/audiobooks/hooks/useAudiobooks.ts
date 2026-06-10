import { useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { AudiobooksService, type AudiobookEntry } from '@yay-tsa/core';
import { useClient } from '@/features/auth/stores/auth.store';
import { readLocalResume } from '@/features/audiobooks/stores/local-resume';

const AUDIOBOOKS_QUERY_KEY = ['audiobooks'] as const;
const TICKS_PER_MS = 10_000;

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
  inProgress: AudiobookBook[];
  finished: AudiobookBook[];
  notStarted: AudiobookBook[];
}

const titleCollator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

// Canonical chapter order: disc then track number when the scanner provided them (audiobook files
// almost always carry track numbers), falling back to numeric-aware name collation otherwise.
function compareChapters(a: AudiobookEntry, b: AudiobookEntry): number {
  const aDisc = a.item.ParentIndexNumber ?? 0;
  const bDisc = b.item.ParentIndexNumber ?? 0;
  if (aDisc !== bDisc) return aDisc - bDisc;
  const aIdx = a.item.IndexNumber;
  const bIdx = b.item.IndexNumber;
  if (aIdx != null && bIdx != null && aIdx !== bIdx) return aIdx - bIdx;
  if (aIdx != null && bIdx == null) return -1;
  if (aIdx == null && bIdx != null) return 1;
  return titleCollator.compare(a.item.Name ?? '', b.item.Name ?? '');
}

// Local-first reconciliation: the device's localStorage write-through is the freshest truth for its
// own playback (it survives lost network writes and a stale server cache), while the server is
// authoritative for progress made on other devices. Last-write-wins by updatedAt keeps both correct.
function mergeLocalResume(entry: AudiobookEntry): AudiobookEntry {
  const local = readLocalResume(entry.item.Id);
  if (!local) return entry;
  if (Date.parse(local.updatedAt) <= Date.parse(entry.resume.updatedAt)) return entry;

  const runTimeMs = entry.resume.runTimeMs || local.runTimeMs;
  const positionMs = Math.min(local.positionMs, runTimeMs > 0 ? runTimeMs : local.positionMs);
  const progressPercent = runTimeMs > 0 ? Math.floor((positionMs / runTimeMs) * 100) : 0;
  const status =
    entry.resume.status === 'finished' || entry.resume.status === 'relistening'
      ? entry.resume.status
      : positionMs > 0
        ? 'in_progress'
        : entry.resume.status;

  return {
    item: {
      ...entry.item,
      UserData: {
        PlaybackPositionTicks: status === 'finished' ? 0 : positionMs * TICKS_PER_MS,
        PlayCount: entry.item.UserData?.PlayCount ?? 0,
        IsFavorite: entry.item.UserData?.IsFavorite ?? false,
        Played: entry.item.UserData?.Played ?? status === 'finished',
        Key: entry.item.UserData?.Key,
        ItemId: entry.item.UserData?.ItemId,
      },
    },
    resume: {
      ...entry.resume,
      positionMs,
      runTimeMs,
      progressPercent,
      remainingMs: Math.max(0, runTimeMs - positionMs),
      status,
      updatedAt: local.updatedAt,
    },
  };
}

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
  const chapters = [...entries].sort(compareChapters);
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

function groupAudiobooks(rawEntries: AudiobookEntry[]): GroupedAudiobooks {
  const entries = rawEntries.map(mergeLocalResume);
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
    // Local-first merge (mergeLocalResume) makes same-device staleness moot; a short server
    // staleTime just narrows the cross-device refresh window without hammering the backend.
    staleTime: 10_000,
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
