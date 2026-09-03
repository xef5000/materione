/**
 * The defect this file exists for: the first Content tab rendered a header
 * whose action cluster could not shrink, inside grids whose columns could not
 * shrink, so the browser grew a horizontal scrollbar and the primary action
 * ("Create item") fell off the right edge. These are the CSS invariants that
 * make that impossible; they are cheap to check and expensive to rediscover.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const SURFACES = [
  'src/lib/views/ContentView.svelte',
  'src/lib/studio/Studio.svelte',
  'src/lib/studio/TextureEditor.svelte',
  'src/lib/components/AppHeader.svelte',
  'src/lib/components/Workbench.svelte',
];

function source(relative: string): string {
  return readFileSync(fileURLToPath(new URL(`../${relative}`, import.meta.url)), 'utf8');
}

/** Every declaration of one property across a file's <style> block. */
function declarations(css: string, property: string): string[] {
  const pattern = new RegExp(`(?<![-\\w])${property}\\s*:\\s*([^;}]+)`, 'g');
  return [...css.matchAll(pattern)].map((match) => match[1]!.trim());
}

describe.each(SURFACES)('%s', (surface) => {
  const css = source(surface);

  it('never scrolls sideways: no overflow-x auto/scroll', () => {
    const offenders = [
      ...declarations(css, 'overflow-x'),
      ...declarations(css, 'overflow'),
    ].filter((value) => /\b(auto|scroll)\b/.test(value.split(/\s+/)[0] ?? ''));
    expect(offenders).toEqual([]);
  });

  it('lets every grid column shrink below its content', () => {
    const rigid = declarations(css, 'grid-template-columns').filter((value) => {
      // `repeat(auto-fill, minmax(N, 1fr))` wraps instead of overflowing, and
      // fixed pixel columns (icons, buttons) are what the flexible ones absorb.
      if (value.includes('auto-fill') || value.includes('auto-fit')) return false;
      const tracks = value.split(/\s+(?![^(]*\))/);
      return tracks.some((track) => track === '1fr' || track === 'auto' || track === 'max-content');
    });
    expect(rigid).toEqual([]);
  });
});

describe('content browser toolbar', () => {
  const css = source('src/lib/views/ContentView.svelte');

  it('keeps the primary action visible by letting the flexible part shrink', () => {
    // A flex row is only safe when something in it declares min-width: 0.
    expect(declarations(css, 'min-width')).toContain('0');
  });

  it('ellipsises long names instead of widening the row', () => {
    expect(declarations(css, 'text-overflow')).toContain('ellipsis');
  });
});

describe('no filler prose', () => {
  it('the content browser explains itself through its controls, not paragraphs', () => {
    const markup = source('src/lib/views/ContentView.svelte').split('<style')[0]!;
    const sentences = [...markup.matchAll(/>\s*([A-Z][^<>{}]{60,})\s*</g)].map((m) => m[1]!.trim());
    expect(sentences).toEqual([]);
  });
});
