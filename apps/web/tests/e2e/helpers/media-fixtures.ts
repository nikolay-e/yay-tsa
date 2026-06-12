import type { Page, Route } from '@playwright/test';

export const VALID_TOKEN = 'mock-access-token';
export const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };

// The boilerplate every mocked-backend suite needs before its scenario-specific routes:
// a benign JSON catch-all (specific routes registered later take precedence), token auth,
// session validation, placeholder cover art, and the device-sync array stub (RootLayout's
// RemotePlaybackBanner calls devices.filter, so the object catch-all would crash the page).
export function installBaseMock(page: Page): void {
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
  void page.route(/\/v1\/me\/devices(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  );
}

export async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill('mock-user');
  await page.getByLabel('Password').fill('mock-pass');
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL('/', { timeout: 15000 });
}

// Minimal valid 8-bit mono PCM WAV (~3s of silence) — small but decodable, so Chromium can
// actually load it, seek within it, and advance currentTime in mocked-backend E2E suites.
export function buildWav(): Buffer {
  const sampleRate = 8000;
  const seconds = 3;
  const numSamples = sampleRate * seconds;
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + numSamples, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate, 28);
  header.writeUInt16LE(1, 32);
  header.writeUInt16LE(8, 34);
  header.write('data', 36);
  header.writeUInt32LE(numSamples, 40);
  const data = Buffer.alloc(numSamples, 128);
  return Buffer.concat([header, data]);
}

// Longer 16-bit variant for suites that play through queues without hitting track end.
export function silentWav(seconds = 30): Buffer {
  const sampleRate = 8000;
  const numSamples = sampleRate * seconds;
  const dataSize = numSamples * 2;
  const buf = Buffer.alloc(44 + dataSize);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + dataSize, 4);
  buf.write('WAVE', 8);
  buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(dataSize, 40);
  return buf;
}

export const TRANSPARENT_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64'
);
