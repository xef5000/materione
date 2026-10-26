import { expect, test } from '@playwright/test';
import '../contract';

async function openFixture(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
}

test('Activity is absent by default and the sidebar toggles the whole panel', async ({ page }) => {
  await openFixture(page);

  await expect(page.locator('#activity-dock')).toHaveCount(0);
  await expect(page.locator('footer, .statusbar')).toHaveCount(0);

  const activity = page.locator('.rail').getByRole('button', { name: /^Activity(?:,|$)/ });
  await activity.click();
  await expect(page.locator('#activity-dock')).toBeVisible();

  await activity.click();
  await expect(page.locator('#activity-dock')).toHaveCount(0);

  await activity.click();
  await page.getByRole('button', { name: 'Close Activity' }).click();
  await expect(page.locator('#activity-dock')).toHaveCount(0);
  await expect(activity).toBeFocused();
  await page.evaluate(() => window.__materioneTestControls?.emitPush('push.diagnostic', {}));
  await expect(page.locator('#activity-dock')).toHaveCount(0);
  await expect(activity).toHaveAccessibleName('Activity, 1 error');
});

test('collapsing navigation labels preserves group spacing without horizontal overflow', async ({ page }) => {
  await openFixture(page);

  const rail = page.locator('.rail');
  const names = ['Content', 'Files', 'Problems', 'Activity', 'Server'];
  const before = await Promise.all(names.map(async (name) => {
    const button = rail.getByRole('button', { name: new RegExp(`^${name}(?:,|$)`) });
    const box = await button.boundingBox();
    if (!box) throw new Error(`${name} navigation button is not visible.`);
    return box.y;
  }));

  await page.getByRole('button', { name: 'Collapse navigation labels' }).click();

  const after = await Promise.all(names.map(async (name) => {
    const button = rail.getByRole('button', { name: new RegExp(`^${name}(?:,|$)`) });
    const box = await button.boundingBox();
    if (!box) throw new Error(`${name} navigation button is not visible after collapsing.`);
    return box.y;
  }));

  expect(after).toEqual(before);
  expect(await rail.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  await page.getByRole('button', { name: 'Expand navigation labels' }).click();
});

test('Server disconnect keeps dirty document drafts when its confirmation is canceled', async ({ page }) => {
  await openFixture(page);
  await page.locator('.rail').getByRole('button', { name: 'Files', exact: true }).click();
  await page.getByRole('button', { name: /ruby_blade\.yml/i }).click();

  const displayName = page.getByLabel('Display name', { exact: true });
  await displayName.fill('Unsaved draft remains');
  await displayName.press('Tab');
  await expect(displayName).toHaveValue('Unsaved draft remains');

  await page.locator('.rail').getByRole('button', { name: 'Server', exact: true }).click();
  let confirmation = '';
  page.once('dialog', async (dialog) => {
    expect(dialog.type()).toBe('confirm');
    confirmation = dialog.message();
    await dialog.dismiss();
  });
  await page.getByRole('button', { name: 'disconnect', exact: true }).click();

  expect(confirmation).toContain('Disconnect and discard local drafts?');
  await page.locator('.rail').getByRole('button', { name: 'Files', exact: true }).click();
  await expect(page.getByLabel('Display name', { exact: true })).toHaveValue('Unsaved draft remains');
});

test('audit trail keeps separate events with identical timestamp, operation, and target', async ({ page }) => {
  await openFixture(page);
  await page.evaluate(() => {
    const controls = window.__materioneTestControls;
    if (!controls) throw new Error('Browser fixture controls are missing.');
    const ts = '2026-10-25T20:00:00.000Z';
    controls.setAuditEntries([
      { ts, actor: 'alice', op: 'reload', target: 'fixture_pack' },
      { ts, actor: 'bob', op: 'reload', target: 'fixture_pack' },
    ]);
  });

  await page.locator('.rail').getByRole('button', { name: 'Server', exact: true }).click();
  await expect(page.locator('table tbody tr')).toHaveCount(2);
});
