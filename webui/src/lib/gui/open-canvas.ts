/**
 * The open canvas (gui.md section 11): the geometry the studio draws and clips by. A GUI's art is
 * not boxed into the screen any more; it may sit anywhere within the **art cap**, and the screen is
 * three regions (the container, the player's inventory, and the open space around it). Everything
 * here is pure geometry in GUI pixels (origin: the top-left of the vanilla screen). The *rules* about
 * what the compiler accepts stay the server's (`gui.validate`); this module only knows the numbers it
 * needs to draw and to keep a brush inside the box.
 *
 * Pure: no DOM, no Svelte.
 */
import { cellsOf, itemRect, screenRect, type ContainerProfile } from './containers';
import type { Rect } from './raster/canvas';

export { screenRect };

/**
 * The art cap, gui.md section 11.2: x -424..599, y -243..780. The top edge is a client fact (a glyph's
 * top is bounded by `ascent <= height <= 256`, section 9.10); the other three are the owner's "about
 * 1024" and may grow.
 */
export const ART_CAP: Readonly<Rect> = { x: -424, y: -243, w: 1024, h: 1024 };

export type Region = 'container' | 'player' | 'outside';

/** Which region of the open canvas a GUI-pixel point is in; the player's part starts at the profile's `inventoryTop` (gui.md section 12.5.1). */
export function regionAt(profile: ContainerProfile, x: number, y: number): Region {
  const screen = screenRect(profile);
  if (x < screen.x || y < screen.y || x >= screen.x + screen.w || y >= screen.y + screen.h) return 'outside';
  return y >= profile.inventoryTop ? 'player' : 'container';
}

export function isEmptyRect(rect: Rect): boolean {
  return rect.w <= 0 || rect.h <= 0;
}

/** The overlap of two rectangles, or null when they share no pixel. */
export function intersectRects(a: Rect, b: Rect): Rect | null {
  const left = Math.max(a.x, b.x);
  const top = Math.max(a.y, b.y);
  const right = Math.min(a.x + a.w, b.x + b.w);
  const bottom = Math.min(a.y + a.h, b.y + b.h);
  return right > left && bottom > top ? { x: left, y: top, w: right - left, h: bottom - top } : null;
}

/** The smallest rectangle holding both; an empty one contributes nothing. */
export function unionRects(a: Rect, b: Rect): Rect {
  if (isEmptyRect(a)) return isEmptyRect(b) ? { x: a.x, y: a.y, w: 0, h: 0 } : { ...b };
  if (isEmptyRect(b)) return { ...a };
  const left = Math.min(a.x, b.x);
  const top = Math.min(a.y, b.y);
  return { x: left, y: top, w: Math.max(a.x + a.w, b.x + b.w) - left, h: Math.max(a.y + a.h, b.y + b.h) - top };
}

export function containsRect(outer: Rect, inner: Rect): boolean {
  return inner.x >= outer.x && inner.y >= outer.y && inner.x + inner.w <= outer.x + outer.w &&
    inner.y + inner.h <= outer.y + outer.h;
}

/** A rectangle cut to the art cap; null when nothing of it is inside. */
export function clipToCap(rect: Rect): Rect | null {
  return intersectRects(rect, ART_CAP);
}

export function insideCap(x: number, y: number): boolean {
  return x >= ART_CAP.x && y >= ART_CAP.y && x < ART_CAP.x + ART_CAP.w && y < ART_CAP.y + ART_CAP.h;
}

// ------------------------------------------------------------------ what the player's window cuts (section 11.5)

/** The window the guides assume (gui.md section 11.5): 1920 x 1080. */
export const GUIDE_WINDOW = { w: 1920, h: 1080 } as const;
/** The GUI scales the guides show: 2, 3 and 4 (4 is "Auto" at 1080p). */
export const GUIDE_SCALES = [2, 3, 4] as const;

export interface WindowGuide {
  scale: number;
  /** The GUI-pixel area of the window, `ceil(W / s)` by `ceil(H / s)`. */
  area: { w: number; h: number };
  /** That area in GUI pixels relative to the screen's origin: what a player at this scale sees of the art. */
  rect: Rect;
  /** How much art is visible above / below / left / right of the screen. */
  margin: { top: number; bottom: number; left: number; right: number };
  label: string;
}

/**
 * What a player's window shows around the screen at one GUI scale (gui.md section 9.12): the screen is centered in a
 * `ceil(W / s) x ceil(H / s)` area, `leftPos = floor((area.w - 176) / 2)` and `topPos = floor((area.h - imageHeight) / 2)`.
 */
export function windowGuide(profile: ContainerProfile, scale: number, window: { w: number; h: number } = GUIDE_WINDOW): WindowGuide {
  const area = { w: Math.ceil(window.w / scale), h: Math.ceil(window.h / scale) };
  const { width, height } = profile;
  const left = Math.floor((area.w - width) / 2);
  const top = Math.floor((area.h - height) / 2);
  return {
    scale,
    area,
    rect: { x: -left, y: -top, w: area.w, h: area.h },
    margin: { top, bottom: area.h - top - height, left, right: area.w - left - width },
    label: `Scale ${scale} at ${window.w} x ${window.h}`,
  };
}

export function windowGuides(profile: ContainerProfile): WindowGuide[] {
  return GUIDE_SCALES.map((scale) => windowGuide(profile, scale));
}

// ------------------------------------------------------------------ clicks that reach no container slot (sections 9.14, 11.4)

/** The components that take clicks by themselves; any other layer takes them only when a click rule filters on it. */
const CLICKABLE_COMPONENTS: ReadonlySet<string> = new Set(['button']);

/** Whether a layer is clickable: a button, or a layer with at least one click rule on it (`ruleCount`, from the Logic tab's link). */
export function isClickable(componentName: string, ruleCount: number): boolean {
  return CLICKABLE_COMPONENTS.has(componentName) || ruleCount > 0;
}

/** The sentence the layers panel and the inspector show for a clickable layer placed outside the screen. */
export const DECORATION_ONLY = 'Decoration only — clicks outside the screen arrive as click_outside';
/** A clickable layer on the screen that covers no container slot: nothing under it is the layer's to click. */
export const DECORATION_NO_SLOT = 'Decoration only — it covers no container slot, so a click on it is never a click on this layer';
export const DECORATION_PARTLY = 'Partly decoration only — clicks on the part outside the screen arrive as click_outside';

export type ClickReach = 'inside' | 'partial' | 'outside';

/** How much of a rectangle is on the screen, where clicks are the container's own. */
export function clickReach(bounds: Rect, profile: ContainerProfile): ClickReach {
  const screen = screenRect(profile);
  if (isEmptyRect(bounds)) return 'inside';
  if (containsRect(screen, bounds)) return 'inside';
  return intersectRects(screen, bounds) ? 'partial' : 'outside';
}

/** Whether a rectangle covers at least one container slot (the 16 x 16 area the client hits). The compiler warns when it covers none (MTR-0092). */
export function coversContainerSlot(bounds: Rect, profile: ContainerProfile): boolean {
  return cellsOf(profile, 'container').some((cell) => intersectRects(bounds, itemRect(cell)) !== null);
}

/**
 * The note for a layer that takes clicks (a button, or any layer a click rule filters on) but whose click rectangle covers no
 * container slot, or null. The client sends a click outside the screen as "outside" with no position, and a click on the screen
 * that is not on a slot is a plain click, so art there can only be decoration; `gui.click_outside` is the one trigger outside
 * (gui.md section 11.4). This is the compiler's own rule (MTR-0092), stated where the author is looking; a layer that covers a slot
 * but also extends outside the screen is only partly decoration.
 */
export function decorationNote(clickable: boolean, bounds: Rect | null, profile: ContainerProfile): string | null {
  if (!clickable || !bounds || isEmptyRect(bounds)) return null;
  const reach = clickReach(bounds, profile);
  if (!coversContainerSlot(bounds, profile)) return reach === 'inside' ? DECORATION_NO_SLOT : DECORATION_ONLY;
  return reach === 'inside' ? null : DECORATION_PARTLY;
}
