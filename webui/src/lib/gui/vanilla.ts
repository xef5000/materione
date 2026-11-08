/**
 * The vanilla chest under every GUI (gui.md section 11.2). The client draws its own chest and Materione
 * never compiles it, so the studio shows the owner's reference art (`static/vanilla/generic_54.png`: the
 * 176 x 222 six-row chest at the top-left of a 256 x 256 sheet) composed for 1-6 rows exactly as the client
 * composes it, with the player's inventory part under a grey veil that lifts per painted pixel. None of this
 * is a layer, none of it is saved and none of it is in the history.
 *
 * Pure: no DOM, no Svelte (the sheet arrives as a decoded canvas).
 */
import { CANVAS_WIDTH, canvasHeight } from './chest';
import { inventoryTop } from './open-canvas';
import { ArgbCanvas, blendOver } from './raster/canvas';
import { alphaOf, type Argb } from './raster/color';

/** The sheet's own size: 256 x 256, with the six-row chest at the top-left. */
export const SHEET_SIZE = 256;
/** Where the sheet's player-inventory part starts (`v = 126`) and how tall it is. */
export const INVENTORY_V = 126;
export const INVENTORY_HEIGHT = 96;

/**
 * The veil over the player's inventory: translucent grey, source-over. It says "this is the player's own
 * space"; a pixel Materione paints there is drawn at full strength with the veil lifted under it.
 */
export const VEIL: Argb = 0xa0303030;

function copyRows(target: ArgbCanvas, source: ArgbCanvas, sourceY: number, targetY: number, height: number): void {
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < CANVAS_WIDTH; x++) {
      target.pixels[(targetY + y) * target.width + x] = source.get(x, sourceY + y);
    }
  }
}

/**
 * The vanilla screen for a row count, `176 x (114 + 18 * rows)`, composed as the client's `blit` calls do: the
 * top `rows * 18 + 17` rows of the sheet, then the 96 rows of the inventory part from `v = 126`, directly below.
 * The last row of the screen stays transparent (the two blits cover `rows * 18 + 113` rows), as on the client.
 */
export function composeVanilla(sheet: ArgbCanvas, rows: number): ArgbCanvas {
  const screen = new ArgbCanvas(CANVAS_WIDTH, canvasHeight(rows));
  const top = inventoryTop(rows);
  copyRows(screen, sheet, 0, 0, top);
  copyRows(screen, sheet, INVENTORY_V, top, INVENTORY_HEIGHT);
  return screen;
}

/**
 * The chest as the studio shows it: the vanilla screen with the player's inventory part veiled everywhere except
 * under a painted pixel. `painted` says whether Materione draws something at a GUI pixel (any pixel of the
 * composed art with alpha above 0); transparent vanilla pixels (the rounded corners) stay transparent.
 */
export function veiledScreen(vanilla: ArgbCanvas, rows: number, painted: (x: number, y: number) => boolean): ArgbCanvas {
  const out = vanilla.clone();
  for (let y = inventoryTop(rows); y < out.height; y++) {
    for (let x = 0; x < out.width; x++) {
      const index = y * out.width + x;
      const color = out.pixels[index]!;
      if (alphaOf(color) === 0 || painted(x, y)) continue;
      out.pixels[index] = blendOver(color, VEIL) >>> 0;
    }
  }
  return out;
}
