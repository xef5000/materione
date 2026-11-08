/**
 * The GUI container profiles (gui.md section 12.5.1): one file, `shared/gui/containers.json`, read here by the studio
 * and by the Java compiler (`GuiContainers`), and pinned by a golden on both sides. Nothing in the studio knows the
 * word "chest": the six chests are six entries of the file, like the hopper and the dispenser.
 *
 * Until the studio's container row (gui.md section 12.10, row S) the studio draws the containers the server opens by
 * size, which is the chest grid; a container opened by its inventory type (the hopper, the dispenser) is read and
 * kept as authored, but not drawn. Pure: no DOM, no Svelte.
 */
import profileFile from '../../../../shared/gui/containers.json';

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

/** The slot grid's rows: the number the studio's canvas geometry is drawn with (a 9-wide chest has `slotCount / 9`). */
export function gridRows(profile: ContainerProfile): number {
  return profile.slotCount / profile.columns;
}

/** Whether the studio draws this container: the ones the server opens by size (until the studio's container row). */
export function studioDraws(profile: ContainerProfile): boolean {
  return profile.open.size !== undefined;
}

/** The supported ids, for the messages that name an unknown one. */
export function supportedContainerIds(): string {
  return CONTAINERS.map((profile) => profile.id).join(', ');
}
