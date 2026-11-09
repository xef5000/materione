/**
 * The studio's drawing of every container (gui.md section 12.7): the screen composed from a profile's blit list
 * (the owner's art when its sheets exist, else a generated diagram), the one-line cost of a container switch, and
 * the ban that keeps the GUI code container-agnostic (section 12.13 item 5). Pure.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';
import { CONTAINERS, cellsOf, containerProfile, wellRect } from '../src/lib/gui/containers';
import type { GuiLayer } from '../src/lib/gui/model';
import { ArgbCanvas } from '../src/lib/gui/raster/canvas';
import { newlyFlagged, switchCost } from '../src/lib/gui/switch-cost';
import { composeDiagram, composeUnderlay, hasArt, VEIL, veiledScreen } from '../src/lib/gui/underlay';
import { decodePng } from './helpers/png';

const profile = (id: string) => containerProfile(id)!;
const SHEET = decodePng(new Uint8Array(readFileSync(fileURLToPath(new URL('../static/vanilla/generic_54.png', import.meta.url)))));
const SHEETS = new Map([['generic_54', SHEET]]);

describe('the underlay of a profile (gui.md section 12.7, options A and C)', () => {
  it('is the owner\'s art when every sheet the blit list names exists, and says so', () => {
    const { screen, kind } = composeUnderlay(profile('chest_3'), SHEETS);
    expect(kind).toBe('art');
    expect([screen.width, screen.height]).toEqual([176, 168]);
    // the first blit puts the sheet's top-left rows at the origin, the second the inventory part below
    expect(screen.get(88, 10)).toBe(SHEET.get(88, 10));
    expect(screen.get(8, 71)).toBe(SHEET.get(8, 126));
  });

  it('is a generated diagram for a container whose sheet is missing, of exactly the profile\'s screen size', () => {
    for (const id of ['hopper', 'dispenser']) {
      const container = profile(id);
      expect(hasArt(container, SHEETS)).toBe(false);
      const { screen, kind } = composeUnderlay(container, SHEETS);
      expect(kind).toBe('diagram');
      expect([screen.width, screen.height]).toEqual([container.width, container.height]);
    }
  });

  it('falls back to the diagram for any profile when no sheet exists at all (a failed load)', () => {
    for (const container of CONTAINERS) expect(composeUnderlay(container, new Map()).kind).toBe('diagram');
  });

  it('draws a well at every slot of the container and the player\'s inventory, at the profile\'s exact positions', () => {
    for (const id of ['hopper', 'dispenser']) {
      const container = profile(id);
      const diagram = composeDiagram(container);
      const cells = cellsOf(container, 'all');
      expect(cells).toHaveLength(container.slotCount + 36);
      const interior = (cell: { x: number; y: number }) => diagram.get(cell.x + 8, cell.y + 8);
      const frame = diagram.get(5, 5); // the frame's own fill, away from its bevel
      for (const cell of cells) {
        const well = wellRect(cell);
        // the well's fill differs from the frame's, and it is the 18 x 18 square around the 16 x 16 item area
        expect(interior(cell), `${id} slot ${cell.index}`).not.toBe(frame);
        expect(well).toEqual({ x: cell.x - 1, y: cell.y - 1, w: 18, h: 18 });
      }
      // the pixel just above the first well is the frame again: nothing else is drawn there
      expect(diagram.get(cells[0]!.x + 8, cells[0]!.y - 3)).toBe(frame);
    }
  });

  it('leaves the diagram\'s corners transparent and everything else opaque', () => {
    const diagram = composeDiagram(profile('hopper'));
    expect(diagram.get(0, 0) >>> 24).toBe(0);
    expect(diagram.get(88, 60) >>> 24).toBe(255);
    expect(diagram.get(88, 132) >>> 24).toBe(255);
  });

  it('veils the player\'s part below the profile\'s inventoryTop, for the diagram as for the art, and nothing above it', () => {
    for (const id of ['hopper', 'dispenser', 'chest_2']) {
      const container = profile(id);
      const { screen } = composeUnderlay(container, SHEETS);
      const veiled = veiledScreen(screen, container, () => false);
      expect(veiled.get(88, container.inventoryTop - 1), id).toBe(screen.get(88, container.inventoryTop - 1));
      const below = screen.get(20, container.inventoryTop + 6);
      if (below >>> 24 !== 0) expect(veiled.get(20, container.inventoryTop + 6), id).not.toBe(below);
      const lifted = veiledScreen(screen, container, (x, y) => x === 20 && y === container.inventoryTop + 6);
      expect(lifted.get(20, container.inventoryTop + 6), id).toBe(screen.get(20, container.inventoryTop + 6));
    }
    expect(VEIL >>> 24).toBe(0xa0);
  });

  it('never alters the canvas it was given, and shares nothing between two profiles', () => {
    const screen = composeDiagram(profile('hopper'));
    const before = screen.clone();
    veiledScreen(screen, profile('hopper'), () => false);
    expect(screen.equals(before)).toBe(true);
    expect(composeDiagram(profile('hopper'))).not.toBe(composeDiagram(profile('hopper')));
    expect(new ArgbCanvas(1, 1)).toBeTruthy();
  });
});

describe('the one-line cost of a container switch (gui.md section 12.7)', () => {
  const layer = (id: string, type: string): GuiLayer => ({ id, type }) as GuiLayer;
  const layers = [layer('frame', 'panel'), layer('a', 'item'), layer('b', 'materione:item'), layer('c', 'item'), layer('wall', 'list')];

  it('picks the layers the target container flags and the current one did not', () => {
    const before = new Map([['c', []]]); // c was already flagged: not a cost of this switch
    const after = new Map([['a', []], ['b', []], ['c', []], ['wall', []]]);
    expect(newlyFlagged(layers, before, after).map((entry) => entry.id)).toEqual(['a', 'b', 'wall']);
    expect(newlyFlagged(layers, after, after)).toEqual([]);
  });

  it('says what is kept and skipped, counting the item layers', () => {
    const text = switchCost('Hopper', [layer('a', 'item'), layer('b', 'materione:item'), layer('c', 'item')]);
    expect(text).toBe('On Hopper, 3 item layers use slots it does not have; they are kept and skipped until you move them.');
  });

  it('is singular for one layer, and names lists and other layers', () => {
    expect(switchCost('Dispenser', [layer('a', 'item')]))
      .toBe('On Dispenser, 1 item layer uses slots it does not have; it is kept and skipped until you move it.');
    expect(switchCost('Hopper', [layer('a', 'item'), layer('w', 'list'), layer('x', 'button'), layer('y', 'button')]))
      .toContain('1 item layer, 1 list layer and 2 other layers use slots it does not have');
  });

  it('says nothing is flagged when every layer fits', () => {
    expect(switchCost('Chest, 6 rows', [])).toBe('Nothing is flagged on Chest, 6 rows: every layer fits.');
  });
});

// ---------------------------------------------------------------------------------- the ban

/** The directories whose code is GUI code: the studio's modules and its components. */
const SCANNED = ['../src/lib/gui', '../src/lib/studio/gui'];
/** The one file that may name containers: the profile reader. */
const PROFILE_READER = 'src/lib/gui/containers.ts';

/** Files where `rows` means something else, and why. */
const ROWS_ARE_NOT_CONTAINER_ROWS: Record<string, string> = {
  'src/lib/gui/font/render.ts': 'the glyph sheet\'s rows of pixels',
  'src/lib/gui/font/smallcaps.ts': 'the glyph sheet\'s rows of pixels',
  'src/lib/studio/gui/LayersPanel.svelte': 'the layer list\'s own rows',
};

function sourcesUnder(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...sourcesUnder(path));
    else if (/\.(ts|svelte)$/.test(name)) out.push(path);
  }
  return out;
}

/** Source with comments removed; string literals blanked to their quotes (`keepLiterals` false) or kept. */
export function stripped(source: string, keepLiterals: boolean): string {
  let out = '';
  let i = 0;
  while (i < source.length) {
    const c = source[i]!;
    if (source.startsWith('//', i) && source[i - 1] !== ':') {
      while (i < source.length && source[i] !== '\n') i++;
    } else if (source.startsWith('/*', i)) {
      const end = source.indexOf('*/', i + 2);
      i = end < 0 ? source.length : end + 2;
      out += ' ';
    } else if (source.startsWith('<!--', i)) {
      const end = source.indexOf('-->', i + 4);
      i = end < 0 ? source.length : end + 3;
      out += ' ';
    } else if (c === '"' || c === "'" || c === '`') {
      const start = i++;
      while (i < source.length && source[i] !== c) i += source[i] === '\\' ? 2 : 1;
      i = Math.min(i + 1, source.length);
      out += keepLiterals ? source.slice(start, i) : `${c}${c}`;
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/**
 * What a rule reads of a source file: its code (comments removed, string literals blanked) and its literals (comments
 * removed, strings kept). A .svelte file is its script, plus the template's `{...}` expressions for the code rules (the
 * markup's text holds apostrophes a string-aware stripper cannot tell from quotes) and the whole template for the word.
 */
function analysed(name: string, text: string): { code: string; literals: string } {
  if (!name.endsWith('.svelte')) return { code: stripped(text, false), literals: stripped(text, true) };
  const script = [...text.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((match) => match[1]!).join('\n');
  const markup = text
    .replace(/<script[^>]*>[\s\S]*?<\/script>/g, ' ')
    .replace(/<style[^>]*>[\s\S]*?<\/style>/g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ');
  const expressions = (markup.match(/\{[^{}]*\}/g) ?? []).join('\n');
  return {
    code: stripped(`${script}\n${expressions}`, false),
    literals: `${stripped(script, true)}\n${markup}`,
  };
}

const ROWS = /\brows\b/;
const NINE = /[%/]\s*9\b/;
const CHEST_SIZE = /\b(176|114)\b/;
const CHEST_WORD = /chest/i;

describe('the GUI code is container-agnostic (gui.md section 12.13 item 5)', () => {
  const root = fileURLToPath(new URL('..', import.meta.url));
  const files = SCANNED.flatMap((dir) => sourcesUnder(fileURLToPath(new URL(dir, import.meta.url))))
    .map((path) => ({ path, name: relative(root, path).replace(/\\/g, '/') }))
    .filter(({ name }) => name !== PROFILE_READER);

  it('scans the studio\'s GUI code (a ban that finds no files bans nothing)', () => {
    expect(files.length).toBeGreaterThan(40);
    expect(files.some(({ name }) => name === 'src/lib/gui/studio.svelte.ts')).toBe(true);
    expect(files.some(({ name }) => name === 'src/lib/studio/gui/InspectorPanel.svelte')).toBe(true);
  });

  it('names no chest, a chest\'s row count, its 9 columns or its 176 x 114 size outside the profile reader', () => {
    const offenders: string[] = [];
    for (const { path, name } of files) {
      const text = readFileSync(path, 'utf8');
      const { code, literals } = analysed(name, text);
      if (!(name in ROWS_ARE_NOT_CONTAINER_ROWS) && ROWS.test(code)) offenders.push(`${name}: the identifier 'rows'`);
      if (NINE.test(code)) offenders.push(`${name}: '% 9' or '/ 9'`);
      if (CHEST_SIZE.test(code)) offenders.push(`${name}: 176 or 114`);
      // the word in an identifier, a string or the markup's text: each is a chest the code knows by name
      if (CHEST_WORD.test(literals)) offenders.push(`${name}: the word chest in code, a string or the markup`);
    }
    expect(offenders).toEqual([]);
  });

  it('the scanner sees what it bans', () => {
    // a guard on the scanner itself, so a broken stripper cannot turn the ban into a pass
    expect(ROWS.test(stripped('const n = doc.rows * 9;', false))).toBe(true);
    expect(NINE.test(stripped('const c = slot % 9;', false))).toBe(true);
    expect(CHEST_SIZE.test(stripped('const w = 176;', false))).toBe(true);
    expect(CHEST_WORD.test(stripped("const id = 'chest_3';", true))).toBe(true);
    expect(CHEST_WORD.test(stripped('const chest = studio.underlay;', true))).toBe(true);
    expect(ROWS.test(stripped('// rows\n/* rows */ const s = "rows";', false))).toBe(false);
    expect(CHEST_WORD.test(stripped('// the chest\nconst a = 1; <!-- chest -->', true))).toBe(false);
    expect(CHEST_SIZE.test(stripped('const s = "a chest is 176";', false))).toBe(false);
  });
});
