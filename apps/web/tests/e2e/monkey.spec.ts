import type { Locator, Page } from '@playwright/test';
import { test as authTest, expect } from './fixtures/auth.fixture';
import { TEST_CREDENTIALS } from './helpers/test-config';

authTest.setTimeout(5 * 60 * 1000);

const KEYS = [
  ' ',
  'Escape',
  'Enter',
  'ArrowLeft',
  'ArrowRight',
  'ArrowUp',
  'ArrowDown',
  'Tab',
  'Backspace',
  'Delete',
  'Home',
  'End',
  'PageUp',
  'PageDown',
  'MediaPlayPause',
  'MediaTrackNext',
  'MediaTrackPrevious',
  'Control+a',
  'Control+z',
  'Shift+Tab',
  'Control+ArrowRight',
  'Control+ArrowLeft',
  'Alt+ArrowLeft',
  'Alt+ArrowRight',
  'Shift+End',
  'Shift+Home',
  'Control+Shift+End',
  'F5',
  'F11',
];

const TEXTS = [
  'the',
  'love',
  'rock',
  '',
  '!@#$%^&*()',
  'a',
  '   ',
  'x'.repeat(200),
  '0',
  '日本語テスト',
  'привет мир',
  '\n\n\n',
  '"><script>alert(1)</script>',
  "'; DROP TABLE users; --",
  '\u0000\u0001\u0002',
  '',
  'null',
  'undefined',
  'NaN',
  '-1',
  '99999999',
  'true',
  '{}',
  '[]',
];

const VIEWPORTS = [
  { width: 375, height: 812 },
  { width: 414, height: 896 },
  { width: 768, height: 1024 },
  { width: 1280, height: 800 },
  { width: 1920, height: 1080 },
  { width: 320, height: 568 },
  { width: 2560, height: 1440 },
  { width: 360, height: 640 },
];

const RANDOM_PATHS = [
  '/',
  '/library',
  '/search',
  '/settings',
  '/queue',
  '/favorites',
  '/playlists',
  '/history',
  '/nonexistent-page-404',
  '/items/00000000-0000-0000-0000-000000000000',
];

function pick<T>(arr: T[]): T {
  return arr[Math.floor(Math.random() * arr.length)];
}

function weighted(weights: number[]): number {
  const total = weights.reduce((a, b) => a + b, 0);
  let r = Math.random() * total;
  for (let i = 0; i < weights.length; i++) {
    r -= weights[i];
    if (r <= 0) return i;
  }
  return weights.length - 1;
}

async function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  return Promise.race([promise, new Promise<null>(resolve => setTimeout(() => resolve(null), ms))]);
}

async function recoverPage(page: Page): Promise<void> {
  try {
    await page.goto('/', { waitUntil: 'commit', timeout: 5000 });
  } catch {
    // if goto itself hangs, force reload
    try {
      await page.reload({ waitUntil: 'commit', timeout: 3000 });
    } catch {
      /* give up */
    }
  }
}

function scrollAllContainers(page: Page): void {
  page
    .evaluate(() => {
      const dirs = [0, 1, -1];
      const y = Math.random() < 0.4 ? document.body.scrollHeight : Math.random() * 9999;
      globalThis.scrollTo({ top: y, behavior: 'instant' });
      document.querySelectorAll<HTMLElement>('*').forEach(el => {
        if (el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1) {
          const s = globalThis.getComputedStyle(el);
          const scrollsY = s.overflowY === 'auto' || s.overflowY === 'scroll';
          const scrollsX = s.overflowX === 'auto' || s.overflowX === 'scroll';
          if (scrollsY) {
            const dir = dirs[Math.floor(Math.random() * dirs.length)];
            if (dir === 1) el.scrollTop = el.scrollHeight;
            else if (dir === -1) el.scrollTop = 0;
            else el.scrollTop = Math.random() * el.scrollHeight;
          }
          if (scrollsX) {
            el.scrollLeft = Math.random() * el.scrollWidth;
          }
        }
      });
    })
    .catch(() => {});
}

function burstKeyboard(page: Page): void {
  const count = Math.floor(Math.random() * 12) + 2;
  for (let b = 0; b < count; b++) page.keyboard.press(pick(KEYS)).catch(() => {});
}

function navigateBackForward(page: Page): void {
  const count = Math.floor(Math.random() * 6) + 1;
  for (let b = 0; b < count; b++) {
    if (Math.random() < 0.5) page.goBack({ timeout: 300 }).catch(() => {});
    else page.goForward({ timeout: 300 }).catch(() => {});
  }
}

function randomResize(page: Page): void {
  page.setViewportSize(pick(VIEWPORTS)).catch(() => {});
}

function coordClick(page: Page): void {
  const { width, height } = page.viewportSize() ?? { width: 1280, height: 800 };
  const count = Math.random() < 0.3 ? 10 : Math.ceil(Math.random() * 3);
  for (let i = 0; i < count; i++) {
    page.mouse
      .click(Math.floor(Math.random() * width), Math.floor(Math.random() * height))
      .catch(() => {});
  }
}

function dragSliders(page: Page): void {
  page
    .evaluate(() => {
      document.querySelectorAll<HTMLInputElement>('input[type="range"]').forEach(el => {
        const min = Number.parseFloat(el.min || '0');
        const max = Number.parseFloat(el.max || '100');
        el.value = String(min + Math.random() * (max - min));
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      });
    })
    .catch(() => {});
}

function injectDomEvents(page: Page): void {
  page
    .evaluate(() => {
      const all = Array.from(document.querySelectorAll<HTMLElement>('*'));
      const picks = Array.from({ length: 20 }, () => all[Math.floor(Math.random() * all.length)]);
      picks.forEach(el => {
        const eventType = [
          'mousedown',
          'mouseup',
          'mousemove',
          'touchstart',
          'touchend',
          'pointerdown',
          'pointerup',
          'wheel',
          'focus',
          'blur',
          'input',
          'keydown',
          'keyup',
          'click',
          'contextmenu',
        ][Math.floor(Math.random() * 15)];
        try {
          if (eventType === 'touchstart' || eventType === 'touchend') {
            const touch = new Touch({
              identifier: Date.now(),
              target: el,
              clientX: Math.random() * 400,
              clientY: Math.random() * 800,
            });
            el.dispatchEvent(
              new TouchEvent(eventType, {
                bubbles: true,
                touches: [touch],
                changedTouches: [touch],
              })
            );
          } else if (eventType === 'wheel') {
            el.dispatchEvent(
              new WheelEvent('wheel', {
                bubbles: true,
                deltaY: (Math.random() - 0.5) * 2000,
                deltaX: (Math.random() - 0.5) * 500,
              })
            );
          } else {
            el.dispatchEvent(new MouseEvent(eventType, { bubbles: true, cancelable: true }));
          }
        } catch {
          /* ignore */
        }
      });
    })
    .catch(() => {});
}

const MALICIOUS_PAYLOADS = [
  "'; DROP TABLE items; --",
  '"><script>alert(1)</script>',
  '../../../etc/passwd',
  '{{7*7}}',
  '${7*7}',
  '%00%0d%0aX-Injected: header',
  'UNION SELECT * FROM api_tokens--',
  '\x00\x01\x02\x03',
  'a'.repeat(10000),
  '日本語メタル',
  '{"$gt":""}',
  '-1 OR 1=1',
  '00000000-0000-0000-0000-000000000000',
  'not-a-uuid',
  '../../audio/stream',
  '%2e%2e%2f%2e%2e%2f',
  'null',
  'undefined',
  'NaN',
  'Infinity',
  '-Infinity',
  '0',
  '-1',
  '99999999999999',
  Array(50).fill('A').join(','),
];

const ZERO_UUID = '00000000-0000-0000-0000-000000000000';

const API_CHAOS_ENDPOINTS = [
  // Items — boundary params
  { method: 'GET', path: '/Items?IncludeItemTypes=INVALID&Recursive=true' },
  { method: 'GET', path: '/Items?IncludeItemTypes=Audio,Video,FAKE,,,&Recursive=true' },
  { method: 'GET', path: '/Items?Limit=0&Recursive=true' },
  { method: 'GET', path: '/Items?Limit=-1&Recursive=true' },
  { method: 'GET', path: '/Items?Limit=-999999&Recursive=true' },
  { method: 'GET', path: '/Items?Limit=999999&Recursive=true' },
  { method: 'GET', path: '/Items?StartIndex=-1&Recursive=true' },
  { method: 'GET', path: '/Items?StartIndex=999999999&Recursive=true' },
  { method: 'GET', path: '/Items?Recursive=true&SortBy=NONEXISTENT' },
  { method: 'GET', path: '/Items?Recursive=true&SortOrder=BACKWARDS' },
  { method: 'GET', path: '/Items?Recursive=true&Fields=Everything,Nothing' },
  { method: 'GET', path: `/Items?Recursive=true&ArtistIds=${ZERO_UUID},not-uuid,${ZERO_UUID}` },
  { method: 'GET', path: `/Items?Recursive=true&AlbumIds=${ZERO_UUID}` },
  { method: 'GET', path: `/Items?Recursive=true&GenreIds=${ZERO_UUID}` },
  { method: 'GET', path: '/Items?Recursive=true&IsFavorite=maybe' },
  { method: 'GET', path: `/Items?Recursive=true&Ids=${Array(100).fill(ZERO_UUID).join(',')}` },

  // Items — malformed IDs
  { method: 'GET', path: '/Items/not-a-uuid' },
  { method: 'GET', path: `/Items/${ZERO_UUID}` },
  { method: 'GET', path: '/Items/../../etc/passwd' },
  { method: 'GET', path: "/Items/'; DROP TABLE items;--" },
  { method: 'GET', path: '/Items/' + 'A'.repeat(1000) },

  // Streaming — path traversal, malformed
  { method: 'GET', path: '/Audio/not-a-uuid/stream' },
  { method: 'GET', path: `/Audio/${ZERO_UUID}/stream` },
  { method: 'GET', path: '/Audio/../../etc/passwd/stream' },
  { method: 'HEAD', path: `/Audio/${ZERO_UUID}/stream` },

  // Images — malformed
  { method: 'GET', path: `/Items/${ZERO_UUID}/Images/Primary` },
  { method: 'GET', path: `/Items/${ZERO_UUID}/Images/NONEXISTENT` },
  { method: 'GET', path: `/Items/${ZERO_UUID}/Images/Primary?maxWidth=-1&maxHeight=-1` },
  { method: 'GET', path: `/Items/${ZERO_UUID}/Images/Primary?maxWidth=99999&quality=999` },
  { method: 'GET', path: `/Items/${ZERO_UUID}/Images/Primary?tag=../../../../etc/passwd` },

  // Sessions — malformed bodies
  { method: 'POST', path: '/Sessions/Playing', body: '{}' },
  { method: 'POST', path: '/Sessions/Playing', body: '{"invalid": true}' },
  { method: 'POST', path: '/Sessions/Playing', body: 'NOT JSON AT ALL' },
  { method: 'POST', path: '/Sessions/Playing', body: '{' },
  { method: 'POST', path: '/Sessions/Playing/Progress', body: '{"PositionTicks": -1}' },
  {
    method: 'POST',
    path: '/Sessions/Playing/Progress',
    body: '{"PositionTicks": 9999999999999999}',
  },
  { method: 'POST', path: '/Sessions/Playing/Stopped', body: '{"PositionTicks": -999}' },
  { method: 'POST', path: '/Sessions/Logout' },

  // Auth — injection
  { method: 'POST', path: '/Users/AuthenticateByName', body: '{"Username":"","Pw":""}' },
  {
    method: 'POST',
    path: '/Users/AuthenticateByName',
    body: `{"Username":"${MALICIOUS_PAYLOADS[0]}","Pw":"x"}`,
  },
  { method: 'POST', path: '/Users/AuthenticateByName', body: '{"Username":null,"Pw":null}' },
  {
    method: 'POST',
    path: '/Users/AuthenticateByName',
    body: `{"Username":"${'A'.repeat(10000)}","Pw":"x"}`,
  },

  // Users — unauthorized access attempts
  { method: 'GET', path: '/Users/not-a-uuid' },
  { method: 'GET', path: `/Users/${ZERO_UUID}` },
  { method: 'GET', path: `/Users/${ZERO_UUID}/FavoriteItems` },
  { method: 'GET', path: `/Users/${ZERO_UUID}/Items` },
  { method: 'GET', path: `/Users/${ZERO_UUID}/Items/Resume` },
  { method: 'POST', path: `/Users/Me/FavoriteItems/${ZERO_UUID}` },
  { method: 'DELETE', path: `/Users/Me/FavoriteItems/${ZERO_UUID}` },
  { method: 'DELETE', path: `/Users/${ZERO_UUID}/FavoriteItems/${ZERO_UUID}` },

  // Playlists — CRUD chaos
  { method: 'GET', path: '/Playlists/not-a-uuid/Items' },
  { method: 'GET', path: `/Playlists/${ZERO_UUID}/Items` },
  { method: 'POST', path: '/Playlists', body: '{"Name":""}' },
  { method: 'POST', path: '/Playlists', body: '{}' },
  { method: 'POST', path: '/Playlists', body: `{"Name":"${'X'.repeat(5000)}"}` },
  { method: 'DELETE', path: `/Playlists/${ZERO_UUID}` },
  { method: 'POST', path: `/Playlists/${ZERO_UUID}/Items`, body: `{"Ids":["${ZERO_UUID}"]}` },
  { method: 'POST', path: `/Playlists/${ZERO_UUID}/Items/${ZERO_UUID}/Move/0` },
  { method: 'POST', path: `/Playlists/${ZERO_UUID}/Items/${ZERO_UUID}/Move/-1` },
  { method: 'POST', path: `/Playlists/${ZERO_UUID}/Items/${ZERO_UUID}/Move/999999` },

  // Karaoke / Lyrics
  { method: 'GET', path: `/Karaoke/${ZERO_UUID}/status` },
  { method: 'POST', path: `/Karaoke/${ZERO_UUID}/process` },
  { method: 'GET', path: `/Karaoke/${ZERO_UUID}/status/stream` },
  { method: 'GET', path: `/Lyrics/${ZERO_UUID}` },
  { method: 'POST', path: `/Lyrics/${ZERO_UUID}/fetch` },
  { method: 'POST', path: `/Lyrics/${ZERO_UUID}/fetch?force=true` },

  // Radio / DJ
  { method: 'POST', path: '/v1/sessions', body: '{}' },
  { method: 'POST', path: '/v1/sessions', body: '{"invalid":true}' },
  { method: 'GET', path: `/v1/sessions/${ZERO_UUID}/queue` },
  { method: 'POST', path: `/v1/sessions/${ZERO_UUID}/queue/refresh` },
  { method: 'POST', path: `/v1/sessions/${ZERO_UUID}/signals`, body: '{}' },
  { method: 'POST', path: `/v1/sessions/${ZERO_UUID}/signals`, body: '{"type":"FAKE_SIGNAL"}' },
  { method: 'GET', path: '/v1/radio/seeds' },
  { method: 'GET', path: '/v1/preferences' },
  { method: 'PUT', path: '/v1/preferences', body: '{"garbage": true}' },

  // Admin — privilege escalation attempts
  { method: 'POST', path: '/Admin/Users', body: '{"Username":"hacker","Password":"pwn"}' },
  { method: 'POST', path: `/Admin/Users/${ZERO_UUID}/ResetPassword` },
  { method: 'DELETE', path: `/Admin/Users/${ZERO_UUID}` },
  { method: 'POST', path: '/Admin/Library/Scan' },
  { method: 'POST', path: '/Admin/Cache/Invalidate' },

  // System
  { method: 'GET', path: '/System/Info/Public' },
  { method: 'GET', path: '/manage/health' },

  // Upload — malformed
  { method: 'POST', path: '/Upload/Track', body: 'not-multipart-data' },
];

const PROTECTED_KEYS = ['yaytsa_session', 'yaytsa_dj_session'];

function storageChaos(page: Page): void {
  page
    .evaluate(
      ({ texts, protectedKeys }) => {
        const store = Math.random() < 0.5 ? localStorage : sessionStorage;
        const action = Math.random();
        if (action < 0.4) {
          store.setItem(`monkey_${Math.random()}`, texts[Math.floor(Math.random() * texts.length)]);
        } else if (action < 0.6 && store.length > 0) {
          const key = store.key(Math.floor(Math.random() * store.length));
          if (key && !protectedKeys.includes(key)) store.removeItem(key);
        } else {
          const key = store.key(0);
          if (key) store.getItem(key);
        }
      },
      { texts: TEXTS, protectedKeys: PROTECTED_KEYS }
    )
    .catch(() => {});
}

function keyBurst(page: Page): void {
  const modifiers = ['Control', 'Shift', 'Alt'];
  const mod = pick(modifiers);
  page.keyboard.down(mod).catch(() => {});
  const count = Math.floor(Math.random() * 8) + 3;
  for (let b = 0; b < count; b++) {
    page.keyboard
      .press(pick(['a', 'z', 'c', 'v', 'x', 's', 'ArrowLeft', 'ArrowRight', 'Enter']))
      .catch(() => {});
  }
  page.keyboard.up(mod).catch(() => {});
}

async function parallelClicks(page: Page): Promise<void> {
  const selector =
    'button, a, [role="button"], [role="tab"], input, [tabindex]:not([tabindex="-1"])';
  const els = (await withTimeout(page.locator(selector).all(), 2000)) ?? [];
  if (els.length > 0) {
    const count = Math.min(12, Math.ceil(Math.random() * 12));
    const batch = [...els].sort(() => Math.random() - 0.5).slice(0, count);
    batch.forEach(el => el.click({ timeout: 150, force: true }).catch(() => {}));
  }
}

function isTextInput(tag: string, type: string): boolean {
  return tag === 'INPUT' && (!type || type === 'text' || type === 'search' || type === 'email');
}

function interactWithElement(
  page: Page,
  el: Locator,
  tag: string,
  type: string,
  opts: string[],
  actionIdx: number
): void {
  if (tag === 'INPUT' && type === 'range') {
    el.fill(String(Math.floor(Math.random() * 100))).catch(() => {});
  } else if (isTextInput(tag, type)) {
    el.click({ timeout: 150, force: true }).catch(() => {});
    if (Math.random() < 0.5) el.fill('').catch(() => {});
    el.pressSequentially(pick(TEXTS), { delay: 0 }).catch(() => {});
  } else if (tag === 'TEXTAREA') {
    el.click({ timeout: 150, force: true }).catch(() => {});
    el.pressSequentially(pick(TEXTS), { delay: 0 }).catch(() => {});
  } else if (tag === 'SELECT') {
    if (opts.length) el.selectOption(pick(opts)).catch(() => {});
  } else if (actionIdx === 6) {
    el.hover({ timeout: 150, force: true }).catch(() => {});
  } else if (actionIdx === 7) {
    el.dblclick({ timeout: 150, force: true }).catch(() => {});
  } else if (actionIdx === 8) {
    el.click({ button: 'right', timeout: 150, force: true }).catch(() => {});
    page.keyboard.press('Escape').catch(() => {});
  } else {
    el.click({ timeout: 150, force: true }).catch(() => {});
    if (actionIdx === 3) page.keyboard.type(pick(TEXTS), { delay: 0 }).catch(() => {});
  }
}

function dispatchSimpleAction(page: Page, actionIdx: number): boolean {
  if (actionIdx === 1) {
    scrollAllContainers(page);
    return true;
  }
  if (actionIdx === 2) {
    burstKeyboard(page);
    return true;
  }
  if (actionIdx === 4) {
    navigateBackForward(page);
    return true;
  }
  if (actionIdx === 5) {
    randomResize(page);
    return true;
  }
  if (actionIdx === 9) {
    coordClick(page);
    return true;
  }
  if (actionIdx === 10) {
    dragSliders(page);
    return true;
  }
  if (actionIdx === 11) {
    injectDomEvents(page);
    return true;
  }
  if (actionIdx === 12) {
    storageChaos(page);
    return true;
  }
  if (actionIdx === 13) {
    keyBurst(page);
    return true;
  }
  return false;
}

async function processBatch(
  page: Page,
  els: Locator[],
  actionIdx: number,
  startIteration: number
): Promise<number> {
  let added = 0;
  const shuffled = [...els].sort(() => Math.random() - 0.5);
  const batchSize = Math.random() < 0.1 ? shuffled.length : Math.ceil(Math.random() * 20);
  for (const el of shuffled.slice(0, batchSize)) {
    try {
      const infoResult = await withTimeout(
        el
          .evaluate(e => ({
            tag: e.tagName,
            type: (e as HTMLInputElement).type || '',
            opts:
              e.tagName === 'SELECT'
                ? Array.from((e as HTMLSelectElement).options).map(o => o.value)
                : [],
          }))
          .catch(() => ({ tag: '', type: '', opts: [] as string[] })),
        3000
      );
      if (infoResult) {
        interactWithElement(page, el, infoResult.tag, infoResult.type, infoResult.opts, actionIdx);
      }
      added++;
      if ((startIteration + added) % 200 === 0) break;
    } catch {
      added++;
    }
  }
  return added;
}

const INTERACTIVE_SELECTOR =
  'button, a, [role="button"], [role="tab"], [role="listitem"], ' +
  'input, select, textarea, [tabindex]:not([tabindex="-1"])';

const ACTION_WEIGHTS = [30, 15, 12, 8, 4, 3, 4, 3, 2, 3, 4, 5, 2, 3, 2];
const ACTION_TIMEOUT_MS = 3000;

async function reAuthenticate(page: Page): Promise<void> {
  try {
    await page.goto('/login', { waitUntil: 'domcontentloaded', timeout: 10000 });
    await page.getByLabel('Username').fill(TEST_CREDENTIALS.USERNAME);
    await page.locator('input[type="password"]').fill(TEST_CREDENTIALS.PASSWORD);
    await page.getByRole('button', { name: 'Sign In' }).click();
    await page.waitForURL('/', { timeout: 15000 });
  } catch {
    await recoverPage(page);
  }
}

async function executeInteractiveAction(
  page: Page,
  actionIdx: number,
  iteration: number
): Promise<{ iterationDelta: number; failed: boolean }> {
  if (actionIdx === 14) {
    const result = await withTimeout(parallelClicks(page), ACTION_TIMEOUT_MS);
    return { iterationDelta: 1, failed: result === null };
  }

  const els =
    (await withTimeout(page.locator(INTERACTIVE_SELECTOR).all(), ACTION_TIMEOUT_MS)) ?? [];
  if (els.length === 0) {
    return { iterationDelta: 1, failed: true };
  }

  const added = await processBatch(page, els, actionIdx, iteration);
  return { iterationDelta: added, failed: false };
}

function apiChaos(page: Page, serverErrors: string[]): void {
  const endpoints = Array.from({ length: 3 + Math.floor(Math.random() * 5) }, () =>
    pick(API_CHAOS_ENDPOINTS)
  );
  const searchPayload = pick(MALICIOUS_PAYLOADS);

  page
    .evaluate(
      async ({
        endpoints: eps,
        searchPayload: sp,
        maliciousPayloads,
      }: {
        endpoints: typeof API_CHAOS_ENDPOINTS;
        searchPayload: string;
        maliciousPayloads: string[];
      }) => {
        const token = sessionStorage.getItem('yaytsa_session') || '';
        const headers: Record<string, string> = {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        };

        const requests: Promise<Response>[] = [];

        for (const ep of eps) {
          requests.push(
            fetch(ep.path, {
              method: ep.method,
              headers,
              body: ep.method !== 'GET' && ep.method !== 'HEAD' ? ep.body || null : undefined,
            }).catch(() => new Response(null, { status: 0 }))
          );
        }

        requests.push(
          fetch(
            `/Items?SearchTerm=${encodeURIComponent(sp)}&Recursive=true&IncludeItemTypes=Audio`,
            { headers }
          ).catch(() => new Response(null, { status: 0 }))
        );

        const toxicSearch = maliciousPayloads[Math.floor(Math.random() * maliciousPayloads.length)];
        requests.push(
          fetch(
            `/Items?SearchTerm=${encodeURIComponent(toxicSearch)}&Recursive=true&IncludeItemTypes=Audio,MusicAlbum,MusicArtist`,
            { headers }
          ).catch(() => new Response(null, { status: 0 }))
        );

        if (Math.random() < 0.3) {
          requests.push(
            fetch('/Items?IncludeItemTypes=Audio&Recursive=true&Limit=1', {
              headers: {
                ...headers,
                'Content-Type': 'text/xml',
                'X-Forwarded-For': '127.0.0.1',
                'X-Real-IP': '10.0.0.1',
              },
            }).catch(() => new Response(null, { status: 0 }))
          );
        }

        if (Math.random() < 0.2) {
          requests.push(
            fetch('/Sessions/Playing', {
              method: 'POST',
              headers: { ...headers, 'Content-Type': 'text/plain' },
              body: 'AAAA'.repeat(2500),
            }).catch(() => new Response(null, { status: 0 }))
          );
        }

        if (Math.random() < 0.4) {
          const burst = Array.from({ length: 20 }, () =>
            fetch(`/Items?IncludeItemTypes=Audio&Recursive=true&Limit=1&_bust=${Math.random()}`, {
              headers,
            }).catch(() => new Response(null, { status: 0 }))
          );
          requests.push(...burst);
        }

        const results = await Promise.allSettled(requests);
        return results
          .filter(r => r.status === 'fulfilled')
          .map(r => {
            const resp = (r as PromiseFulfilledResult<Response>).value;
            return { status: resp.status, url: resp.url };
          })
          .filter(r => r.status >= 500);
      },
      { endpoints, searchPayload, maliciousPayloads: MALICIOUS_PAYLOADS }
    )
    .then(errors => {
      errors.forEach(e => {
        const msg = `[API 5xx] ${e.status} ${e.url}`;
        serverErrors.push(msg);
        console.log(`  ${msg}`);
      });
    })
    .catch(() => {});
}

function concurrentMutationStorm(page: Page, serverErrors: string[]): void {
  page
    .evaluate(async () => {
      const token = sessionStorage.getItem('yaytsa_session') || '';
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      };

      const resp = await fetch('/Items?IncludeItemTypes=Audio&Recursive=true&Limit=5', {
        headers,
      }).catch(() => null);
      if (!resp || !resp.ok) return [];

      const data = await resp.json().catch(() => null);
      if (!data?.Items?.length) return [];

      const trackIds = data.Items.map((i: { Id: string }) => i.Id);
      const trackId = trackIds[0];

      const mutations: Promise<Response>[] = [];

      // Favorite toggle storm — 10 concurrent POST + 10 concurrent DELETE on same track
      for (let i = 0; i < 10; i++) {
        mutations.push(
          fetch(`/Users/Me/FavoriteItems/${trackId}`, { method: 'POST', headers }).catch(
            () => new Response(null, { status: 0 })
          )
        );
        mutations.push(
          fetch(`/Users/Me/FavoriteItems/${trackId}`, { method: 'DELETE', headers }).catch(
            () => new Response(null, { status: 0 })
          )
        );
      }

      // Playlist create + delete storm
      for (let i = 0; i < 3; i++) {
        mutations.push(
          fetch('/Playlists', {
            method: 'POST',
            headers,
            body: JSON.stringify({ Name: `monkey-storm-${Date.now()}-${i}` }),
          }).catch(() => new Response(null, { status: 0 }))
        );
      }

      // Session reporting storm — concurrent progress reports
      for (const tid of trackIds.slice(0, 3)) {
        mutations.push(
          fetch('/Sessions/Playing', {
            method: 'POST',
            headers,
            body: JSON.stringify({ ItemId: tid, PositionTicks: 0 }),
          }).catch(() => new Response(null, { status: 0 }))
        );
        mutations.push(
          fetch('/Sessions/Playing/Progress', {
            method: 'POST',
            headers,
            body: JSON.stringify({ ItemId: tid, PositionTicks: Math.floor(Math.random() * 1e10) }),
          }).catch(() => new Response(null, { status: 0 }))
        );
        mutations.push(
          fetch('/Sessions/Playing/Stopped', {
            method: 'POST',
            headers,
            body: JSON.stringify({ ItemId: tid, PositionTicks: Math.floor(Math.random() * 1e10) }),
          }).catch(() => new Response(null, { status: 0 }))
        );
      }

      // Favorite toggle on ALL tracks simultaneously
      for (const tid of trackIds) {
        mutations.push(
          fetch(`/Users/Me/FavoriteItems/${tid}`, { method: 'POST', headers }).catch(
            () => new Response(null, { status: 0 })
          )
        );
      }

      const results = await Promise.allSettled(mutations);
      return results
        .filter(r => r.status === 'fulfilled')
        .map(r => {
          const res = (r as PromiseFulfilledResult<Response>).value;
          return { status: res.status, url: res.url };
        })
        .filter(r => r.status >= 500);
    })
    .then(errors => {
      (errors || []).forEach((e: { status: number; url: string }) => {
        const msg = `[MUTATION 5xx] ${e.status} ${e.url}`;
        serverErrors.push(msg);
        console.log(`  ${msg}`);
      });
    })
    .catch(() => {});
}

function collectJsErrors(page: Page, jsErrors: string[], uncaughtExceptions: string[]): void {
  page.on('pageerror', err => {
    uncaughtExceptions.push(err.message);
    jsErrors.push(err.message);
    console.log(`  [pageerror] ${err.message}`);
  });
  page.on('console', msg => {
    if (msg.type() === 'error') {
      jsErrors.push(msg.text());
      console.log(`  [console.error] ${msg.text()}`);
    }
  });
}

function trackNetworkErrors(page: Page, serverErrors: string[]): void {
  page.on('response', response => {
    if (response.status() >= 500) {
      const msg = `[NET 5xx] ${response.status()} ${response.url()}`;
      serverErrors.push(msg);
      console.log(`  ${msg}`);
    }
  });
}

async function handleLoginRecovery(page: Page, iteration: number): Promise<{ recovered: boolean }> {
  if (!page.url().includes('/login')) return { recovered: false };
  console.log(`[iter ${iteration}] landed on /login (session lost) — re-authenticating`);
  await reAuthenticate(page);
  return { recovered: true };
}

function triggerPeriodicActions(page: Page, iteration: number, serverErrors: string[]): void {
  if (iteration > 0 && iteration % 150 === 0) {
    page.goto(pick(RANDOM_PATHS), { waitUntil: 'commit', timeout: 4000 }).catch(() => {});
  }
  if (iteration > 0 && iteration % 20 === 0) {
    apiChaos(page, serverErrors);
  }
  if (iteration > 0 && iteration % 100 === 0) {
    concurrentMutationStorm(page, serverErrors);
  }
}

authTest('monkey testing - pure chaos', async ({ authenticatedPage: page }) => {
  const jsErrors: string[] = [];
  const uncaughtExceptions: string[] = [];
  const serverErrors: string[] = [];
  collectJsErrors(page, jsErrors, uncaughtExceptions);
  trackNetworkErrors(page, serverErrors);

  await page.goto('/', { waitUntil: 'commit' });

  let iteration = 0;
  let consecutiveFails = 0;
  const MAX = 1000;
  const RECOVER_AFTER_FAILS = 5;

  while (iteration < MAX) {
    if (iteration % 100 === 0)
      console.log(
        `[iter ${iteration}/${MAX}] jsErrors=${jsErrors.length} serverErrors=${serverErrors.length}`
      );

    const { recovered } = await handleLoginRecovery(page, iteration);
    if (recovered) {
      consecutiveFails = 0;
      iteration++;
      continue;
    }

    if (consecutiveFails >= RECOVER_AFTER_FAILS) {
      console.log(`[iter ${iteration}] hung/failed ${consecutiveFails}x — recovering to /`);
      await recoverPage(page);
      consecutiveFails = 0;
    }

    triggerPeriodicActions(page, iteration, serverErrors);

    const actionIdx = weighted(ACTION_WEIGHTS);

    if (dispatchSimpleAction(page, actionIdx)) {
      iteration++;
      consecutiveFails = 0;
      continue;
    }

    const { iterationDelta, failed } = await executeInteractiveAction(page, actionIdx, iteration);
    consecutiveFails = failed ? consecutiveFails + 1 : 0;
    iteration += iterationDelta;
  }

  // Final backend health check
  const healthOk = await page.evaluate(async () => {
    try {
      const resp = await fetch('/System/Info/Public');
      return resp.ok;
    } catch {
      return false;
    }
  });

  console.log(
    `\n=== DONE: ${iteration} iterations, ${jsErrors.length} JS errors, ` +
      `${uncaughtExceptions.length} uncaught, ${serverErrors.length} server 5xx, ` +
      `backend health=${healthOk ? 'OK' : 'DOWN'} ===`
  );
  uncaughtExceptions.forEach((e, i) => console.log(`  UNCAUGHT[${i + 1}]: ${e}`));
  serverErrors.forEach((e, i) => console.log(`  SERVER[${i + 1}]: ${e}`));

  expect(uncaughtExceptions).toHaveLength(0);
  expect(serverErrors).toHaveLength(0);
  expect(healthOk).toBe(true);
  await expect(page.locator('body')).toBeVisible();
});
