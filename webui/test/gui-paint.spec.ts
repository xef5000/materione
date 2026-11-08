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
  cellsOfPatch,
  clampBrush,
  fillPatch,
  footprint,
  outlinePath,
  paintedBox,
  pushRecent,
  workingCanvas,
  type PixelPatch,
} from '../src/lib/gui/paint';
import { geometryFields, paintRect, trimPaintLayers } from '../src/lib/gui/paint-geometry';
import { PixelCommand, paintCommand, type PixelStore } from '../src/lib/gui/pixel-command';
import type { GuiLayer } from '../src/lib/gui/model';
import { ART_CAP } from '../src/lib/gui/open-canvas';
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
/** Writes a patch's before or after values straight into a canvas (an index patch is local to the canvas it was made on). */
const writePatch = (canvas: ArgbCanvas, patch: PixelPatch, direction: 'after' | 'before'): void => {
  patch.indices.forEach((index, k) => (canvas.pixels[index] = patch[direction][k]!));
};

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
    writePatch(canvas, patch, 'before');
    expect(lit(canvas)).toEqual(['5,5']);
    expect(canvas.get(5, 5)).toBe(BLUE);
    writePatch(canvas, patch, 'after');
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
    writePatch(canvas, patch, 'before');
    expect(canvas.pixels.every((value) => value === 0)).toBe(true);
  });
});

describe('a stroke is one undoable step, and the history knows whether the pixels are clean', () => {
  /** A document with one paint layer whose bitmap sits in a store, as the studio keeps it. */
  const setup = (bitmap = new ArgbCanvas(0, 0), layer: GuiLayer = { id: 'art', type: 'paint', image: 'gui/a' }) => {
    const bitmaps = new Map<string, ArgbCanvas>([['gui/a', bitmap]]);
    const touched: string[] = [];
    const store: PixelStore = {
      get: (reference) => bitmaps.get(reference),
      set: (reference, canvas) => void bitmaps.set(reference, canvas),
      touched: (reference) => void touched.push(reference),
    };
    const doc = { ...starterDocument(), layers: [layer] };
    const history = new GuiHistory(doc);
    /** What the studio does for a stroke: run it on a working copy that covers the cap, then build one command. */
    const stroke = (label: string, x: number, y = 1, color = RED) => {
      const current = history.doc.layers[0]!;
      const current_bitmap = bitmaps.get('gui/a')!;
      const rect = paintRect(current, current_bitmap);
      const working = workingCanvas(current_bitmap, rect, ART_CAP);
      const patch = new Stroke(working, 'gui/a', color, brush(1), { x, y }).end()!;
      const fields = Object.fromEntries(['x', 'y', 'width', 'height'].map((key) => [key, Object.hasOwn(current, key) ? current[key] : undefined]));
      return paintCommand(label, 'art', 'gui/a', { rect, fields }, cellsOfPatch(patch, working), store);
    };
    return { bitmaps, touched, store, stroke, history };
  };
  const layerOf = (history: GuiHistory) => history.doc.layers[0]!;

  it('makes the studio dirty, undo restores the pixels and the clean state, redo reapplies', () => {
    const { bitmaps, stroke, history } = setup();
    expect(history.dirty).toBe(false);
    history.execute(stroke('Paint stroke', 3));
    expect(history.dirty).toBe(true);
    expect(history.pixelsDirty).toBe(true);
    expect(history.undoLabel).toBe('Paint stroke');
    expect(bitmaps.get('gui/a')!.get(0, 0)).toBe(RED); // a 1 x 1 layer holding the pixel, at (3, 1)
    history.undo();
    expect(bitmaps.get('gui/a')!.width).toBe(0);
    expect(layerOf(history)).toEqual({ id: 'art', type: 'paint', image: 'gui/a' }); // the very same fields, none left behind
    expect(history.dirty).toBe(false);
    history.redo();
    expect(bitmaps.get('gui/a')!.get(0, 0)).toBe(RED);
    expect(history.dirty).toBe(true);
  });

  it('reports the touched asset on apply, undo and redo so it is (re)saved', () => {
    const { stroke, touched, history } = setup();
    history.execute(stroke('Paint stroke', 3));
    history.undo();
    history.redo();
    expect(touched).toEqual(['gui/a', 'gui/a', 'gui/a']);
  });

  it('is clean exactly at the saved state: undoing past a save dirties it, a new stroke dirties it, undoing back cleans it', () => {
    const { stroke, history } = setup();
    history.execute(stroke('one', 1));
    history.markSaved();
    expect(history.dirty).toBe(false);
    history.undo(); // before the saved stroke
    expect(history.dirty).toBe(true);
    history.redo();
    expect(history.dirty).toBe(false);
    history.execute(stroke('two', 2));
    expect(history.dirty).toBe(true);
    history.undo();
    expect(history.dirty).toBe(false);
    // undoing the saved stroke and painting a different one must not look clean
    history.undo();
    history.execute(stroke('three', 5));
    expect(history.dirty).toBe(true);
  });

  it('a stroke that leaves the layer grows it, in every direction, and the growth is part of the same undo step', () => {
    const { bitmaps, stroke, history } = setup();
    history.execute(stroke('one', 10, 10));
    expect(layerOf(history)).toMatchObject({ x: 10, y: 10, width: 1, height: 1 });
    history.execute(stroke('left and up', 4, 7));
    expect(layerOf(history)).toMatchObject({ x: 4, y: 7, width: 7, height: 4 });
    expect([bitmaps.get('gui/a')!.width, bitmaps.get('gui/a')!.height]).toEqual([7, 4]);
    history.execute(stroke('far out', -300, 500)); // outside the screen on both axes, still inside the cap
    expect(layerOf(history)).toMatchObject({ x: -300, y: 7, width: 311, height: 494 });
    expect(bitmaps.get('gui/a')!.get(0, 493)).toBe(RED);
    expect(bitmaps.get('gui/a')!.get(310, 3)).toBe(RED); // the first pixel, (10, 10), kept its place while the box grew around it
    history.undo();
    expect(layerOf(history)).toMatchObject({ x: 4, y: 7, width: 7, height: 4 });
    expect(bitmaps.get('gui/a')!.get(6, 3)).toBe(RED);
    history.undo();
    history.undo();
    expect(layerOf(history)).toEqual({ id: 'art', type: 'paint', image: 'gui/a' });
    expect(history.dirty).toBe(false);
  });

  it('a stroke inside the layer changes no document field, and a layer from before the open canvas keeps having none', () => {
    const legacy = new ArgbCanvas(176, 168);
    const { stroke, history } = setup(legacy);
    history.execute(stroke('inside', 30, 40));
    expect(layerOf(history)).toEqual({ id: 'art', type: 'paint', image: 'gui/a' });
    history.execute(stroke('outside', 200, 40));
    expect(layerOf(history)).toMatchObject({ x: 0, y: 0, width: 201, height: 168 });
    history.undo();
    expect(layerOf(history)).toEqual({ id: 'art', type: 'paint', image: 'gui/a' });
    expect(history.dirty).toBe(true); // one undone, one still applied
    history.undo();
    expect(history.dirty).toBe(false);
  });

  it('keeps working after the layer was trimmed in between (a save): the cells are at GUI positions', () => {
    const { bitmaps, stroke, history } = setup();
    history.execute(stroke('one', 10, 10));
    history.execute(stroke('two', 40, 30));
    // what a save does to a layer with a margin: the box shrinks to the painted pixels, the history is untouched
    const trim = trimPaintLayers(history.doc, (reference) => bitmaps.get(reference), () => true);
    for (const entry of trim.layers) bitmaps.set(entry.reference, entry.canvas);
    history.adopt(trim.doc);
    history.undo();
    expect(layerOf(history)).toMatchObject({ x: 10, y: 10, width: 1, height: 1 });
    expect(bitmaps.get('gui/a')!.get(0, 0)).toBe(RED);
    history.redo();
    expect(layerOf(history)).toMatchObject({ x: 10, y: 10, width: 31, height: 21 });
    expect(bitmaps.get('gui/a')!.get(30, 20)).toBe(RED);
  });

  it('a clear is one step too, and leaves a layer with no pixel', () => {
    const canvas = new ArgbCanvas(16, 4);
    for (let x = 0; x < 16; x++) canvas.set(x, 1, BLUE);
    const { bitmaps, store, history } = setup(canvas, { id: 'art', type: 'paint', image: 'gui/a', x: 5, y: 6, width: 16, height: 4 });
    const cells = {
      x: Int32Array.from({ length: 16 }, (_, k) => 5 + k),
      y: new Int32Array(16).fill(7),
      before: new Uint32Array(16).fill(BLUE),
      after: new Uint32Array(16),
    };
    const emptied = { x: 5, y: 6, w: 0, h: 0 };
    history.execute(new PixelCommand(
      'Clear paint layer', 'art', 'gui/a', cells,
      { rect: { x: 5, y: 6, w: 16, h: 4 }, fields: { x: 5, y: 6, width: 16, height: 4 } },
      { rect: emptied, fields: geometryFields(emptied) },
      store,
    ));
    expect(layerOf(history)).toMatchObject({ x: 5, y: 6, width: 0, height: 0 });
    expect(bitmaps.get('gui/a')!.width).toBe(0);
    history.undo();
    expect(layerOf(history)).toMatchObject({ width: 16, height: 4 });
    expect(bitmaps.get('gui/a')!.get(7, 1)).toBe(BLUE);
  });
});

describe('working canvases and cells', () => {
  it('a working canvas holds the part of a bitmap inside its window, at the same GUI positions', () => {
    const bitmap = new ArgbCanvas(4, 4);
    bitmap.set(0, 0, RED);
    bitmap.set(3, 3, BLUE);
    const working = workingCanvas(bitmap, { x: 10, y: 20 }, { x: 11, y: 19, w: 20, h: 20 });
    expect(working.get(10, 20)).toBe(0); // outside the window
    expect(working.get(13, 23)).toBe(BLUE);
    expect([working.originX, working.originY, working.width]).toEqual([11, 19, 20]);
  });

  it('a stroke on a window that starts away from the origin works in GUI pixels', () => {
    const working = new ArgbCanvas(32, 32, -10, -5);
    const stroke = new Stroke(working, 'gui/a', RED, brush(1), { x: -8, y: -3 });
    stroke.to({ x: 4, y: -3 });
    expect(working.get(-8, -3)).toBe(RED);
    expect(working.get(4, -3)).toBe(RED);
    expect(stroke.last).toEqual({ x: 4, y: -3 });
    expect(stroke.inkBox()).toEqual({ x: -8, y: -3, w: 13, h: 1 });
    const cells = cellsOfPatch(stroke.end()!, working);
    expect([...cells.x].sort((a, b) => a - b)).toEqual(Array.from({ length: 13 }, (_, k) => -8 + k));
    expect(new Set(cells.y)).toEqual(new Set([-3]));
    expect(paintedBox(cells)).toEqual({ x: -8, y: -3, w: 13, h: 1 });
  });

  it('erasing paints no pixel, so it has no painted box and the ink box ignores it', () => {
    const working = new ArgbCanvas(16, 16);
    const stroke = new Stroke(working, 'gui/a', ERASER, brush(3), { x: 4, y: 4 });
    expect(stroke.end()).toBeNull();
    expect(stroke.inkBox()).toBeNull();
    expect(paintedBox({ x: new Int32Array([1]), y: new Int32Array([1]), before: new Uint32Array([RED]), after: new Uint32Array([0]) })).toBeNull();
  });

  it('fills on a window in GUI pixels', () => {
    const window = new ArgbCanvas(8, 8, 100, 100);
    const patch = fillPatch(window, 'gui/a', 103, 103, RED)!;
    expect(patch.indices).toHaveLength(64);
    expect(window.get(100, 100)).toBe(RED);
    expect(fillPatch(window, 'gui/a', 99, 100, BLUE)).toBeNull(); // outside the window
  });
});

describe('helpers', () => {
  it('keeps the recent colours newest first, without duplicates, bounded, and never remembers transparent', () => {
    let recent: number[] = [];
    for (const color of [1, 2, 3, 2]) recent = pushRecent(recent, 0xff000000 + color);
    expect(recent).toEqual([0xff000002, 0xff000003, 0xff000001]);
    expect(pushRecent(recent, 0)).toEqual(recent);
    expect(pushRecent(Array.from({ length: 12 }, (_, n) => 0xff000000 + n), 0xff0000ff)).toHaveLength(12);
  });
});
