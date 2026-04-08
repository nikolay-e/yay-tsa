import { test, expect } from './fixtures/auth.fixture';
import { RADIO_TEST_IDS } from './helpers/test-ids';

const MOCK_SEEDS = [
  {
    trackId: '00000000-0000-0000-0000-000000000001',
    name: 'Mock Radio Track One',
    artistName: 'Mock Artist',
    albumName: 'Mock Album One',
    albumId: '00000000-0000-0000-0000-000000000010',
    imageTag: null,
  },
  {
    trackId: '00000000-0000-0000-0000-000000000002',
    name: 'Mock Radio Track Two',
    artistName: 'Mock Artist',
    albumName: 'Mock Album Two',
    albumId: '00000000-0000-0000-0000-000000000011',
    imageTag: null,
  },
  {
    trackId: '00000000-0000-0000-0000-000000000003',
    name: 'Mock Radio Track Three',
    artistName: 'Mock Artist',
    albumName: 'Mock Album Three',
    albumId: '00000000-0000-0000-0000-000000000012',
    imageTag: null,
  },
  {
    trackId: '00000000-0000-0000-0000-000000000004',
    name: 'Mock Radio Track Four',
    artistName: 'Mock Artist',
    albumName: 'Mock Album Four',
    albumId: '00000000-0000-0000-0000-000000000013',
    imageTag: null,
  },
];

test.describe('Radio', () => {
  test.beforeEach(async ({ authenticatedPage }) => {
    await authenticatedPage.route('**/v1/recommend/radio/seeds', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ seeds: MOCK_SEEDS }),
      });
    });
    await authenticatedPage.goto('/');
  });

  test('should display seed cards on home page', async ({ authenticatedPage }) => {
    const section = authenticatedPage.getByTestId(RADIO_TEST_IDS.SECTION);
    await expect(section).toBeVisible({ timeout: 10000 });

    const cards = section.getByTestId(RADIO_TEST_IDS.SEED_CARD);
    await expect(cards.first()).toBeVisible();
    expect(await cards.count()).toBeGreaterThanOrEqual(2);

    const firstCard = cards.first();
    await expect(firstCard.locator('img')).toBeVisible();
    await expect(firstCard.locator('p').first()).not.toBeEmpty();
  });

  test.skip(
    'should start DJ session when seed card clicked',
    'Requires full recommendation pipeline with real track data and embeddings'
  );

  test.skip(
    'should populate queue after DJ session start',
    'Requires full recommendation pipeline with real track data and embeddings'
  );

  test.skip(
    'should persist session across page reload',
    'Player state (currentTrack) is not persisted across page reload'
  );

  test.skip(
    'should show karaoke button that toggles gracefully',
    'Requires full recommendation pipeline with real track data and embeddings'
  );
});
