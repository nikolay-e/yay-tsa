import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const BASE_URL = process.env.CRAWL_URL || 'https://yay-tsa.com';
const USERNAME = process.env.CRAWL_USERNAME || 'test';
const PASSWORD = process.env.CRAWL_PASSWORD || '';
const MAX_PAGES = parseInt(process.env.CRAWL_MAX_PAGES || '50', 10);
const WAIT_MS = parseInt(process.env.CRAWL_WAIT_MS || '2000', 10);

const visited = new Set();
const queue = [];
const results = {
  pagesVisited: 0,
  jsErrors: [],
  networkErrors: [],
  axeViolations: [],
  brokenLinks: [],
  consoleWarnings: [],
};

async function login(page, retries = 3) {
  for (let i = 0; i < retries; i++) {
    try {
      await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle', timeout: 30000 });
      await page.fill('input[type="text"]', USERNAME);
      await page.fill('input[type="password"]', PASSWORD);
      await page.click('button');
      await page.waitForURL(url => !url.toString().includes('/login'), {
        timeout: 30000,
      });
      await page.waitForTimeout(1000);
      return;
    } catch (err) {
      console.log(`Login attempt ${i + 1}/${retries} failed: ${err.message}`);
      if (i < retries - 1) await page.waitForTimeout(5000);
    }
  }
  throw new Error('Login failed after retries');
}

function extractLinks(page) {
  return page.evaluate(baseUrl => {
    const links = new Set();
    document.querySelectorAll('a[href]').forEach(a => {
      const href = a.getAttribute('href');
      if (!href) return;
      if (href.startsWith('/') && !href.startsWith('//')) {
        links.add(href.split('?')[0].split('#')[0]);
      }
      if (href.startsWith(baseUrl)) {
        const path = new URL(href).pathname;
        links.add(path.split('?')[0].split('#')[0]);
      }
    });
    return [...links];
  }, BASE_URL);
}

async function crawlPage(page, path) {
  if (visited.has(path) || visited.size >= MAX_PAGES) return;
  visited.add(path);

  const pageErrors = [];
  const networkFailures = [];
  const warnings = [];

  page.on('pageerror', err => {
    pageErrors.push({ path, error: err.message });
  });

  page.on('console', msg => {
    if (msg.type() === 'error') {
      const text = msg.text();
      if (!text.includes('Failed to load resource')) {
        pageErrors.push({ path, error: `console.error: ${text}` });
      }
    }
    if (msg.type() === 'warning') {
      warnings.push({ path, warning: msg.text() });
    }
  });

  page.on('response', response => {
    const status = response.status();
    const url = response.url();
    if (status >= 400 && !url.includes('/api/v1/sessions/active')) {
      networkFailures.push({ path, url, status });
    }
  });

  try {
    const response = await page.goto(`${BASE_URL}${path}`, {
      waitUntil: 'networkidle',
      timeout: 30000,
    });

    if (!response || response.status() >= 400) {
      results.brokenLinks.push({
        path,
        status: response ? response.status() : 'no response',
      });
    }

    await page.waitForTimeout(WAIT_MS);

    results.pagesVisited++;
    results.jsErrors.push(...pageErrors);
    results.networkErrors.push(...networkFailures);
    results.consoleWarnings.push(...warnings);

    try {
      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'best-practice'])
        .analyze();

      for (const violation of axeResults.violations) {
        results.axeViolations.push({
          path,
          id: violation.id,
          impact: violation.impact,
          description: violation.description,
          nodes: violation.nodes.length,
        });
      }
    } catch {
      // axe can fail on some pages
    }

    const links = await extractLinks(page);
    for (const link of links) {
      if (!visited.has(link) && !queue.includes(link)) {
        queue.push(link);
      }
    }

    const status = response ? response.status() : '?';
    const errorCount = pageErrors.length;
    const axeCount = results.axeViolations.filter(v => v.path === path).length;
    console.log(`  ${status} ${path} | errors:${errorCount} axe:${axeCount} links:${links.length}`);
  } catch (err) {
    results.brokenLinks.push({ path, status: `timeout: ${err.message}` });
    console.log(`  ERR ${path} | ${err.message}`);
  }

  page.removeAllListeners('pageerror');
  page.removeAllListeners('console');
  page.removeAllListeners('response');
}

async function main() {
  if (!PASSWORD) {
    console.error('CRAWL_PASSWORD required');
    process.exit(1);
  }

  console.log(`\nCrawling ${BASE_URL} (max ${MAX_PAGES} pages)\n`);

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    ignoreHTTPSErrors: true,
  });
  const page = await context.newPage();

  await login(page);
  console.log('Logged in\n');

  queue.push('/', '/albums', '/artists', '/songs', '/favorites', '/settings');

  while (queue.length > 0 && visited.size < MAX_PAGES) {
    const path = queue.shift();
    await crawlPage(page, path);
  }

  await browser.close();

  console.log('\n========== CRAWL REPORT ==========');
  console.log(`Pages visited: ${results.pagesVisited}`);
  console.log(`JS errors: ${results.jsErrors.length}`);
  console.log(`Network errors: ${results.networkErrors.length}`);
  console.log(`Axe violations: ${results.axeViolations.length}`);
  console.log(`Broken links: ${results.brokenLinks.length}`);

  if (results.jsErrors.length > 0) {
    console.log('\n--- JS ERRORS ---');
    for (const e of results.jsErrors) {
      console.log(`  [${e.path}] ${e.error}`);
    }
  }

  if (results.networkErrors.length > 0) {
    console.log('\n--- NETWORK ERRORS ---');
    const unique = new Map();
    for (const e of results.networkErrors) {
      const key = `${e.status} ${new URL(e.url).pathname}`;
      if (!unique.has(key)) unique.set(key, e);
    }
    for (const [key, e] of unique) {
      console.log(`  [${e.path}] ${e.status} ${e.url}`);
    }
  }

  if (results.axeViolations.length > 0) {
    console.log('\n--- ACCESSIBILITY VIOLATIONS ---');
    const grouped = new Map();
    for (const v of results.axeViolations) {
      const key = v.id;
      if (!grouped.has(key)) {
        grouped.set(key, { ...v, totalNodes: 0, pages: [] });
      }
      const g = grouped.get(key);
      g.totalNodes += v.nodes;
      g.pages.push(v.path);
    }
    for (const [, v] of grouped) {
      console.log(
        `  [${v.impact}] ${v.id}: ${v.description} (${v.totalNodes} nodes on ${v.pages.length} pages)`
      );
    }
  }

  if (results.brokenLinks.length > 0) {
    console.log('\n--- BROKEN LINKS ---');
    for (const b of results.brokenLinks) {
      console.log(`  ${b.path} -> ${b.status}`);
    }
  }

  console.log('\n==================================');

  const hasFailures =
    results.jsErrors.length > 0 ||
    results.brokenLinks.length > 0 ||
    results.axeViolations.some(v => v.impact === 'critical');

  process.exit(hasFailures ? 1 : 0);
}

main();
