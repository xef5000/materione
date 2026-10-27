import { expect, test } from '@playwright/test';
import {
  addModule, blockCdn, documentOf, openModules, openResource, saveAndApply,
} from './helpers/modules';

// Sounds and text effects as display + modules (issue #153). Schemas come from
// the plugin's golden snapshot through the ?modules fixture; its anthem source
// is a real half-second tone, so decoding, waveform and duration are real.
test.beforeEach(async ({ page }) => blockCdn(page));

test('a sound lists its sources with play, a waveform and a duration, and flags a missing file', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Sounds', 'anthem');

  const display = page.getByRole('article', { name: 'Display' });
  const real = display.locator('[data-source="ui/anthem"]');
  await expect(real.getByRole('img', { name: 'Waveform of ui/anthem' })).toBeVisible();
  await expect(real.locator('[data-duration]')).toHaveText('0.5 s');
  const play = real.getByRole('button', { name: 'Play ui/anthem' });
  await expect(play).toBeEnabled();
  await play.click();
  await expect(real.getByRole('button', { name: 'Stop ui/anthem' })).toHaveAttribute('aria-pressed', 'true');
  // It ends by itself after its half second.
  await expect(real.getByRole('button', { name: 'Play ui/anthem' })).toBeVisible({ timeout: 5000 });

  const missing = display.locator('[data-source="ui/missing_take"]');
  await expect(missing).toContainText('file missing');
  await expect(missing.getByRole('button', { name: 'Play ui/missing_take' })).toBeDisabled();
  await missing.getByRole('button', { name: 'Remove source 2' }).click();
  await expect(missing).toHaveCount(0);

  // A volume above 1 is authored data, not an error: opening the studio changes nothing.
  await expect(display.getByRole('spinbutton', { name: 'Volume' })).toHaveValue('1.5');
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeEnabled(); // only the source removal

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([{ field: ['anthem', 'sounds'], value: ['ui/anthem'] }]);
  expect(pageErrors).toEqual([]);
});

test('subtitle presets fill the box, and the category is a labelled choice added as a module', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Sounds', 'chime');

  const display = page.getByRole('article', { name: 'Display' });
  await display.getByRole('group', { name: 'Subtitle suggestions' }).getByRole('button', { name: 'Chime' }).click();
  await expect(display.getByRole('textbox', { name: 'Subtitle' })).toHaveValue('Something chimes');

  await addModule(page, 'category');
  const category = page.locator('[data-module="category"]');
  await category.getByRole('combobox').selectOption({ label: 'Jukebox / note blocks' });
  await expect(category.getByText('The jukebox and note blocks slider')).toBeVisible();

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([
    { field: ['chime', 'subtitle'], value: 'Something chimes' },
    { field: ['chime', 'category'], value: 'record' },
  ]);
  expect(pageErrors).toEqual([]);
});

test('the music-disc module wraps the jukebox fields, keeps unknown keys and clamps its range', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Sounds', 'anthem');

  const disc = page.locator('[data-module="jukebox"]');
  await expect(disc).toContainText('Music disc');
  await expect(disc.getByRole('textbox', { name: 'Song title' })).toHaveValue('Ruby Anthem');
  await expect(disc.getByRole('spinbutton', { name: 'Length' })).toHaveValue('90');
  await expect(disc).toContainText('seconds');
  await expect(disc).toContainText('Other settings (future_song_key) are kept as written');

  const signal = disc.getByRole('spinbutton', { name: 'Comparator signal' });
  await signal.fill('20');
  await signal.press('Tab');
  await expect(signal).toHaveValue('15');

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([{
    field: ['anthem', 'jukebox'],
    value: { description: 'Ruby Anthem', duration: 90, comparator_output: 15, range: 1, future_song_key: 1 },
  }]);

  // Removing the module removes the whole section, nothing else.
  await disc.getByRole('button', { name: 'Remove Music disc' }).click();
  const removal = await saveAndApply(page, 2);
  expect(removal.payload.edits).toEqual([{ field: ['anthem', 'jukebox'], remove: true }]);
  expect((await documentOf(page, 'sounds/anthem.yml'))?.parsed.anthem).toHaveProperty('volume', 1.5);
  expect(pageErrors).toEqual([]);
});

test('a text effect previews live on sample text; kind, speed and the palette drive it', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Text effects', 'rainbow');

  const preview = page.getByRole('region', { name: 'Effect preview' });
  await expect(preview).toHaveAttribute('data-effect-kind', 'rainbow');
  const first = preview.locator('[data-ch="0"]');
  const initial = await first.evaluate((element) => getComputedStyle(element).color);
  // The colours flow: the first letter changes colour as time passes.
  await expect.poll(() => first.evaluate((element) => getComputedStyle(element).color)).not.toBe(initial);

  // Pausing holds the picture still.
  await preview.getByRole('button', { name: 'Pause preview' }).click();
  const held = await first.evaluate((element) => getComputedStyle(element).color);
  await page.waitForTimeout(300);
  expect(await first.evaluate((element) => getComputedStyle(element).color)).toBe(held);
  await preview.getByRole('button', { name: 'Play preview' }).click();

  // Your own sample text.
  await preview.getByRole('textbox', { name: 'Sample text' }).fill('Hi');
  await expect(preview.locator('[data-ch]')).toHaveCount(2);

  const display = page.getByRole('article', { name: 'Display' });
  const kind = display.getByRole('radiogroup', { name: 'Kind' });
  await expect(kind.getByRole('radio', { name: 'Rainbow' })).toHaveAttribute('aria-checked', 'true');
  await kind.getByRole('radio', { name: 'Wave' }).click();
  await expect(preview).toHaveAttribute('data-effect-kind', 'wave');
  await expect.poll(() => preview.locator('[data-ch="1"]').evaluate((element) => element.getAttribute('style')))
    .toMatch(/translate\(0px, (?!-?0px)/);
  await expect(display.getByRole('spinbutton', { name: 'Speed' })).toHaveValue('2');
  await kind.getByRole('radio', { name: 'Rainbow' }).click();

  // Palette chips: swatches with their colour; add and remove.
  const palette = page.locator('[data-module="colors"]');
  await expect(palette.locator('[data-color="#FF0000"]')).toBeVisible();
  await expect(palette.locator('li')).toHaveCount(3);
  await palette.getByRole('button', { name: 'Add color' }).click();
  await expect(palette.locator('li')).toHaveCount(4);
  await palette.getByRole('button', { name: 'Remove color 1' }).click();

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([
    { field: ['rainbow', 'colors'], value: ['#FFAA00', '#FF00FF', '#FF0000'] },
  ]);
  expect(pageErrors).toEqual([]);
});

test('an effect without a palette is added as a module; the preview says the spectrum cycles', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Text effects', 'wave');

  await addModule(page, 'palette');
  const palette = page.locator('[data-module="colors"]');
  await palette.getByRole('button', { name: 'Add color' }).click();
  await expect(palette.locator('li')).toHaveCount(1);
  await expect(palette.locator('[data-color="#FF0000"]')).toBeVisible();

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([{ field: ['wave', 'colors'], value: ['#FF0000'] }]);
  expect(pageErrors).toEqual([]);
});
