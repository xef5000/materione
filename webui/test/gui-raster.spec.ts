/**
 * The TS side of the shared raster contract (gui.md section 4): every vector in
 * fixtures/gui/raster/ must render to exactly the expected PNG. The Java
 * compiler (issue #184) is tested against the same files. The explicit pixel
 * tables below restate the README rules independently of the vectors, so a
 * regenerated vector set cannot silently redefine them.
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { decodePng } from './helpers/png';
import { ArgbCanvas, blendOver, scaleAlpha } from '../src/lib/gui/raster/canvas';
import { argb, formatColor, parseColor } from '../src/lib/gui/raster/color';
import { raisedPart, insetPart } from '../src/lib/gui/raster/bevel';
import { flattenRaster, RAISED_DEFAULT, SLOT_DEFAULT } from '../src/lib/gui/raster/flatten';
import { renderVector, type RasterVector } from '../src/lib/gui/raster/vector';
import { canvasSize, cellsOf, slotCell, wellRect } from '../src/lib/gui/chest';

const DIR = fileURLToPath(new URL('../../fixtures/gui/raster/', import.meta.url));
const names = readdirSync(DIR)
  .filter((file) => file.endsWith('.json'))
  .map((file) => file.slice(0, -'.json'.length))
  .sort();

function firstDifference(actual: ArgbCanvas, expected: ArgbCanvas): string {
  if (actual.width !== expected.width || actual.height !== expected.height) {
    return `size ${actual.width}x${actual.height} != ${expected.width}x${expected.height}`;
  }
  for (let index = 0; index < actual.pixels.length; index++) {
    if (actual.pixels[index] !== expected.pixels[index]) {
      const x = index % actual.width;
      const y = Math.floor(index / actual.width);
      return `pixel (${x},${y}): got ${actual.pixels[index]!.toString(16)} expected ${expected.pixels[index]!.toString(16)}`;
    }
  }
  return '';
}

describe('shared raster vectors (fixtures/gui/raster)', () => {
  it('has the vectors the README promises', () => {
    expect(names.length).toBeGreaterThanOrEqual(12);
    for (const required of ['chest-6-rows', 'chest-1-row', 'button-1px-bevel', 'panel-2px-bevel',
      'slots-container-1row', 'slots-player-3rows', 'opacity-rounding', 'image-nearest-neighbour']) {
      expect(names).toContain(required);
    }
  });

  it.each(names)('%s renders to the expected PNG, pixel for pixel', (name) => {
    const vector = JSON.parse(readFileSync(`${DIR}${name}.json`, 'utf8')) as RasterVector;
    const expected = decodePng(readFileSync(`${DIR}${name}.png`));
    expect(vector.name).toBe(name);
    const actual = renderVector(vector);
    expect(firstDifference(actual, expected)).toBe('');
  });

  it('the vanilla chest vector has the chest canvas size', () => {
    const size = canvasSize(6);
    const expected = decodePng(readFileSync(`${DIR}chest-6-rows.png`));
    expect([expected.width, expected.height]).toEqual([size.width, size.height]);
    expect([size.width, size.height]).toEqual([176, 222]);
  });
});

/** Renders a pattern of single characters: . skip, O outline, H highlight, S shadow, F fill. */
function pattern(w: number, h: number, part: (x: number, y: number) => string): string[] {
  const letters: Record<string, string> = { skip: '.', outline: 'O', highlight: 'H', shadow: 'S', fill: 'F' };
  return Array.from({ length: h }, (_, y) =>
    Array.from({ length: w }, (_, x) => letters[part(x, y)]).join(''));
}

describe('bevel rules', () => {
  it('raised 1 px: outline ring with transparent corners, highlight top/left, shadow bottom/right', () => {
    expect(pattern(6, 6, (x, y) => raisedPart(x, y, 6, 6, 1))).toEqual([
      '.OOOO.',
      'OHHHFO',
      'OHFFSO',
      'OHFFSO',
      'OFSSSO',
      '.OOOO.',
    ]);
  });

  it('raised 2 px (panel): the off-diagonal corners are 2x2 blocks of fill', () => {
    expect(pattern(10, 10, (x, y) => raisedPart(x, y, 10, 10, 2))).toEqual([
      '.OOOOOOOO.',
      'OHHHHHHFFO',
      'OHHHHHHFFO',
      'OHHFFFFSSO',
      'OHHFFFFSSO',
      'OHHFFFFSSO',
      'OHHFFFFSSO',
      'OFFSSSSSSO',
      'OFFSSSSSSO',
      '.OOOOOOOO.',
    ]);
  });

  it('inset (slot well): shadow top/left, highlight bottom/right, fill at the two meeting corners', () => {
    expect(pattern(5, 5, (x, y) => insetPart(x, y, 5, 5))).toEqual([
      'SSSSF',
      'SFFFH',
      'SFFFH',
      'SFFFH',
      'FHHHH',
    ]);
  });

  it('degenerate rectangles never throw and never draw more than they contain', () => {
    const scene = { width: 8, height: 8, rows: 1, image: () => null };
    const layers = [
      { id: 'a', type: 'button', x: 1, y: 1, width: 0, height: 4 },
      { id: 'b', type: 'button', x: 3, y: 3, width: 1, height: 1 },
      { id: 'c', type: 'panel', x: 5, y: 5, width: 2, height: 2 },
    ];
    expect(() => flattenRaster(layers, scene)).not.toThrow();
    const drawn = flattenRaster(layers, scene);
    // 1x1 and 2x2 rectangles are all corners: nothing is drawn.
    expect([...drawn.pixels].every((value) => value === 0)).toBe(true);
  });
});

describe('chest geometry', () => {
  it('matches the vanilla slot grid', () => {
    expect(canvasSize(1)).toEqual({ width: 176, height: 132 });
    expect(canvasSize(6)).toEqual({ width: 176, height: 222 });
    const six = cellsOf(6, 'container');
    expect(six).toHaveLength(54);
    expect(six[0]).toMatchObject({ index: 0, x: 8, y: 18 });
    expect(six[53]).toMatchObject({ index: 53, x: 8 + 8 * 18, y: 18 + 5 * 18 });
    const player = cellsOf(6, 'player');
    expect(player).toHaveLength(36);
    expect(player[0]).toMatchObject({ index: 54, x: 8, y: 6 * 18 + 31 });
    expect(player[26]).toMatchObject({ y: 6 * 18 + 31 + 2 * 18 });
    expect(player[27]).toMatchObject({ index: 54 + 27, x: 8, y: 6 * 18 + 89 });
    // vanilla 6-row chest: inventory at y=139, hotbar at y=197
    expect([player[0]!.y, player[27]!.y]).toEqual([139, 197]);
    expect(wellRect(six[0]!)).toEqual({ x: 7, y: 17, w: 18, h: 18 });
    expect(slotCell(3, 27)).toMatchObject({ region: 'player', y: 3 * 18 + 31 });
    expect(slotCell(3, 999)).toBeNull();
  });
});

describe('compositing', () => {
  it('formats and parses colors', () => {
    expect(parseColor('#336699')).toBe(argb(255, 0x33, 0x66, 0x99));
    expect(parseColor('CC336699')).toBe(argb(0xcc, 0x33, 0x66, 0x99));
    expect(parseColor('#12')).toBeNull();
    expect(formatColor(argb(255, 0xc6, 0xc6, 0xc6))).toBe('#C6C6C6');
    expect(formatColor(argb(0x80, 1, 2, 3))).toBe('#80010203');
    // alpha 0 has exactly one representation
    expect(parseColor('#00FFFFFF')).toBe(0);
  });

  it('opacity scales alpha with round half up', () => {
    const opaque = argb(255, 10, 20, 30);
    expect(scaleAlpha(opaque, 100)).toBe(opaque);
    expect(scaleAlpha(opaque, 33) >>> 24).toBe(84); // 84.15
    expect(scaleAlpha(opaque, 50) >>> 24).toBe(128); // 127.5 -> up
    expect(scaleAlpha(argb(1, 1, 1, 1), 50) >>> 24).toBe(1); // 0.5 -> up
    expect(scaleAlpha(argb(1, 1, 1, 1), 49)).toBe(0); // 0.49 -> down, vanishes
    expect(scaleAlpha(opaque, 0)).toBe(0);
  });

  it('source-over is exact on the easy cases and rounds half up on the rest', () => {
    const red = argb(255, 255, 0, 0);
    const half = argb(128, 0, 0, 255);
    expect(blendOver(0, half)).toBe(half);
    expect(blendOver(red, 0)).toBe(red);
    expect(blendOver(red, argb(255, 1, 2, 3))).toBe(argb(255, 1, 2, 3));
    // opaque dst: (sc*sa + dc*(255-sa)) / 255, rounded half up
    const over = blendOver(red, half);
    expect(over >>> 24).toBe(255);
    expect((over >>> 16) & 255).toBe(Math.floor((2 * 255 * 127 + 255) / 510)); // 127
    expect(over & 255).toBe(128);
    // translucent over translucent: alpha is round((sa*255 + da*(255-sa)) / 255)
    const both = blendOver(argb(128, 255, 0, 0), argb(128, 0, 0, 255));
    expect(both >>> 24).toBe(Math.floor((128 * 255 + 128 * 127 + 127) / 255));
  });

  it('keeps the vanilla default colors', () => {
    expect(RAISED_DEFAULT).toEqual({
      fill: argb(255, 0xc6, 0xc6, 0xc6), highlight: argb(255, 255, 255, 255),
      shadow: argb(255, 0x55, 0x55, 0x55), outline: argb(255, 0, 0, 0),
    });
    expect(SLOT_DEFAULT.fill).toBe(argb(255, 0x8b, 0x8b, 0x8b));
    expect(SLOT_DEFAULT.shadow).toBe(argb(255, 0x37, 0x37, 0x37));
  });
});
