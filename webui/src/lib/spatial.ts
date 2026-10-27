/**
 * Geometry for the spatial editor (issue 153): furniture seats, hitboxes and
 * lights are lists of maps with an `offset: [x, y, z]`. Pure helpers so the
 * reading (what is a row, what kind of marker) and the writing (snapped
 * offsets) are unit-tested without a browser.
 */
import type { TypeParam } from './api';

export type SpatialKind = 'seat' | 'light' | 'box';

export interface SpatialRow {
  /** The row exactly as authored (written back with only `offset` replaced). */
  raw: Record<string, unknown>;
  offset: [number, number, number];
  /** Box rows only; 1 when absent, as the type's default says. */
  width: number;
  height: number;
  level: number | null;
}

/** What the nested shape says a row is — the editor never names a field. */
export function kindOf(field: TypeParam): SpatialKind {
  const ids = new Set((field.nested ?? []).map((nested) => nested.id));
  if (ids.has('width') && ids.has('height')) return 'box';
  if (ids.has('level')) return 'light';
  return 'seat';
}

/** Offsets are authored in whole hundredths of a block at most; snap to 5 cm. */
export function snap(value: number): number {
  return Math.round(value * 20) / 20;
}

export function toOffset(values: readonly number[]): number[] {
  return [0, 1, 2].map((index) => snap(values[index] ?? 0));
}

function numberAt(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

/** Rows that are maps; anything else in the list is left to the numeric rows. */
export function rowsOf(value: unknown): SpatialRow[] {
  if (!Array.isArray(value)) return [];
  return value.map((entry) => {
    const raw = entry !== null && typeof entry === 'object' && !Array.isArray(entry)
      ? entry as Record<string, unknown>
      : {};
    const offset = Array.isArray(raw.offset) ? raw.offset : [];
    return {
      raw,
      offset: [numberAt(offset[0], 0), numberAt(offset[1], 0), numberAt(offset[2], 0)],
      width: numberAt(raw.width, 1),
      height: numberAt(raw.height, 1),
      level: typeof raw.level === 'number' ? raw.level : null,
    };
  });
}
