/**
 * The Recipes panel's reactive core (logic.md section 7.5, 33): which recipe is chosen, the values of its parameters, the
 * server's expansion and its warnings, and the insertion. A recipe is the server's (the registry, D5) and so is its
 * expansion (`logic.instantiate`, validated by the compiler that loads a file): the studio sends the parameters, shows
 * what comes back and, when the author keeps it, inserts the rules through the canvas as one history command, so the
 * insertion is one undo step and reaches the file through `logic.edit` (D6).
 */
import type { RpcChannel } from '../../api';
import { RpcError } from '../../api';
import type { LogicCanvasState } from '../canvas.svelte';
import { logicApiAvailable, logicExpand, type LogicDiagnostic } from '../server';
import type { Json } from '../tree';
import {
  missingRequired, planInsertRecipe, sendable, standIns, startingValues, type RecipeEntry,
} from './recipes';

export class RecipesState {
  readonly canvas: LogicCanvasState;

  open = $state(false);
  selected = $state<string | null>(null);
  values = $state.raw<Json>({});
  /** The server's reasons a recipe cannot run here, by recipe, found by asking for an expansion (empty: it can). */
  unavailable = $state.raw<ReadonlyMap<string, string>>(new Map());
  probing = $state(false);
  busy = $state(false);
  /** What the last insertion said: the warnings that came with the rules, or the server's refusal. */
  warnings = $state.raw<LogicDiagnostic[]>([]);
  error = $state('');
  inserted = $state('');
  #probed = false;

  recipe: RecipeEntry | null;
  missing: string[];

  constructor(canvas: LogicCanvasState) {
    this.canvas = canvas;
    this.recipe = $derived(this.selected ? canvas.vocabulary?.recipes.get(this.selected) ?? null : null);
    this.missing = $derived(this.recipe ? missingRequired(this.recipe, this.values) : []);
  }

  /** The recipes in the order the server lists them. */
  get list(): RecipeEntry[] {
    return [...(this.canvas.vocabulary?.recipes.values() ?? [])];
  }

  /** Why a recipe is greyed: the vocabulary's own flag first, else what the probe found. */
  reasonFor(recipe: RecipeEntry): string | null {
    if (!recipe.available) return recipe.reason ?? 'This server cannot run it.';
    return this.unavailable.get(recipe.id) ?? null;
  }

  toggle(channel: RpcChannel | null): void {
    this.open = !this.open;
    if (this.open) void this.probe(channel);
  }

  choose(id: string | null, layer?: string): void {
    this.selected = id;
    this.warnings = [];
    this.error = '';
    this.inserted = '';
    const recipe = id ? this.canvas.vocabulary?.recipes.get(id) : undefined;
    this.values = recipe ? startingValues(recipe, layer) : {};
  }

  setValues(next: Json): void {
    this.values = next;
    this.error = '';
  }

  // ------------------------------------------------------------------ availability

  /**
   * Finds out which recipes write an action this server cannot run (a payment with no economy). The vocabulary does not
   * say, but the expansion does: it warns with `MTR-0088` for each such action. So each recipe is expanded once with
   * stand-in values, in parallel, and nothing is written. A recipe the stand-ins cannot expand (it needs a declared
   * variable) is left as available: the real expansion warns when it matters.
   */
  async probe(channel: RpcChannel | null): Promise<void> {
    if (this.#probed || this.probing || !channel || !logicApiAvailable(channel)) return;
    this.probing = true;
    try {
      const found = new Map<string, string>();
      await Promise.all(this.list.map(async (recipe) => {
        const probe = standIns(recipe);
        if (!probe || !recipe.available) return;
        try {
          const { warnings } = await logicExpand(channel, recipe.id, probe);
          const first = warnings.find((warning) => warning.code === 'MTR-0088');
          if (first) found.set(recipe.id, first.message);
        } catch {
          // refused for its stand-ins: nothing learned
        }
      }));
      this.unavailable = found;
      this.#probed = true;
    } finally {
      this.probing = false;
    }
  }

  // ------------------------------------------------------------------ inserting

  /** Expands the chosen recipe on the server and inserts its rules as one undo step. */
  async insert(channel: RpcChannel | null): Promise<boolean> {
    const recipe = this.recipe;
    if (!recipe || !channel || this.busy) return false;
    this.error = '';
    this.inserted = '';
    this.warnings = [];
    if (this.missing.length > 0) {
      this.error = `Fill in ${this.missing.join(', ')} first.`;
      return false;
    }
    this.busy = true;
    try {
      const { rules, warnings } = await logicExpand(channel, recipe.id, sendable(recipe, this.values));
      this.warnings = warnings;
      const plan = planInsertRecipe(this.canvas.block, rules, recipe.displayName);
      const done = this.canvas.run(plan);
      if (done) this.inserted = `Inserted ${rules.length} rule${rules.length === 1 ? '' : 's'}. Ctrl+Z takes them back.`;
      else this.error = this.canvas.message || 'The rules could not be inserted.';
      return done;
    } catch (error) {
      this.error = error instanceof RpcError ? error.message : error instanceof Error ? error.message : String(error);
      return false;
    } finally {
      this.busy = false;
    }
  }
}
