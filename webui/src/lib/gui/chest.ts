/**
 * Vanilla chest geometry (gui.md section 1, fixtures/gui/raster/README.md): a
 * GUI canvas is the whole container screen, `176 x (114 + rows * 18)` GUI
 * pixels, and every slot sits on the 18 px grid the client draws.
 */
import type { Rect } from './raster/canvas';

export const CANVAS_WIDTH = 176;
export const SLOT_PITCH = 18;
/** The item image inside a slot is 16 x 16; the well around it is 18 x 18, one pixel out. */
export const ITEM_SIZE = 16;
export const MIN_ROWS = 1;
export const MAX_ROWS = 6;

export type SlotRegion = 'container' | 'player' | 'all';

export interface SlotCell {
  /** Menu slot index: container 0..rows*9-1, then the 27 inventory slots, then the 9 hotbar slots. */
  index: number;
  /** Top-left of the 16 x 16 item area. */
  x: number;
  y: number;
  region: 'container' | 'player';
}

export function clampRows(rows: unknown): number {
  const value = typeof rows === 'number' && Number.isFinite(rows) ? Math.round(rows) : MAX_ROWS;
  return Math.min(MAX_ROWS, Math.max(MIN_ROWS, value));
}

export function canvasHeight(rows: number): number {
  return 114 + rows * SLOT_PITCH;
}

export function canvasSize(rows: number): { width: number; height: number } {
  return { width: CANVAS_WIDTH, height: canvasHeight(rows) };
}

export function containerCells(rows: number): SlotCell[] {
  const cells: SlotCell[] = [];
  for (let row = 0; row < rows; row++) {
    for (let col = 0; col < 9; col++) {
      cells.push({
        index: row * 9 + col,
        x: 8 + col * SLOT_PITCH,
        y: 18 + row * SLOT_PITCH,
        region: 'container',
      });
    }
  }
  return cells;
}

/** The 27 inventory slots (y = rows*18+31 ...) then the hotbar (y = rows*18+89). */
export function playerCells(rows: number): SlotCell[] {
  const cells: SlotCell[] = [];
  const first = rows * 9;
  for (let row = 0; row < 3; row++) {
    for (let col = 0; col < 9; col++) {
      cells.push({
        index: first + row * 9 + col,
        x: 8 + col * SLOT_PITCH,
        y: rows * SLOT_PITCH + 31 + row * SLOT_PITCH,
        region: 'player',
      });
    }
  }
  for (let col = 0; col < 9; col++) {
    cells.push({
      index: first + 27 + col,
      x: 8 + col * SLOT_PITCH,
      y: rows * SLOT_PITCH + 89,
      region: 'player',
    });
  }
  return cells;
}

export function cellsOf(rows: number, region: SlotRegion): SlotCell[] {
  if (region === 'container') return containerCells(rows);
  if (region === 'player') return playerCells(rows);
  return [...containerCells(rows), ...playerCells(rows)];
}

export function slotCell(rows: number, index: number): SlotCell | null {
  if (!Number.isInteger(index) || index < 0) return null;
  const containerCount = rows * 9;
  if (index < containerCount) return containerCells(rows)[index] ?? null;
  return playerCells(rows)[index - containerCount] ?? null;
}

/** The inset well drawn around a slot's item area: one pixel out on every side, 18 x 18. */
export function wellRect(cell: { x: number; y: number }): Rect {
  return { x: cell.x - 1, y: cell.y - 1, w: SLOT_PITCH, h: SLOT_PITCH };
}

export function itemRect(cell: { x: number; y: number }): Rect {
  return { x: cell.x, y: cell.y, w: ITEM_SIZE, h: ITEM_SIZE };
}

/** The slot whose well contains a GUI-pixel point, or the nearest by center when none does. */
export function nearestCell(rows: number, x: number, y: number): SlotCell {
  let best: SlotCell | null = null;
  let bestDistance = Infinity;
  for (const cell of cellsOf(rows, 'all')) {
    const dx = x - (cell.x + ITEM_SIZE / 2);
    const dy = y - (cell.y + ITEM_SIZE / 2);
    const distance = dx * dx + dy * dy;
    if (distance < bestDistance) {
      bestDistance = distance;
      best = cell;
    }
  }
  return best!;
}

/**
 * The container slot (never an inventory slot: an `item` layer fills the chest's own slots,
 * `ItemComponent` rejects the rest) nearest to a GUI-pixel point, by item-area centers.
 */
export function nearestContainerCell(rows: number, x: number, y: number): SlotCell {
  let best = containerCells(rows)[0]!;
  let bestDistance = Infinity;
  for (const cell of containerCells(rows)) {
    const dx = x - (cell.x + ITEM_SIZE / 2);
    const dy = y - (cell.y + ITEM_SIZE / 2);
    const distance = dx * dx + dy * dy;
    if (distance < bestDistance) {
      bestDistance = distance;
      best = cell;
    }
  }
  return best;
}
