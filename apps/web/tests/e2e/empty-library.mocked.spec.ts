import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { installBaseMock, login, VALID_TOKEN } from './helpers/media-fixtures';

const ADMIN_USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: true } };

function installEmptyLibraryMock(
  page: Page,
  opts: { admin?: boolean } = {}
): { scanning: boolean } {
  const scanState = { scanning: false };

  installBaseMock(page);

  void page.route(/\/Items(\?|$)/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: [], TotalRecordCount: 0, StartIndex: 0 }),
    })
  );

  void page.route(/\/Admin\/Library\/ScanStatus/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        scanning: scanState.scanning,
        lastCompletedAt: null,
        lastTrackCount: null,
      }),
    })
  );

  void page.route(/\/Admin\/Library\/Rescan/, (route: Route) => {
    scanState.scanning = true;
    return route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'started' }),
    });
  });

  if (opts.admin) {
    void page.route(/\/Users\/AuthenticateByName/, (route: Route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ AccessToken: VALID_TOKEN, User: ADMIN_USER, SessionInfo: {} }),
      })
    );
    void page.route(/\/Admin\/Users(\?|$)/, (route: Route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    );
    void page.route(/\/Users\/Me(\?|$)/, (route: Route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(ADMIN_USER),
      })
    );
  }

  return scanState;
}

test.describe('Empty library guidance (mocked backend)', () => {
  test('home shows guidance instead of feed sections for a regular user', async ({ page }) => {
    installEmptyLibraryMock(page);
    await login(page);

    const guidance = page.getByTestId('empty-library-guidance');
    await expect(guidance).toBeVisible();
    await expect(guidance).toContainText('Your library is empty');
    await expect(guidance).toContainText('Ask your server admin');
    await expect(page.getByText('Daily Mix')).toHaveCount(0);
    await expect(page.getByTestId('empty-library-rescan-button')).toHaveCount(0);

    await guidance.getByRole('link', { name: 'Open Settings' }).click();
    await expect(page).toHaveURL('/settings');
    await expect(page.getByText('Rescan library')).toHaveCount(0);
  });

  test('admin sees a rescan button that starts a scan', async ({ page }) => {
    installEmptyLibraryMock(page, { admin: true });
    await login(page);

    const rescanButton = page.getByTestId('empty-library-rescan-button');
    await expect(rescanButton).toBeVisible();
    await expect(rescanButton).toContainText('Rescan library');

    await rescanButton.click();

    await expect(page.getByText('Library scan started')).toBeVisible();
    await expect(rescanButton).toContainText('Scan in progress…');
    await expect(rescanButton).toBeDisabled();
  });

  test('admin settings page shows the library scan panel with status', async ({ page }) => {
    installEmptyLibraryMock(page, { admin: true });
    await login(page);

    await page.goto('/settings');
    await expect(page.getByText('Rescan library')).toBeVisible();
    await expect(page.getByTestId('library-scan-status')).toContainText(
      'No completed scan recorded yet'
    );

    await page.getByTestId('library-rescan-button').click();
    await expect(page.getByTestId('library-scan-status')).toContainText('Scan in progress');
    await expect(page.getByTestId('library-rescan-button')).toBeDisabled();
  });
});
