/**
 * The mapping between the Design tab's layers and the Logic tab's rules (logic.md section 7.4, 33). A rule is tied to
 * a layer by its `layer:` filter, a layer id or a list of them, so the link is data in the file (D1) and not a model of
 * the studio's own: nothing here is stored, every answer is read from the rules as they stand.
 *
 * Everything that changes the file is a `Plan` of `LogicEdit`s, the same ones the canvas plans with, so "Add click
 * logic" and a layer's deletion travel the one pipeline (D6) and are one undo step each.
 *
 * Pure of the DOM and of Svelte.
 */
import { refuse, type Plan } from '../plan';
import type { LogicEdit } from '../server';
import { clone, isRecord, type LogicBlock } from '../tree';
import type { NodeFlag } from '../verdicts';

/** The component types a click reaches, and so the ones "Add click logic" is offered for (logic.md section 7.4). */
export const CLICKABLE = ['button', 'item', 'list'] as const;

/** The layer ids a rule's `layer:` filter names: a text, or a list of texts. Anything else names none. */
export function layersOfRule(rule: unknown): string[] {
  if (!isRecord(rule)) return [];
  const filter = rule.layer;
  if (typeof filter === 'string') return filter.trim() ? [filter.trim()] : [];
  if (Array.isArray(filter)) return filter.filter((entry): entry is string => typeof entry === 'string' && entry.trim() !== '').map((entry) => entry.trim());
  return [];
}

/** The indices of the rules whose `layer:` filter names `layerId`. */
export function rulesOfLayer(block: LogicBlock, layerId: string): number[] {
  const out: number[] = [];
  (block.behavior ?? []).forEach((rule, index) => {
    if (layersOfRule(rule).includes(layerId)) out.push(index);
  });
  return out;
}

/** How many rules each layer has. */
export function ruleCounts(block: LogicBlock): Map<string, number> {
  const counts = new Map<string, number>();
  for (const rule of block.behavior ?? []) {
    for (const id of new Set(layersOfRule(rule))) counts.set(id, (counts.get(id) ?? 0) + 1);
  }
  return counts;
}

/** The first layer a rule is filtered to, for the thumbnail on its trigger node. */
export function layerOfNode(block: LogicBlock, nodeId: string): string | null {
  const match = /^behavior\.(\d+)$/.exec(nodeId);
  if (!match) return null;
  return layersOfRule((block.behavior ?? [])[Number(match[1])])[0] ?? null;
}

/** The flags of the rules a layer carries: the verdict on any node of such a rule is the layer's. */
export function layerFlags(block: LogicBlock, verdicts: ReadonlyMap<string, NodeFlag[]>): Map<string, NodeFlag[]> {
  const out = new Map<string, NodeFlag[]>();
  (block.behavior ?? []).forEach((rule, index) => {
    const layers = layersOfRule(rule);
    if (layers.length === 0) return;
    const prefix = `behavior.${index}`;
    const flags: NodeFlag[] = [];
    for (const [id, nodeFlags] of verdicts) if (id === prefix || id.startsWith(`${prefix}.`)) flags.push(...nodeFlags);
    if (flags.length === 0) return;
    for (const layer of new Set(layers)) out.set(layer, [...(out.get(layer) ?? []), ...flags]);
  });
  return out;
}

// ------------------------------------------------------------------ adding

/** A new `gui.click` rule filtered to the layer, at the end of `behavior:`; the canvas selects its trigger. */
export function planAddClickLogic(block: LogicBlock, layerId: string, layerName = layerId): Plan {
  if (!layerId) return refuse('Select a layer first.');
  const index = block.behavior?.length ?? 0;
  return {
    ok: true,
    edits: [{ op: 'addRule', index, rule: { on: 'gui.click', layer: layerId, do: [] } }],
    label: `Add click logic to ${layerName}`,
    select: `behavior.${index}`,
  };
}

// ------------------------------------------------------------------ deleting

/** What deleting some layers means for the rules: the ones that go with them, and the ones shared with other layers. */
export interface Affected {
  /** Rules filtered only to the deleted layers. */
  exclusive: number[];
  /** Rules that also name a layer that stays: they keep serving it. */
  shared: number[];
}

export function affectedBy(block: LogicBlock, ids: readonly string[]): Affected {
  const gone = new Set(ids);
  const out: Affected = { exclusive: [], shared: [] };
  (block.behavior ?? []).forEach((rule, index) => {
    const layers = layersOfRule(rule);
    if (!layers.some((layer) => gone.has(layer))) return;
    (layers.every((layer) => gone.has(layer)) ? out.exclusive : out.shared).push(index);
  });
  return out;
}

export type DeleteChoice = 'delete' | 'detach';

/**
 * The edits for what happens to the rules of layers that are being deleted.
 *
 * - `delete`: the rules filtered only to those layers are removed.
 * - `detach`: the layer filter is taken off those rules and the rules stay (they then answer any click no other rule
 *   claims, logic.md section 21, so this is a deliberate choice, not a default).
 *
 * A rule shared with a layer that stays is never removed: the deleted layers are just taken off its filter.
 */
export function planLayerDeletion(block: LogicBlock, ids: readonly string[], choice: DeleteChoice): LogicEdit[] {
  const gone = new Set(ids);
  const edits: LogicEdit[] = [];
  const removals: number[] = [];
  (block.behavior ?? []).forEach((rule, index) => {
    const layers = layersOfRule(rule);
    if (!layers.some((layer) => gone.has(layer))) return;
    const remaining = layers.filter((layer) => !gone.has(layer));
    if (remaining.length > 0) {
      // only a list can name a layer that stays, so the filter stays a list
      edits.push({ op: 'patchRule', index, set: { layer: clone(remaining) } });
    } else if (choice === 'delete') {
      removals.push(index);
    } else {
      edits.push({ op: 'patchRule', index, unset: ['layer'] });
    }
  });
  if (removals.length > 0) edits.push({ op: 'removeRules', indices: removals });
  return edits;
}
