/**
 * The TS side of the container profile contract (gui.md section 12.5.1): the file the studio reads must be the one
 * the golden pins (fixtures/gui/containers/golden.txt, which the Java test pins too), and the studio's reader must
 * give the chests the geometry the compiler uses.
 */
import { describe, expect, it } from 'vitest';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { CONTAINERS, containerProfile, gridRows, profileCells, studioDraws, NEW_GUI_CONTAINER } from '../src/lib/gui/containers';
import { cellsOf } from '../src/lib/gui/chest';

const FILE = readFileSync(fileURLToPath(new URL('../../shared/gui/containers.json', import.meta.url)));
const GOLDEN = readFileSync(fileURLToPath(new URL('../../fixtures/gui/containers/golden.txt', import.meta.url)), 'utf8');

function pinned(key: string): string | undefined {
  const line = GOLDEN.split('\n').find((raw) => raw.startsWith(`${key}=`));
  return line?.slice(key.length + 1).trim();
}

describe('the container profile file', () => {
  it('is the file the golden pins (the Java reader pins the same digest)', () => {
    expect(createHash('sha256').update(FILE).digest('hex')).toBe(pinned('sha256'));
    expect(CONTAINERS.map((profile) => profile.id).join(',')).toBe(pinned('containers'));
  });

  it('gives every chest the geometry the chest compiler always used', () => {
    for (let rows = 1; rows <= 6; rows++) {
      const chest = containerProfile(`chest_${rows}`)!;
      expect(gridRows(chest)).toBe(rows);
      expect(chest.slotCount).toBe(9 * rows);
      expect(chest.height).toBe(114 + 18 * rows);
      expect(chest.inventory).toEqual({ x: 8, y: 31 + 18 * rows });
      expect(chest.inventoryTop).toBe(17 + 18 * rows);
      expect(chest.inventoryLabel).toEqual({ x: 8, y: 20 + 18 * rows });
      expect(studioDraws(chest)).toBe(true);
    }
  });

  it('reads the hopper and the dispenser, which the studio keeps but does not draw yet', () => {
    expect(containerProfile('hopper')?.slotCount).toBe(5);
    expect(containerProfile('dispenser')?.title.centred).toBe(true);
    expect(studioDraws(containerProfile('hopper')!)).toBe(false);
    expect(studioDraws(containerProfile('dispenser')!)).toBe(false);
  });

  it('answers nothing for an unknown or non-string id, and starts new GUIs on chest_3', () => {
    expect(containerProfile('chest_7')).toBeUndefined();
    expect(containerProfile(3)).toBeUndefined();
    expect(NEW_GUI_CONTAINER).toBe('chest_3');
  });
});

describe('the slot cells of a profile (the raster vectors of hopper and dispenser rest on them)', () => {
  it('give every chest the cells the rows-shaped geometry gives', () => {
    for (let rows = 1; rows <= 6; rows++) {
      for (const region of ['container', 'player', 'all'] as const) {
        expect(profileCells(containerProfile(`chest_${rows}`)!, region)).toEqual(cellsOf(rows, region));
      }
    }
  });

  it('place the hopper on one row of five and the dispenser on a 3 x 3 grid', () => {
    const hopper = profileCells(containerProfile('hopper')!, 'all');
    expect(hopper.slice(0, 5).map((cell) => [cell.index, cell.x, cell.y])).toEqual(
      [0, 1, 2, 3, 4].map((i) => [i, 44 + 18 * i, 20]));
    expect(hopper[5]).toMatchObject({ index: 5, region: 'player', x: 8, y: 51 });
    expect(hopper[32]).toMatchObject({ index: 32, region: 'player', x: 8, y: 51 + 58 });
    const dispenser = profileCells(containerProfile('dispenser')!, 'container');
    expect(dispenser.map((cell) => [cell.x, cell.y])).toEqual(
      [0, 1, 2].flatMap((row) => [0, 1, 2].map((col) => [62 + 18 * col, 17 + 18 * row])));
    expect(profileCells(containerProfile('dispenser')!, 'player')[0]).toMatchObject({ index: 9, x: 8, y: 84 });
  });
});
