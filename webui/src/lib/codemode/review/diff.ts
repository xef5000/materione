/**
 * A line diff for the review (codemode.md section 5.6): the dry run's resulting text against the stored text, minimal and
 * per file. The editor had no text diff before this (its only "diff" is a per-field change list in the bulk dialog), and the
 * review needs the file's own lines, so this is the one, and it is small: common prefix and suffix trimmed, a longest
 * common subsequence over the middle, and a blunt fallback (the middle shown as removed then added) past a size bound,
 * never an unbounded computation on the page.
 *
 * Pure and DOM-free: the review components draw rows, a test checks them.
 */

export type RowKind = 'same' | 'add' | 'del';

export interface DiffRow {
  kind: RowKind;
  /** One-based line number in the stored text (absent for an added line). */
  before?: number;
  /** One-based line number in the resulting text (absent for a removed line). */
  after?: number;
  text: string;
}

/** A run of rows to draw, or a fold of unchanged lines between two runs. */
export type DiffBlock = { kind: 'rows'; rows: DiffRow[] } | { kind: 'fold'; hidden: number; rows: DiffRow[] };

export interface Diff {
  rows: DiffRow[];
  added: number;
  removed: number;
}

/** The most (middle lines before) x (middle lines after) the LCS table may have; past it the middle is replaced whole. */
export const MAX_CELLS = 4_000_000;

function lines(text: string | null): string[] {
  if (text === null || text === '') return [];
  const split = text.replace(/\r\n/g, '\n').split('\n');
  // a trailing newline ends the last line; it does not start an empty one
  if (split[split.length - 1] === '') split.pop();
  return split;
}

/** The line diff of two texts; `null` is "no such document" (every line of the other side is added or removed). */
export function diffText(before: string | null, after: string | null): Diff {
  const a = lines(before);
  const b = lines(after);
  const rows: DiffRow[] = [];

  let prefix = 0;
  while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++;
  let suffix = 0;
  while (suffix < a.length - prefix && suffix < b.length - prefix && a[a.length - 1 - suffix] === b[b.length - 1 - suffix]) suffix++;

  for (let i = 0; i < prefix; i++) rows.push({ kind: 'same', before: i + 1, after: i + 1, text: a[i] as string });

  const midA = a.slice(prefix, a.length - suffix);
  const midB = b.slice(prefix, b.length - suffix);
  let added = 0;
  let removed = 0;
  const emit = (kind: 'add' | 'del', index: number, text: string): void => {
    if (kind === 'del') {
      rows.push({ kind, before: index, text });
      removed++;
    } else {
      rows.push({ kind, after: index, text });
      added++;
    }
  };

  if (midA.length * midB.length > MAX_CELLS) {
    midA.forEach((text, i) => emit('del', prefix + i + 1, text));
    midB.forEach((text, i) => emit('add', prefix + i + 1, text));
  } else {
    // LCS lengths from the end, then walk forward: removals before additions inside a changed run, as a reader expects
    const n = midA.length;
    const m = midB.length;
    const width = m + 1;
    const table = new Uint32Array((n + 1) * width);
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        table[i * width + j] =
          midA[i] === midB[j]
            ? (table[(i + 1) * width + j + 1] as number) + 1
            : Math.max(table[(i + 1) * width + j] as number, table[i * width + j + 1] as number);
      }
    }
    let i = 0;
    let j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && midA[i] === midB[j]) {
        rows.push({ kind: 'same', before: prefix + i + 1, after: prefix + j + 1, text: midA[i] as string });
        i++;
        j++;
      } else if (j >= m || (i < n && (table[(i + 1) * width + j] as number) >= (table[i * width + j + 1] as number))) {
        emit('del', prefix + i + 1, midA[i] as string);
        i++;
      } else {
        emit('add', prefix + j + 1, midB[j] as string);
        j++;
      }
    }
  }

  for (let k = 0; k < suffix; k++) {
    const ai = a.length - suffix + k;
    const bi = b.length - suffix + k;
    rows.push({ kind: 'same', before: ai + 1, after: bi + 1, text: a[ai] as string });
  }
  return { rows, added, removed };
}

/**
 * Collapses long unchanged stretches: every changed row keeps `context` unchanged rows on each side, and anything farther
 * from a change is a fold that says how many lines it hides. A diff with no change is one fold-free block of its rows
 * (the caller says "no change").
 */
export function blocks(diff: Diff, context = 3): DiffBlock[] {
  const keep = new Array<boolean>(diff.rows.length).fill(false);
  diff.rows.forEach((row, index) => {
    if (row.kind === 'same') return;
    for (let k = Math.max(0, index - context); k <= Math.min(diff.rows.length - 1, index + context); k++) keep[k] = true;
  });
  if (!keep.some(Boolean)) return [{ kind: 'rows', rows: diff.rows }];
  const result: DiffBlock[] = [];
  let run: DiffRow[] = [];
  let hidden: DiffRow[] = [];
  const flushRun = (): void => {
    if (run.length > 0) result.push({ kind: 'rows', rows: run });
    run = [];
  };
  diff.rows.forEach((row, index) => {
    if (keep[index]) {
      if (hidden.length > 0) {
        flushRun();
        result.push({ kind: 'fold', hidden: hidden.length, rows: hidden });
        hidden = [];
      }
      run.push(row);
    } else {
      hidden.push(row);
    }
  });
  flushRun();
  if (hidden.length > 0) result.push({ kind: 'fold', hidden: hidden.length, rows: hidden });
  return result;
}
