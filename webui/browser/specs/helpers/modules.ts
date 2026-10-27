import { expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../../contract';

/**
 * Shared steps for the per-type display + modules specs (issue #153). They run
 * on the deterministic `?modules` fixture, which serves the block, furniture
 * (and later types') schemas straight from the plugin's golden snapshot.
 */
export async function blockCdn(page: Page): Promise<void> {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
}

export async function openModules(page: Page): Promise<string[]> {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.goto('/browser/fixture.html?modules');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  return pageErrors;
}

/** Open a resource of a type from the Content view: the type tab, then the entry. */
export async function openResource(page: Page, tab: string, name: string): Promise<void> {
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: tab }).click();
  await page.getByRole('option', { name: new RegExp(name, 'i') }).dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

export function writes(page: Page) {
  return page.evaluate(() => window.__materioneTestControls?.writes() ?? []);
}

export function documentOf(page: Page, path: string) {
  return page.evaluate(([target]) => {
    const found = window.__materioneTestControls?.documents().find((entry) => entry.path === target);
    return found ? { raw: found.raw, parsed: found.parsed } : null;
  }, [path]);
}

/** Add a module through the picker by searching its label. */
export async function addModule(page: Page, search: string): Promise<void> {
  await page.getByRole('button', { name: 'Add module' }).click();
  await page.getByLabel('Search modules').fill(search);
  await page.getByLabel('Search modules').press('Enter');
}

export async function saveAndApply(page: Page, expectedWrites: number) {
  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(expectedWrites);
  const all = await writes(page);
  return all[all.length - 1]!;
}
