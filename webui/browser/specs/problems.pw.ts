import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

// Vanilla item icons load from jsDelivr at runtime; keep these specs offline
// and deterministic (vanilla-icons.pw.ts serves a fake manifest instead).
test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

async function repeatDiagnostics(page: Page): Promise<void> {
  await page.evaluate(() => {
    const controls = window.__materioneTestControls;
    if (!controls) throw new Error('Browser fixture controls are missing.');
    const diagnostic = {
      code: 'MTR-9002',
      severity: 'ERROR',
      message: 'A repeated texture problem.',
      resource: 'fixture_pack:ruby_blade',
      location: 'fixture_pack/items/ruby_blade.yml:3:3',
    };
    controls.emitPush('push.diagnostic', diagnostic);
    controls.emitPush('push.diagnostic', diagnostic);
    controls.emitPush('push.diagnostic', {
      ...diagnostic,
      severity: 'WARN',
      resource: 'fixture_pack:ruby_a',
      location: 'fixture_pack/items/shared_gems.yml:2:3',
    });
  });
}

for (const timing of ['before opening', 'while open'] as const) {
  test(`Problems preserves repeated diagnostics ${timing} without crashing navigation`, async ({ page }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(error.message));
    await page.goto('/browser/fixture.html');
    await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
    const problemsButton = page.getByRole('button', { name: /^Problems(?:, .+)?$/ });
    if (timing === 'while open') await problemsButton.click();
    await repeatDiagnostics(page);
    if (timing === 'before opening') await problemsButton.click();

    const problems = page.getByRole('region', { name: 'Problems', exact: true });
    await expect(problems.getByText('A repeated texture problem.', { exact: true })).toHaveCount(3);
    await expect(problems.getByRole('button', { name: 'all 4', exact: true })).toBeVisible();
    await problems.getByRole('button', { name: '1 warnings', exact: true }).click();
    await expect(problems.getByText('A repeated texture problem.', { exact: true })).toHaveCount(1);
    await expect(problems.getByRole('button', {
      name: 'Open items/shared_gems.yml in Files at fixture_pack/items/shared_gems.yml:2:3',
    })).toBeVisible();
    await problems.getByRole('button', { name: '3 errors', exact: true }).click();
    await expect(problems.getByText('A repeated texture problem.', { exact: true })).toHaveCount(2);
    await problems.getByRole('button', { name: 'all 4', exact: true }).click();

    await page.getByRole('button', { name: 'Content', exact: true }).click();
    await expect(page.getByRole('listbox', { name: /items/i })).toBeVisible();
    await problemsButton.click();
    await expect(problems.getByText('A repeated texture problem.', { exact: true })).toHaveCount(3);
    expect(pageErrors).toEqual([]);
    expect(await page.evaluate(() => window.__materioneTestControls?.writes())).toEqual([]);
  });
}
