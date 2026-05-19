import { test as authTest } from './auth.fixture';
import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';

type AccessibilityFixtures = {
  checkAccessibility: (page: Page, options?: { excludeRules?: string[] }) => Promise<void>;
};

export const test = authTest.extend<AccessibilityFixtures>({
  checkAccessibility: async ({}, use) => {
    const checker = async (page: Page, options?: { excludeRules?: string[] }) => {
      let builder = new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']);

      if (options?.excludeRules) {
        builder = builder.disableRules(options.excludeRules);
      }

      const results = await builder.analyze();

      if (results.violations.length > 0) {
        const violationsSummary = results.violations
          .map(v => {
            const nodes = v.nodes
              .slice(0, 3)
              .map(n => `    ${n.html}`)
              .join('\n');
            return `- ${v.id}: ${v.description} (${v.nodes.length} occurrences)\n${nodes}`;
          })
          .join('\n');
        throw new Error(`Accessibility violations found:\n${violationsSummary}`);
      }
    };

    await use(checker);
  },
});

export { expect } from './auth.fixture';
