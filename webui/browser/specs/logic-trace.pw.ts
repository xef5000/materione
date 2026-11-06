import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';
import { blockCdn } from './helpers/modules';

// The live trace on the Logic canvas (issue 226, logic.md section 7.6, 32) on the deterministic `?logic&seed=trace`
// fixture: a click rule with a branch, a call and a stop, a second rule and a named flow. The fixture answers
// logic.trace.subscribe like a server would and the test plays the server's push.logic.trace frames by hand, so the
// canvas is judged on what it draws for a given stream: executed wires light, the branch not taken stays dim, a failed
// action shows its reason on the node, a drop is said, and the picture fades by itself.
test.beforeEach(async ({ page }) => blockCdn(page));

const FIXTURE_GUI = 'fixture_pack:main';

async function openLogic(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html?logic&economy&seed=trace');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
  await page.getByRole('option', { name: /shop/i }).dblclick();
  await page.getByRole('option', { name: /main/i }).dblclick();
  await expect(page.getByTestId('gui-art')).toBeVisible();
  await page.getByRole('tab', { name: 'Logic' }).click();
  await expect(page.getByTestId('logic-canvas')).toBeVisible();
  await expect(page.locator('.node')).toHaveCount(12);
}

const toggle = (page: Page): Locator => page.getByTestId('logic-trace-toggle');
const node = (page: Page, id: string): Locator => page.locator(`.node[data-node="${id}"]`);
const wire = (page: Page, id: string): Locator => page.locator(`[data-wire="${id}"]`);

type Entry = { path: string; kind: string; detail?: string; run?: number };

/** The server's push.logic.trace frame for entries of one run. */
async function stream(page: Page, entries: Entry[], extra: Record<string, unknown> = {}): Promise<void> {
  await page.evaluate(
    ({ resource, entries: rows, extra: more }) => {
      window.__materioneTestControls!.emitPush('push.logic.trace', {
        resource,
        seq: 1,
        entries: rows.map((row) => ({ run: 1, rule: 'fixture_pack:main#0', at: 1, ...row })),
        ...more,
      });
    },
    { resource: FIXTURE_GUI, entries, extra },
  );
}

const traced = (page: Page): Promise<readonly string[]> => page.evaluate(() => window.__materioneTestControls!.tracedResources());
const calls = (page: Page, op: string): Promise<number> =>
  page.evaluate((name) => window.__materioneTestControls!.calls().filter((call) => call.op === name).length, op);

/** A click that pays: the rule starts, the first message runs, the branch is taken, money is taken, the flow is called, the run stops. */
const PAID: Entry[] = [
  { path: 'behavior.0', kind: 'run-started' },
  { path: 'behavior.0.do.0', kind: 'ran' },
  { path: 'behavior.0.do.1', kind: 'branch', detail: 'then' },
  { path: 'behavior.0.do.1.then.0', kind: 'ran' },
  { path: 'behavior.0.do.1.then.1', kind: 'ran' },
  { path: 'behavior.0.do.2', kind: 'called', detail: 'bonus' },
  { path: 'flows.bonus.0', kind: 'ran' },
  { path: 'behavior.0.do.3', kind: 'ran', detail: 'stop' },
  { path: 'behavior.0', kind: 'run-ended', detail: 'STOPPED: stop' },
];

test('the live trace is off by default: nothing is asked of the server and nothing is dimmed', async ({ page }) => {
  await openLogic(page);

  await expect(toggle(page)).toHaveAttribute('aria-pressed', 'false');
  expect(await calls(page, 'logic.trace.subscribe')).toBe(0);
  await expect(page.locator('.node[data-trace]')).toHaveCount(0);
  await expect(page.locator('.world.tracing')).toHaveCount(0);

  // a push that arrives with the toggle off is ignored: nothing lights
  await stream(page, PAID);
  await expect(page.locator('.node[data-trace]')).toHaveCount(0);
});

test('turning it on subscribes for this GUI; turning it off lets go', async ({ page }) => {
  await openLogic(page);

  await toggle(page).click();
  await expect(toggle(page)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'live');
  expect(await traced(page)).toEqual([FIXTURE_GUI]);
  expect(await calls(page, 'logic.trace.subscribe')).toBe(1); // one ask, not one per re-render

  await toggle(page).click();
  await expect(toggle(page)).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByTestId('logic-trace-status')).toHaveCount(0);
  await expect.poll(() => traced(page)).toEqual([]);
});

test('a click lights the nodes it reached and the wires that fed them; the branch not taken stays dim', async ({ page }) => {
  await openLogic(page);
  await toggle(page).click();
  await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'live');

  await stream(page, PAID);

  for (const id of ['behavior.0', 'behavior.0.do.0', 'behavior.0.do.1', 'behavior.0.do.1.then.0', 'behavior.0.do.1.then.1', 'behavior.0.do.2', 'flows.bonus.0', 'behavior.0.do.3']) {
    await expect(node(page, id)).toHaveAttribute('data-trace', 'ran');
  }
  // wires: executed edges light, the else branch and the other rule do not
  for (const id of ['behavior.0|out>behavior.0.do.0', 'behavior.0.do.0|after>behavior.0.do.1', 'behavior.0.do.1|then>behavior.0.do.1.then.0', 'behavior.0.do.1|after>behavior.0.do.2', 'flows.bonus|out>flows.bonus.0']) {
    await expect(wire(page, id)).toHaveAttribute('data-ran', 'true');
  }
  await expect(wire(page, 'behavior.0.do.1|else>behavior.0.do.1.else.0')).not.toHaveAttribute('data-ran', 'true');
  await expect(wire(page, 'behavior.1|out>behavior.1.do.0')).not.toHaveAttribute('data-ran', 'true');

  // the picture dims what the run did not reach
  await expect(page.locator('.world.tracing')).toHaveCount(1);
  await expect(node(page, 'behavior.0.do.1.else.0')).toHaveClass(/dim/);
  await expect(node(page, 'behavior.1')).toHaveClass(/dim/);
  await expect(node(page, 'behavior.0.do.0')).not.toHaveClass(/dim/);
  expect(Number(await node(page, 'behavior.0.do.1.else.0').evaluate((element) => getComputedStyle(element).opacity))).toBeLessThan(0.6);
  expect(Number(await node(page, 'behavior.0.do.0').evaluate((element) => getComputedStyle(element).opacity))).toBe(1);
  const dimWire = await wire(page, 'behavior.1|out>behavior.1.do.0').evaluate((element) => getComputedStyle(element).opacity);
  expect(Number(dimWire)).toBeLessThan(0.5);
});

test('a failed action shows its reason on the node, in the same badge a compiler verdict wears', async ({ page }) => {
  await openLogic(page);
  await toggle(page).click();
  await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'live');

  await stream(page, [
    { path: 'behavior.0', kind: 'run-started' },
    { path: 'behavior.0.do.0', kind: 'ran' },
    { path: 'behavior.0.do.1', kind: 'branch', detail: 'then' },
    { path: 'behavior.0.do.1.then.0', kind: 'failed', detail: "action 'take_money' failed: not enough money" },
  ]);

  const failed = node(page, 'behavior.0.do.1.then.0');
  await expect(failed).toHaveAttribute('data-trace', 'failed');
  await expect(failed.getByTestId('logic-trace-reason')).toContainText('not enough money');
  await expect(failed.getByRole('img', { name: /error: Failed in the last run: .*not enough money/ })).toBeVisible();
  // the steps after the failure were not reached
  await expect(node(page, 'behavior.0.do.1.then.1')).toHaveClass(/dim/);
  await expect(page.getByTestId('logic-trace-reason')).toHaveCount(1);
});

test('a skipped step is marked and its wire stays dark', async ({ page }) => {
  await openLogic(page);
  await toggle(page).click();
  await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'live');

  await stream(page, [
    { path: 'behavior.1', kind: 'run-started' },
    { path: 'behavior.1.do.0', kind: 'skipped', detail: 'chance 0.5' },
  ]);

  await expect(node(page, 'behavior.1.do.0')).toHaveAttribute('data-trace', 'skipped');
  await expect(wire(page, 'behavior.1|out>behavior.1.do.0')).not.toHaveAttribute('data-ran', 'true');
  await expect(node(page, 'behavior.1.do.0').getByRole('img', { name: /info: Skipped in the last run: chance 0\.5/ })).toBeVisible();
});

test('the next run replaces the previous one of its rule, and a drop is said', async ({ page }) => {
  await openLogic(page);
  await toggle(page).click();
  await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'live');
  await stream(page, PAID);
  await expect(node(page, 'behavior.0.do.1.then.1')).toHaveAttribute('data-trace', 'ran');

  // a second click that does not pay: the else branch, and the server dropped 7 entries on the way
  await stream(page, [
    { path: 'behavior.0', kind: 'run-started', run: 2 },
    { path: 'behavior.0.do.0', kind: 'ran', run: 2 },
    { path: 'behavior.0.do.1', kind: 'branch', detail: 'else', run: 2 },
    { path: 'behavior.0.do.1.else.0', kind: 'ran', run: 2 },
  ], { dropped: 7 });

  await expect(node(page, 'behavior.0.do.1.else.0')).toHaveAttribute('data-trace', 'ran');
  await expect(node(page, 'behavior.0.do.1.then.1')).not.toHaveAttribute('data-trace', /.+/);
  await expect(wire(page, 'behavior.0.do.1|else>behavior.0.do.1.else.0')).toHaveAttribute('data-ran', 'true');
  await expect(wire(page, 'behavior.0.do.1|then>behavior.0.do.1.then.0')).not.toHaveAttribute('data-ran', 'true');
  await expect(page.getByTestId('logic-trace-dropped')).toContainText('dropped 7 entries');
});

test('the picture fades by itself once the clicking stops', async ({ page }) => {
  await page.clock.install();
  await openLogic(page);
  await toggle(page).click();
  await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'live');

  await stream(page, PAID);
  await expect(node(page, 'behavior.0.do.0')).toHaveAttribute('data-trace', 'ran');

  await page.clock.runFor(9_000);
  await expect(page.locator('.node[data-trace]')).toHaveCount(0);
  await expect(page.locator('.world.tracing')).toHaveCount(0);
});

test('a server at its cap says so and the toggle stays where the author put it', async ({ page }) => {
  await openLogic(page);
  await page.evaluate(() => window.__materioneTestControls!.refuseNextTrace());

  await toggle(page).click();

  await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'failed');
  await expect(page.getByTestId('logic-trace-status')).toContainText('8 live trace subscriptions');
  await expect(toggle(page)).toHaveAttribute('aria-pressed', 'true');
});

test('leaving the Logic tab lets go of the server and coming back takes it again', async ({ page }) => {
  await openLogic(page);
  await toggle(page).click();
  await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'live');
  expect(await traced(page)).toEqual([FIXTURE_GUI]);

  await page.getByRole('tab', { name: 'Design' }).click();
  await expect.poll(() => traced(page)).toEqual([]);

  await page.getByRole('tab', { name: 'Logic' }).click();
  await expect(toggle(page)).toHaveAttribute('aria-pressed', 'true');
  await expect.poll(() => traced(page)).toEqual([FIXTURE_GUI]);
});

test.describe('motion', () => {
  test.use({ reducedMotion: 'no-preference' });

  test('lit wires draw in one after the other; with reduced motion they are simply lit', async ({ page }) => {
    await openLogic(page);
    await toggle(page).click();
    await expect(page.getByTestId('logic-trace-status')).toHaveAttribute('data-trace-status', 'live');
    await stream(page, PAID);

    const pulse = (id: string) => wire(page, id).locator('.pulse');
    const animation = (id: string) => pulse(id).evaluate((element) => {
      const style = getComputedStyle(element);
      return { name: style.animationName, delay: style.animationDelay };
    });
    const first = await animation('behavior.0|out>behavior.0.do.0');
    const later = await animation('behavior.0.do.1|then>behavior.0.do.1.then.0');
    expect(first.name).toMatch(/pulse-in$/);
    expect(later.name).toMatch(/pulse-in$/);
    expect(parseFloat(later.delay)).toBeGreaterThan(parseFloat(first.delay)); // reached later, drawn later

    await page.emulateMedia({ reducedMotion: 'reduce' });
    expect((await animation('behavior.0|out>behavior.0.do.0')).name).toBe('none');
    await expect(wire(page, 'behavior.0|out>behavior.0.do.0')).toHaveAttribute('data-ran', 'true');
    await expect(node(page, 'behavior.0.do.0').evaluate((element) => getComputedStyle(element).animationName)).resolves.toBe('none');
  });
});
