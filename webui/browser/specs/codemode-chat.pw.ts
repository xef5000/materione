/**
 * The assistant panel (issue 348, codemode.md D13), in a real browser against the fixture:
 * - server without codemode capability: plain explanation, no composer, Escape restores focus
 * - local editor (`host=plugin`): `editor.materione.com only` notice, no demo button, no composer, zero external calls
 * - cloud editor (`host=cloud`) without model connected: `No model connected` notice, no demo button, no composer
 * - full Bloodfang scripted-provider E2E: disclosure, missing primitives, repair + auto-apply, inline approval + revert,
 *   script_ended refusal, and New conversation preserving Change log.
 */
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import * as path from 'node:path';
import '../contract';

const SCREENSHOT_DIR = process.env.CHAT_SCREENSHOT_DIR ?? '';

async function maybeShot(page: Page, filename: string): Promise<void> {
  if (!SCREENSHOT_DIR) return;
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, filename), fullPage: false });
}

test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

const rail = (page: Page): Locator => page.getByRole('button', { name: /^Assistant/ });
const panel = (page: Page): Locator => page.getByRole('region', { name: 'Assistant', exact: true });
const composer = (page: Page): Locator => page.locator('#assistant-input');

async function documentRaw(page: Page, targetPath: string): Promise<string | null> {
  return page.evaluate((target) => window.__materioneTestControls!.documents().find((doc) => doc.path === target)?.raw ?? null, targetPath);
}

async function applies(page: Page): Promise<number> {
  return page.evaluate(() => window.__materioneTestControls!.codemode!.calls.filter((call) => call.op === 'authoring.transaction' && !call.dryRun).length);
}

test('server without codemode advertises no assistant capability, shows no composer, and closes on Escape', async ({ page }) => {
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');

  await expect(rail(page)).toBeVisible();
  await rail(page).click();

  await expect(panel(page)).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.activeElement?.id ?? '')).toBe('assistant-title');
  await expect(panel(page).getByRole('note', { name: 'Assistant not available' })).toContainText('codemode');
  await expect(composer(page)).toHaveCount(0);

  await page.keyboard.press('Escape');
  await expect(panel(page)).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => document.activeElement?.id ?? '')).toBe('assistant-toggle');
});

test('local editor on a codemode server shows editor.materione.com only notice and makes zero external requests', async ({ page }) => {
  await page.goto('/browser/fixture.html?codemode');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');

  const externalUrls: string[] = [];
  page.on('request', (req) => {
    const url = req.url();
    if (!url.startsWith('http://127.0.0.1') && !url.startsWith('http://localhost') && !url.startsWith('data:') && !url.startsWith('blob:')) {
      externalUrls.push(url);
    }
  });

  await rail(page).click();
  await expect(panel(page)).toBeVisible();
  await expect(panel(page).getByText('editor.materione.com only', { exact: true })).toBeVisible();
  await expect(panel(page).getByRole('note', { name: 'Available on editor.materione.com' })).toBeVisible();
  await expect(panel(page).getByRole('button', { name: /Use the next recorded message|demo/i })).toHaveCount(0);
  await expect(composer(page)).toHaveCount(0);
  expect(externalUrls).toEqual([]);

  await maybeShot(page, 'assistant-local-notice.png');
});

test('cloud editor without a model connected shows No model connected notice and no composer', async ({ page }) => {
  await page.goto('/browser/fixture.html?codemode&host=cloud');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');

  await rail(page).click();
  await expect(panel(page)).toBeVisible();
  await expect(panel(page).getByText('No model connected', { exact: true })).toBeVisible();
  await expect(panel(page).getByRole('note', { name: 'No model connected' })).toBeVisible();
  await expect(panel(page).getByRole('button', { name: /Use the next recorded message|demo/i })).toHaveCount(0);
  await expect(composer(page)).toHaveCount(0);
});

test('Bloodfang conversation end-to-end: disclosure, missing primitives, repair and auto-apply, inline approval and revert, refusal, and New conversation', async ({ page }) => {
  await page.goto('/browser/fixture.html?codemode&host=cloud');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');

  const prompts = await page.evaluate(() => window.__materioneCodemode!.connectBloodfangChat(0));
  expect(prompts).toHaveLength(3);

  await rail(page).click();
  await expect(panel(page)).toBeVisible();

  // Before first message: disclosure + composer
  await expect(panel(page).locator('section[aria-label="What leaves this page"]')).toBeVisible();
  await expect(composer(page)).toBeVisible();
  await maybeShot(page, 'assistant-disclosure.png');

  // Prompt 1: read-only + failing asAsked -> reports missing primitives (holder, target), 0 applies
  await composer(page).fill(prompts[0]!);
  await composer(page).press('Enter');

  const transcript = panel(page).getByRole('log', { name: 'Conversation' });
  await expect(transcript.getByText('Do you want that version, or should I wait until the server can do the rest?')).toBeVisible({ timeout: 30_000 });
  await expect(transcript.getByText('Read only', { exact: true })).toBeVisible();
  await expect(transcript.getByText('Type error', { exact: true })).toBeVisible();
  await expect(transcript).toContainText('holder');
  await expect(transcript).toContainText('target');
  expect(await applies(page)).toBe(0);

  // Prompt 2: repairs TS error (Type error -> Dry run clean) and auto-applies 4 new documents
  await composer(page).fill(prompts[1]!);
  await composer(page).press('Enter');

  await expect(transcript.getByText(/Bloodfang is in\./)).toBeVisible({ timeout: 30_000 });
  await expect(transcript.getByText('Dry run clean', { exact: true })).toBeVisible();
  expect(await applies(page)).toBe(1);

  for (const createdPath of ['items/bloodfang.yml', 'guis/bloodfang_menu.yml', 'variables/bloodfang_level.yml', 'variables/bloodfang_xp.yml']) {
    expect(await documentRaw(page, createdPath)).not.toBeNull();
  }

  const appliedCard = transcript.getByRole('article').first();
  await expect(appliedCard.getByText('Applied', { exact: true }).first()).toBeVisible();
  await expect(appliedCard.getByRole('button', { name: /Approve|Reject/ })).toHaveCount(0);
  await maybeShot(page, 'assistant-bloodfang-applied.png');

  await panel(page).getByRole('tab', { name: /^Change log \(1\)/ }).click();
  await expect(panel(page).getByRole('article', { name: /^Applied tx-/ })).toHaveCount(1);
  await panel(page).getByRole('tab', { name: 'Conversation' }).click();

  // Prompt 3: modifies existing items/ruby_sword.yml -> pauses in Needs your approval with diff
  const rubyBefore = await documentRaw(page, 'items/ruby_sword.yml');
  await composer(page).fill(prompts[2]!);
  await composer(page).press('Enter');

  await expect(transcript.getByText(/I cannot approve it\./)).toBeVisible({ timeout: 30_000 });
  expect(await applies(page)).toBe(1);

  const waitingCard = transcript.getByRole('article').nth(1);
  await expect(waitingCard.getByText('Needs your approval')).toBeVisible();
  await expect(waitingCard).toContainText('A smith can reforge it into Bloodfang');
  await maybeShot(page, 'assistant-needs-approval.png');

  await waitingCard.getByRole('button', { name: 'Approve and apply' }).click();
  await expect(waitingCard.getByText('Applied', { exact: true }).first()).toBeVisible();
  expect(await applies(page)).toBe(2);
  expect(await documentRaw(page, 'items/ruby_sword.yml')).toContain('A smith can reforge it into Bloodfang');

  // Revert ruby_sword from the Assistant's Change log tab
  await panel(page).getByRole('tab', { name: /^Change log \(2\)/ }).click();
  const rubyEntry = panel(page).getByRole('article', { name: /^Applied tx-/ }).first();
  await rubyEntry.getByRole('button', { name: /^Revert/ }).click();
  const preview = rubyEntry.getByRole('group', { name: 'Revert preview' });
  await expect(preview).toBeVisible();
  await preview.getByRole('button', { name: 'Confirm revert' }).click();
  await expect(rubyEntry.getByText('Reverted', { exact: true }).first()).toBeVisible();
  expect(await documentRaw(page, 'items/ruby_sword.yml')).toBe(rubyBefore);

  // Prompt 4: scripted provider refuses (script_ended)
  await panel(page).getByRole('tab', { name: 'Conversation' }).click();
  await composer(page).fill('And make it glow.');
  await composer(page).press('Enter');
  await expect(transcript.getByRole('status')).toContainText('The recorded demonstration has ended.');

  // New conversation clears transcript while preserving Change log
  await panel(page).getByRole('button', { name: 'New conversation' }).click();
  await expect(transcript.locator('.msg')).toHaveCount(0);
  await expect(panel(page).getByRole('tab', { name: /^Change log \(2\)/ })).toBeVisible();
});
