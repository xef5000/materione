import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';
import { blockCdn, openModules, openResource, addModule } from './helpers/modules';

// Purposeful motion (issue #155, web.md "Motion"). Every other spec runs through the
// reduced-motion path (playwright.config.ts), so this file is the one place that turns
// motion on, and the one place that proves the reduced path turns it off.

test.beforeEach(async ({ page }) => {
  await blockCdn(page);
});

async function openFixture(page: Page, query = ''): Promise<void> {
  await page.goto(`/browser/fixture.html${query}`);
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
}

/** Running and pending animations with a duration, as target class and timing, read from the page. */
function animations(page: Page, clickText?: string) {
  // With clickText, the button is clicked and read in one task, so a 200ms intro is still running.
  return page.evaluate(async (text) => {
    if (text) {
      [...document.querySelectorAll('button')].find((node) => node.textContent?.trim() === text)?.click();
      await new Promise((resolve) => setTimeout(resolve, 30));
    }
    return document.getAnimations()
      .filter((animation) => Number((animation.effect as KeyframeEffect | null)?.getTiming().duration ?? 0) > 0)
      .map((animation) => {
        const effect = animation.effect as KeyframeEffect | null;
        const target = effect?.target as Element | null;
        return {
          target: target ? `${target.tagName.toLowerCase()}.${String(target.getAttribute('class') ?? '').split(' ')[0]}` : '',
          name: (animation as CSSAnimation).animationName ?? '',
          duration: Number(effect?.getTiming().duration ?? 0),
          delay: Number(effect?.getTiming().delay ?? 0),
        };
      });
  }, clickText);
}

test.describe('reduced motion', () => {
  test.use({ reducedMotion: 'reduce' });

  test('selecting a module swaps its settings in the panel with no animation', async ({ page }) => {
    await openModules(page);
    await openResource(page, 'Items', 'Ruby Sword');
    expect(await animations(page, 'Durability')).toEqual([]);
    await expect(page.getByRole('region', { name: 'Module settings' }).getByLabel(/max durability/i)).toHaveValue('500');
  });

  test('the motion tokens collapse to 0ms and transitions stop', async ({ page }) => {
    await openFixture(page);
    const tokens = await page.evaluate(() => {
      const style = getComputedStyle(document.documentElement);
      return ['fast', 'base', 'slow'].map((name) => style.getPropertyValue(`--motion-${name}`).trim());
    });
    expect(tokens).toEqual(['0ms', '0ms', '0ms']);

    const button = page.getByRole('button', { name: 'New folder' });
    await expect(button).toBeEnabled();
    expect(await button.evaluate((node) => getComputedStyle(node).transitionDuration)).toBe('0s');
  });

  test('a dialog opens and closes with no animation at all', async ({ page }) => {
    await openFixture(page);
    await page.getByRole('button', { name: 'New folder' }).click();
    const dialog = page.getByRole('dialog', { name: 'New folder' });
    await expect(dialog).toBeVisible();
    expect(await animations(page)).toEqual([]);

    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    expect(await animations(page)).toEqual([]);
  });

  test('loading placeholders are still, not shimmering', async ({ page }) => {
    await openFixture(page);
    const name = await page.evaluate(() => {
      const bar = document.createElement('span');
      bar.className = 'skeleton';
      document.body.append(bar);
      const value = getComputedStyle(bar).animationName;
      bar.remove();
      return value;
    });
    expect(name).toBe('none');
  });
});

test.describe('motion on', () => {
  test.use({ reducedMotion: 'no-preference' });

  test('a dialog fades and scales in over the base token, and fades out over the fast one', async ({ page }) => {
    await openFixture(page);
    const opening = await animations(page, 'New folder');
    await expect(page.getByRole('dialog', { name: 'New folder' })).toBeVisible();
    expect(opening).toContainEqual(expect.objectContaining({ target: 'div.dialog', duration: 200 }));
    expect(opening).toContainEqual(expect.objectContaining({ target: 'div.scrim', duration: 200 }));

    // Dispatch Escape and read the page in the same task: the outro is created and the
    // node stays mounted for its fast duration, before the removal flush.
    const closing = await page.evaluate(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      await new Promise((resolve) => setTimeout(resolve, 20));
      const outros = document.getAnimations()
        .filter((animation) => (animation.effect as KeyframeEffect | null)?.target instanceof Element
          && ((animation.effect as KeyframeEffect).target as Element).matches('.dialog'))
        .map((animation) => Number((animation.effect as KeyframeEffect).getTiming().duration));
      return { stillMounted: document.querySelector('.dialog') !== null, outros };
    });
    expect(closing.stillMounted).toBe(true);
    expect(closing.outros).toContain(120);
    await expect(page.getByRole('dialog', { name: 'New folder' })).toHaveCount(0);
  });

  test('a selected module settles into the panel over the fast token', async ({ page }) => {
    await openModules(page);
    await openResource(page, 'Items', 'Ruby Sword');
    // Click and read in one task: the settings fade lasts 120ms, longer than the 30ms read.
    const selecting = await animations(page, 'Durability');
    expect(selecting).toContainEqual(expect.objectContaining({ target: 'div.settings', duration: 120 }));
  });

  test('the module picker staggers its rows in on first open', async ({ page }) => {
    const pageErrors = await openModules(page);
    await openResource(page, 'Blocks', 'Plain Stone');
    // Click and read in one task: the stagger lasts about 300ms, shorter than a round trip.
    // Svelte delays a row with a placeholder animation as long as its delay, so rows 1..5
    // read 18, 36, 54, 72, 90 ms: row n starts 18n ms after row 0.
    const durations = await page.getByRole('button', { name: 'Add module' }).evaluate(async (button) => {
      (button as HTMLButtonElement).click();
      await new Promise((resolve) => setTimeout(resolve, 30));
      return document.getAnimations()
        .filter((animation) => (animation.effect as KeyframeEffect | null)?.target?.tagName === 'LI')
        .map((animation) => Number((animation.effect as KeyframeEffect).getTiming().duration));
    });
    await expect(page.getByLabel('Search modules')).toBeVisible();
    expect(durations).toEqual(expect.arrayContaining([18, 36, 54, 72, 90]));
    expect(pageErrors).toEqual([]);
  });

  test('a module row added to the studio flies in; the studio fades in on open', async ({ page }) => {
    await openModules(page);
    await openResource(page, 'Blocks', 'Plain Stone');
    await addModule(page, 'explosion');
    await expect(page.locator('[data-module="blast_resistant"]')).toBeVisible();
    const added = await animations(page);
    expect(added).toContainEqual(expect.objectContaining({ target: 'li.row-item', duration: 200 }));

    // The Studio mounts with the view-enter class: the Content <-> Studio switch fades in.
    const studio = page.locator('section.studio.view-enter');
    await expect(studio).toBeVisible();
    expect(await studio.evaluate((node) => getComputedStyle(node).animationName)).toBe('fade-in');
  });

  test('a thumbnail fades in once its image arrives, in a box already sized for it', async ({ page }) => {
    await openFixture(page, '?catalog');
    await page.getByRole('button', { name: 'Content', exact: true }).click();
    await page.getByRole('button', { name: 'Glyphs', exact: true }).click();
    const thumbnail = page.getByRole('option', { name: /Heart Icon/ }).locator('.thumbnail');
    await expect(thumbnail.locator('img.fade-in')).toBeVisible();
    // The box is sized by its own style before the image arrives, so arriving adds no layout shift.
    const outer = await thumbnail.evaluate((node) => {
      const style = getComputedStyle(node);
      return parseFloat(node.style.width) + parseFloat(style.borderLeftWidth) + parseFloat(style.borderRightWidth);
    });
    expect((await thumbnail.boundingBox())?.width).toBe(outer);
  });
});

test.describe('large packs keep their frame budget', () => {
  test.use({ reducedMotion: 'no-preference' });

  test('a 1,000-row list scrolls at 60 fps, and filtering it does not animate its rows', async ({ page }) => {
    await openFixture(page, '?rows=1000');
    await page.getByRole('button', { name: 'Content', exact: true }).click();
    const list = page.getByRole('listbox', { name: /in / }).first();
    await expect.poll(() => page.locator('.entry').count(), { timeout: 20_000 }).toBeGreaterThanOrEqual(1000);

    // Scroll one row per frame for about a second, and time every frame.
    const frames = await list.evaluate(async (node) => {
      const times: number[] = [];
      await new Promise<void>((resolve) => {
        const start = performance.now();
        const step = (now: number) => {
          times.push(now);
          node.scrollTop += 24;
          if (now - start < 1200) requestAnimationFrame(step);
          else resolve();
        };
        requestAnimationFrame(step);
      });
      return times.slice(1).map((time, index) => time - times[index]!);
    });
    const sorted = [...frames].sort((a, b) => a - b);
    const p95 = sorted[Math.floor(sorted.length * 0.95)]!;
    const median = sorted[Math.floor(sorted.length / 2)]!;
    test.info().annotations.push({ type: 'frame-budget', description: `median ${median.toFixed(1)}ms, p95 ${p95.toFixed(1)}ms over ${frames.length} frames` });
    // 60 fps is a 16.7ms frame. The p95 bound is looser so a shared CI machine is not flaky.
    expect(median).toBeLessThan(20);
    expect(p95).toBeLessThan(50);

    // Still above the row limit after the filter (1,000 bulk rows match, the 4 fixture items drop),
    // so the rows that move are not animated: no flip reads, no per-row motion.
    await page.getByRole('searchbox', { name: /search/i }).fill('bulk');
    await expect.poll(() => page.locator('.entry').count()).toBe(1000);
    expect((await animations(page)).filter((entry) => entry.target.startsWith('div.entry'))).toEqual([]);
  });
});
