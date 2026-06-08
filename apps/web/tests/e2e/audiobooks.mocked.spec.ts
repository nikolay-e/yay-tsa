import { test, expect, type Page, type Route } from '@playwright/test';

// Backend-free Audiobooks-tab suite (chromium-mocked project): every /api/* call is stubbed, so this
// runs without a live backend. It exercises the real Audiobooks tab in a browser — the Continue
// Listening / In Progress / Finished grouping derived from /v1/me/audiobooks, plus the
// mark-finished → restart status transition that flips a book into "relistening" with position 0.
//
//   npx playwright test --project=chromium-mocked audiobooks.mocked.spec.ts

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };
const TRANSPARENT_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64'
);

interface MockResume {
  positionMs: number;
  runTimeMs: number;
  status: 'not_started' | 'in_progress' | 'finished' | 'relistening';
  updatedAt: string;
}

function audiobookItem(id: string, name: string) {
  return {
    Id: id,
    Name: name,
    Type: 'Audio',
    MediaType: 'Book',
    Genres: ['Audiobook'],
    RunTimeTicks: 670_000_000_000,
    Artists: ['Narrator'],
    AlbumPrimaryImageTag: 'tag',
    UserData: { PlaybackPositionTicks: 0, PlayCount: 0, IsFavorite: false, Played: false },
  };
}

function entry(id: string, name: string, resume: MockResume) {
  const remainingMs = Math.max(0, resume.runTimeMs - resume.positionMs);
  const progressPercent =
    resume.runTimeMs > 0 ? Math.floor((resume.positionMs / resume.runTimeMs) * 100) : 0;
  return { item: audiobookItem(id, name), resume: { ...resume, remainingMs, progressPercent } };
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

  void page.route('**/api/**', (route: Route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: [], TotalRecordCount: 0, StartIndex: 0 }),
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  void page.route(/\/Users\/AuthenticateByName/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ AccessToken: VALID_TOKEN, User: USER, SessionInfo: {} }),
    })
  );
  void page.route(/\/Users\/Me(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(USER) })
  );
  void page.route(/\/Images\//, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: TRANSPARENT_PNG })
  );

  // Device-sync endpoints return arrays; the RootLayout RemotePlaybackBanner calls devices.filter.
  void page.route(/\/v1\/me\/devices(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  );

  void page.route(/\/v1\/me\/audiobooks(\?|$)/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        entry('book1', 'The Name of the Wind', resumes.book1!),
        entry('book2', 'The Wise Man Fear', resumes.book2!),
        entry('book3', 'The Slow Regard', resumes.book3!),
        entry('book4', 'The Doors of Stone', resumes.book4!),
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

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill('mock-user');
  await page.getByLabel('Password').fill('mock-pass');
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL('/', { timeout: 15000 });
}

test.describe('Audiobooks tab (mocked backend)', () => {
  test('groups books into Continue Listening, In Progress and Finished', async ({ page }) => {
    installMock(page);
    await login(page);

    await page.goto('/audiobooks');
    await expect(page.getByTestId('audiobooks-page')).toBeVisible();

    // Continue Listening hero = most recently listened unfinished book.
    const hero = page.getByTestId('audiobook-continue');
    await expect(hero).toBeVisible();
    await expect(hero).toContainText('The Name of the Wind');
    await expect(hero).toContainText('10% ·');

    // Both unfinished books appear under In Progress; the finished one does not.
    await expect(page.getByRole('heading', { name: 'In Progress' })).toBeVisible();
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

  test('mark finished then restart moves a book through the lifecycle', async ({ page }) => {
    installMock(page);
    await login(page);
    await page.goto('/audiobooks');

    // Mark the hero book finished → it should leave In Progress and gain a Restart control.
    const hero = page.getByTestId('audiobook-continue');
    await hero.getByTestId('audiobook-finish').click();

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
