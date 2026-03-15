import { test as authTest, expect } from './fixtures/auth.fixture';

authTest.setTimeout(15 * 60 * 1000);

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
  '日本語',
  'привет',
  '\n\n\n',
  '"><script>',
];

const VIEWPORTS = [
  { width: 375, height: 812 },
  { width: 414, height: 896 },
  { width: 768, height: 1024 },
  { width: 1280, height: 800 },
  { width: 1920, height: 1080 },
  { width: 320, height: 568 },
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

authTest('monkey testing - pure chaos', async ({ authenticatedPage: page }) => {
  const jsErrors: string[] = [];
  page.on('pageerror', err => jsErrors.push(err.message));
  page.on('console', msg => {
    if (msg.type() === 'error') jsErrors.push(msg.text());
  });

  await page.goto('/');
  await page.waitForLoadState('networkidle');

  let iteration = 0;

  while (iteration < 3000) {
    // Go home every ~100
    if (iteration > 0 && iteration % 100 === 0) {
      console.log(`[${iteration}] --- home ---`);
      await page.goto('/').catch(() => {});
      await page.waitForTimeout(300);
    }

    // Pick a random action category
    // weights: click, scroll, keyboard, type_in_input, nav, resize, hover, dblclick, rightclick, coord_click
    const actionIdx = weighted([35, 20, 15, 10, 5, 5, 4, 3, 2, 1]);

    if (actionIdx === 1) {
      // SCROLL - scroll window + all overflow containers
      const toBottom = Math.random() < 0.5;
      await page
        .evaluate(bottom => {
          const y = bottom ? document.body.scrollHeight : Math.random() * 9999;
          window.scrollTo({ top: y, behavior: 'instant' });
          document.querySelectorAll<HTMLElement>('*').forEach(el => {
            if (el.scrollHeight > el.clientHeight + 1) {
              const s = window.getComputedStyle(el);
              if (s.overflowY === 'auto' || s.overflowY === 'scroll') {
                el.scrollTop = bottom ? el.scrollHeight : Math.random() * el.scrollHeight;
              }
            }
          });
        }, toBottom)
        .catch(() => {});
      await page.waitForTimeout(Math.random() < 0.3 ? 0 : 100);
      iteration++;
      continue;
    }

    if (actionIdx === 2) {
      // KEYBOARD
      const burstCount = Math.random() < 0.3 ? Math.floor(Math.random() * 5) + 2 : 1;
      for (let b = 0; b < burstCount; b++) {
        const key = pick(KEYS);
        await page.keyboard.press(key).catch(() => {});
        await page.waitForTimeout(Math.random() < 0.5 ? 0 : 20);
      }
      console.log(`[${iteration}] key burst x${burstCount}`);
      iteration++;
      continue;
    }

    if (actionIdx === 4) {
      // NAVIGATE BACK/FORWARD
      for (let b = 0; b < (Math.random() < 0.3 ? 3 : 1); b++) {
        if (Math.random() < 0.5) {
          await page.goBack({ timeout: 1000 }).catch(() => {});
        } else {
          await page.goForward({ timeout: 1000 }).catch(() => {});
        }
        await page.waitForTimeout(100);
      }
      console.log(`[${iteration}] nav`);
      iteration++;
      continue;
    }

    if (actionIdx === 5) {
      // RESIZE VIEWPORT
      const vp = pick(VIEWPORTS);
      await page.setViewportSize(vp).catch(() => {});
      console.log(`[${iteration}] resize ${vp.width}x${vp.height}`);
      iteration++;
      continue;
    }

    if (actionIdx === 9) {
      // CLICK RANDOM COORDINATES
      const { width, height } = page.viewportSize() ?? { width: 1280, height: 800 };
      const x = Math.floor(Math.random() * width);
      const y = Math.floor(Math.random() * height);
      await page.mouse.click(x, y).catch(() => {});
      console.log(`[${iteration}] coord click ${x},${y}`);
      iteration++;
      continue;
    }

    // For the rest: need elements on page
    const selector =
      'button, a, [role="button"], [role="tab"], [role="listitem"], ' +
      'input, select, textarea, [tabindex]:not([tabindex="-1"])';
    const els = await page.locator(selector).all();

    if (els.length === 0) {
      await page.waitForTimeout(200);
      iteration++;
      continue;
    }

    const shuffled = [...els].sort(() => Math.random() - 0.5);

    // How many elements to act on this round (burst or single)
    const batchSize = Math.random() < 0.2 ? shuffled.length : Math.ceil(Math.random() * 10);

    for (const el of shuffled.slice(0, batchSize)) {
      try {
        const tag = await el.evaluate(e => e.tagName).catch(() => '');
        const inputType = await el.getAttribute('type').catch(() => '');
        const text = await el.textContent().catch(() => '');
        const testid = await el.getAttribute('data-testid').catch(() => '');
        const label = testid || text?.trim().slice(0, 20) || tag;

        if (tag === 'INPUT' && inputType === 'range') {
          await el.fill(String(Math.floor(Math.random() * 100))).catch(() => {});
        } else if (
          tag === 'INPUT' &&
          (inputType === 'text' || inputType === 'search' || inputType === 'email' || !inputType)
        ) {
          await el.click({ timeout: 300, force: true }).catch(() => {});
          if (Math.random() < 0.5) await el.fill('').catch(() => {});
          await el.pressSequentially(pick(TEXTS), { delay: 0 }).catch(() => {});
        } else if (tag === 'TEXTAREA') {
          await el.click({ timeout: 300, force: true }).catch(() => {});
          await el.pressSequentially(pick(TEXTS), { delay: 0 }).catch(() => {});
        } else if (tag === 'SELECT') {
          const opts = await el
            .evaluate(s => Array.from((s as HTMLSelectElement).options).map(o => o.value))
            .catch(() => [] as string[]);
          if (opts.length) await el.selectOption(pick(opts)).catch(() => {});
        } else if (actionIdx === 6) {
          // HOVER
          await el.hover({ timeout: 300, force: true }).catch(() => {});
        } else if (actionIdx === 7) {
          // DOUBLE CLICK
          await el.dblclick({ timeout: 300, force: true }).catch(() => {});
        } else if (actionIdx === 8) {
          // RIGHT CLICK
          await el.click({ button: 'right', timeout: 300, force: true }).catch(() => {});
          await page.keyboard.press('Escape').catch(() => {});
        } else {
          // Normal click + optionally type if it's an input
          await el.click({ timeout: 300, force: true }).catch(() => {});
          if (actionIdx === 3) {
            // TYPE INTO whatever is now focused
            await page.keyboard.type(pick(TEXTS), { delay: 0 }).catch(() => {});
          }
        }

        console.log(`[${iteration}] ${tag}(${actionIdx}): ${label}`);
        iteration++;
        await page.waitForTimeout(Math.random() < 0.4 ? 0 : 20);

        if (iteration % 100 === 0) break;
      } catch {
        iteration++;
      }
    }
  }

  console.log(`\n=== DONE: ${iteration} iterations, ${jsErrors.length} JS errors ===`);
  jsErrors.slice(0, 20).forEach(e => console.log(`  ERR: ${e}`));

  const crashes = jsErrors.filter(e => e.toLowerCase().includes('crash'));
  expect(crashes).toHaveLength(0);
  await expect(page.locator('body')).toBeVisible();
});
