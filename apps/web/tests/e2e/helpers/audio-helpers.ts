import type { Page } from '@playwright/test';

export async function getVolumeFromLocalStorage(page: Page): Promise<number | null> {
  return page.evaluate(() => {
    const stored = localStorage.getItem('yaytsa_volume');
    return stored ? Number.parseFloat(stored) : null;
  });
}
