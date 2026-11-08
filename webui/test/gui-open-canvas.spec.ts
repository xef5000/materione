/**
 * The open canvas (gui.md section 11): regions, the art cap, the player-window guides, the vanilla underlay
 * composed from the owner's sheet, the veil that lifts per painted pixel, paint layers that own their box
 * (grow, trim, empty), and the canvas rules that must work outside the screen. Pure; the canvas wiring is in
 * the browser spec.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { canvasHeight } from '../src/lib/gui/chest';
import type { GuiDocument, GuiLayer } from '../src/lib/gui/model';
import {
  ART_CAP,
  clickReach,
  clipToCap,
  decorationNote,
  DECORATION_NO_SLOT,
  DECORATION_ONLY,
  coversContainerSlot,
  DECORATION_PARTLY,
  insideCap,
  intersectRects,
  inventoryTop,
  isClickable,
  regionAt,
  screenRect,
  unionRects,
  windowGuide,
  windowGuides,
} from '../src/lib/gui/open-canvas';
import { geometryFields, inkBounds, paintRect, reshape, trimPaintLayers } from '../src/lib/gui/paint-geometry';
import { ArgbCanvas } from '../src/lib/gui/raster/canvas';
import { blendOver } from '../src/lib/gui/raster/canvas';
import { flattenRaster } from '../src/lib/gui/raster/flatten';
import { coversScreen, hitTest, layerBounds, layersInRect, sceneOf } from '../src/lib/gui/scene';
import { collectTargets, snapMove } from '../src/lib/gui/snap';
import { composeVanilla, INVENTORY_HEIGHT, INVENTORY_V, VEIL, veiledScreen } from '../src/lib/gui/vanilla';
import { centered, panBy } from '../src/lib/gui/viewport';
import { decodePng } from './helpers/png';
import { registry } from './helpers/gui-registry';

const SHEET = decodePng(new Uint8Array(readFileSync(fileURLToPath(new URL('../static/vanilla/generic_54.png', import.meta.url)))));
const RED = 0xffff0000;

function documentOf(layers: GuiLayer[], rows = 6): GuiDocument {
  return { title: '', container: `chest_${rows}`, rows, layers, rest: {}, order: ['title', 'container', 'layers'] };
}

describe('the art cap and the regions (gui.md section 11.2)', () => {
  it('is x -424..599, y -243..780: 1024 x 1024, asymmetric at the top', () => {
    expect(ART_CAP).toEqual({ x: -424, y: -243, w: 1024, h: 1024 });
    expect(insideCap(-424, -243)).toBe(true);
    expect(insideCap(599, 780)).toBe(true);
    expect(insideCap(600, 0)).toBe(false);
    expect(insideCap(0, -244)).toBe(false);
    expect(insideCap(-425, 0)).toBe(false);
    expect(insideCap(0, 781)).toBe(false);
  });

  it('cuts a rectangle to the cap, or says nothing is inside', () => {
    expect(clipToCap({ x: -500, y: -300, w: 100, h: 100 })).toEqual({ x: -424, y: -243, w: 24, h: 43 });
    expect(clipToCap({ x: 0, y: 0, w: 10, h: 10 })).toEqual({ x: 0, y: 0, w: 10, h: 10 });
    expect(clipToCap({ x: 700, y: 0, w: 10, h: 10 })).toBeNull();
  });

  it('knows the container, the player inventory (y >= rows * 18 + 17) and the open space', () => {
    expect(screenRect(6)).toEqual({ x: 0, y: 0, w: 176, h: 222 });
    expect(inventoryTop(6)).toBe(125);
    expect(inventoryTop(1)).toBe(35);
    expect(regionAt(6, 10, 124)).toBe('container');
    expect(regionAt(6, 10, 125)).toBe('player');
    expect(regionAt(6, 175, 221)).toBe('player');
    expect(regionAt(6, 176, 10)).toBe('outside');
    expect(regionAt(6, -1, 10)).toBe('outside');
    expect(regionAt(6, 10, 222)).toBe('outside');
    expect(regionAt(1, 10, 34)).toBe('container');
    expect(regionAt(1, 10, 35)).toBe('player');
  });

  it('joins and cuts rectangles; an empty one adds nothing', () => {
    expect(unionRects({ x: 0, y: 0, w: 2, h: 2 }, { x: 5, y: 5, w: 1, h: 1 })).toEqual({ x: 0, y: 0, w: 6, h: 6 });
    expect(unionRects({ x: 9, y: 9, w: 0, h: 0 }, { x: 5, y: 5, w: 1, h: 1 })).toEqual({ x: 5, y: 5, w: 1, h: 1 });
    expect(unionRects({ x: 9, y: 9, w: 0, h: 0 }, { x: 1, y: 1, w: 0, h: 0 })).toEqual({ x: 9, y: 9, w: 0, h: 0 });
    expect(intersectRects({ x: 0, y: 0, w: 4, h: 4 }, { x: 4, y: 0, w: 4, h: 4 })).toBeNull();
  });
});

describe('the player-window guides (gui.md section 9.12 and 11.5)', () => {
  it('reproduce the measured margins of a 6-row screen at 1920 x 1080', () => {
    const [two, three, four] = windowGuides(6);
    expect(two!.margin).toEqual({ top: 159, bottom: 159, left: 392, right: 392 });
    expect(three!.margin).toEqual({ top: 69, bottom: 69, left: 232, right: 232 });
    expect(four!.margin).toEqual({ top: 24, bottom: 24, left: 152, right: 152 });
    expect(two!.rect).toEqual({ x: -392, y: -159, w: 960, h: 540 });
    expect(three!.rect).toEqual({ x: -232, y: -69, w: 640, h: 360 });
    expect(four!.rect).toEqual({ x: -152, y: -24, w: 480, h: 270 });
  });

  it('give a 1-row screen 45 more px above and below', () => {
    expect(windowGuide(1, 2).margin.top).toBe(159 + 45);
    expect(windowGuide(1, 4).margin.top).toBe(24 + 45);
    expect(windowGuide(1, 2).margin.left).toBe(392);
  });

  it('can describe the small windows too', () => {
    expect(windowGuide(6, 3, { w: 1280, h: 720 }).margin).toMatchObject({ top: 9, left: 125 });
    expect(windowGuide(6, 2, { w: 854, h: 480 }).margin).toMatchObject({ top: 9, left: 125 });
  });
});

describe('clickable layers outside the container are decoration only (gui.md section 11.4)', () => {
  it('a button or a layer with a click rule is clickable', () => {
    expect(isClickable('button', 0)).toBe(true);
    expect(isClickable('image', 1)).toBe(true);
    expect(isClickable('image', 0)).toBe(false);
    expect(isClickable('panel', 0)).toBe(false);
  });

  it('is decided by the geometry of the screen alone', () => {
    expect(clickReach({ x: 10, y: 10, w: 20, h: 20 }, 3)).toBe('inside');
    expect(clickReach({ x: 0, y: 0, w: 176, h: 168 }, 3)).toBe('inside');
    expect(clickReach({ x: 200, y: 10, w: 20, h: 20 }, 3)).toBe('outside');
    expect(clickReach({ x: -5, y: 10, w: 20, h: 20 }, 3)).toBe('partial');
    expect(clickReach({ x: 10, y: 160, w: 20, h: 20 }, 3)).toBe('partial'); // 3 rows is 168 tall
  });

  it('the note names click_outside, only for clickable layers placed outside', () => {
    expect(DECORATION_ONLY).toContain('clicks outside the chest arrive as click_outside');
    expect(decorationNote(true, { x: 200, y: 20, w: 10, h: 10 }, 3)).toBe(DECORATION_ONLY);
    expect(decorationNote(true, { x: 160, y: 20, w: 40, h: 10 }, 3)).toBe(DECORATION_PARTLY); // covers the last column of slots, then leaves the screen
    expect(decorationNote(true, { x: 10, y: 20, w: 10, h: 10 }, 3)).toBeNull(); // over a slot
    expect(decorationNote(false, { x: 200, y: 20, w: 10, h: 10 }, 3)).toBeNull();
    expect(decorationNote(true, null, 3)).toBeNull();
  });

  it('is the compiler rule (MTR-0092): a clickable layer whose rectangle covers no container slot', () => {
    // a 3-row chest has slots at x = 8 + 18 c, y = 18 + 18 r, 16 x 16; the gap between two slots and the player inventory cover none
    expect(coversContainerSlot({ x: 8, y: 18, w: 1, h: 1 }, 3)).toBe(true);
    expect(coversContainerSlot({ x: 24, y: 18, w: 2, h: 16 }, 3)).toBe(false); // the two pixels between slot 0 and slot 1
    expect(coversContainerSlot({ x: 8, y: 100, w: 100, h: 20 }, 3)).toBe(false); // the player's inventory
    expect(decorationNote(true, { x: 24, y: 18, w: 2, h: 16 }, 3)).toBe(DECORATION_NO_SLOT);
    expect(decorationNote(true, { x: 8, y: 100, w: 100, h: 20 }, 3)).toBe(DECORATION_NO_SLOT);
    expect(decorationNote(true, { x: 8, y: 18, w: 160, h: 52 }, 3)).toBeNull(); // the whole container
  });
});

describe('the vanilla underlay (gui.md section 11.2)', () => {
  it('is the owner\'s sheet: 256 x 256 with the six-row chest at the top-left', () => {
    expect([SHEET.width, SHEET.height]).toEqual([256, 256]);
    expect(SHEET.get(0, 0) >>> 24).toBe(0); // the rounded corner is cut
    expect(SHEET.get(88, 100) >>> 24).toBe(255);
  });

  it.each([1, 2, 3, 4, 5, 6])('composes %i rows as the client blits them: top rows*18+17, then 96 rows from v = 126', (rows) => {
    const screen = composeVanilla(SHEET, rows);
    expect([screen.width, screen.height]).toEqual([176, canvasHeight(rows)]);
    const top = rows * 18 + 17;
    for (let y = 0; y < top; y++) for (let x = 0; x < 176; x++) expect(screen.get(x, y), `top ${x},${y}`).toBe(SHEET.get(x, y));
    for (let y = 0; y < INVENTORY_HEIGHT; y++) {
      for (let x = 0; x < 176; x++) expect(screen.get(x, top + y), `inventory ${x},${y}`).toBe(SHEET.get(x, INVENTORY_V + y));
    }
    // the two blits cover rows*18+113 rows; the last row of the screen stays empty, as on the client
    for (let x = 0; x < 176; x++) expect(screen.get(x, screen.height - 1) >>> 24).toBe(0);
  });

  it('a 6-row screen is the sheet, but for the row between the two blits and the last row', () => {
    const screen = composeVanilla(SHEET, 6);
    let different = 0;
    for (let y = 0; y < 222; y++) for (let x = 0; x < 176; x++) if (screen.get(x, y) !== SHEET.get(x, y)) different++;
    // the sheet's row 125 is dropped and everything below it moves up a row: the rest of the screen differs only where the rows differ
    expect(screen.get(88, 10)).toBe(SHEET.get(88, 10));
    expect(different).toBeGreaterThan(0);
  });

  it('a 1-row screen has the container part of the sheet then its inventory part, 132 px tall', () => {
    const screen = composeVanilla(SHEET, 1);
    expect(screen.height).toBe(132);
    expect(screen.get(8, 34)).toBe(SHEET.get(8, 34));
    expect(screen.get(8, 35)).toBe(SHEET.get(8, 126));
  });
});

describe('the veil lifts per painted pixel', () => {
  const rows = 3;
  const vanilla = composeVanilla(SHEET, rows);
  const top = inventoryTop(rows);

  it('veils exactly the opaque vanilla pixels of the inventory part and nothing above it', () => {
    const out = veiledScreen(vanilla, rows, () => false);
    for (let y = 0; y < vanilla.height; y++) {
      for (let x = 0; x < 176; x++) {
        const v = vanilla.get(x, y);
        const expected = y >= top && v >>> 24 !== 0 ? blendOver(v, VEIL) >>> 0 : v;
        expect(out.get(x, y), `${x},${y}`).toBe(expected);
      }
    }
    expect(out.get(88, top + 20)).not.toBe(vanilla.get(88, top + 20));
  });

  it('lifts it under a painted pixel and only there; the vanilla pixels around it stay veiled', () => {
    const painted = new Set(['20,' + (top + 10), '21,' + (top + 10), '20,' + (top + 11)]);
    const out = veiledScreen(vanilla, rows, (x, y) => painted.has(`${x},${y}`));
    for (const key of painted) {
      const [x, y] = key.split(',').map(Number) as [number, number];
      expect(out.get(x, y), key).toBe(vanilla.get(x, y)); // full strength: the vanilla colour itself
    }
    expect(out.get(22, top + 10)).toBe(blendOver(vanilla.get(22, top + 10), VEIL) >>> 0);
    expect(out.get(21, top + 11)).toBe(blendOver(vanilla.get(21, top + 11), VEIL) >>> 0);
  });

  it('paint above the inventory part changes nothing: the container is never veiled', () => {
    const out = veiledScreen(vanilla, rows, () => true);
    expect(out.equals(vanilla)).toBe(true);
  });

  it('does not touch the vanilla canvas it was given', () => {
    const before = vanilla.clone();
    veiledScreen(vanilla, rows, () => false);
    expect(vanilla.equals(before)).toBe(true);
  });
});

describe('a canvas with an origin', () => {
  it('speaks the coordinates of the screen it sits on', () => {
    const canvas = new ArgbCanvas(4, 4, -2, -3);
    canvas.set(-2, -3, RED);
    canvas.blend(1, 0, RED);
    expect(canvas.pixels[0]).toBe(RED);
    expect(canvas.pixels[3 * 4 + 3]).toBe(RED);
    expect(canvas.get(-2, -3)).toBe(RED);
    expect(canvas.inside(2, 0)).toBe(false);
    expect(canvas.inside(-3, 0)).toBe(false);
    canvas.fillRect(-10, -10, 100, 100, 0xff00ff00);
    expect(canvas.pixels.every((value) => value !== 0)).toBe(true);
    expect(canvas.clone().originY).toBe(-3);
  });

  it('flattens the raster over the cap: a panel off the screen lands where it says', () => {
    const layers = [
      { id: 'out', type: 'panel', x: -300, y: -100, width: 20, height: 10 },
      { id: 'under', type: 'panel', x: 590, y: 770, width: 50, height: 50 },
    ];
    const flat = flattenRaster(layers, { width: 1024, height: 1024, originX: -424, originY: -243, rows: 3, image: () => null });
    expect(flat.get(-300, -100) >>> 24).toBe(0); // the panel's cut corner
    expect(flat.get(-299, -100) >>> 24).toBe(255);
    expect(flat.get(-281, -91) >>> 24).toBe(0);
    expect(flat.get(0, 0) >>> 24).toBe(0);
    expect(flat.get(599, 780) >>> 24).toBe(255); // clipped at the cap: only a 10 x 10 corner of the panel is inside
    expect(flat.get(600, 780)).toBe(0);
  });

  it('draws a paint layer at its x, y with its bitmap\'s own size', () => {
    const bitmap = new ArgbCanvas(3, 2);
    bitmap.set(0, 0, RED);
    bitmap.set(2, 1, RED);
    const layers = [{ id: 'art', type: 'paint', image: 'gui/a', x: 50, y: -20, width: 3, height: 2 }];
    const flat = flattenRaster(layers, { width: 1024, height: 1024, originX: -424, originY: -243, rows: 3, image: () => bitmap });
    expect(flat.get(50, -20)).toBe(RED);
    expect(flat.get(52, -19)).toBe(RED);
    expect(flat.get(51, -20)).toBe(0);
  });
});

describe('paint layers own their geometry', () => {
  const layer = (extra: Record<string, unknown> = {}): GuiLayer => ({ id: 'art', type: 'paint', image: 'gui/a', ...extra });

  it('sit at x, y with the bitmap\'s size; the document\'s size only stands in until it has loaded', () => {
    const bitmap = new ArgbCanvas(5, 4);
    expect(paintRect(layer({ x: 3, y: -4, width: 99, height: 99 }), bitmap)).toEqual({ x: 3, y: -4, w: 5, h: 4 });
    expect(paintRect(layer({ x: 3, y: -4, width: 7, height: 6 }), null)).toEqual({ x: 3, y: -4, w: 7, h: 6 });
    expect(paintRect(layer(), bitmap)).toEqual({ x: 0, y: 0, w: 5, h: 4 }); // a canvas-sized layer from before the open canvas
    expect(paintRect(layer(), null)).toEqual({ x: 0, y: 0, w: 0, h: 0 });
    expect(geometryFields({ x: 1, y: 2, w: 3, h: 4 })).toEqual({ x: 1, y: 2, width: 3, height: 4 });
  });

  it('are bounded by the layer fields and the bitmap, and a layer with no pixel has no bounds', () => {
    const bitmap = new ArgbCanvas(5, 4);
    const doc = documentOf([layer({ x: 3, y: -4 })]);
    const scene = sceneOf(doc, registry, () => bitmap);
    expect(layerBounds(doc.layers[0]!, scene)).toEqual({ x: 3, y: -4, w: 5, h: 4 });
    expect(layerBounds(doc.layers[0]!, sceneOf(doc, registry, () => new ArgbCanvas(0, 0)))).toBeNull();
    expect(layerBounds(doc.layers[0]!, sceneOf(doc, registry, () => null))).toBeNull();
  });

  it('find the box of the painted pixels, and crop to it', () => {
    const bitmap = new ArgbCanvas(10, 10);
    expect(inkBounds(bitmap)).toBeNull();
    bitmap.set(2, 3, RED);
    bitmap.set(6, 8, 0x01000000); // alpha 1 is still painted
    expect(inkBounds(bitmap)).toEqual({ x: 2, y: 3, w: 5, h: 6 });
    const cropped = reshape(bitmap, { x: 0, y: 0, w: 10, h: 10 }, { x: 2, y: 3, w: 5, h: 6 });
    expect(cropped.get(0, 0)).toBe(RED);
    expect(cropped.get(4, 5)).toBe(0x01000000);
    // growing keeps the pixels where they were in GUI pixels
    const grown = reshape(cropped, { x: 2, y: 3, w: 5, h: 6 }, { x: 0, y: 0, w: 20, h: 20 });
    expect(grown.get(2, 3)).toBe(RED);
    expect(reshape(cropped, { x: 2, y: 3, w: 5, h: 6 }, { x: 0, y: 0, w: 0, h: 0 }).pixels).toHaveLength(0);
  });

  describe('trimmed on save', () => {
    const edited = () => false;

    it('a layer with a margin is cut to its painted pixels, with its x and y moved to match, and its PNG is written', () => {
      const bitmap = new ArgbCanvas(176, 168);
      bitmap.set(6, 3, RED);
      bitmap.set(167, 79, RED);
      const doc = documentOf([layer()]);
      const trim = trimPaintLayers(doc, () => bitmap, edited);
      expect(trim.doc.layers[0]).toMatchObject({ x: 6, y: 3, width: 162, height: 77 });
      expect(trim.layers[0]).toMatchObject({ rect: { x: 6, y: 3, w: 162, h: 77 }, needsPng: true });
      expect([trim.layers[0]!.canvas.width, trim.layers[0]!.canvas.height]).toEqual([162, 77]);
      expect(trim.layers[0]!.canvas.get(0, 0)).toBe(RED);
      expect(trim.layers[0]!.canvas.get(161, 76)).toBe(RED);
      expect(doc.layers[0]).toEqual(layer()); // the document it was given is untouched
    });

    it('a layer already trimmed is left alone: the same document, no PNG unless it was edited', () => {
      const bitmap = new ArgbCanvas(2, 2);
      bitmap.set(0, 0, RED);
      bitmap.set(1, 1, RED);
      const doc = documentOf([layer({ x: 5, y: 6, width: 2, height: 2 })]);
      const untouched = trimPaintLayers(doc, () => bitmap, edited);
      expect(untouched.doc).toBe(doc);
      expect(untouched.layers[0]!.needsPng).toBe(false);
      expect(trimPaintLayers(doc, () => bitmap, () => true).layers[0]!.needsPng).toBe(true);
    });

    it('a layer with no painted pixel saves no PNG and is written as width 0, height 0', () => {
      const doc = documentOf([layer({ x: 5, y: 6, width: 30, height: 30 })]);
      const trim = trimPaintLayers(doc, () => new ArgbCanvas(30, 30), () => true);
      expect(trim.doc.layers[0]).toMatchObject({ x: 5, y: 6, width: 0, height: 0 });
      expect(trim.layers[0]).toMatchObject({ needsPng: false });
      expect(trim.layers[0]!.canvas.width).toBe(0);
      // a layer that was never painted is the same
      expect(trimPaintLayers(documentOf([layer()]), () => new ArgbCanvas(0, 0), edited).doc.layers[0])
        .toMatchObject({ x: 0, y: 0, width: 0, height: 0 });
    });

    it('never empties a layer whose bitmap could not be read', () => {
      const doc = documentOf([layer({ x: 5, y: 6, width: 30, height: 30 })]);
      expect(trimPaintLayers(doc, () => null, () => true).doc).toBe(doc);
      expect(trimPaintLayers(doc, () => undefined, () => true).layers).toEqual([]);
    });

    it('trims every paint layer and leaves the other layers as they are', () => {
      const a = new ArgbCanvas(10, 10);
      a.set(4, 4, RED);
      const b = new ArgbCanvas(10, 10);
      b.set(0, 9, RED);
      const doc = documentOf([
        layer({ id: 'a', image: 'gui/a' }),
        { id: 'frame', type: 'panel', x: 0, y: 0, width: 5, height: 5 },
        layer({ id: 'b', image: 'gui/b', x: -50 }),
      ]);
      const trim = trimPaintLayers(doc, (reference) => (reference === 'gui/a' ? a : b), edited);
      expect(trim.doc.layers[0]).toMatchObject({ x: 4, y: 4, width: 1, height: 1 });
      expect(trim.doc.layers[1]).toBe(doc.layers[1]);
      expect(trim.doc.layers[2]).toMatchObject({ x: -50, y: 9, width: 1, height: 1 });
    });
  });
});

describe('the canvas rules outside the screen', () => {
  const frame: GuiLayer = { id: 'frame', type: 'panel', x: 0, y: 0, width: 176, height: 168 };
  const banner: GuiLayer = { id: 'banner', type: 'panel', x: 200, y: -30, width: 60, height: 20 };
  const art: GuiLayer = { id: 'art', type: 'paint', image: 'gui/a', x: -100, y: 300 };
  const bitmap = new ArgbCanvas(8, 8);
  bitmap.set(3, 3, RED);
  const doc = documentOf([frame, banner, art], 3);
  const scene = sceneOf(doc, registry, () => bitmap);
  const alpha = (_: GuiLayer, x: number, y: number) => (bitmap.get(x + 100, y - 300) >>> 24) > 0;

  it('hits a layer anywhere within the cap, and nothing in empty space', () => {
    expect(hitTest(doc, scene, { x: 210, y: -20 }, alpha)?.id).toBe('banner');
    expect(hitTest(doc, scene, { x: -97, y: 303 }, alpha)?.id).toBe('art');
    expect(hitTest(doc, scene, { x: -96, y: 303 }, alpha)).toBeNull();
    expect(hitTest(doc, scene, { x: 400, y: 400 }, alpha)).toBeNull();
  });

  it('a marquee in the open space selects what it touches, paint included when it covers a painted pixel', () => {
    const touches = (_: GuiLayer, area: { x: number; y: number; w: number; h: number }) =>
      area.x <= -97 && area.x + area.w >= -97 && area.y <= 303 && area.y + area.h >= 303;
    const ids = (area: { x: number; y: number; w: number; h: number }) => layersInRect(doc, scene, area, touches).map((layer) => layer.id);
    expect(ids({ x: 190, y: -40, w: 100, h: 40 })).toEqual(['banner']);
    expect(ids({ x: -110, y: 290, w: 30, h: 30 })).toEqual(['art']);
    expect(ids({ x: 300, y: 300, w: 50, h: 50 })).toEqual([]);
    expect(layersInRect(doc, scene, { x: -110, y: 290, w: 30, h: 30 })).toEqual([]); // no paint test given: paint is never selected
  });

  it('only a layer covering the whole screen is the backdrop you marquee over', () => {
    expect(coversScreen({ x: 0, y: 0, w: 176, h: 168 }, scene)).toBe(true);
    expect(coversScreen({ x: -20, y: -20, w: 300, h: 300 }, scene)).toBe(true);
    expect(coversScreen({ x: 0, y: 0, w: 176, h: 100 }, scene)).toBe(false);
    expect(layersInRect(doc, scene, { x: 0, y: 0, w: 100, h: 100 }).map((layer) => layer.id)).not.toContain('frame');
  });

  it('snaps to the art cap\'s edges as well as the screen', () => {
    const targets = collectTargets({ width: 176, height: 168, rows: 3, slots: false, siblings: [], cap: ART_CAP });
    expect(snapMove({ x: -422, y: 0, w: 10, h: 10 }, targets, 3).dx).toBe(-2);
    expect(snapMove({ x: 0, y: 770, w: 10, h: 13 }, targets, 3).dy).toBe(-2); // bottom edge 783 -> the cap's 781
    expect(snapMove({ x: 0, y: 0, w: 10, h: 10 }, collectTargets({ width: 176, height: 168, rows: 3, slots: false, siblings: [] }), 3).dx).toBe(0);
  });

  it('pans over the whole cap, not just the screen', () => {
    const viewport = { w: 800, h: 600 };
    const content = { w: ART_CAP.w, h: ART_CAP.h };
    const origin = { x: ART_CAP.x, y: ART_CAP.y };
    const view = centered(viewport, { w: 176, h: 222 }, 3);
    // far to the left: the cap's right edge may come to within 64 px of the stage's left edge, no further
    const far = panBy(view, -99999, 0, viewport, content, 64, origin);
    expect(far.panX + (ART_CAP.x + ART_CAP.w) * 3).toBeCloseTo(64);
    // the screen is far off the stage by then, which the old screen-sized clamp could not allow
    expect(far.panX).toBeLessThan(-1000);
    const back = panBy(view, 99999, 0, viewport, content, 64, origin);
    expect(back.panX + ART_CAP.x * 3).toBeCloseTo(800 - 64);
    // without an origin the old behaviour is unchanged
    expect(panBy(view, -99999, 0, viewport, { w: 176, h: 222 }).panX + 176 * 3).toBeGreaterThanOrEqual(64);
  });
});
