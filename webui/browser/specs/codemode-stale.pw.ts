/**
 * Issue 461, in a real browser against the `?codemode` fixture: an approved transaction (and its revert) that changes an
 * existing document reaches every open view of it, with no reload. The detail view and the Files tab are open on the
 * document when the recorded program is approved; what they show afterwards is the server's text, and a draft the owner
 * had typed is kept and offered the choice instead of being overwritten.
 */
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';

test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

const LORE = 'Kept for the harvest festival';

async function open(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html?codemode');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await expect(page.getByRole('button', { name: /^Review/ })).toBeVisible();
}

async function propose(page: Page, name: string): Promise<string> {
  return page.evaluate((program) => window.__materioneCodemode!.propose(program), name);
}

const card = (page: Page, id: string): Locator => page.getByRole('article', { name: id, exact: true });
const panel = (page: Page): Locator => page.getByRole('region', { name: 'Review', exact: true });

/** A proposal that needs the owner opens the panel on its own; opening it again would close it. */
async function openPanel(page: Page): Promise<void> {
  if (!(await panel(page).isVisible())) await page.getByRole('button', { name: /^Review/ }).click();
  await expect(panel(page)).toBeVisible();
}

async function documentRaw(page: Page, path: string): Promise<string> {
  const raw = await page.evaluate((target) => window.__materioneTestControls!.documents().find((doc) => doc.path === target)?.raw ?? null, path);
  if (raw === null) throw new Error(`no fixture document ${path}`);
  return raw;
}

/** The Files tab's raw YAML of `items/ruby.yml`, opened (the file tree entry, then its YAML view). */
async function openRubyInFiles(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.getByRole('button', { name: /^ruby\.yml/i }).click();
  await page.getByRole('tab', { name: 'yaml' }).click();
  return page.getByLabel('raw YAML');
}

async function openRubyInDetail(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('option', { name: /^Ruby\b(?! )/ }).first().dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

test('an approved transaction, then its revert, show the new and the old bytes in the detail view and the Files tab with no reload', async ({ page }) => {
  await open(page);
  const rawBefore = await documentRaw(page, 'items/ruby.yml');
  await openRubyInDetail(page);
  await expect(page.locator('[data-field="lore"]')).not.toContainText(LORE);
  const yaml = await openRubyInFiles(page);
  await expect(yaml).not.toHaveValue(new RegExp(LORE));

  const id = await propose(page, 'c01-bulk-lore');
  await openPanel(page);
  await card(page, id).getByRole('button', { name: 'Approve and apply' }).click();
  await expect(card(page, id).getByText('Applied', { exact: true }).first()).toBeVisible();
  expect(await documentRaw(page, 'items/ruby.yml')).toContain('harvest');

  // the Files tab (open, showing the old file) holds the new bytes
  await expect(yaml).toHaveValue(new RegExp(LORE));
  // and so does the detail view
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await expect(page.locator('[data-field="lore"]')).toContainText(LORE);

  // revert: the same, the other way
  await page.getByRole('tab', { name: /^Change log/ }).click();
  const entry = page.getByRole('article', { name: /^Applied tx-/ }).first();
  await entry.getByRole('button', { name: /^Revert/ }).click();
  await entry.getByRole('group', { name: 'Revert preview' }).getByRole('button', { name: 'Confirm revert' }).click();
  await expect(entry.getByText('Reverted', { exact: true }).first()).toBeVisible();
  expect(await documentRaw(page, 'items/ruby.yml')).toBe(rawBefore);
  await expect(page.locator('[data-field="lore"]')).not.toContainText(LORE);
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await expect(yaml).not.toHaveValue(new RegExp(LORE));
});

test('a draft typed over a document the transaction changed is kept and flagged, and the owner chooses', async ({ page }) => {
  await open(page);
  const yaml = await openRubyInFiles(page);
  const draft = (await yaml.inputValue()).replace('Ruby', 'My own ruby');
  await yaml.fill(draft);

  const id = await propose(page, 'c01-bulk-lore');
  await openPanel(page);
  await card(page, id).getByRole('button', { name: 'Approve and apply' }).click();
  await expect(card(page, id).getByText('Applied', { exact: true }).first()).toBeVisible();

  // neither side was overwritten: the server has the transaction's text, the editor still has the draft
  const conflict = page.getByTestId('draft-conflict');
  await expect(conflict).toBeVisible();
  await expect(yaml).toHaveValue(draft);
  expect(await documentRaw(page, 'items/ruby.yml')).toContain('harvest');
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeDisabled();

  // both can be read
  await conflict.getByRole('button', { name: 'Compare' }).click();
  await expect(conflict.getByRole('region', { name: 'Server version' })).toContainText('harvest');
  await expect(conflict.getByRole('region', { name: 'Your draft' })).toContainText('My own ruby');

  // "Keep my draft": the draft stays and can be applied over the server copy
  await conflict.getByRole('button', { name: 'Keep my draft' }).click();
  await expect(conflict).toHaveCount(0);
  await expect(yaml).toHaveValue(draft);
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeEnabled();
});

test('"Use server version" drops the draft for the transaction\'s text', async ({ page }) => {
  await open(page);
  const yaml = await openRubyInFiles(page);
  await yaml.fill((await yaml.inputValue()).replace('Ruby', 'My own ruby'));

  const id = await propose(page, 'c01-bulk-lore');
  await openPanel(page);
  await card(page, id).getByRole('button', { name: 'Approve and apply' }).click();
  await expect(card(page, id).getByText('Applied', { exact: true }).first()).toBeVisible();

  const conflict = page.getByTestId('draft-conflict');
  await expect(conflict).toBeVisible();
  await conflict.getByRole('button', { name: 'Use server version' }).click();
  await expect(conflict).toHaveCount(0);
  await expect(yaml).toHaveValue(new RegExp(LORE));
  await expect(yaml).not.toHaveValue(/My own ruby/);
});
