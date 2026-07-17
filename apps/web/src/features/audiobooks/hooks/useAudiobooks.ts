import { useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { AudiobooksService, TICKS_PER_MS, type AudiobookEntry } from '@yay-tsa/core';
import { useClient } from '@/features/auth/stores/auth.store';
import {
  clearLocalResume,
  readLocalResume,
  useResumeVersion,
} from '@/features/audiobooks/stores/local-resume';

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
  const keepsServerStatus =
    entry.resume.status === 'finished' || entry.resume.status === 'relistening' || positionMs <= 0;
  const status = keepsServerStatus ? entry.resume.status : 'in_progress';

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

  // Resume the chapter most recently listened — the one with the latest resume timestamp among
  // started, unfinished chapters ("continue where I left off", even if you jumped around). Falls
  // back to the first unfinished chapter, then the start of a fully finished book.
  let resumeChapterIndex = -1;
  let latestResumeAt = '';
  chapters.forEach((c, i) => {
    const started = c.resume.status === 'in_progress' || c.resume.status === 'relistening';
    if (started && c.resume.positionMs > 0 && c.resume.updatedAt > latestResumeAt) {
      latestResumeAt = c.resume.updatedAt;
      resumeChapterIndex = i;
    }
  });
  if (resumeChapterIndex < 0) resumeChapterIndex = chapters.findIndex(c => !chapterIsFinished(c));
  if (resumeChapterIndex < 0) resumeChapterIndex = 0;

  const allChaptersFinished = total > 0 && finished === total;
  const anyChapterStarted = chapters.some(chapterIsStarted);
  let status: BookStatus = 'not_started';
  if (allChaptersFinished) status = 'finished';
  else if (anyChapterStarted) status = 'in_progress';

  const current = chapters[resumeChapterIndex];
  const currentFraction = current ? current.resume.progressPercent / 100 : 0;
  const progressPercent =
    total > 0 ? Math.min(100, Math.floor(((finished + currentFraction) / total) * 100)) : 0;

  const lastUpdatedAt = chapters.reduce(
    (max, c) => (c.resume.updatedAt > max ? c.resume.updatedAt : max),
    chapters[0]?.resume.updatedAt ?? ''
  );

  const first = chapters[0]?.item;
  // Chapter tracks carry no primary image of their own — the cover lives on the album entity,
  // requested by AlbumId (as album/track lists do). Only emit the request when a real image
  // tag exists; fabricating one from the album id forced a guaranteed 404 for coverless
  // audiobooks (MediaCard already shows the placeholder when the tag is absent).
  const coverItemId = first?.AlbumId ?? first?.Id ?? albumId;
  return {
    albumId,
    title: first?.Album ?? first?.Name ?? 'Unknown',
    author: (first?.Artists ?? []).join(', '),
    coverItemId,
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

  // resumeVersion bumps when local resume changes (seek/pause/switch) so the merge re-runs against
  // fresh localStorage even when the server response (query.data) hasn't changed — otherwise a book
  // advanced without leaving the page would resume from the stale snapshot taken at mount.
  const resumeVersion = useResumeVersion(state => state.value);
  const grouped = useMemo(() => groupAudiobooks(query.data ?? []), [query.data, resumeVersion]);

  return { ...query, grouped };
}

export function useAudiobookActions() {
  const client = useClient();
  const queryClient = useQueryClient();

  const invalidate = async () => queryClient.invalidateQueries({ queryKey: AUDIOBOOKS_QUERY_KEY });

  // Book-level actions fan out across every chapter so a whole book can be marked
  // finished or reset in one click.
  // A book fans the action out over every chapter. allSettled (not all) means one failing
  // chapter can't abandon the rest, and onSettled always refetches so the shelf reflects whatever
  // actually persisted rather than silently doing nothing.
  const markFinished = useMutation({
    mutationFn: async (itemIds: string[]) => {
      if (!client) throw new Error('Not authenticated');
      const service = new AudiobooksService(client);
      await Promise.allSettled(itemIds.map(async id => service.markFinished(id)));
      // The user explicitly discarded their place; a stale local record must not
      // out-timestamp the server's reset on the next merge.
      itemIds.forEach(clearLocalResume);
    },
    onSettled: invalidate,
  });

  const restart = useMutation({
    mutationFn: async (itemIds: string[]) => {
      if (!client) throw new Error('Not authenticated');
      const service = new AudiobooksService(client);
      await Promise.allSettled(itemIds.map(async id => service.restart(id)));
      itemIds.forEach(clearLocalResume);
    },
    onSettled: invalidate,
  });

  return { markFinished, restart };
}
