/**
 * The review panel and the change log (issue 347, codemode.md section 5.6, D13), in a real browser against the `?codemode`
 * fixture: a recorded corpus program is fed in through the agent port of the review store (the only seam; the product has
 * no producer until the chat), and everything the owner does is a real click or key press.
 */
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';

test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

async function open(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html?codemode');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await expect(page.getByRole('button', { name: /^Review/ })).toBeVisible();
}

async function propose(page: Page, name: string, options: { slice?: [number, number]; plan?: string } = {}): Promise<string> {
  return page.evaluate(([program, opts]) => window.__materioneCodemode!.propose(program as string, opts as never), [name, options] as const);
}

const panel = (page: Page): Locator => page.getByRole('region', { name: 'Review', exact: true });
const card = (page: Page, id: string): Locator => page.getByRole('article', { name: id, exact: true });
const rail = (page: Page): Locator => page.getByRole('button', { name: /^Review/ });

async function openPanel(page: Page): Promise<void> {
  if (!(await panel(page).isVisible())) await rail(page).click();
  await expect(panel(page)).toBeVisible();
}

async function documentRaw(page: Page, path: string): Promise<string | null> {
  return page.evaluate((target) => window.__materioneTestControls!.documents().find((doc) => doc.path === target)?.raw ?? null, path);
}

async function applies(page: Page): Promise<number> {
  return page.evaluate(() => window.__materioneTestControls!.codemode!.calls.filter((call) => call.op === 'authoring.transaction' && !call.dryRun).length);
}

test('the rail has no Review entry when the server does not advertise code mode', async ({ page }) => {
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await expect(page.getByRole('button', { name: /^Review/ })).toHaveCount(0);
});

test('content that only creates applies on its own, is listed, and offers nothing to approve', async ({ page }) => {
  await open(page);
  const id = await propose(page, 't11-golden-berry');
  await openPanel(page);
  const proposal = card(page, id);
  await expect(proposal.getByText('Applied', { exact: true }).first()).toBeVisible();
  await expect(proposal.getByText('it only creates new content, so nothing needed your approval')).toBeVisible();
  await expect(proposal.getByRole('button', { name: /Approve|Reject/ })).toHaveCount(0);
  await expect(proposal.getByText('Creates 2 documents')).toHaveCount(0);
  await expect(proposal.getByText(/^Creates 1 document\./)).toBeVisible();
  expect(await documentRaw(page, 'items/golden_berry.yml')).toContain('golden_berry');
  // the agent's summary is shown apart and labelled as its own words
  await proposal.getByRole('button', { name: /^Expand/ }).click();
  await expect(proposal.getByLabel("The agent's summary")).toContainText("The agent's words, not checked by the editor");
  await page.getByRole('tab', { name: /^Change log/ }).click();
  const entry = page.getByRole('article', { name: /^Applied tx-/ });
  await expect(entry).toContainText('No approval needed: new content only');
  await expect(entry.getByRole('button', { name: /^Revert/ })).toBeVisible();
});

test('a change to an existing object waits, shows the diff, and applies only after a click on Approve', async ({ page }) => {
  await open(page);
  const id = await propose(page, 'c01-bulk-lore');
  // it needs the owner, so the panel shows itself, and takes no focus
  await expect(panel(page)).toBeVisible();
  const focused = await page.evaluate(() => document.activeElement?.id ?? '');
  expect(focused).not.toBe('review-title');
  const proposal = card(page, id);
  await expect(proposal.getByText('Needs your approval')).toBeVisible();
  await expect(proposal.getByText(/^Changes 4 existing documents\./)).toBeVisible();
  await expect(proposal.getByText('Changes to existing objects (4)')).toBeVisible();
  // the diff is from the dry run's text against the stored text
  const diff = proposal.getByRole('group', { name: /^Changes to items\/ruby\.yml: 1 added, 1 removed/ });
  await expect(diff).toContainText('Kept for the harvest festival');
  await expect(proposal.getByLabel('Program text')).toBeHidden();
  expect(await applies(page)).toBe(0);
  expect(await documentRaw(page, 'items/ruby.yml')).not.toContain('harvest');
  await proposal.getByRole('button', { name: 'Approve and apply' }).click();
  await expect(proposal.getByText('Applied', { exact: true }).first()).toBeVisible();
  expect(await documentRaw(page, 'items/ruby.yml')).toContain('harvest');
  expect(await applies(page)).toBe(1);
  await page.getByRole('tab', { name: /^Change log/ }).click();
  await expect(page.getByRole('article', { name: /^Applied tx-/ })).toContainText('Approved by you');
});

test('the program is collapsed, and each line that staged an operation says which', async ({ page }) => {
  await open(page);
  const id = await propose(page, 'c01-bulk-lore');
  const proposal = card(page, id);
  await proposal.getByText(/^Program \(/).click();
  const code = proposal.getByRole('region', { name: 'Program text' });
  await expect(code).toContainText('staged operation 1: setFields');
  await expect(code).toContainText('staged operation 4: setFields');
});

test('a plan approves the existing objects of two programs once', async ({ page }) => {
  await open(page);
  const objects = ['ruby', 'ruby_disc', 'ruby_sword', 'templated_ruby'].map((name) => `fixture_pack:items/${name}.yml`);
  const plan = await page.evaluate((list) => window.__materioneCodemode!.declarePlan(list, 'festival lore'), objects);
  const first = await propose(page, 'c01-bulk-lore', { slice: [0, 2], plan });
  const second = await propose(page, 'c01-bulk-lore', { slice: [2, 4], plan });
  await openPanel(page);
  for (const object of objects) await expect(panel(page).locator('.plans')).toContainText(object);
  await expect(card(page, first).getByText('Needs your approval')).toBeVisible();
  await expect(card(page, second).getByText('Needs your approval')).toBeVisible();
  expect(await applies(page)).toBe(0);
  await panel(page).locator('.plans').getByRole('button', { name: 'Approve plan: 4 objects, once' }).click();
  await expect(card(page, first).getByText('Applied', { exact: true }).first()).toBeVisible();
  await expect(card(page, second).getByText('Applied', { exact: true }).first()).toBeVisible();
  expect(await applies(page)).toBe(2);
  for (const name of ['ruby', 'ruby_disc', 'ruby_sword', 'templated_ruby']) expect(await documentRaw(page, `items/${name}.yml`)).toContain('harvest');
});

test('reject writes nothing and says so', async ({ page }) => {
  await open(page);
  const id = await propose(page, 'c01-bulk-lore');
  const proposal = card(page, id);
  await proposal.getByRole('button', { name: 'Reject' }).click();
  await expect(proposal.getByText('Rejected', { exact: true })).toBeVisible();
  await expect(proposal.getByText('You rejected it. Nothing was written.')).toBeVisible();
  await expect(proposal.getByRole('button', { name: /Approve|Reject/ })).toHaveCount(0);
  expect(await applies(page)).toBe(0);
  expect(await documentRaw(page, 'items/ruby.yml')).not.toContain('harvest');
});

test('a document that changed after the dry run makes the proposal stale: no force, and what to do', async ({ page }) => {
  await open(page);
  const id = await propose(page, 'c01-bulk-lore');
  await page.evaluate(() => window.__materioneTestControls!.externalEdit('fixture_pack', 'items/ruby.yml'));
  const proposal = card(page, id);
  await proposal.getByRole('button', { name: 'Approve and apply' }).click();
  await expect(proposal.getByText('Out of date', { exact: true })).toBeVisible();
  await expect(proposal.getByText('Ask the agent to run the program again against the current files.')).toBeVisible();
  await expect(proposal.getByRole('button', { name: /Approve|Apply|Force|Retry/ })).toHaveCount(0);
  await expect(proposal.getByText('MTR-0034', { exact: true }).first()).toBeVisible();
  expect(await documentRaw(page, 'items/ruby.yml')).not.toContain('harvest');
  expect(await documentRaw(page, 'items/ruby_sword.yml')).not.toContain('harvest');
});

test('errors refuse the proposal with no Approve; warnings and findings that predate the change are shown as such', async ({ page }) => {
  await open(page);
  await page.evaluate(() => window.__materioneTestControls!.codemode!.diagnoseNext([
    { code: 'MTR-0022', severity: 'error', message: 'rule x: unexpected trailing input', operation: 0 },
  ]));
  const refused = await propose(page, 'c01-bulk-lore');
  await openPanel(page);
  const bad = card(page, refused);
  await bad.getByRole('button', { name: /^Expand/ }).click();
  await expect(bad.getByText('Refused', { exact: true })).toBeVisible();
  await expect(bad.getByText('Error', { exact: true })).toBeVisible();
  await expect(bad.getByText('MTR-0022')).toBeVisible();
  await expect(bad.getByRole('button', { name: /Approve|Apply/ })).toHaveCount(0);
  await page.evaluate(() => window.__materioneTestControls!.codemode!.diagnoseNext([
    { code: 'MTR-0021', severity: 'warning', message: 'this trigger never fires for rule documents', operation: 0 },
    { code: 'MTR-0063', severity: 'error', message: 'unknown variable already in this document', operation: 0, preexisting: true },
  ]));
  const warned = await propose(page, 'c01-bulk-lore', { slice: [0, 1] });
  const ok = card(page, warned);
  await expect(ok.getByText('Needs your approval')).toBeVisible();
  await expect(ok.getByText('Warning', { exact: true })).toBeVisible();
  await expect(ok.getByText('Already there before this change')).toBeVisible();
  await expect(ok.getByRole('button', { name: 'Approve and apply' })).toBeEnabled();
});

test('revert shows a preview first, then restores each document after the confirmation', async ({ page }) => {
  await open(page);
  const before = await documentRaw(page, 'items/ruby.yml');
  const id = await propose(page, 'c01-bulk-lore');
  await card(page, id).getByRole('button', { name: 'Approve and apply' }).click();
  await expect(card(page, id).getByText('Applied', { exact: true }).first()).toBeVisible();
  await page.getByRole('tab', { name: /^Change log/ }).click();
  const entry = page.getByRole('article', { name: /^Applied tx-/ }).first();
  await entry.getByRole('button', { name: /^Revert/ }).click();
  const preview = entry.getByRole('group', { name: 'Revert preview' });
  await expect(preview).toContainText('A revert would restore 4 documents. Nothing is written until you confirm.');
  await expect(preview.getByRole('group', { name: /^Changes to items\/ruby\.yml: 1 added, 1 removed/ })).toContainText('Kept for the harvest festival');
  expect(await documentRaw(page, 'items/ruby.yml')).toContain('harvest');
  await preview.getByRole('button', { name: 'Confirm revert' }).click();
  await expect(entry.getByText('Reverted', { exact: true }).first()).toBeVisible();
  await expect(entry.getByText(/by tx-fixture/)).toBeVisible();
  expect(await documentRaw(page, 'items/ruby.yml')).toBe(before);
});

test('a revert the server refuses names the document that moved on and offers no confirmation', async ({ page }) => {
  await open(page);
  const id = await propose(page, 'c01-bulk-lore');
  await card(page, id).getByRole('button', { name: 'Approve and apply' }).click();
  await expect(card(page, id).getByText('Applied', { exact: true }).first()).toBeVisible();
  await page.evaluate(() => window.__materioneTestControls!.externalEdit('fixture_pack', 'items/ruby_disc.yml'));
  await page.getByRole('tab', { name: /^Change log/ }).click();
  const entry = page.getByRole('article', { name: /^Applied tx-/ }).first();
  await entry.getByRole('button', { name: /^Revert/ }).click();
  const preview = entry.getByRole('group', { name: 'Revert preview' });
  await expect(preview).toContainText('Revert refused');
  await expect(preview).toContainText('items/ruby_disc.yml');
  await expect(preview).toContainText('MTR-0034');
  await expect(preview.getByRole('button', { name: 'Confirm revert' })).toHaveCount(0);
  await preview.getByRole('button', { name: 'Close' }).click();
  expect(await documentRaw(page, 'items/ruby.yml')).toContain('harvest');
});

test('a transaction the server cannot revert says why and offers no Revert', async ({ page }) => {
  await open(page);
  await page.evaluate(() => window.__materioneTestControls!.codemode!.makeNextUnrevertible());
  await propose(page, 't11-golden-berry');
  await openPanel(page);
  await page.getByRole('tab', { name: /^Change log/ }).click();
  const entry = page.getByRole('article', { name: /^Applied tx-/ }).first();
  await expect(entry.getByText('Cannot be reverted', { exact: true })).toBeVisible();
  await expect(entry.getByText(/larger than the 4 MiB/)).toBeVisible();
  await expect(entry.getByRole('button', { name: /^Revert/ })).toHaveCount(0);
});

test('the change log lists recent transactions from the audit, with who and the program text', async ({ page }) => {
  await open(page);
  await page.evaluate(() => window.__materioneTestControls!.setAuditEntries([
    {
      ts: '2026-11-14T10:00:00Z', actor: 'Other Admin', op: 'authoring.transaction', target: 'fixture_pack (2 documents, tx-elsewhere)',
      origin: 'code-mode', approval: 'granted', transaction: 'tx-elsewhere', program: 'm.summary("from another session")', programLength: 30, digest: 'sha256:abc',
    },
  ]));
  await openPanel(page);
  await page.getByRole('tab', { name: /^Change log/ }).click();
  const row = page.getByRole('article', { name: 'Transaction tx-elsewhere' });
  await expect(row).toContainText('Other Admin');
  await expect(row).toContainText('2 documents');
  await row.getByText(/^Program \(/).click();
  await expect(row.getByRole('region', { name: 'Program text' })).toContainText('from another session');
  // the server's dry run is the only thing that can say whether it can still be reverted: here there is no record
  await row.getByRole('button', { name: /^Revert/ }).click();
  await expect(row.getByRole('group', { name: 'Revert preview' })).toContainText('MTR-0104');
});

test('a script cannot approve: a synthetic click does nothing, a real click does', async ({ page }) => {
  await open(page);
  const id = await propose(page, 'c01-bulk-lore');
  const proposal = card(page, id);
  const approve = proposal.getByRole('button', { name: 'Approve and apply' });
  await expect(approve).toBeVisible();
  const trusted = await page.evaluate(() => {
    const button = [...document.querySelectorAll('button')].find((candidate) => candidate.textContent?.trim() === 'Approve and apply') as HTMLButtonElement;
    const results: boolean[] = [];
    button.addEventListener('click', (event) => results.push(event.isTrusted), { once: true });
    button.click();
    button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return results;
  });
  expect(trusted).toEqual([false]);
  await expect(proposal.getByText('ignored: that was not a click or key press from you')).toBeVisible();
  await expect(proposal.getByText('Needs your approval')).toBeVisible();
  expect(await applies(page)).toBe(0);
  expect(await documentRaw(page, 'items/ruby.yml')).not.toContain('harvest');
  await approve.click();
  await expect(proposal.getByText('Applied', { exact: true }).first()).toBeVisible();
});

test('the whole flow works from the keyboard, focus moves into the panel on open and back on close', async ({ page }) => {
  await open(page);
  await propose(page, 't11-golden-berry');
  await page.locator('#review-toggle').focus();
  await page.keyboard.press('Enter');
  await expect(panel(page)).toBeVisible();
  await expect(page.locator('#review-title')).toBeFocused();
  const id = await propose(page, 'c01-bulk-lore');
  const proposal = card(page, id);
  await expect(proposal.getByText('Needs your approval')).toBeVisible();
  // Tab until the Approve button has focus, then press Enter
  let reached = false;
  for (let step = 0; step < 60 && !reached; step++) {
    await page.keyboard.press('Tab');
    reached = await page.evaluate(() => document.activeElement?.textContent?.trim() === 'Approve and apply');
  }
  expect(reached).toBe(true);
  await page.keyboard.press('Enter');
  await expect(proposal.getByText('Applied', { exact: true }).first()).toBeVisible();
  expect(await documentRaw(page, 'items/ruby.yml')).toContain('harvest');
  await page.keyboard.press('Escape');
  await expect(panel(page)).toBeHidden();
  await expect(page.locator('#review-toggle')).toBeFocused();
});

test('state is carried in words: every state names itself in text', async ({ page }) => {
  await open(page);
  const id = await propose(page, 'c01-bulk-lore');
  const proposal = card(page, id);
  await expect(proposal.getByText('Needs your approval')).toBeVisible();
  await expect(proposal.getByText('Needs approval').first()).toBeVisible();
  await expect(proposal.locator('.docs').first()).toBeVisible();
  // a diff row says added or removed to assistive technology, not only by colour
  await expect(proposal.getByRole('group', { name: /^Changes to items\/ruby\.yml/ }).getByText('added:')).toBeAttached();
});
