import { test as base, expect, type Page } from '@playwright/test';

// Auto-fixture that fails any spec which leaks an uncaught pageerror, a
// console.error, or an unexpected 4xx/5xx response. Compose it into the spec's
// base (auth.fixture composes it) so every flow inherits "0 pageerror + 0
// console.error + 0 unexpected 4xx/5xx" with no per-spec wiring. This is the
// committed pre-merge enforcement of QA.md's "0 console errors" mandate.

// 401/404 are part of normal flows (probing auth state, missing-art fallbacks).
// Extend per-spec via the `allowConsole` / `allowResponses` option fixtures.
const ALWAYS_ALLOWED_STATUSES = new Set([401, 404]);

const DEFAULT_CONSOLE_ALLOW: RegExp[] = [
  // Browser-extension / benign-quirk noise that is never the app's bug.
  /ResizeObserver loop/i,
  /(chrome|moz|safari)-extension:\/\//i,
  // Chromium logs every 4xx/5xx response to the console at error level. The
  // statuses in ALWAYS_ALLOWED_STATUSES are normal flow (auth probe / missing-art
  // fallback), so allow the browser's console message about them too — keeps the
  // console check consistent with the response check.
  /Failed to load resource: the server responded with a status of (401|404)\b/,
];

type ConsoleGuardOptions = {
  allowConsole: RegExp[];
  allowResponses: RegExp[];
};

type ConsoleGuardFixtures = {
  consoleGuard: void;
};

function isAllowed(text: string, patterns: RegExp[]): boolean {
  return patterns.some(p => p.test(text));
}

export const test = base.extend<ConsoleGuardOptions & ConsoleGuardFixtures>({
  allowConsole: [[], { option: true }],
  allowResponses: [[], { option: true }],

  consoleGuard: [
    async ({ page, allowConsole, allowResponses }, use) => {
      const consoleErrors: string[] = [];
      const pageErrors: string[] = [];
      const badResponses: string[] = [];
      const consoleAllow = [...DEFAULT_CONSOLE_ALLOW, ...allowConsole];

      const onPageError = (err: Error) => {
        pageErrors.push(err.message);
      };
      const onConsole = (msg: { type(): string; text(): string }) => {
        if (msg.type() !== 'error') return;
        const text = msg.text();
        if (isAllowed(text, consoleAllow)) return;
        consoleErrors.push(text);
      };
      const onResponse = (response: { status(): number; url(): string; request(): unknown }) => {
        const status = response.status();
        if (status < 400) return;
        const url = response.url();
        if (ALWAYS_ALLOWED_STATUSES.has(status)) return;
        if (isAllowed(url, allowResponses)) return;
        badResponses.push(`${status} ${url}`);
      };

      page.on('pageerror', onPageError);
      page.on('console', onConsole as Parameters<Page['on']>[1]);
      page.on('response', onResponse as Parameters<Page['on']>[1]);

      await use();

      page.off('pageerror', onPageError);
      page.off('console', onConsole as Parameters<Page['on']>[1]);
      page.off('response', onResponse as Parameters<Page['on']>[1]);

      expect(pageErrors, `Uncaught page errors:\n${pageErrors.join('\n')}`).toHaveLength(0);
      expect(consoleErrors, `console.error output:\n${consoleErrors.join('\n')}`).toHaveLength(0);
      expect(
        badResponses,
        `Unexpected 4xx/5xx responses:\n${badResponses.join('\n')}`
      ).toHaveLength(0);
    },
    { auto: true },
  ],
});

export { expect } from '@playwright/test';
