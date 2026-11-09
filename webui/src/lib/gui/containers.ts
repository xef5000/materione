/**
 * The GUI container profiles (gui.md section 12.5.1): one file, `shared/gui/containers.json`, read here by the studio
 * and by the Java compiler (`GuiContainers`), and pinned by a golden on both sides. This is the studio's one reader of
 * it: every geometry question the studio asks (the screen, the slot cells, the nearest slot, which region a point is in)
 * is a function of a profile, whatever the container. Nothing in the studio knows a container by name: the six chests
 * are six entries of the file, like the hopper and the dispenser, and the only default is the one a new GUI starts with.
 *
 * Pure: no DOM, no Svelte.
 */
import profileFile from '../../../../shared/gui/containers.json';
import type { Rect } from './raster/canvas';

export interface ContainerBlit {
  readonly sheet: string;
  readonly src: readonly [number, number, number, number];
  readonly dst: readonly [number, number];
}

/** One container profile, as the file holds it (field names as in the file). */
export interface ContainerProfile {
  readonly id: string;
  readonly label: string;
  readonly group: string;
  readonly slotCount: number;
  readonly columns: number;
  readonly slots: { readonly x: number; readonly y: number };
  readonly width: number;
  readonly height: number;
  readonly inventory: { readonly x: number; readonly y: number };
  readonly inventoryTop: number;
  readonly title: { readonly x?: number; readonly y: number; readonly centred?: boolean };
  readonly inventoryLabel: { readonly x: number; readonly y: number };
  readonly underlay: readonly ContainerBlit[];
  readonly open: { readonly size?: number; readonly inventoryType?: string };
}

/** The file's version; a reader that meets another version must not guess (the Java reader refuses it too). */
export const CONTAINER_FILE_VERSION = 1;

if (profileFile.version !== CONTAINER_FILE_VERSION) {
  throw new Error(`shared/gui/containers.json is version ${profileFile.version}, this studio reads ${CONTAINER_FILE_VERSION}`);
}

/** Every container profile, in file order. */
export const CONTAINERS: readonly ContainerProfile[] = profileFile.containers as unknown as readonly ContainerProfile[];

/** The container a new GUI starts with (gui.md section 12.4). */
export const NEW_GUI_CONTAINER = 'chest_3';

/** The profile a `container:` value names, or undefined for an unknown or non-string value. */
export function containerProfile(id: unknown): ContainerProfile | undefined {
  return typeof id === 'string' ? CONTAINERS.find((profile) => profile.id === id) : undefined;
}

/**
 * The profile the studio draws a container id in: the named one, else the one a new GUI starts with. A GUI whose
 * container the studio does not know is read-only (the server quarantines it, MTR-0097), but its canvas still needs a screen.
 */
export function drawingProfile(id: unknown): ContainerProfile {
  return containerProfile(id) ?? containerProfile(NEW_GUI_CONTAINER)!;
}

/** The supported ids, for the messages that name an unknown one. */
export function supportedContainerIds(): string {
  return CONTAINERS.map((profile) => profile.id).join(', ');
}

/** The vanilla slot pitch, in pixels (the Java `GuiContainer.PITCH`). */
export const SLOT_PITCH = 18;

/** The item image inside a slot is 16 x 16; the well around it is 18 x 18, one pixel out. */
export const ITEM_SIZE = 16;

/** The hotbar's distance below the first row of the player's inventory (gui.md section 12.2.3, every container). */
const HOTBAR_GAP = 58;

export type SlotRegion = 'container' | 'player' | 'all';

export interface SlotCell {
  /** Menu slot index: the container's own `0..slotCount-1`, then the 27 inventory slots, then the 9 hotbar slots. */
  index: number;
  /** Top-left of the 16 x 16 item area. */
  x: number;
  y: number;
  region: 'container' | 'player';
}

/** The whole screen the client draws for this container: the canvas the studio shows, at the origin. */
export function canvasSize(profile: ContainerProfile): { width: number; height: number } {
  return { width: profile.width, height: profile.height };
}

/** The vanilla screen as a rectangle at the origin. */
export function screenRect(profile: ContainerProfile): Rect {
  return { x: 0, y: 0, w: profile.width, h: profile.height };
}

/**
 * The slot cells a region covers in a container, container slots first then the player's 27 and the hotbar, read from the
 * profile alone (gui.md section 12.5.2): the Java `SlotsComponent.itemOrigins`. `index` is the menu slot id: the
 * container's own `0..slotCount-1`, then the player's.
 */
export function cellsOf(profile: ContainerProfile, region: SlotRegion): SlotCell[] {
  const cells: SlotCell[] = [];
  if (region === 'container' || region === 'all') {
    for (let slot = 0; slot < profile.slotCount; slot++) {
      cells.push({
        index: slot,
        x: profile.slots.x + SLOT_PITCH * (slot % profile.columns),
        y: profile.slots.y + SLOT_PITCH * Math.floor(slot / profile.columns),
        region: 'container',
      });
    }
  }
  if (region === 'player' || region === 'all') {
    for (let row = 0; row < 3; row++) {
      for (let col = 0; col < 9; col++) {
        cells.push({
          index: profile.slotCount + row * 9 + col,
          x: profile.inventory.x + col * SLOT_PITCH,
          y: profile.inventory.y + row * SLOT_PITCH,
          region: 'player',
        });
      }
    }
    for (let col = 0; col < 9; col++) {
      cells.push({
        index: profile.slotCount + 27 + col,
        x: profile.inventory.x + col * SLOT_PITCH,
        y: profile.inventory.y + HOTBAR_GAP,
        region: 'player',
      });
    }
  }
  return cells;
}

/** The cell of a menu slot index (the container's own slots, then the player's), or null outside the menu. */
export function slotCell(profile: ContainerProfile, index: number): SlotCell | null {
  if (!Number.isInteger(index) || index < 0) return null;
  return cellsOf(profile, 'all')[index] ?? null;
}

/** The inset well drawn around a slot's item area: one pixel out on every side, 18 x 18. */
export function wellRect(cell: { x: number; y: number }): Rect {
  return { x: cell.x - 1, y: cell.y - 1, w: SLOT_PITCH, h: SLOT_PITCH };
}

export function itemRect(cell: { x: number; y: number }): Rect {
  return { x: cell.x, y: cell.y, w: ITEM_SIZE, h: ITEM_SIZE };
}

function nearestOf(cells: readonly SlotCell[], x: number, y: number): SlotCell {
  let best = cells[0]!;
  let bestDistance = Infinity;
  for (const cell of cells) {
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

/** The slot whose item area is nearest to a GUI-pixel point, by item-area centres. */
export function nearestCell(profile: ContainerProfile, x: number, y: number): SlotCell {
  return nearestOf(cellsOf(profile, 'all'), x, y);
}

/**
 * The container slot (never an inventory slot: an `item` layer fills the container's own slots, `ItemComponent` rejects
 * the rest) nearest to a GUI-pixel point, by item-area centres.
 */
export function nearestContainerCell(profile: ContainerProfile, x: number, y: number): SlotCell {
  return nearestOf(cellsOf(profile, 'container'), x, y);
}

/**
 * One entry of the `guiContainers` list `authoring.describe` reports (gui.md section 12.4): what the select shows. The
 * geometry stays the profile file's; the server's list says which containers this server compiles and opens.
 */
export interface ContainerChoice {
  readonly id: string;
  readonly label: string;
  readonly group: string;
}

/** A heading of the select and the choices under it, in the order the server listed them. */
export interface ContainerGroup {
  readonly group: string;
  readonly choices: readonly ContainerChoice[];
}

/** The choices grouped under the profiles' `group` headings, keeping the server's order; an id the studio has no profile for is dropped. */
export function groupChoices(choices: readonly ContainerChoice[]): ContainerGroup[] {
  const groups: { group: string; choices: ContainerChoice[] }[] = [];
  for (const choice of choices) {
    if (!containerProfile(choice.id)) continue;
    let heading = groups.find((entry) => entry.group === choice.group);
    if (!heading) groups.push((heading = { group: choice.group, choices: [] }));
    heading.choices.push(choice);
  }
  return groups;
}
