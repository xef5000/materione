/**
 * The screen under every GUI (gui.md sections 11.2 and 12.7). The client draws its own container screen and Materione
 * never compiles it, so the studio shows a stand-in, composed from the container's profile alone:
 *
 * - **the owner's art** (option A) when every sheet the profile's blit list names is available: the blits are applied
 *   exactly as the client's `blit` calls place them, so the screen is the sheet's pixels where the client draws them;
 * - **a generated diagram** (option C) otherwise: the screen's outline, the slot wells at their exact positions and the
 *   player's inventory block, in a neutral palette that cannot be mistaken for the vanilla look. It is correct for
 *   geometry and snapping, which is what the underlay is for, and it works for a container the day its profile is added.
 *
 * In both, the player's inventory part sits under a grey veil that lifts per painted pixel. None of this is a layer, none
 * of it is saved and none of it is in the history. Mojang's assets never enter the repository.
 *
 * Pure: no DOM, no Svelte (a sheet arrives as a decoded canvas).
 */
import { cellsOf, wellRect, type ContainerProfile } from './containers';
import { paintInset, paintRaised, PANEL_EDGE, type BevelStyle } from './raster/bevel';
import { ArgbCanvas, blendOver } from './raster/canvas';
import { alphaOf, type Argb } from './raster/color';

/** The decoded sheets by the name a profile's blit gives (`generic_54`), whatever sheets exist. */
export type UnderlaySheets = ReadonlyMap<string, ArgbCanvas>;

/** Where the screen's pixels came from: the owner's art, or the generated diagram. */
export type UnderlayKind = 'art' | 'diagram';

/**
 * The veil over the player's inventory: translucent grey, source-over. It says "this is the player's own
 * space"; a pixel Materione paints there is drawn at full strength with the veil lifted under it.
 */
export const VEIL: Argb = 0xa0303030;

/** The diagram's palette: cool greys, deliberately unlike the vanilla screen. */
const DIAGRAM_PANEL: BevelStyle = { fill: 0xffaab3c0, highlight: 0xffcdd3dc, shadow: 0xff7c8696, outline: 0xff3d4554 };
const DIAGRAM_WELL: BevelStyle = { fill: 0xff8791a1, highlight: 0xffcdd3dc, shadow: 0xff566072, outline: 0xff3d4554 };

/** Whether every sheet the profile's blits name is in `sheets`: the condition for drawing the owner's art. */
export function hasArt(profile: ContainerProfile, sheets: UnderlaySheets): boolean {
  return profile.underlay.length > 0 && profile.underlay.every((blit) => sheets.has(blit.sheet));
}

/**
 * The profile's blits applied to an empty screen: each copies its source rectangle of its sheet to its destination.
 * The profiles' blits never overlap, so a copy is the client's blend. A pixel the screen has no blit for stays
 * transparent, as on the client.
 */
function composeArt(profile: ContainerProfile, sheets: UnderlaySheets): ArgbCanvas {
  const screen = new ArgbCanvas(profile.width, profile.height);
  for (const blit of profile.underlay) {
    const sheet = sheets.get(blit.sheet)!;
    const [sx, sy, w, h] = blit.src;
    const [dx, dy] = blit.dst;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const tx = dx + x;
        const ty = dy + y;
        if (tx < 0 || ty < 0 || tx >= screen.width || ty >= screen.height) continue;
        screen.pixels[ty * screen.width + tx] = sheet.get(sx + x, sy + y);
      }
    }
  }
  return screen;
}

/** The generated diagram (option C): a flat frame and a well at every slot, from the profile's geometry alone. */
export function composeDiagram(profile: ContainerProfile): ArgbCanvas {
  const screen = new ArgbCanvas(profile.width, profile.height);
  paintRaised(screen, 0, 0, profile.width, profile.height, DIAGRAM_PANEL, PANEL_EDGE);
  for (const cell of cellsOf(profile, 'all')) {
    const well = wellRect(cell);
    paintInset(screen, well.x, well.y, well.w, well.h, DIAGRAM_WELL);
  }
  return screen;
}

/** The screen of a container: the owner's art when the profile's sheets are present, else the generated diagram. */
export function composeUnderlay(profile: ContainerProfile, sheets: UnderlaySheets): { screen: ArgbCanvas; kind: UnderlayKind } {
  return hasArt(profile, sheets)
    ? { screen: composeArt(profile, sheets), kind: 'art' }
    : { screen: composeDiagram(profile), kind: 'diagram' };
}

/**
 * The screen as the studio shows it: the underlay with the player's inventory part veiled everywhere except under a
 * painted pixel. `painted` says whether Materione draws something at a GUI pixel (any pixel of the composed art with
 * alpha above 0); transparent underlay pixels (the vanilla screen's rounded corners) stay transparent.
 */
export function veiledScreen(underlay: ArgbCanvas, profile: ContainerProfile, painted: (x: number, y: number) => boolean): ArgbCanvas {
  const out = underlay.clone();
  for (let y = profile.inventoryTop; y < out.height; y++) {
    for (let x = 0; x < out.width; x++) {
      const index = y * out.width + x;
      const color = out.pixels[index]!;
      if (alphaOf(color) === 0 || painted(x, y)) continue;
      out.pixels[index] = blendOver(color, VEIL) >>> 0;
    }
  }
  return out;
}
