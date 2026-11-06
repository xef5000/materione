/**
 * The seam between the studio's tabs (logic.md section 7.1, 7.4, 33): which tab is showing, the layer a Logic node is
 * pointing at, and the gestures that cross from one tab to another. It holds no model of the document. The rules a
 * layer has, the bolt on its row and the diagnostics on it are all read from the rules in the file as they stand,
 * and every change (a new click rule, the fate of a deleted layer's rules) is a plan of `LogicEdit`s, one history
 * command, so it shares the undo of the Design tab and goes to the server through `logic.edit` (D6).
 */
import { removeLayers } from '../../gui/commands';
import { bareType } from '../../gui/raster/flatten';
import { findLayer, isLocked, layerLabel, type GuiLayer } from '../../gui/model';
import type { GuiStudioState } from '../../gui/studio.svelte';
import type { LogicCanvasState } from '../canvas.svelte';
import { blockProblems } from '../tree';
import type { NodeFlag } from '../verdicts';
import { joinWithLogic } from './command';
import {
  affectedBy, CLICKABLE, layerFlags, layerOfNode, planAddClickLogic, planLayerDeletion, ruleCounts, rulesOfLayer,
  type DeleteChoice,
} from './layers';

export type StudioView = 'design' | 'logic' | 'script';

/** A deletion waiting for the author to say what becomes of the rules that name the layers. */
export interface PendingDelete {
  ids: string[];
  /** The layers' names, for the question. */
  names: string[];
  /** Rules filtered only to these layers. */
  exclusive: number;
  /** Rules that name another layer as well. */
  shared: number;
}

export class LinkageState {
  readonly studio: GuiStudioState;
  readonly logic: LogicCanvasState;

  /** The tab showing. It lives here so a gesture in one tab can open another. */
  view = $state<StudioView>('design');
  /** The layer a Logic node is hovered or focused on: highlighted on the mini preview. */
  hovered = $state<string | null>(null);
  pendingDelete = $state<PendingDelete | null>(null);

  /** How many rules each layer has. */
  counts: Map<string, number>;
  /** What the compiler says about the rules of each layer (the step paths of its diagnostics, drawn on the layer). */
  flags: Map<string, NodeFlag[]>;

  constructor(studio: GuiStudioState, logic: LogicCanvasState) {
    this.studio = studio;
    this.logic = logic;
    this.counts = $derived(ruleCounts(logic.block));
    this.flags = $derived(layerFlags(logic.block, logic.verdicts));
    logic.links = this;
    studio.deleteGuard = (ids) => this.askDelete(ids);
  }

  ruleCount(layerId: string): number {
    return this.counts.get(layerId) ?? 0;
  }

  flagsFor(layerId: string): NodeFlag[] {
    return this.flags.get(layerId) ?? [];
  }

  /** Whether the layer is one a click reaches, so "Add click logic" is offered (a button, an item or a list). */
  clickable(layer: GuiLayer): boolean {
    return (CLICKABLE as readonly string[]).includes(bareType(layer));
  }

  /** Whether a click rule can be added now: the logic is editable, which needs the server's vocabulary. */
  get canAdd(): boolean {
    return this.logic.editable;
  }

  // ------------------------------------------------------------------ across tabs

  /** "Add click logic": a `gui.click` rule filtered to the layer, then the Logic tab with its trigger selected. */
  addClickLogic(layerId: string): boolean {
    const layer = findLayer(this.studio.doc, layerId);
    if (!layer || !this.clickable(layer)) return false;
    const done = this.logic.run(planAddClickLogic(this.logic.block, layer.id, layerLabel(layer)));
    if (done) this.#showLogic();
    return done;
  }

  /** Opens the Logic tab on the first rule of the layer. */
  openRules(layerId: string): boolean {
    const first = rulesOfLayer(this.logic.block, layerId)[0];
    if (first === undefined) return false;
    this.logic.select(`behavior.${first}`);
    this.#showLogic();
    return true;
  }

  #showLogic(): void {
    this.logic.focusOnShow = true;
    this.view = 'logic';
  }

  /** The layer a Logic node points at, for its thumbnail. */
  layerOf(nodeId: string): GuiLayer | null {
    const id = layerOfNode(this.logic.block, nodeId);
    return id ? findLayer(this.studio.doc, id) ?? null : null;
  }

  hover(layerId: string | null): void {
    this.hovered = layerId;
  }

  // ------------------------------------------------------------------ deleting a layer that has rules

  /**
   * Called before layers are deleted. When rules name them, the deletion waits for the author's choice and this answers
   * true; otherwise it answers false and the studio deletes at once. Logic that cannot be edited here (a `behavior:`
   * that is not a list) is left alone: the layer goes, and the compiler reports a rule that names a layer no longer there.
   */
  askDelete(ids: readonly string[]): boolean {
    const removable = ids.filter((id) => {
      const layer = findLayer(this.studio.doc, id);
      return layer !== undefined && !isLocked(layer);
    });
    if (removable.length === 0 || blockProblems(this.studio.doc.rest).length > 0) return false;
    const affected = affectedBy(this.logic.block, removable);
    if (affected.exclusive.length + affected.shared.length === 0) return false;
    this.pendingDelete = {
      ids: removable,
      names: removable.map((id) => layerLabel(findLayer(this.studio.doc, id)!)),
      exclusive: affected.exclusive.length,
      shared: affected.shared.length,
    };
    return true;
  }

  /** The author's answer: delete the rules with the layers, keep the rules without the layer filter, or cancel. */
  resolveDelete(choice: DeleteChoice | 'cancel'): void {
    const pending = this.pendingDelete;
    this.pendingDelete = null;
    if (!pending || choice === 'cancel') return;
    const doc = this.studio.doc;
    const design = removeLayers(doc, pending.ids, pending.ids.length === 1 ? 'Delete layer' : 'Delete layers');
    const label = choice === 'delete'
      ? `${pending.ids.length === 1 ? 'Delete layer' : 'Delete layers'} and its rules`
      : `${pending.ids.length === 1 ? 'Delete layer' : 'Delete layers'}, detach its rules`;
    const joined = joinWithLogic(doc, label, design, planLayerDeletion(this.logic.block, pending.ids, choice));
    if (!joined.ok) {
      this.studio.say(joined.reason);
      return;
    }
    this.studio.run(joined.command);
    this.logic.settle();
  }
}
