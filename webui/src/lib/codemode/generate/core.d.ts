/**
 * The fixed core's type declarations, as far as the generated surface refers to them (codemode.md section
 * 3.1). Types only, no runtime: issue 339 (the SDK core) owns this file and may reshape it. What the
 * generator needs from it is small and stable:
 *
 *  - the brands and builders the generated members return (`Ref`, `MiniMessage`, `ActionStep`, `Condition`,
 *    `MechanicEntry`, `TriggerBinding`, `Expr`, ...);
 *  - the empty interfaces the generated `.d.ts` fills by declaration merging (`PackWriter`, `RefFactory`,
 *    `ActionCatalog`, `ConditionCatalog`, `MechanicCatalog`, `TriggerCatalog`, `FunctionCatalog`,
 *    `RecipeCatalog`, `GuiComponentCatalog`, `ItemSourceCatalog`, `ListSourceCatalog`, `RuleOptions`), so a
 *    member a server does not register is absent and using it is TS2339 (codemode.md section 9.2).
 *
 * Both files are global scripts (no top-level import or export) and are loaded together.
 */

// ---- values -----------------------------------------------------------------------------------------

/** Any JSON value: the type of a `raw` field, which the entry validates and the schema does not describe. */
type JsonValue = string | number | boolean | null | readonly JsonValue[] | { readonly [key: string]: JsonValue };

/** A formatted-text field (master section 5.7): MiniMessage, built with `m.mm` so its tags are checked when staged. */
type MiniMessage = string & { readonly __brand: "MiniMessage" };

/** A typed reference to a resource or an asset of kind `K`: `"pack:id"` at runtime (codemode.md section 3.4). */
interface Ref<K extends string = string> {
  readonly kind: K;
  readonly id: string;
}

/** What `create` returns for content type `K` with fields `F`: a name, usable as a `Ref<K>`. */
interface ResourceHandle<K extends string = string, F = unknown> extends Ref<K> {
  readonly __fields?: F;
}

interface CreateOptions {
  /** The file to write the resource into; default one file per resource under the type's directory. */
  readonly file?: string;
}

/** The writer of one content type inside a pack. */
interface Collection<K extends string, F, H extends ResourceHandle<K, F>> {
  create(id: string, fields: F, options?: CreateOptions): H;
}

// ---- expressions ------------------------------------------------------------------------------------

/** A logic expression (logic.md section 2) of result type `T`: built with `m.expr` or `m.fn`. */
interface Expr<T = unknown> {
  readonly __expr?: T;
  readonly source: string;
}

/** A value of a trigger's context schema (`ctx.player.sneaking`): interpolates into `m.expr` as its path. */
interface ContextValue<T = unknown> {
  readonly __context?: T;
  readonly path: string;
}

/** Anything an expression function takes for a `T`: a literal, an expression or a context value. */
type ExprArg<T> = T | Expr<T> | ContextValue<T>;

/** What may be interpolated into `m.expr`. */
type ExprOperand = string | number | boolean | Expr | ContextValue | Ref;

// ---- behavior ---------------------------------------------------------------------------------------

/** One step of a flow: an action, or a structural step from `m.steps`. */
interface Step {
  readonly __step: true;
}

/** An action step built by `m.actions.*`. `I` is the registry id. */
interface ActionStep<I extends string = string> extends Step {
  readonly kind: "action";
  readonly id: I;
}

/** A condition built by `m.conditions.*`. */
interface Condition<I extends string = string> {
  readonly __condition: true;
  readonly id: I;
}

/** A mechanic entry built by `m.mechanics.*`. */
interface MechanicEntry<I extends string = string> {
  readonly __mechanic: true;
  readonly id: I;
}

/** A GUI layer built by `m.guiComponents.*`. */
interface GuiLayerEntry<I extends string = string> {
  readonly __layer: true;
  readonly id: I;
}

/** An item source (`head`, ...) built by `m.itemSources.*`, written where an item is a raw value. */
interface ItemSourceSpec<I extends string = string> {
  readonly __itemSource: true;
  readonly id: I;
}

/** A list source built by `m.listSources.*`. */
interface ListSourceSpec<I extends string = string> {
  readonly __listSource: true;
  readonly id: I;
}

/** A logic recipe instance built by `m.recipes.*` and instantiated with `rules.instantiate`. */
interface RecipeInstance<I extends string = string> {
  readonly __recipe: true;
  readonly id: I;
}

/**
 * A trigger with its filters bound, built by `m.triggers.*`. `C` maps each host that fires the trigger
 * (a content-type name) to the context a rule on that host receives, so a rule set typed for host `H`
 * accepts only triggers whose map has an `H` entry, and its callback sees exactly that context.
 */
interface TriggerBinding<I extends string = string, C = unknown> {
  readonly __trigger: true;
  readonly id: I;
  readonly __context?: C;
}

/** The registered options a rule may carry besides `on`, `if` and `do` (filled by the generated surface). */
interface RuleOptions {}

/** What a rule's builder returns. */
interface RuleBody extends RuleOptions {
  readonly if?: Condition | Expr<boolean>;
  readonly do: readonly Step[];
}

interface RuleHandle {
  readonly __rule: true;
}

/** The rules of one resource of host (content type) `H`. */
interface RuleSet<H extends string> {
  /**
   * Adds a rule. The trigger comes first and types the rest: `build` receives the context the trigger
   * guarantees on host `H`, and a trigger no `H` fires is a type error. A key the body may not carry (the
   * registered rule options are `RuleOptions`) is a type error too: the `never` mapping stands in for the
   * excess-property check TypeScript does not make on an arrow function's return value.
   */
  add<C extends { [P in H]: object }, B extends RuleBody>(
    on: TriggerBinding<string, C>,
    build: (ctx: C[H]) => B & { readonly [K in Exclude<keyof B, keyof RuleBody>]: never },
  ): RuleHandle;
  instantiate(recipe: RecipeInstance): RuleHandle;
}

/** The structural steps of the flow model (logic.md section 4); their parameters are not registry data. */
interface StepBuilders {
  if(condition: Condition | Expr<boolean>, then: readonly Step[], otherwise?: readonly Step[]): Step;
  require(conditions: Condition | Expr<boolean> | readonly (Condition | Expr<boolean> | ActionStep)[], options?: { readonly else?: readonly Step[] }): Step;
  wait(ticks: number): Step;
  stop(): Step;
  choose(branches: readonly { readonly if: Condition | Expr<boolean>; readonly then: readonly Step[] }[], otherwise?: readonly Step[]): Step;
  call(flow: string): Step;
}

// ---- extension points: the generated surface merges its members into these -------------------------

/** One pack's writer. Generated: one member per content type, named by the type's directory. */
interface PackWriter {
  readonly id: string;
}

/** `m.ref`. Generated: one lookup per content type. */
interface RefFactory {
  /** An asset the owner uploaded; checked when the program runs. `K` follows the field it is assigned to. */
  asset<K extends string = string>(path: string): Ref<K>;
}

interface ActionCatalog {}
interface ConditionCatalog {}
interface MechanicCatalog {}
interface TriggerCatalog {}
interface FunctionCatalog {}
interface RecipeCatalog {}
interface GuiComponentCatalog {}
interface ItemSourceCatalog {}
interface ListSourceCatalog {}

// ---- the program's one global -----------------------------------------------------------------------

interface Materione {
  /** The writer of a pack; every write of a program goes to one pack (codemode.md section 5.4). */
  pack(id: string): PackWriter;
  readonly ref: RefFactory;
  readonly actions: ActionCatalog;
  readonly conditions: ConditionCatalog;
  readonly mechanics: MechanicCatalog;
  readonly triggers: TriggerCatalog;
  readonly fn: FunctionCatalog;
  readonly recipes: RecipeCatalog;
  readonly guiComponents: GuiComponentCatalog;
  readonly itemSources: ItemSourceCatalog;
  readonly listSources: ListSourceCatalog;
  readonly steps: StepBuilders;
  /** A formatted-text value; the tag syntax is checked when the edit is staged. */
  mm(text: string): MiniMessage;
  /** A logic expression; interpolate context values, variable handles and `m.fn` results. */
  expr<T = unknown>(strings: TemplateStringsArray, ...values: readonly ExprOperand[]): Expr<T>;
  /** A one-line, human-readable summary of the change, carried with the operation list for review. */
  summary(text: string): void;
  /** Ends the run with a diagnostic and no operations. */
  fail(message: string): never;
  /** Whether the connected plugin advertises a capability. */
  hasCapability(name: Capability): boolean;
}

declare const m: Materione;
