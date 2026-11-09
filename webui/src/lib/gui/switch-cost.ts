/**
 * What switching a GUI's container costs, in one line (gui.md section 12.7). A switch changes the `container:` line and
 * nothing else; a layer that no longer fits is kept and the compiler skips it (MTR-0052), which the studio shows as it
 * does for any layer. The studio carries no copy of the compiler's rules: which layers no longer fit is the server's
 * verdict on the document with the target container (`gui.validate`); this module only words it.
 *
 * Pure: no DOM, no Svelte.
 */
import type { GuiLayer } from './model';
import { bareType } from './raster/flatten';

/** The layers (by id) the verdict flags with the target container but did not flag before the switch. */
export function newlyFlagged(
  layers: readonly GuiLayer[],
  before: ReadonlyMap<string, unknown>,
  after: ReadonlyMap<string, unknown>,
): GuiLayer[] {
  return layers.filter((layer) => after.has(layer.id) && !before.has(layer.id));
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

/** The one-line cost of switching to the container called `label`, from the layers it would newly flag. */
export function switchCost(label: string, flagged: readonly GuiLayer[]): string {
  if (flagged.length === 0) return `Nothing is flagged on ${label}: every layer fits.`;
  const count = (type: string): number => flagged.filter((layer) => bareType(layer) === type).length;
  const items = count('item');
  const lists = count('list');
  const others = flagged.length - items - lists;
  const parts = [
    ...(items ? [plural(items, 'item layer')] : []),
    ...(lists ? [plural(lists, 'list layer')] : []),
    ...(others ? [plural(others, 'other layer')] : []),
  ];
  const subject = parts.length === 1 ? parts[0]! : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`;
  const single = flagged.length === 1;
  return `On ${label}, ${subject} ${single ? 'uses' : 'use'} slots it does not have; ${single ? 'it is' : 'they are'} kept and skipped until you move ${single ? 'it' : 'them'}.`;
}

/** The cost line when the server's verdict cannot be asked (an older plugin, the demo, no connection). */
export const SWITCH_COST_UNKNOWN =
  'The server\'s check is not available here, so what the switch flags is not shown. No layer is changed by the switch.';
