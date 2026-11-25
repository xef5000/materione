/**
 * The fixed core's type declarations, as far as the generated surface refers to them (codemode.md section
 * 3.1). Types only, no runtime: issue 339 (the SDK core) owns this file and may reshape it. What the
 * generator needs from it is small and stable:
 *
 *  - the brands and builders the generated members return (`Ref`, `MiniMessage`, `ActionStep`, `Condition`,
 *    `MechanicEntry`, `TriggerBinding`, `Expr`, ...);
 *  - the empty interfaces the generated `.d.ts` fills by declaration merging (`PackWriter`, `RefFactory`,
 *    `ActionCatalog`, `ConditionCatalog`, `MechanicCatalog`, `TriggerCatalog`, `FunctionCatalog`,
 *    `RecipeCatalog`, `GuiComponentCatalog`, `ItemSourceCatalog`, `ListSourceCatalog`, `RuleOptions`, `RuleBody`,
 *    `StepBuilders`), so a member a server does not register is absent and using it is TS2339 (codemode.md
 *    section 9.2).
 *
 * It types no engine vocabulary (issue 355): no step, no rule key, no condition word. A ban test keeps it so.
 *
 * Both files are global scripts (no top-level import or export) and are loaded together.
 */

// ---- values -----------------------------------------------------------------------------------------

/** Any JSON value: the type of a `raw` field, which the entry validates and the schema does not describe. */
type JsonValue = string | number | boolean | null | readonly JsonValue[] | { readonly [key: string]: JsonValue };

type CustodyTransferSlot = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13
  | 14 | 15 | 16 | 17 | 18 | 19 | 20 | 21 | 22 | 23 | 24 | 25 | 26
  | 27 | 28 | 29 | 30 | 31 | 32 | 33 | 34 | 35 | 36 | 37 | 38 | 39
  | 40 | 41 | 42 | 43 | 44 | 45 | 46 | 47 | 48 | 49 | 50 | 51 | 52 | 53;
type CustodyKey = Lowercase<string>;
type CustodyHolderAddress =
  | { readonly kind: "player"; readonly player?: string | Expr; readonly key?: never }
  | { readonly kind: "keyed"; readonly player?: string | Expr; readonly key: CustodyKey | Expr };
type CustodyTransferSource =
  | { readonly slot: CustodyTransferSlot | Expr; readonly holder?: never; readonly entry?: never }
  | { readonly holder: CustodyHolderAddress; readonly entry: string | Expr; readonly slot?: never };
type CustodyTransferDestination = {
  readonly holder: CustodyHolderAddress;
  readonly slot: CustodyTransferSlot | Expr;
};

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

/** One step of a flow: an action, or a structural step built by `m.steps`. */
interface Step {
  readonly __step: true;
}

/** An action step built by `m.actions.*`. `I` is the registry id. */
interface ActionStep<I extends string = string> extends Step {
  readonly __action: true;
  readonly id: I;
}

/** A condition built by `m.conditions.*`. */
interface Condition<I extends string = string> {
  readonly __condition: true;
  readonly id: I;
}

/** What a condition is written as: a built condition, or an expression. */
type ConditionValue = Condition | Expr<boolean>;

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

/** The registered options a rule may carry (filled by the generated surface). */
interface RuleOptions {}

/** What a rule's builder returns: the rule options and the flow keys of a rule, both filled by the generated surface. */
interface RuleBody extends RuleOptions {}

interface RuleHandle {
  readonly __rule: true;
}

/** The rules of one resource of host (content type) `H`. */
interface RuleSet<H extends string> {
  /**
   * Adds a rule. The trigger comes first and types the rest: `build` receives the context the trigger
   * guarantees on host `H`, and a trigger no `H` fires is a type error. A key the body may not carry (the
   * rule's keys are `RuleBody`) is a type error too: the `never` mapping stands in for the
   * excess-property check TypeScript does not make on an arrow function's return value.
   */
  add<C extends { [P in H]: object }, B extends RuleBody>(
    on: TriggerBinding<string, C>,
    build: (ctx: C[H]) => B & { readonly [K in Exclude<keyof B, keyof RuleBody>]: never },
  ): RuleHandle;
  instantiate(recipe: RecipeInstance): RuleHandle;
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

/** `m.steps`. Generated: one builder per step kind the server's engine describes. */
interface StepBuilders {}

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
  readonly acceptsExpression?: boolean;
}

interface TypeDoc {
  readonly name: string;
  readonly directory: string;
  readonly description: string;
  readonly fields: readonly ParamDoc[];
}

/** One registry entry found by `m.docs.search` or `m.docs.describe`. `member` is its spelling in the SDK (`m.actions.takeMoney`). */
interface DocEntry {
  readonly kind: string;
  readonly id: string;
  readonly member: string;
  readonly summary: string;
  readonly shorthand?: string;
  readonly parameters?: readonly ParamDoc[];
  readonly filters?: readonly ParamDoc[];
  readonly firedBy?: readonly string[];
  readonly returns?: string;
}

/** The registry's own text, searched in the program: the same words the declarations carry as documentation. */
interface DocsApi {
  type(name: ContentTypeName): TypeDoc;
  describe(entry: string): DocEntry;
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
