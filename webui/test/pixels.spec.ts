/**
 * The drawing rules, pinned. Every one of these is a thing that silently goes
 * wrong in a pixel editor and that nobody notices until a texture is ruined:
 * strokes that leave gaps, fills that leak, mirroring that is off by one,
 * undo that loses a step.
 */
import { describe, expect, it } from 'vitest';
import {
  History,
  colorToHex,
  createBitmap,
  fill,
  getPixel,
  hexToColor,
  line,
  rectangle,
  replaceColor,
  resize,
  setPixel,
  stamp,
  toPixel,
  usedColors,
  type Bitmap,
  type Color,
} from '../src/lib/pixels';

const RED: Color = [255, 0, 0, 255];
const BLUE: Color = [0, 0, 255, 255];

function brush(color: Color, size = 1, mirrorX = false) {
  return { color, size, mirrorX };
}

/** A readable picture of the alpha channel, for asserting shapes. */
function render(bitmap: Bitmap): string[] {
  const rows: string[] = [];
  for (let y = 0; y < bitmap.height; y++) {
    let row = '';
    for (let x = 0; x < bitmap.width; x++) {
      row += getPixel(bitmap, x, y)[3] === 0 ? '.' : '#';
    }
    rows.push(row);
  }
  return rows;
}

describe('pixels', () => {
  it('starts fully transparent', () => {
    expect(render(createBitmap(3, 2))).toEqual(['...', '...']);
  });

  it('drops out-of-bounds writes instead of wrapping to the next row', () => {
    const bitmap = createBitmap(4, 4);
    setPixel(bitmap, 4, 0, RED);
    setPixel(bitmap, -1, 2, RED);
    expect(render(bitmap)).toEqual(['....', '....', '....', '....']);
  });
});

describe('stamp', () => {
  it('paints a square brush centred on the cursor', () => {
    const bitmap = createBitmap(5, 5);
    stamp(bitmap, 2, 2, brush(RED, 3));
    expect(render(bitmap)).toEqual(['.....', '.###.', '.###.', '.###.', '.....']);
  });

  it('mirrors across the vertical centre without an off-by-one', () => {
    const bitmap = createBitmap(6, 1);
    stamp(bitmap, 1, 0, brush(RED, 1, true));
    expect(render(bitmap)).toEqual(['.#..#.']);
  });
});

describe('line', () => {
  it('leaves no gaps on a diagonal', () => {
    const bitmap = createBitmap(4, 4);
    line(bitmap, 0, 0, 3, 3, brush(RED));
    expect(render(bitmap)).toEqual(['#...', '.#..', '..#.', '...#']);
  });

  it('draws right-to-left as well as left-to-right', () => {
    const bitmap = createBitmap(4, 1);
    line(bitmap, 3, 0, 0, 0, brush(RED));
    expect(render(bitmap)).toEqual(['####']);
  });

  it('paints a single pixel when start and end match', () => {
    const bitmap = createBitmap(3, 1);
    line(bitmap, 1, 0, 1, 0, brush(RED));
    expect(render(bitmap)).toEqual(['.#.']);
  });
});

describe('rectangle', () => {
  it('outlines by default', () => {
    const bitmap = createBitmap(4, 4);
    rectangle(bitmap, 0, 0, 3, 3, brush(RED));
    expect(render(bitmap)).toEqual(['####', '#..#', '#..#', '####']);
  });

  it('fills when asked, whatever the drag direction', () => {
    const bitmap = createBitmap(3, 3);
    rectangle(bitmap, 2, 2, 0, 0, brush(RED), true);
    expect(render(bitmap)).toEqual(['###', '###', '###']);
  });
});

describe('fill', () => {
  it('spreads over equal pixels only, four-connected', () => {
    const bitmap = createBitmap(3, 3);
    setPixel(bitmap, 1, 0, RED);
    setPixel(bitmap, 1, 1, RED);
    setPixel(bitmap, 1, 2, RED);
    fill(bitmap, 0, 0, BLUE);
    expect(getPixel(bitmap, 0, 0)).toEqual(BLUE);
    expect(getPixel(bitmap, 0, 2)).toEqual(BLUE);
    // The red wall stops it reaching the far column.
    expect(getPixel(bitmap, 2, 0)[3]).toBe(0);
  });

  it('does nothing when the target already has the colour, and does not hang', () => {
    const bitmap = createBitmap(2, 2);
    fill(bitmap, 0, 0, RED);
    fill(bitmap, 0, 0, RED);
    expect(render(bitmap)).toEqual(['##', '##']);
  });

  it('ignores clicks outside the canvas', () => {
    const bitmap = createBitmap(2, 2);
    fill(bitmap, 5, 5, RED);
    expect(render(bitmap)).toEqual(['..', '..']);
  });
});

describe('replaceColor', () => {
  it('recolours every matching pixel and leaves the rest', () => {
    const bitmap = createBitmap(2, 1);
    setPixel(bitmap, 0, 0, RED);
    setPixel(bitmap, 1, 0, BLUE);
    replaceColor(bitmap, RED, BLUE);
    expect(getPixel(bitmap, 0, 0)).toEqual(BLUE);
    expect(getPixel(bitmap, 1, 0)).toEqual(BLUE);
  });
});

describe('resize', () => {
  it('doubles by nearest neighbour, keeping the art crisp', () => {
    const bitmap = createBitmap(2, 2);
    setPixel(bitmap, 0, 0, RED);
    const bigger = resize(bitmap, 4, 4);
    expect(render(bigger)).toEqual(['##..', '##..', '....', '....']);
  });

  it('shrinks without reading out of bounds', () => {
    const bitmap = createBitmap(4, 4);
    rectangle(bitmap, 0, 0, 3, 3, brush(RED), true);
    expect(render(resize(bitmap, 2, 2))).toEqual(['##', '##']);
  });
});

describe('toPixel', () => {
  it('maps a canvas point through zoom and pan', () => {
    expect(toPixel({ x: 34, y: 10 }, { zoom: 8, panX: 2, panY: 2 })).toEqual({ x: 4, y: 1 });
  });

  it('floors towards the top-left so a click never lands a pixel late', () => {
    expect(toPixel({ x: 7, y: 7 }, { zoom: 8, panX: 0, panY: 0 })).toEqual({ x: 0, y: 0 });
  });
});

describe('colours', () => {
  it('round-trips hex', () => {
    expect(colorToHex(hexToColor('#7aa2f7'))).toBe('#7aa2f7');
  });

  it('accepts short hex and defaults alpha to opaque', () => {
    expect(hexToColor('#f00')).toEqual([255, 0, 0, 255]);
  });

  it('falls back to black rather than throwing on nonsense', () => {
    expect(hexToColor('not-a-colour')).toEqual([0, 0, 0, 255]);
  });

  it('offers the art’s own palette, most used first, transparency excluded', () => {
    const bitmap = createBitmap(3, 1);
    setPixel(bitmap, 0, 0, BLUE);
    setPixel(bitmap, 1, 0, BLUE);
    setPixel(bitmap, 2, 0, RED);
    expect(usedColors(bitmap)).toEqual([BLUE, RED]);
  });
});

describe('History', () => {
  it('undoes and redoes a step', () => {
    const history = new History();
    let bitmap = createBitmap(1, 1);
    history.push(bitmap);
    setPixel(bitmap, 0, 0, RED);

    bitmap = history.undo(bitmap)!;
    expect(getPixel(bitmap, 0, 0)[3]).toBe(0);

    bitmap = history.redo(bitmap)!;
    expect(getPixel(bitmap, 0, 0)).toEqual(RED);
  });

  it('drops the redo branch once you draw again', () => {
    const history = new History();
    const bitmap = createBitmap(1, 1);
    history.push(bitmap);
    history.undo(bitmap);
    expect(history.canRedo).toBe(true);
    history.push(bitmap);
    expect(history.canRedo).toBe(false);
  });

  it('reports nothing to undo on an untouched texture', () => {
    const history = new History();
    expect(history.canUndo).toBe(false);
    expect(history.undo(createBitmap(1, 1))).toBeNull();
  });

  it('stays bounded so long sessions do not grow without limit', () => {
    const history = new History(2);
    const bitmap = createBitmap(1, 1);
    history.push(bitmap);
    history.push(bitmap);
    history.push(bitmap);
    expect(history.undo(bitmap)).not.toBeNull();
    expect(history.undo(bitmap)).not.toBeNull();
    expect(history.undo(bitmap)).toBeNull();
  });

  it('snapshots, so later edits cannot reach back into history', () => {
    const history = new History();
    const bitmap = createBitmap(1, 1);
    history.push(bitmap);
    setPixel(bitmap, 0, 0, RED);
    expect(getPixel(history.undo(bitmap)!, 0, 0)[3]).toBe(0);
  });
});
