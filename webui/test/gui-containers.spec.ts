/**
 * The TS side of the container profile contract (gui.md section 12.5.1): the file the studio reads must be the one
 * the golden pins (fixtures/gui/containers/golden.txt, which the Java test pins too), and the studio's reader must
 * give every container, the chests included, the geometry the compiler uses, from the same code.
 */
import { describe, expect, it } from 'vitest';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { guiContainersOf } from '../src/lib/api';
import {
  CONTAINERS,
  NEW_GUI_CONTAINER,
  canvasSize,
  cellsOf,
  containerProfile,
  drawingProfile,
  groupChoices,
  nearestCell,
  nearestContainerCell,
  slotCell,
} from '../src/lib/gui/containers';
import { parseGui, serializeGui, starterDocument } from '../src/lib/gui/model';
import { regionAt, screenRect, windowGuide } from '../src/lib/gui/open-canvas';

const FILE = readFileSync(fileURLToPath(new URL('../../shared/gui/containers.json', import.meta.url)));
const GOLDEN = readFileSync(fileURLToPath(new URL('../../fixtures/gui/containers/golden.txt', import.meta.url)), 'utf8');
const SNAPSHOT = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../fixtures/golden/editor-demo-snapshot.json', import.meta.url)), 'utf8'),
) as { describe: Record<string, unknown> };

function pinned(key: string): string | undefined {
  const line = GOLDEN.split('\n').find((raw) => raw.startsWith(`${key}=`));
  return line?.slice(key.length + 1).trim();
}

const profile = (id: string) => containerProfile(id)!;

describe('the container profile file', () => {
  it('is the file the golden pins (the Java reader pins the same digest)', () => {
    expect(createHash('sha256').update(FILE).digest('hex')).toBe(pinned('sha256'));
    expect(CONTAINERS.map((entry) => entry.id).join(',')).toBe(pinned('containers'));
  });

  it('gives every chest the geometry the chest compiler always used', () => {
    for (let rows = 1; rows <= 6; rows++) {
      const chest = profile(`chest_${rows}`);
      expect(chest.slotCount / chest.columns).toBe(rows);
      expect(chest.slotCount).toBe(9 * rows);
      expect(chest.height).toBe(114 + 18 * rows);
      expect(chest.inventory).toEqual({ x: 8, y: 31 + 18 * rows });
      expect(chest.inventoryTop).toBe(17 + 18 * rows);
      expect(chest.inventoryLabel).toEqual({ x: 8, y: 20 + 18 * rows });
    }
  });

  it('reads the hopper and the dispenser with the verified client numbers (gui.md section 12.2.6)', () => {
    const hopper = profile('hopper');
    expect([hopper.slotCount, hopper.width, hopper.height, hopper.inventoryTop]).toEqual([5, 176, 133, 37]);
    const dispenser = profile('dispenser');
    expect([dispenser.slotCount, dispenser.columns, dispenser.width, dispenser.height, dispenser.inventoryTop]).toEqual([9, 3, 176, 166, 70]);
    expect(dispenser.title.centred).toBe(true);
  });

  it('answers nothing for an unknown or non-string id, and starts new GUIs on chest_3', () => {
    expect(containerProfile('chest_7')).toBeUndefined();
    expect(containerProfile(3)).toBeUndefined();
    expect(NEW_GUI_CONTAINER).toBe('chest_3');
    expect(drawingProfile('chest_7').id).toBe(NEW_GUI_CONTAINER); // a read-only GUI still has a screen to draw
  });
});

describe('the geometry of any profile, from one reader', () => {
  it('sizes the canvas from the profile: 176 x 132 to 176 x 222 for the chests, 133 for the hopper, 166 for the dispenser', () => {
    expect(canvasSize(profile('chest_1'))).toEqual({ width: 176, height: 132 });
    expect(canvasSize(profile('chest_6'))).toEqual({ width: 176, height: 222 });
    expect(canvasSize(profile('hopper'))).toEqual({ width: 176, height: 133 });
    expect(canvasSize(profile('dispenser'))).toEqual({ width: 176, height: 166 });
    expect(screenRect(profile('hopper'))).toEqual({ x: 0, y: 0, w: 176, h: 133 });
  });

  it('places the hopper on one row of five and the dispenser on a 3 x 3 grid', () => {
    const hopper = cellsOf(profile('hopper'), 'all');
    expect(hopper).toHaveLength(5 + 36);
    expect(hopper.slice(0, 5).map((cell) => [cell.index, cell.x, cell.y])).toEqual(
      [0, 1, 2, 3, 4].map((i) => [i, 44 + 18 * i, 20]));
    expect(hopper[5]).toMatchObject({ index: 5, region: 'player', x: 8, y: 51 });
    expect(hopper[32]).toMatchObject({ index: 32, region: 'player', x: 8, y: 51 + 58 });
    const dispenser = cellsOf(profile('dispenser'), 'container');
    expect(dispenser.map((cell) => [cell.x, cell.y])).toEqual(
      [0, 1, 2].flatMap((row) => [0, 1, 2].map((col) => [62 + 18 * col, 17 + 18 * row])));
    expect(cellsOf(profile('dispenser'), 'player')[0]).toMatchObject({ index: 9, x: 8, y: 84 });
  });

  it('numbers slots by the container\'s own ids, then the player\'s', () => {
    expect(slotCell(profile('hopper'), 4)).toMatchObject({ region: 'container', x: 44 + 72, y: 20 });
    expect(slotCell(profile('hopper'), 5)).toMatchObject({ region: 'player', y: 51 });
    expect(slotCell(profile('hopper'), 41)).toBeNull(); // 5 + 36 slots: ids 0..40
    expect(slotCell(profile('dispenser'), 8)).toMatchObject({ region: 'container', x: 62 + 36, y: 17 + 36 });
    expect(slotCell(profile('chest_3'), -1)).toBeNull();
  });

  it('finds the nearest slot, and the nearest container slot, in any profile', () => {
    expect(nearestCell(profile('hopper'), 12, 60).region).toBe('player');
    expect(nearestContainerCell(profile('hopper'), 12, 60).index).toBe(0);
    expect(nearestContainerCell(profile('dispenser'), 70, 25).index).toBe(0);
  });

  it('puts the veil boundary where the profile says: row 37 on the hopper, 70 on the dispenser', () => {
    expect(regionAt(profile('hopper'), 10, 36)).toBe('container');
    expect(regionAt(profile('hopper'), 10, 37)).toBe('player');
    expect(regionAt(profile('hopper'), 10, 133)).toBe('outside');
    expect(regionAt(profile('dispenser'), 10, 69)).toBe('container');
    expect(regionAt(profile('dispenser'), 10, 70)).toBe('player');
    expect(regionAt(profile('dispenser'), 176, 70)).toBe('outside');
  });

  it('centres each profile\'s screen in the player\'s window from its own height', () => {
    // 1920 x 1080 at scale 3 is a 640 x 360 area: the margin above is floor((360 - height) / 2)
    expect(windowGuide(profile('hopper'), 3).margin).toMatchObject({ top: Math.floor((360 - 133) / 2), left: 232 });
    expect(windowGuide(profile('dispenser'), 3).margin).toMatchObject({ top: Math.floor((360 - 166) / 2), left: 232 });
  });
});

describe('the container choices the server lists (gui.md section 12.4)', () => {
  it('are read from the describe answer: id, label and group, in the server\'s order', () => {
    const choices = guiContainersOf(SNAPSHOT.describe);
    expect(choices.map((choice) => choice.id)).toEqual(CONTAINERS.map((entry) => entry.id));
    expect(choices[0]).toEqual({ id: 'chest_1', label: 'Chest, 1 row', group: 'Chests' });
  });

  it('are none from a server too old to list them, and malformed entries are dropped, never guessed at', () => {
    expect(guiContainersOf({})).toEqual([]);
    expect(guiContainersOf({ guiContainers: 'chest_3' })).toEqual([]);
    expect(guiContainersOf({ guiContainers: [null, { id: 'hopper' }, { id: 'hopper', label: 'Hopper', group: 'Other' }] }))
      .toEqual([{ id: 'hopper', label: 'Hopper', group: 'Other' }]);
  });

  it('group under the profiles\' headings; an id the studio has no profile for is not offered', () => {
    const groups = groupChoices([
      ...guiContainersOf(SNAPSHOT.describe),
      { id: 'furnace', label: 'Furnace', group: 'Later' },
    ]);
    const headings = groups.map((entry) => entry.group);
    expect(new Set(headings).size).toBe(headings.length);
    expect(headings).not.toContain('Later');
    expect(groups.find((entry) => entry.group === 'Chests')!.choices).toHaveLength(6);
    const others = groups.filter((entry) => entry.group !== 'Chests').flatMap((entry) => entry.choices.map((choice) => choice.id));
    expect(others).toEqual(['hopper', 'dispenser']);
  });
});

describe('a hopper or dispenser file is a document like any other', () => {
  it.each(['hopper', 'dispenser'])('%s: parses editable, and an untouched save is byte-identical to what was loaded', (container) => {
    const file = {
      main: {
        title: 'Orders',
        container,
        layers: [{ id: 'frame', type: 'panel', x: 0, y: 0, width: 176, height: 100, future_field: { keep: [1, 2] } }],
        unknown_top_level: 'kept',
      },
    };
    const parsed = parseGui(file, 'main')!;
    expect(parsed.blocking).toEqual([]);
    expect(parsed.doc.container).toBe(container);
    expect(JSON.stringify(serializeGui(parsed.doc))).toBe(JSON.stringify(file.main));
  });

  it('starts a new GUI on the default container, whatever else exists', () => {
    expect(starterDocument().container).toBe(NEW_GUI_CONTAINER);
    expect(Object.keys(serializeGui(starterDocument()))).toEqual(['title', 'container', 'layers']);
  });
});
