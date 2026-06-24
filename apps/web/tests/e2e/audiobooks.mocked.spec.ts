import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';

// Backend-free Audiobooks-tab suite (chromium-mocked project): every /api/* call is stubbed, so this
// runs without a live backend. It exercises the real Audiobooks tab in a browser — the Continue
// Listening / In Progress / Finished grouping derived from /v1/me/audiobooks, plus the
// mark-finished → restart status transition that flips a book into "relistening" with position 0.
//
//   npx playwright test --project=chromium-mocked audiobooks.mocked.spec.ts

import { buildWav, installBaseMock, login } from './helpers/media-fixtures';

const WAV = buildWav();

interface MockResume {
  positionMs: number;
  runTimeMs: number;
  status: 'not_started' | 'in_progress' | 'finished' | 'relistening';
  updatedAt: string;
}

interface Book {
  id: string;
  name: string;
}

function audiobookItem(id: string, name: string, album?: Book) {
  return {
    Id: id,
    Name: name,
    Type: 'Audio',
    MediaType: 'Book',
    Genres: ['Audiobook'],
    RunTimeTicks: 670_000_000_000,
    Artists: ['Narrator'],
    AlbumPrimaryImageTag: 'tag',
    AlbumId: album?.id,
    Album: album?.name,
    UserData: { PlaybackPositionTicks: 0, PlayCount: 0, IsFavorite: false, Played: false },
  };
}

function entry(id: string, name: string, resume: MockResume, album?: Book) {
  const remainingMs = Math.max(0, resume.runTimeMs - resume.positionMs);
  const progressPercent =
    resume.runTimeMs > 0 ? Math.floor((resume.positionMs / resume.runTimeMs) * 100) : 0;
  return {
    item: audiobookItem(id, name, album),
    resume: { ...resume, remainingMs, progressPercent },
  };
}

function installMock(page: Page): void {
  const resumes: Record<string, MockResume> = {
    book1: {
      positionMs: 1_800_000,
      runTimeMs: 18_000_000,
      status: 'in_progress',
      updatedAt: '2026-06-06T10:00:00Z',
    },
    book2: {
      positionMs: 9_000_000,
      runTimeMs: 18_000_000,
      status: 'in_progress',
      updatedAt: '2026-06-05T10:00:00Z',
    },
    book3: {
      positionMs: 17_500_000,
      runTimeMs: 18_000_000,
      status: 'finished',
      updatedAt: '2026-06-01T10:00:00Z',
    },
    book4: {
      positionMs: 0,
      runTimeMs: 18_000_000,
      status: 'not_started',
      updatedAt: '1970-01-01T00:00:00Z',
    },
  };

  installBaseMock(page);

  // Range-aware stream stub: Chromium refuses to seek media served without byte-range support,
  // silently snapping currentTime back to 0 — which is exactly the regression these tests guard.
  void page.route(/\/Audio\/[^/]+\/stream/, (route: Route) => {
    const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers()['range'] ?? '');
    if (range) {
      const start = Number(range[1]);
      const end = range[2] ? Math.min(Number(range[2]), WAV.length - 1) : WAV.length - 1;
      return route.fulfill({
        status: 206,
        headers: {
          'Content-Type': 'audio/wav',
          'Accept-Ranges': 'bytes',
          'Content-Range': `bytes ${start}-${end}/${WAV.length}`,
        },
        body: WAV.subarray(start, end + 1),
      });
    }
    return route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'audio/wav', 'Accept-Ranges': 'bytes' },
      body: WAV,
    });
  });

  void page.route(/\/v1\/me\/audiobooks(\?|$)/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        entry('book1', 'The Name of the Wind', resumes.book1!),
        entry('book2', 'The Wise Man Fear', resumes.book2!),
        entry('book3', 'The Slow Regard', resumes.book3!),
        entry('book4', 'The Doors of Stone', resumes.book4!),
        // A multi-chapter book: three chapters sharing one album must collapse into a
        // single "Dune" card under Library, not three separate rows.
        entry('dune-1', 'Глава 01', resumes.book4!, { id: 'album-dune', name: 'Dune' }),
        entry('dune-2', 'Глава 02', resumes.book4!, { id: 'album-dune', name: 'Dune' }),
        entry('dune-3', 'Глава 03', resumes.book4!, { id: 'album-dune', name: 'Dune' }),
        // Out-of-order listening: chapter 1 was sampled earlier, chapter 3 is where the listener
        // actually left off (latest updatedAt). Resume must pick chapter 3, not the first in-progress.
        entry(
          'found-1',
          'Foundation Ch 1',
          {
            positionMs: 5_000_000,
            runTimeMs: 18_000_000,
            status: 'in_progress',
            updatedAt: '2026-06-02T10:00:00Z',
          },
          { id: 'album-foundation', name: 'Foundation' }
        ),
        entry('found-2', 'Foundation Ch 2', resumes.book4!, {
          id: 'album-foundation',
          name: 'Foundation',
        }),
        entry(
          'found-3',
          'Foundation Ch 3',
          {
            positionMs: 3_000_000,
            runTimeMs: 18_000_000,
            status: 'in_progress',
            updatedAt: '2026-06-08T10:00:00Z',
          },
          { id: 'album-foundation', name: 'Foundation' }
        ),
        // A short book matching the 3-second mocked WAV stream, so resume-seek and live playback
        // land at real, assertable currentTime offsets within the decodable audio.
        {
          item: {
            ...audiobookItem('seek-book', 'Project Hail Mary'),
            RunTimeTicks: 30_000_000,
            UserData: {
              PlaybackPositionTicks: 15_000_000,
              PlayCount: 0,
              IsFavorite: false,
              Played: false,
            },
          },
          resume: {
            positionMs: 1_500,
            runTimeMs: 3_000,
            status: 'in_progress',
            updatedAt: '2026-06-07T10:00:00Z',
            remainingMs: 1_500,
            progressPercent: 50,
          },
        },
      ]),
    })
  );

  void page.route(/\/v1\/me\/audiobooks\/([^/]+)\/finished/, (route: Route) => {
    const id = route.request().url().split('/audiobooks/')[1]!.split('/')[0]!;
    resumes[id] = { ...resumes[id]!, status: 'finished', updatedAt: '2026-06-07T10:00:00Z' };
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...resumes[id], status: 'finished' }),
    });
  });

  void page.route(/\/v1\/me\/audiobooks\/([^/]+)\/restart/, (route: Route) => {
    const id = route.request().url().split('/audiobooks/')[1]!.split('/')[0]!;
    resumes[id] = {
      positionMs: 0,
      runTimeMs: 18_000_000,
      status: 'relistening',
      updatedAt: '2026-06-07T11:00:00Z',
    };
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ positionMs: 0, status: 'relistening' }),
    });
  });
}

test.describe('Audiobooks tab (mocked backend)', () => {
  test('groups books into In Progress, Finished and Library', async ({ page }) => {
    installMock(page);
    await login(page);

    await page.goto('/audiobooks');
    await expect(page.getByTestId('audiobooks-page')).toBeVisible();

    // Both unfinished books appear under In Progress (most-recently-listened first); the finished one
    // does not. There is no separate "Continue Listening" hero — it duplicated the top In Progress card.
    await expect(page.getByRole('heading', { name: 'In Progress' })).toBeVisible();
    const recent = page.getByTestId('audiobook-card').filter({ hasText: 'The Name of the Wind' });
    await expect(recent).toBeVisible();
    await expect(recent).toContainText('10% ·');
    await expect(
      page.getByTestId('audiobook-card').filter({ hasText: 'The Wise Man Fear' })
    ).toBeVisible();

    // Finished section holds the completed book with a Restart action.
    await expect(page.getByRole('heading', { name: 'Finished' })).toBeVisible();
    const finished = page.getByTestId('audiobook-card').filter({ hasText: 'The Slow Regard' });
    await expect(finished).toBeVisible();
    await expect(finished.getByTestId('audiobook-restart')).toBeVisible();

    // Never-played books surface under Library with a Start action and no progress/finish controls.
    await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible();
    const notStarted = page.getByTestId('audiobook-card').filter({ hasText: 'The Doors of Stone' });
    await expect(notStarted).toBeVisible();
    await expect(notStarted).toContainText('Not started');
    await expect(notStarted.getByTestId('audiobook-resume')).toContainText('Start');
    await expect(notStarted.getByTestId('audiobook-finish')).toHaveCount(0);
  });

  test('collapses a multi-chapter book into a single card', async ({ page }) => {
    installMock(page);
    await login(page);

    await page.goto('/audiobooks');
    await expect(page.getByTestId('audiobooks-page')).toBeVisible();

    // The three "Dune" chapters share one album → exactly one card titled "Dune",
    // labelled with the chapter count, never three "Глава NN" rows.
    const dune = page.getByTestId('audiobook-card').filter({ hasText: 'Dune' });
    await expect(dune).toHaveCount(1);
    await expect(dune).toContainText('3 chapters');
    await expect(page.getByTestId('audiobook-card').filter({ hasText: 'Глава 01' })).toHaveCount(0);
  });

  test('resumes the last-listened chapter, not the first in-progress one', async ({ page }) => {
    installMock(page);
    await login(page);
    await page.goto('/audiobooks');
    await expect(page.getByTestId('audiobooks-page')).toBeVisible();

    // Foundation: chapter 1 in-progress (older), chapter 3 in-progress (latest). The card's resume
    // pointer must be chapter 3 — "continue where I left off" — so it reads "Chapter 3 of 3".
    const foundation = page.getByTestId('audiobook-card').filter({ hasText: 'Foundation' });
    await expect(foundation).toContainText('Chapter 3 of 3');
  });

  test('mark finished then restart moves a book through the lifecycle', async ({ page }) => {
    installMock(page);
    await login(page);
    await page.goto('/audiobooks');

    // Mark the most-recently-listened book finished → it should leave In Progress and gain Restart.
    const recent = page.getByTestId('audiobook-card').filter({ hasText: 'The Name of the Wind' });
    await recent.getByTestId('audiobook-finish').click();

    const finishedCard = page
      .getByTestId('audiobook-card')
      .filter({ hasText: 'The Name of the Wind' });
    await expect(finishedCard.getByTestId('audiobook-restart')).toBeVisible({ timeout: 10000 });

    // Restart → status flips to relistening (an unfinished status), so it returns to In Progress.
    await finishedCard.getByTestId('audiobook-restart').click();
    await expect(
      page
        .getByTestId('audiobook-card')
        .filter({ hasText: 'The Name of the Wind' })
        .getByTestId('audiobook-finish')
    ).toBeVisible({ timeout: 10000 });
  });

  test('resume seeks the audio engine to the saved position, not the start', async ({ page }) => {
    installMock(page);
    await login(page);
    await page.goto('/audiobooks');

    const card = page.getByTestId('audiobook-card').filter({ hasText: 'Project Hail Mary' });
    await card.getByTestId('audiobook-resume').click();

    // The first observable engine position must already be the seeked resume point (~1.5s of the
    // 3s WAV) — a start-from-zero regression would surface here as a sub-second first sample.
    await page.waitForFunction(() => (document.querySelector('audio')?.currentTime ?? 0) > 0, {
      timeout: 15000,
    });
    const firstObserved = await page.evaluate(
      () => document.querySelector('audio')?.currentTime ?? 0
    );
    expect(firstObserved).toBeGreaterThan(1.4);
  });

  test('listened position survives a reload even when the server snapshot is stale', async ({
    page,
  }) => {
    installMock(page);
    await login(page);
    await page.goto('/audiobooks');

    const card = page.getByTestId('audiobook-card').filter({ hasText: 'Project Hail Mary' });
    await card.getByTestId('audiobook-resume').click();

    // Listen past the server-known 1.5s so the localStorage write-through holds a fresher position.
    await page.waitForFunction(() => (document.querySelector('audio')?.currentTime ?? 0) > 1.9, {
      timeout: 15000,
    });

    // The mocked server keeps returning 1.5s forever — after a reload, the local-first merge must
    // still resume from the freshest locally written position, not the stale server snapshot.
    await page.reload();
    await expect(page.getByTestId('audiobooks-page')).toBeVisible();
    await page
      .getByTestId('audiobook-card')
      .filter({ hasText: 'Project Hail Mary' })
      .getByTestId('audiobook-resume')
      .click();

    await page.waitForFunction(() => (document.querySelector('audio')?.currentTime ?? 0) > 0, {
      timeout: 15000,
    });
    const resumedAt = await page.evaluate(() => document.querySelector('audio')?.currentTime ?? 0);
    expect(resumedAt).toBeGreaterThan(1.8);
  });

  test('playback position syncs to the backend continuously while playing', async ({ page }) => {
    installMock(page);
    const progressTicks: number[] = [];
    await page.route(/\/Sessions\/Playing\/Progress/, route => {
      const body = route.request().postDataJSON() as { PositionTicks: number };
      progressTicks.push(body.PositionTicks);
      return route.fulfill({ status: 204, body: '' });
    });
    await login(page);
    await page.goto('/audiobooks');

    await page
      .getByTestId('audiobook-card')
      .filter({ hasText: 'The Doors of Stone' })
      .getByTestId('audiobook-resume')
      .click();

    // The audiobook heartbeat reports every 2s of playback — the 3s WAV must produce at least
    // two progress posts with a strictly advancing position before it ends.
    await expect.poll(() => progressTicks.length, { timeout: 15000 }).toBeGreaterThanOrEqual(2);
    expect(progressTicks.at(-1)!).toBeGreaterThan(progressTicks[0]!);
  });

  test('nav exposes an Audiobooks entry that routes to the tab', async ({ page }) => {
    installMock(page);
    await login(page);

    const navAudiobooks = page.getByTestId('nav-audiobooks').first();
    await expect(navAudiobooks).toBeVisible();
    await navAudiobooks.click();
    await expect(page).toHaveURL(/\/audiobooks/);
    await expect(page.getByTestId('audiobooks-page')).toBeVisible();
  });
});
