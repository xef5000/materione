/**
 * Paint tools on a paint layer's canvas: brush footprints, strokes interpolated between pointer
 * events, fill, the eraser, and the rule that a stroke is ONE undoable step that also makes the
 * history dirty (and clean again when undone). Pure; the canvas wiring is covered in the browser spec.
 */
import { describe, expect, it } from 'vitest';
import { GuiHistory } from '../src/lib/gui/history';
import { starterDocument } from '../src/lib/gui/model';
import {
  ERASER,
  Stroke,
  applyPatch,
  clampBrush,
  diffAll,
  fillPatch,
  footprint,
  outlinePath,
  pushRecent,
  resizedCanvas,
} from '../src/lib/gui/paint';
import { PixelCommand } from '../src/lib/gui/pixel-command';
import { ArgbCanvas } from '../src/lib/gui/raster/canvas';

const RED = 0xffff0000;
const BLUE = 0xff0000ff;
const set = (cells: { x: number; y: number }[]): string[] => cells.map((cell) => `${cell.x},${cell.y}`).sort();
const lit = (canvas: ArgbCanvas): string[] => {
  const out: string[] = [];
  canvas.pixels.forEach((value, index) => value && out.push(`${index % canvas.width},${Math.floor(index / canvas.width)}`));
  return out.sort();
};
const brush = (size: number, shape: 'square' | 'round' = 'square') => ({ size, shape });

describe('the brush', () => {
  it('is 1 to 8 pixels', () => {
    expect([clampBrush(0), clampBrush(1), clampBrush(4.4), clampBrush(8), clampBrush(99)]).toEqual([1, 1, 4, 8, 8]);
  });

  it('a square covers size x size, centered for odd sizes and growing right/down for even ones', () => {
    expect(set(footprint(5, 5, brush(1)))).toEqual(['5,5']);
    expect(footprint(5, 5, brush(3))).toHaveLength(9);
    expect(set(footprint(5, 5, brush(3)))).toContain('4,4');
    expect(set(footprint(5, 5, brush(2)))).toEqual(['5,5', '5,6', '6,5', '6,6']);
    for (let size = 1; size <= 8; size++) expect(footprint(0, 0, brush(size))).toHaveLength(size * size);
  });

  it('a round brush trims the corners: a plus at 3, an octagon at 5, never an empty or square shape', () => {
    expect(set(footprint(5, 5, brush(3, 'round')))).toEqual(['4,5', '5,4', '5,5', '5,6', '6,5']);
    const five = set(footprint(5, 5, brush(5, 'round')));
    expect(five).toHaveLength(21);
    expect(five).not.toContain('3,3');
    expect(five).toContain('3,4');
    for (let size = 1; size <= 8; size++) {
      const round = footprint(5, 5, brush(size, 'round')).length;
      expect(round).toBeGreaterThan(0);
      expect(round).toBeLessThanOrEqual(size * size);
    }
    // symmetric about its centre for odd sizes
    const seven = new Set(set(footprint(0, 0, brush(7, 'round'))));
    for (const key of seven) {
      const [x, y] = key.split(',').map(Number) as [number, number];
      expect(seven.has(`${-x},${y}`)).toBe(true);
      expect(seven.has(`${x},${-y}`)).toBe(true);
    }
  });

  it('outlines a footprint with exactly its exposed edges', () => {
    expect(outlinePath([{ x: 2, y: 3 }])).toBe('M2 3h1M2 4h1M2 3v1M3 3v1');
    // two neighbours share one edge, so it is not drawn
    expect(outlinePath([{ x: 0, y: 0 }, { x: 1, y: 0 }])).not.toContain('M1 0v1M1 0v1');
    expect(outlinePath([])).toBe('');
  });
});

describe('strokes', () => {
  it('stamp the first pixel at the start and join every move with a continuous line (no gaps when the pointer jumps)', () => {
    const canvas = new ArgbCanvas(32, 32);
    const stroke = new Stroke(canvas, 'gui/a', RED, brush(1), { x: 2, y: 2 });
    expect(lit(canvas)).toEqual(['2,2']);
    stroke.to({ x: 12, y: 2 }); // a fast drag: ten pixels in one event
    expect(lit(canvas)).toHaveLength(11);
    for (let x = 2; x <= 12; x++) expect(canvas.get(x, 2)).toBe(RED);
    stroke.to({ x: 12, y: 8 });
    for (let y = 2; y <= 8; y++) expect(canvas.get(12, y)).toBe(RED);
    expect(stroke.to({ x: 12, y: 8 })).toBe(false); // no movement, no work
  });

  it('is one patch, however many events built it, holding exactly the changed pixels', () => {
    const canvas = new ArgbCanvas(16, 16);
    canvas.set(5, 5, BLUE);
    const stroke = new Stroke(canvas, 'gui/a', RED, brush(1), { x: 3, y: 5 });
    for (let x = 4; x <= 8; x++) stroke.to({ x, y: 5 });
    const patch = stroke.end()!;
    expect(patch.reference).toBe('gui/a');
    expect(patch.indices).toHaveLength(6); // x = 3..8 on row 5, the blue pixel included
    expect([...patch.before].filter((value) => value === BLUE)).toHaveLength(1);
    expect([...patch.after].every((value) => value === RED)).toBe(true);
    applyPatch(canvas, patch, 'before');
    expect(lit(canvas)).toEqual(['5,5']);
    expect(canvas.get(5, 5)).toBe(BLUE);
    applyPatch(canvas, patch, 'after');
    expect(canvas.get(3, 5)).toBe(RED);
  });

  it('painting a colour that is already there changes nothing and yields no patch', () => {
    const canvas = new ArgbCanvas(8, 8);
    canvas.set(2, 2, RED);
    expect(new Stroke(canvas, 'gui/a', RED, brush(1), { x: 2, y: 2 }).end()).toBeNull();
  });

  it('the eraser replaces with transparent (it does not blend), and a size-3 round eraser takes a plus', () => {
    const canvas = new ArgbCanvas(16, 16);
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) canvas.set(x, y, 0x80ff0000);
    const patch = new Stroke(canvas, 'gui/a', ERASER, brush(3, 'round'), { x: 8, y: 8 }).end()!;
    expect(patch.indices).toHaveLength(5);
    expect(canvas.get(8, 8)).toBe(0);
    expect(canvas.get(7, 7)).toBe(0x80ff0000);
  });

  it('a straight line from a previous point (shift-click) joins the two', () => {
    const canvas = new ArgbCanvas(32, 32);
    new Stroke(canvas, 'gui/a', RED, brush(1), { x: 4, y: 4 }, { x: 20, y: 4 }).end();
    expect(lit(canvas)).toHaveLength(17);
  });

  it('keeps a round or thick stroke inside the canvas and works at the edges', () => {
    const canvas = new ArgbCanvas(8, 8);
    const stroke = new Stroke(canvas, 'gui/a', RED, brush(8), { x: 0, y: 0 });
    stroke.to({ x: 100, y: 100 });
    expect(canvas.pixels.every((value) => value === RED)).toBe(true);
    expect(stroke.end()!.indices).toHaveLength(64);
  });
});

describe('fill', () => {
  it('floods the contiguous exact-match region only (tolerance 0, four-connected)', () => {
    const canvas = new ArgbCanvas(6, 6);
    for (let y = 0; y < 6; y++) canvas.set(3, y, BLUE); // a wall splitting the canvas
    canvas.set(0, 0, 0xff000001); // a nearly-transparent look-alike is NOT the same colour
    const patch = fillPatch(canvas, 'gui/a', 1, 1, RED)!;
    expect(canvas.get(1, 1)).toBe(RED);
    expect(canvas.get(2, 5)).toBe(RED);
    expect(canvas.get(4, 1)).toBe(0); // the other side of the wall
    expect(canvas.get(0, 0)).toBe(0xff000001);
    expect(patch.indices).toHaveLength(6 * 3 - 1); // the left region minus the look-alike pixel
  });

  it('does nothing when the target already has the colour, or the point is outside', () => {
    const canvas = new ArgbCanvas(4, 4);
    expect(fillPatch(canvas, 'gui/a', 1, 1, 0)).toBeNull();
    expect(fillPatch(canvas, 'gui/a', 9, 9, RED)).toBeNull();
  });

  it('fills a whole transparent canvas in one patch and can be undone exactly', () => {
    const canvas = new ArgbCanvas(176, 168);
    const patch = fillPatch(canvas, 'gui/a', 10, 10, RED)!;
    expect(patch.indices).toHaveLength(176 * 168);
    applyPatch(canvas, patch, 'before');
    expect(canvas.pixels.every((value) => value === 0)).toBe(true);
  });
});

describe('a stroke is one undoable step, and the history knows whether the pixels are clean', () => {
  const setup = () => {
    const canvas = new ArgbCanvas(16, 16);
    const touched: string[] = [];
    const command = (label: string, x: number, color = RED) => {
      const stroke = new Stroke(canvas, 'gui/a', color, brush(1), { x, y: 1 });
      return new PixelCommand(label, stroke.end()!, () => canvas, (reference) => touched.push(reference));
    };
    return { canvas, touched, command, history: new GuiHistory(starterDocument()) };
  };

  it('makes the studio dirty, undo restores the pixels and the clean state, redo reapplies', () => {
    const { canvas, command, history } = setup();
    expect(history.dirty).toBe(false);
    history.execute(command('Paint stroke', 3));
    expect(history.dirty).toBe(true);
    expect(history.pixelsDirty).toBe(true);
    expect(history.undoLabel).toBe('Paint stroke');
    history.undo();
    expect(canvas.get(3, 1)).toBe(0);
    expect(history.dirty).toBe(false);
    history.redo();
    expect(canvas.get(3, 1)).toBe(RED);
    expect(history.dirty).toBe(true);
  });

  it('reports the touched asset on apply, undo and redo so it is (re)saved', () => {
    const { command, touched, history } = setup();
    history.execute(command('Paint stroke', 3));
    history.undo();
    history.redo();
    expect(touched).toEqual(['gui/a', 'gui/a', 'gui/a']);
  });

  it('is clean exactly at the saved state: undoing past a save dirties it, a new stroke dirties it, undoing back cleans it', () => {
    const { command, history } = setup();
    history.execute(command('one', 1));
    history.markSaved();
    expect(history.dirty).toBe(false);
    history.undo(); // before the saved stroke
    expect(history.dirty).toBe(true);
    history.redo();
    expect(history.dirty).toBe(false);
    history.execute(command('two', 2));
    expect(history.dirty).toBe(true);
    history.undo();
    expect(history.dirty).toBe(false);
    // undoing the saved stroke and painting a different one must not look clean
    history.undo();
    history.execute(command('three', 5));
    expect(history.dirty).toBe(true);
  });

  it('a clear is one step too', () => {
    const { canvas, history } = setup();
    for (let x = 0; x < 16; x++) canvas.set(x, 1, BLUE);
    const snapshot = canvas.pixels.slice();
    canvas.pixels.fill(0);
    const patch = diffAll('gui/a', snapshot, canvas)!;
    history.execute(new PixelCommand('Clear paint layer', patch, () => canvas, () => undefined));
    history.undo();
    expect(canvas.get(7, 1)).toBe(BLUE);
  });
});

describe('helpers', () => {
  it('resizes a canvas keeping the overlap (the rows changed under a paint layer)', () => {
    const canvas = new ArgbCanvas(4, 4);
    canvas.set(3, 3, RED);
    const grown = resizedCanvas(canvas, 4, 6);
    expect([grown.width, grown.height, grown.get(3, 3), grown.get(3, 5)]).toEqual([4, 6, RED, 0]);
    expect(resizedCanvas(canvas, 4, 3).get(3, 2)).toBe(0);
  });

  it('keeps the recent colours newest first, without duplicates, bounded, and never remembers transparent', () => {
    let recent: number[] = [];
    for (const color of [1, 2, 3, 2]) recent = pushRecent(recent, 0xff000000 + color);
    expect(recent).toEqual([0xff000002, 0xff000003, 0xff000001]);
    expect(pushRecent(recent, 0)).toEqual(recent);
    expect(pushRecent(Array.from({ length: 12 }, (_, n) => 0xff000000 + n), 0xff0000ff)).toHaveLength(12);
  });
});
