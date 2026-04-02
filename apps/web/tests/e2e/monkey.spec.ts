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

authTest('monkey testing - pure chaos', async ({ authenticatedPage: page }) => {
  const jsErrors: string[] = [];
  const uncaughtExceptions: string[] = [];
  collectJsErrors(page, jsErrors, uncaughtExceptions);

  await page.goto('/', { waitUntil: 'commit' });

  let iteration = 0;
  let consecutiveFails = 0;
  const MAX = 1000;
  const RECOVER_AFTER_FAILS = 5;

  while (iteration < MAX) {
    if (iteration % 100 === 0) console.log(`[iter ${iteration}/${MAX}] errors=${jsErrors.length}`);

    if (page.url().includes('/login')) {
      console.log(`[iter ${iteration}] landed on /login (session lost) — re-authenticating`);
      await reAuthenticate(page);
      consecutiveFails = 0;
      iteration++;
      continue;
    }

    if (consecutiveFails >= RECOVER_AFTER_FAILS) {
      console.log(`[iter ${iteration}] hung/failed ${consecutiveFails}x — recovering to /`);
      await recoverPage(page);
      consecutiveFails = 0;
    }

    if (iteration > 0 && iteration % 150 === 0) {
      page.goto(pick(RANDOM_PATHS), { waitUntil: 'commit', timeout: 4000 }).catch(() => {});
    }

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

  console.log(
    `\n=== DONE: ${iteration} iterations, ${jsErrors.length} JS errors, ${uncaughtExceptions.length} uncaught ===`
  );
  uncaughtExceptions.forEach((e, i) => console.log(`  UNCAUGHT[${i + 1}]: ${e}`));

  expect(uncaughtExceptions).toHaveLength(0);
  await expect(page.locator('body')).toBeVisible();
});
