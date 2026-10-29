/**
 * The canvas rules that need no DOM: where layers are, what the mouse hits,
 * what order the player sees them in (and the badges that say when that
 * differs from the list), snapping, resize math and the camera.
 */
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { sampleGuiRaw } from '../src/lib/demo/gui';
import { registry } from './helpers/gui-registry';
import { parseGui, type GuiDocument, type GuiLayer } from '../src/lib/gui/model';
import { smallcaps } from '../src/lib/gui/font/render';
import { ArgbCanvas } from '../src/lib/gui/raster/canvas';
import {
  compiledOrder,
  hitTest,
  layerBounds,
  layersInRect,
  orderBadges,
  overlays,
  sceneOf,
} from '../src/lib/gui/scene';
import { collectTargets, snapMove, snapResize, snapThreshold } from '../src/lib/gui/snap';
import { normalizeRect, resizeRect, scaleWithin, unionOf } from '../src/lib/gui/transform';
import {
  MAX_ZOOM,
  MIN_ZOOM,
  centered,
  fitView,
  panBy,
  pinchZoom,
  stepZoom,
  toGui,
  toScreen,
  zoomLabel,
  zoomTo,
  type View,
} from '../src/lib/gui/viewport';

const sample = (): GuiDocument => parseGui(parse(sampleGuiRaw()), 'main')!.doc;
const art = new ArgbCanvas(176, 168);
art.set(60, 100, 0xffff0000);
const scene = (doc: GuiDocument) =>
  sceneOf(doc, registry, (reference) => (reference === 'gui/shop/main/art' ? art : reference === 'gui/shop/coin' ? new ArgbCanvas(16, 16) : null));
const layer = (doc: GuiDocument, id: string): GuiLayer => doc.layers.find((entry) => entry.id === id)!;

describe('layer bounds', () => {
  const doc = sample();
  const s = scene(doc);

  it('reads x/y/width/height, image natural sizes, slot cells, wells and the paint canvas', () => {
    expect(layerBounds(layer(doc, 'buy'), s)).toEqual({ x: 118, y: 50, w: 48, h: 18 });
    expect(layerBounds(layer(doc, 'sword'), s)).toEqual({ x: 8 + 4 * 18, y: 18 + 18, w: 16, h: 16 });
    expect(layerBounds(layer(doc, 'wells'), s)).toEqual({ x: 7, y: 17, w: 162, h: 143 });
    expect(layerBounds(layer(doc, 'art'), s)).toEqual({ x: 0, y: 0, w: 176, h: 168 });
    expect(layerBounds({ id: 'i', type: 'image', image: 'gui/shop/coin', x: 3, y: 4 }, s)).toEqual({ x: 3, y: 4, w: 16, h: 16 });
    expect(layerBounds({ id: 'i', type: 'item', slot: 999, item: 'x' }, s)).toBeNull();
    expect(layerBounds({ id: 'i', type: 'panel' }, s)).toBeNull();
  });

  it('measures a text layer by its font: the smallcaps ink extent and the height of its art', () => {
    // the ink extent drops the trailing spacing pixel; the height is the font's glyph rows (cap band + descender row)
    expect(layerBounds(layer(doc, 'heading'), s)).toEqual({ x: 8, y: 6, w: smallcaps.measure('Shop') - 1, h: smallcaps.artRows });
  });
});

describe('compiled order and badges (gui.md section 4)', () => {
  it('renders raster under text under slot items, each in list order', () => {
    const doc = sample();
    expect(compiledOrder(doc, scene(doc)).map((entry) => entry.id)).toEqual(
      ['frame', 'wells', 'art', 'tag', 'buy', 'coin', 'heading', 'sword', 'apple']);
  });

  it('skips hidden layers', () => {
    const doc = sample();
    const hidden = { ...doc, layers: doc.layers.map((entry) => (entry.id === 'buy' ? { ...entry, visible: false } : entry)) };
    expect(compiledOrder(hidden, scene(hidden)).map((entry) => entry.id)).not.toContain('buy');
  });

  it('badges exactly the layers whose list position cannot be honored', () => {
    const doc = sample();
    const badges = orderBadges(doc, scene(doc));
    // heading (text) is listed under tag/buy/coin (raster) but renders over them
    expect([...badges.keys()].sort()).toEqual(['buy', 'coin', 'heading', 'tag']);
    expect(badges.get('heading')).toMatchObject({ direction: 'over' });
    expect(badges.get('heading')!.with.sort()).toEqual(['buy', 'coin', 'tag']);
    expect(badges.get('tag')).toMatchObject({ direction: 'under', with: ['heading'], listed: 5, position: 4 });
    expect(badges.get('tag')!.label).toContain('under heading');
    // frame/wells/art/items are where the list puts them
    expect(badges.has('frame')).toBe(false);
    expect(badges.has('sword')).toBe(false);
  });

  it('badges nothing when the list already matches the compiled order', () => {
    const doc = sample();
    const ordered = { ...doc, layers: compiledOrder(doc, scene(doc)) };
    expect(orderBadges(ordered, scene(ordered)).size).toBe(0);
  });

  it('lists the text and item layers drawn over the art, in compiled order, with rectangles', () => {
    const doc = sample();
    const marked = overlays(doc, scene(doc));
    expect(marked.map((entry) => [entry.layer.id, entry.target])).toEqual(
      [['heading', 'text'], ['sword', 'slot'], ['apple', 'slot']]);
    expect(marked[1]!.rect).toMatchObject({ w: 16, h: 16 });
  });
});

describe('hit testing', () => {
  const doc = sample();
  const s = scene(doc);
  const hit = (x: number, y: number, d = doc) => hitTest(d, s, { x, y }, (l, px, py) => (art.get(px, py) >>> 24) > 0 && l.type === 'paint')?.id ?? null;

  it('picks the topmost layer in compiled order', () => {
    expect(hit(140, 65)).toBe('buy'); // button over the price tag
    expect(hit(140, 55)).toBe('coin'); // listed after the button, so above it
    expect(hit(120, 25)).toBe('tag');
    expect(hit(130, 30)).toBe('coin');
    expect(hit(12, 8)).toBe('heading'); // text renders over the raster
    expect(hit(88, 45)).toBe('sword');
    expect(hit(2, 2)).toBe('frame');
  });

  it('hits slot wells but not the gaps, and paint only on painted pixels', () => {
    expect(hit(30, 120)).toBe('wells'); // inside an inventory well
    expect(hit(30, 80)).toBe('frame'); // the gap between the container and the inventory falls through
    expect(hit(60, 100)).toBe('art'); // painted pixel, above the wells
    expect(hit(61, 100)).toBe('wells'); // transparent paint pixels do not catch clicks
  });

  it('never hits a locked layer: the click falls through', () => {
    const locked = { ...doc, layers: doc.layers.map((entry) => (entry.id === 'buy' ? { ...entry, locked: true } : entry)) };
    expect(hit(140, 65, locked)).toBe('tag');
  });

  it('a marquee selects what it touches, minus locked layers and paint', () => {
    const touched = layersInRect(doc, s, { x: 110, y: 15, w: 60, h: 60 }).map((entry) => entry.id);
    expect(touched).toEqual(expect.arrayContaining(['tag', 'buy', 'coin']));
    expect(touched).not.toContain('art');
    expect(touched).not.toContain('frame'); // the backdrop is what you marquee over
    expect(touched).toContain('wells'); // wells count when the marquee fully covers one
  });
});

describe('snapping', () => {
  const siblings = [{ x: 100, y: 40, w: 30, h: 20 }];
  // the slot grid is dense on purpose, so the edge/sibling cases use targets without it
  const targets = collectTargets({ width: 176, height: 222, rows: 6, slots: false, siblings });
  const withSlots = collectTargets({ width: 176, height: 222, rows: 6, slots: true, siblings: [] });

  it('pulls a moving rectangle onto the canvas edge, the middle or a sibling edge', () => {
    expect(snapMove({ x: 2, y: 100, w: 20, h: 10 }, targets, 3).dx).toBe(-2); // canvas left edge
    expect(snapMove({ x: 77, y: 100, w: 20, h: 10 }, targets, 3).dx).toBe(1); // centered: 176/2 - 10 = 78
    const sibling = snapMove({ x: 131, y: 150, w: 10, h: 10 }, targets, 2); // a sibling's right edge (130)
    expect(sibling.dx).toBe(-1);
    expect(sibling.guides.some((guide) => guide.axis === 'x' && guide.pos === 130 && guide.kind === 'sibling')).toBe(true);
    expect(snapMove({ x: 20, y: 62, w: 10, h: 10 }, targets, 3).dy).toBe(-2); // a sibling's bottom edge (60)
  });

  it('snaps to slot origins, item edges and wells', () => {
    const slot = snapMove({ x: 27, y: 100, w: 4, h: 4 }, withSlots, 2);
    expect(slot.dx).toBe(-1); // item origin x = 8 + 18
    expect(slot.guides.some((guide) => guide.kind === 'slot' && guide.pos === 26)).toBe(true);
    // vanilla chest: the first container row starts at y = 18
    expect(snapMove({ x: 60, y: 19, w: 4, h: 4 }, withSlots, 1).dy).toBe(-1);
  });

  it('does nothing outside the threshold, and the guides span the aligned layers', () => {
    const none = snapMove({ x: 60, y: 90, w: 7, h: 7 }, targets, 1);
    expect([none.dx, none.dy]).toEqual([0, 0]);
    const aligned = snapMove({ x: 102, y: 70, w: 30, h: 10 }, targets, 2);
    const guide = aligned.guides.find((entry) => entry.axis === 'x' && entry.pos === 100)!;
    expect(guide.from).toBeLessThanOrEqual(40);
    expect(guide.to).toBeGreaterThanOrEqual(80);
  });

  it('only ever lands on whole pixels (a half-pixel center is not a snap)', () => {
    const result = snapMove({ x: 81, y: 150, w: 15, h: 10 }, targets, 3); // center 88.5 would be a half pixel
    expect(Number.isInteger(result.dx)).toBe(true);
    expect(result.dx).toBe(0);
  });

  it('resize snaps only the edges being dragged and leaves the opposite edge alone', () => {
    const east = snapResize({ x: 20, y: 100, w: 108, h: 10 }, 'e', targets, 3);
    expect(east.rect).toMatchObject({ x: 20, w: 110 }); // right edge 128 -> the sibling's 130
    const west = snapResize({ x: 2, y: 100, w: 50, h: 10 }, 'w', targets, 2);
    expect([west.rect.x, west.rect.x + west.rect.w]).toEqual([0, 52]); // left edge to the canvas, right edge kept
    const corner = snapResize({ x: 3, y: 3, w: 40, h: 40 }, 'nw', targets, 4);
    expect([corner.rect.x, corner.rect.y, corner.rect.x + corner.rect.w]).toEqual([0, 0, 43]);
  });

  it('keeps the pull constant on screen: tighter in GUI pixels as you zoom in', () => {
    expect(snapThreshold(1)).toBe(6);
    expect(snapThreshold(4)).toBe(1.5);
    expect(snapThreshold(16)).toBe(0.5);
  });
});

describe('resize math', () => {
  const start = { x: 10, y: 20, w: 40, h: 20 };

  it('keeps the opposite edge fixed for every handle', () => {
    expect(resizeRect(start, 'e', 5, 0)).toEqual({ x: 10, y: 20, w: 45, h: 20 });
    expect(resizeRect(start, 'w', -5, 0)).toEqual({ x: 5, y: 20, w: 45, h: 20 });
    expect(resizeRect(start, 's', 0, 4)).toEqual({ x: 10, y: 20, w: 40, h: 24 });
    expect(resizeRect(start, 'n', 0, -4)).toEqual({ x: 10, y: 16, w: 40, h: 24 });
    expect(resizeRect(start, 'se', 3, 3)).toEqual({ x: 10, y: 20, w: 43, h: 23 });
    expect(resizeRect(start, 'nw', -3, -3)).toEqual({ x: 7, y: 17, w: 43, h: 23 });
    expect(resizeRect(start, 'ne', 3, -3)).toEqual({ x: 10, y: 17, w: 43, h: 23 });
    expect(resizeRect(start, 'sw', -3, 3)).toEqual({ x: 7, y: 20, w: 43, h: 23 });
  });

  it('never goes below the minimum, even when dragged through the other side', () => {
    expect(resizeRect(start, 'e', -100, 0)).toEqual({ x: 10, y: 20, w: 1, h: 20 });
    expect(resizeRect(start, 'w', 100, 0)).toEqual({ x: 49, y: 20, w: 1, h: 20 });
    expect(resizeRect(start, 'n', 0, 100, { min: 4 }).h).toBe(4);
  });

  it('keeps the aspect ratio with shift and resizes around the center with alt', () => {
    expect(resizeRect(start, 'e', 20, 0, { keepAspect: true })).toEqual({ x: 10, y: 15, w: 60, h: 30 }); // grows around its middle
    expect(resizeRect(start, 'se', 20, 0, { keepAspect: true })).toMatchObject({ w: 60, h: 30 });
    expect(resizeRect(start, 'e', 10, 0, { fromCenter: true })).toEqual({ x: 0, y: 20, w: 60, h: 20 });
  });

  it('scales group members within the group box, and normalizes marquee corners', () => {
    const group = { x: 0, y: 0, w: 100, h: 50 };
    expect(scaleWithin({ x: 10, y: 10, w: 20, h: 10 }, group, { x: 0, y: 0, w: 200, h: 100 })).toEqual({ x: 20, y: 20, w: 40, h: 20 });
    expect(unionOf([{ x: 5, y: 5, w: 10, h: 10 }, { x: 20, y: 0, w: 5, h: 5 }])).toEqual({ x: 5, y: 0, w: 20, h: 15 });
    expect(unionOf([])).toBeNull();
    expect(normalizeRect({ x: 9, y: 9 }, { x: 3, y: 4 })).toEqual({ x: 3, y: 4, w: 6, h: 5 });
  });
});

describe('the camera', () => {
  const view: View = { zoom: 3, panX: 40, panY: 20 };

  it('converts between screen and GUI pixels', () => {
    expect(toGui(view, { x: 40 + 30, y: 20 + 15 })).toEqual({ x: 10, y: 5 });
    expect(toScreen(view, { x: 10, y: 5 })).toEqual({ x: 70, y: 35 });
  });

  it('zooms around the cursor: the GUI point under it does not move', () => {
    const anchor = { x: 200, y: 150 };
    const before = toGui(view, anchor);
    for (const zoom of [1, 2, 7, 16]) {
      const next = zoomTo(view, zoom, anchor);
      const after = toGui(next, anchor);
      expect(after.x).toBeCloseTo(before.x, 6);
      expect(after.y).toBeCloseTo(before.y, 6);
    }
  });

  it('is clamped to 1x..16x and steps along whole numbers', () => {
    expect(zoomTo(view, 99, { x: 0, y: 0 }).zoom).toBe(MAX_ZOOM);
    expect(zoomTo(view, 0.2, { x: 0, y: 0 }).zoom).toBe(MIN_ZOOM);
    let current = { ...view, zoom: 1 };
    const seen: number[] = [];
    for (let i = 0; i < 12; i++) {
      current = stepZoom(current, 1, { x: 0, y: 0 });
      seen.push(current.zoom);
    }
    expect(seen).toEqual([2, 3, 4, 5, 6, 8, 10, 12, 16, 16, 16, 16]);
    expect(stepZoom({ ...view, zoom: 3.4 }, -1, { x: 0, y: 0 }).zoom).toBe(3);
    expect(stepZoom({ ...view, zoom: 1 }, -1, { x: 0, y: 0 }).zoom).toBe(1);
    expect(pinchZoom(view, -100, { x: 0, y: 0 }).zoom).toBeGreaterThan(3);
    expect(zoomLabel(2)).toBe('200%');
  });

  it('fits at the biggest whole zoom that leaves a margin, centered', () => {
    const fit = fitView({ w: 700, h: 720 }, { w: 176, h: 222 });
    expect(fit.zoom).toBe(3);
    expect(fit.panX).toBe(Math.round((700 - 176 * 3) / 2));
    expect(fitView({ w: 50, h: 50 }, { w: 176, h: 222 }).zoom).toBe(1);
    expect(centered({ w: 100, h: 100 }, { w: 10, h: 10 }, 1)).toEqual({ zoom: 1, panX: 45, panY: 45 });
  });

  it('cannot pan the canvas out of reach', () => {
    const gone = panBy(view, -99999, 99999, { w: 700, h: 700 }, { w: 176, h: 222 });
    expect(gone.panX).toBeLessThan(0);
    expect(gone.panX + 176 * 3).toBeGreaterThanOrEqual(64);
    expect(gone.panY).toBeLessThanOrEqual(700 - 64);
  });
});
