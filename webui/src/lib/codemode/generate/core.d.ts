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

/** What `create` returns for content type `K` with fields `F`, and what `m.edit` returns for an existing one: a name, usable as a `Ref<K>`, that stages edits to its document. */
interface ResourceHandle<K extends string = string, F = unknown> extends Ref<K> {
  readonly __fields?: F;
  /** Stages a field patch: each key given is set, and every other key (and any unknown key of the document) is left as it is. */
  set(fields: Partial<F>): void;
  /** Stages the removal of fields, so the resource reads them from its template or its default again. */
  unset(...keys: readonly (keyof F & string)[]): void;
  /** Stages a copy of this resource under a new id, with `fields` applied over the copy. The copy is a handle of the same kind. */
  duplicate(id: string, options?: DuplicateOptions<F>): this;
  /** Stages the deletion of this resource's document. Only a resource that exists already can be deleted: a resource this program creates is simply not created. */
  delete(): void;
  /** Stages cutting this resource's link to its template, copying what it inherited into its own document. */
  detachTemplate(): void;
  /** Stages saving the named `fields` (at least one) of this resource as the template `templateId`, which others can inherit from. */
  saveAsTemplate(templateId: string, fields: readonly (keyof F & string)[]): void;
}

interface CreateOptions {
  /** A subfolder of the type's directory to write the document into; default the directory itself. One document per resource. */
  readonly folder?: string;
}

interface DuplicateOptions<F = unknown> extends CreateOptions {
  /** Field values applied over the copy. */
  readonly fields?: Partial<F>;
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

/**
 * The structural steps of the flow model, typed by hand from logic.md section 2.2 and the engine's step compiler
 * (`StepCompiler`): they are engine-owned and closed, not registry entries, and self-description does not carry
 * their grammar. TEMPORARY: issue 355 puts the step grammar in self-description, and this interface is then generated.
 */
interface StepBuilders {
  /** A branch on a condition; at least one of `then` and `else` is required. Execution continues after it. */
  if(
    condition: Condition | Expr<boolean>,
    branches:
      | { readonly then: readonly Step[]; readonly else?: readonly Step[] }
      | { readonly then?: readonly Step[]; readonly else: readonly Step[] },
  ): Step;
  /**
   * Runs the **steps** in order until one fails; on a failure it runs `else` and stops the flow (the atomic
   * paywall: a take, then a give). It takes steps, not conditions: guard with `if` and `stop` for a condition.
   */
  require(steps: readonly Step[], options?: { readonly else?: readonly Step[] }): Step;
  /** Suspends the run for whole `ticks` (0 to 1,728,000, one day). */
  wait(ticks: number): Step;
  /** Ends this run; `cancel` also cancels the underlying game event. */
  stop(options?: { readonly cancel?: boolean; readonly cancel_session_runs?: boolean }): Step;
  /** The first branch whose condition holds runs; `otherwise` is the step's own `else`. At least one branch. */
  choose(
    branches: readonly [
      { readonly if: Condition | Expr<boolean>; readonly then: readonly Step[] },
      ...{ readonly if: Condition | Expr<boolean>; readonly then: readonly Step[] }[],
    ],
    otherwise?: readonly Step[],
  ): Step;
  /** Runs a named flow of the same resource inline; `args` arrive as `args.*` in the flow. */
  call(flow: string, args?: { readonly [name: string]: JsonValue }): Step;
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

/** Generated: the record type of each content type, by name. */
interface ContentTypeFields {}
/** Generated: the handle type of each content type, by name. */
interface ContentTypeHandles {}

interface ActionCatalog {}
interface ConditionCatalog {}
interface MechanicCatalog {}
interface TriggerCatalog {}
interface FunctionCatalog {}
interface RecipeCatalog {}
interface GuiComponentCatalog {}
interface ItemSourceCatalog {}
interface ListSourceCatalog {}

// ---- the read side ----------------------------------------------------------------------------------

/** A pack the connected server has. Built-in packs can be read and referenced, never written. */
interface PackInfo {
  readonly id: string;
  readonly builtin: boolean;
}

/** One row of a pack's catalog; `K` is the content type asked for, so `m.edit(row.ref)` is typed by it. */
interface ResourceSummary<K extends string = string> {
  readonly ref: Ref<K>;
  readonly pack: string;
  /** The directory the document lives under (`items`). */
  readonly type: string;
  readonly path: string;
  /** The resource's own id, without its pack. */
  readonly id: string;
  readonly name: string;
  readonly health: string;
  readonly healthMessage?: string;
}

/** A stored resource as a typed object. */
interface ResourceView<F = unknown> {
  readonly ref: Ref;
  readonly path: string;
  readonly revision: number;
  /** The resource's own tree, typed by its content type. */
  readonly fields: F;
  /** What the server computes beside the stored text (inheritance, allocated ids). */
  readonly computed: JsonValue;
}

/** What a scan of the pack set read, so "no hits" is not mistaken for "unused": `complete` is false when something was left unread. */
interface ReferenceCoverage {
  readonly complete: boolean;
  readonly documentsScanned: number;
  readonly notScanned: readonly string[];
}

/** Where a resource is used, with what the scan covered. */
interface ReferenceResult {
  readonly hits: readonly ReferenceHit[];
  readonly coverage: ReferenceCoverage;
}

/** One place a resource is used. */
interface ReferenceHit {
  readonly pack: string;
  readonly type: string;
  readonly path: string;
  readonly id: string;
  readonly field: readonly string[];
  readonly value: string;
}

interface RevisionInfo {
  readonly rev: number;
  readonly [key: string]: JsonValue;
}

/** A diagnostic the server holds now, as the editor's diagnostics list shows it. */
interface ServerDiagnostic {
  readonly [key: string]: JsonValue;
}

/**
 * Reads see the stored state, never the program's own staged edits (codemode.md section 3.2). Each call is budgeted, and
 * each one **waits for the host**: it returns a promise, so a program `await`s it. Nothing else in the SDK waits for
 * anything: staging is synchronous and makes no call (codemode.md section 3.9).
 */
interface ReadApi {
  packs(): Promise<readonly PackInfo[]>;
  resources<T extends ContentTypeName = ContentTypeName>(options: { readonly pack: string; readonly type?: T; readonly query?: string; readonly limit?: number }): Promise<readonly ResourceSummary<T>[]>;
  get<K extends keyof ContentTypeFields & string>(ref: Ref<K>): Promise<ResourceView<ContentTypeFields[K]>>;
  references(ref: Ref): Promise<ReferenceResult>;
  history(ref: Ref, limit?: number): Promise<readonly RevisionInfo[]>;
  diagnostics(): Promise<readonly ServerDiagnostic[]>;
}

/** One parameter of a content type, as the registry describes it. */
interface ParamDoc {
  readonly id: string;
  readonly type: string;
  readonly required: boolean;
  readonly description?: string;
  readonly allowedValues?: readonly string[];
  readonly referenceKind?: string;
  readonly default?: string;
}

interface TypeDoc {
  readonly name: string;
  readonly directory: string;
  readonly description: string;
  readonly fields: readonly ParamDoc[];
}

/** One registry entry found by `m.docs.search`. `member` is its spelling in the SDK (`m.actions.takeMoney`). */
interface DocEntry {
  readonly kind: string;
  readonly id: string;
  readonly member: string;
  readonly summary: string;
}

/** The registry's own text, searched in the program: the same words the declarations carry as documentation. */
interface DocsApi {
  type(name: ContentTypeName): TypeDoc;
  search(query: string, limit?: number): readonly DocEntry[];
}

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
  /** An existing resource's handle: it stages edits to that resource, checked against the stored state when the program runs. */
  edit<K extends keyof ContentTypeHandles & string>(ref: Ref<K>): ContentTypeHandles[K];
  readonly read: ReadApi;
  readonly docs: DocsApi;
  readonly sdk: { readonly version: string };
  /** Adds a line to the run's log (bounded). */
  log(...values: readonly unknown[]): void;
  /** A one-line, human-readable summary of the change, carried with the operation list for review. */
  summary(text: string): void;
  /** Ends the run with a diagnostic and no operations. */
  fail(message: string): never;
  /** Whether the connected plugin advertises a capability. */
  hasCapability(name: Capability): boolean;
}

declare const m: Materione;
