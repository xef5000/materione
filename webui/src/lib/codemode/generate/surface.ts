/**
 * The surface document (codemode.md section 3.1): the one JSON value the SDK generator reads.
 *
 * It is assembled, on the editor side, from what the plugin already serves and nothing else: the reply of
 * `authoring.describe` (content types with field schemas; the behavior vocabulary with actions, conditions,
 * triggers and their context schemas, mechanics, expression functions, recipes, item, list and input
 * sources; the GUI components and containers) and the session blob's capability list. Its field names are
 * the wire's, so the assembler is a tolerant reader and not a second schema (master section 5.6): an entry
 * that lacks what the generator needs is dropped, never guessed at, and a registry a server does not send
 * is empty, so an older jar yields a smaller surface (codemode.md section 7).
 *
 * Pure of the DOM and of the editor's state: it imports nothing from them.
 */

/** One parameter of a self-description: a content-type field, an action or condition parameter, a filter. */
export interface SurfaceParam {
  id: string;
  /** The registry's value kind: `string`, `integer`, `decimal`, `boolean`, `enum`, `reference`, `list`, `raw`. */
  type: string;
  required: boolean;
  default?: string;
  description?: string;
  allowedValues?: string[];
  /** The declared shape of each entry of a list. */
  nested?: SurfaceParam[];
  /** The schema-declared widget; also the carrier of the formatted-text and behavior-rules vocabulary. */
  uiHint?: string;
  /** What a reference (or a typed string or list) points at. */
  referenceKind?: string;
  example?: string;
  min?: number;
  max?: number;
  unit?: string;
  /**
   * What the value is beyond its type, when the registry says (`KIND_STEPS` and the others below): a list of steps, a
   * condition, a list of conditions, the name of a flow, a mapping of arguments.
   */
  valueKind?: string;
  /** The entry binds the value as an expression at parse time: it may be an `Expr` (`${...}`). */
  expression?: boolean;
  /** One value or a list of values is accepted (a trigger filter). */
  multiple?: boolean;
  /** The context variables the steps this value holds can read beside the trigger's own. */
  scope?: SurfaceContextVar[];
}

/** The grammar kinds a parameter's `valueKind` names: schema vocabulary, the same words the wire carries. */
export const KIND_STEPS = 'steps';
export const KIND_CONDITION = 'condition';
export const KIND_CONDITIONS = 'conditions';
export const KIND_FLOW = 'flow';
export const KIND_ARGUMENTS = 'arguments';
export const KIND_INTERVAL = 'interval';
export const KIND_TIME_OF_DAY = 'time-of-day';
export const KIND_GUI_COMPONENT = 'gui-component';

/** Returns the common properties carried by every GUI layer (outside the component-specific fields). */
export function guiLayerCommonParams(surface: SurfaceDocument): SurfaceParam[] {
  const sortedTypes = [...surface.contentTypes].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  for (const type of sortedTypes) {
    for (const param of type.parameters) {
      if (param.type === 'list' && (param.nested ?? []).some((child) => child.referenceKind === KIND_GUI_COMPONENT)) {
        return (param.nested ?? []).filter((child) => child.referenceKind !== KIND_GUI_COMPONENT);
      }
    }
  }
  return [];
}

/** The self-description every registry entry carries (`SelfDescription`, api registry). */
export interface SurfaceEntry {
  id: string;
  displayName: string;
  description: string;
  activation?: string;
  sinceVersion?: string;
  registeredBy?: string;
  /** What replaces the entry, as the registry phrases it (a class F mechanic names its preset). */
  supersededBy?: string;
  parameters: SurfaceParam[];
}

/** `available` is `true` unless the server reported a reason the entry cannot run here. */
export interface SurfaceAvailability {
  available: boolean;
  unavailableReason?: string;
}

/** One variable of a context schema: a dotted path, a trailing `.*` standing for a family. */
export interface SurfaceContextVar {
  path: string;
  type: string;
  description: string;
  /** The hosts that fill it; empty means every host that fires the trigger. */
  hosts: string[];
}

export interface SurfaceContentType {
  /** The registry name (`item`): also the host name a rule set is typed with. */
  name: string;
  /** The pack directory its documents live in (`items`). */
  directory?: string;
  displayName: string;
  description: string;
  /**
   * How a resource of this type is read inside an expression: a template over `{pack}`, `{id}` and `{<field>}`.
   * Absent: an expression does not read one.
   */
  expressionForm?: string;
  /**
   * A file of this type may also hold ONE resource as its top-level fields, with the file name as its id (a flat
   * file): the server reads, lists and edits it as it does a keyed resource, so a program addresses it the same
   * way. Absent: only the keyed form.
   */
  flatFile?: true;
  /**
   * The view facet (views.md section 9): present when the type is a view kind. `viewerRule` is `opened` (a verb
   * attaches the viewer), `predicate` (every player for whom a condition holds) or `spatial`; `rerenderDefault` is
   * `on_change` or `off`. Absent: not a view kind.
   */
  view?: SurfaceViewFacet;
  parameters: SurfaceParam[];
}

/** The view facet of a content type, as the server reports it. */
export interface SurfaceViewFacet {
  viewerRule: string;
  rerenderDefault: string;
  supportsTarget: boolean;
  supportsKey: boolean;
}

export interface SurfaceAction extends SurfaceEntry, SurfaceAvailability {
  category?: string;
  shorthand?: string;
  deferred: boolean;
  takesElse: boolean;
  threading?: string;
}

export interface SurfaceCondition extends SurfaceEntry, SurfaceAvailability {
  category?: string;
  shorthand?: string;
}

export interface SurfaceTrigger extends SurfaceEntry {
  /** The hosts (content-type names) that fire it. Empty: no host fires it yet. */
  firedBy: string[];
  /** The variants of the trigger (`right`, `left`). */
  details: string[];
  filters: SurfaceParam[];
  context: SurfaceContextVar[];
  cancellable: boolean;
}

export interface SurfaceMechanic extends SurfaceEntry {
  repeatable: boolean;
}

export interface SurfaceFunction extends SurfaceEntry {
  returns: string;
  variadic: boolean;
  minArguments: number;
  maxArguments: number;
}

export interface SurfaceRecipeParam {
  name: string;
  type: string;
  required: boolean;
  default?: string | number | boolean;
  description?: string;
  min?: number;
  max?: number;
  scope?: string;
  valueType?: string;
}

export interface SurfaceRecipe {
  id: string;
  displayName: string;
  description: string;
  registeredBy?: string;
  params: SurfaceRecipeParam[];
}

export interface SurfaceSource extends SurfaceEntry {
  shorthand?: string;
  /** A list source's entry schema: the `entry.*` variables its rows carry. */
  entry: SurfaceContextVar[];
}

export interface SurfaceInputMethod extends SurfaceEntry, SurfaceAvailability {
  closesWindow: boolean;
}

/**
 * One form of the engine's closed grammar: a step kind, or a composite condition. `payload` names the parameter
 * written as the value of the form's own key; `siblings` the parameters written as keys beside it; the rest are
 * the keys of a mapping under the form's key. `registry` marks a kind whose members are a registry's entries.
 */
export interface SurfaceStepKind {
  id: string;
  displayName: string;
  description: string;
  supported: boolean;
  parameters: SurfaceParam[];
  payload?: string;
  siblings: string[];
  /** Sibling parameters of which at least one must be written. */
  oneOf: string[];
  registry?: string;
}

/** How a field names an asset: relative to the directory of its kind under `root`, with or without its extension. */
export interface SurfaceAssetReference {
  root: string;
  kindDirectories: string[];
  extension: boolean;
}

export interface SurfaceCategory {
  id: string;
  displayName: string;
}

export interface SurfaceGuiComponent extends SurfaceEntry {
  /** The render target (`raster`, `text`, `slot`). */
  target?: string;
}

export interface SurfaceGuiContainer {
  id: string;
  label: string;
  group: string;
  slotCount?: number;
}

export interface SurfaceBehavior {
  actions: SurfaceAction[];
  conditions: SurfaceCondition[];
  triggers: SurfaceTrigger[];
  mechanics: SurfaceMechanic[];
  functions: SurfaceFunction[];
  recipes: SurfaceRecipe[];
  itemSources: SurfaceSource[];
  listSources: SurfaceSource[];
  inputMethods: SurfaceInputMethod[];
  steps: SurfaceStepKind[];
  categories: SurfaceCategory[];
  /** The options any rule may carry besides `on`, `if` and `do`. */
  ruleOptions: SurfaceParam[];
  /** The options any action may carry besides its own parameters. */
  actionOptions: SurfaceParam[];
  /** The composite conditions, in the shape of a step kind. */
  conditionForms: SurfaceStepKind[];
  /** The flow keys of a rule: the condition that guards it and the steps it runs. */
  ruleBody: SurfaceParam[];
  /** The keys the step of a guarding action (`takesElse`) may carry besides the action options. */
  guardOptions: SurfaceParam[];
}

export interface SurfaceDocument {
  format: 1;
  /**
   * The capability names the connected jar advertises, or `null` for a jar that predates the list (the
   * editor then assumes the full surface, `hasCapability` in the session module).
   */
  capabilities: string[] | null;
  contentTypes: SurfaceContentType[];
  behavior: SurfaceBehavior;
  gui: { components: SurfaceGuiComponent[]; containers: SurfaceGuiContainer[] };
  /** The asset path convention, or `null` when the server does not say (an asset path is then written as given). */
  assets: SurfaceAssetReference | null;
}

type Dict = Record<string, unknown>;

function isDict(value: unknown): value is Dict {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function list(value: unknown): Dict[] {
  return Array.isArray(value) ? value.filter(isDict) : [];
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

function num(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function flag(value: unknown): boolean {
  return value === true;
}

/** The view facet of a type, or undefined for a type that is not a view kind (or a facet too malformed to read). */
function viewFacet(value: unknown): SurfaceViewFacet | undefined {
  if (!isDict(value)) return undefined;
  const viewerRule = text(value.viewerRule);
  if (viewerRule === undefined) return undefined;
  return {
    viewerRule,
    rerenderDefault: text(value.rerenderDefault) ?? 'off',
    supportsTarget: flag(value.supportsTarget),
    supportsKey: flag(value.supportsKey),
  };
}

/** A defined-only object, so a missing optional never becomes an own `undefined` property. */
function clean<T extends object>(value: T): T {
  for (const key of Object.keys(value) as (keyof T)[]) {
    if (value[key] === undefined) delete value[key];
  }
  return value;
}

function param(raw: Dict): SurfaceParam | undefined {
  const id = text(raw.id);
  const type = text(raw.type);
  if (id === undefined || type === undefined) return undefined;
  const nested = list(raw.nested).flatMap((entry) => param(entry) ?? []);
  return clean<SurfaceParam>({
    id,
    type,
    required: flag(raw.required),
    default: text(raw.default),
    description: text(raw.description),
    allowedValues: Array.isArray(raw.allowedValues) ? strings(raw.allowedValues) : undefined,
    nested: nested.length > 0 ? nested : undefined,
    uiHint: text(raw.uiHint),
    referenceKind: text(raw.referenceKind),
    example: text(raw.example),
    min: num(raw.min),
    max: num(raw.max),
    unit: text(raw.unit),
    valueKind: text(raw.valueKind),
    expression: raw.expression === true ? true : undefined,
    multiple: raw.multiple === true ? true : undefined,
    scope: Array.isArray(raw.scope) ? context(raw.scope) : undefined,
  });
}

function params(value: unknown): SurfaceParam[] {
  return list(value).flatMap((entry) => param(entry) ?? []);
}

function entry(raw: Dict): SurfaceEntry | undefined {
  const id = text(raw.id);
  if (id === undefined) return undefined;
  return clean<SurfaceEntry>({
    id,
    displayName: text(raw.displayName) ?? id,
    description: text(raw.description) ?? '',
    activation: text(raw.activation),
    sinceVersion: text(raw.sinceVersion),
    registeredBy: text(raw.registeredBy),
    supersededBy: text(raw.supersededBy),
    parameters: params(raw.parameters),
  });
}

function availability(raw: Dict): SurfaceAvailability {
  return clean<SurfaceAvailability>({
    available: raw.available !== false,
    unavailableReason: text(raw.unavailableReason),
  });
}

function context(value: unknown): SurfaceContextVar[] {
  return list(value).flatMap((raw) => {
    const path = text(raw.path);
    const type = text(raw.type);
    if (path === undefined || type === undefined) return [];
    return [{ path, type, description: text(raw.description) ?? '', hosts: strings(raw.hosts) }];
  });
}

function each<T>(value: unknown, read: (raw: Dict) => T | undefined): T[] {
  return list(value).flatMap((raw) => read(raw) ?? []);
}

/**
 * Builds the surface document from the reply of `authoring.describe` and the session's capability list.
 * `describe` is read as untrusted wire data: anything malformed is dropped, so a hostile or half-upgraded
 * server can shrink the surface and nothing else.
 */
export function assembleSurface(describe: unknown, capabilities: readonly string[] | null | undefined): SurfaceDocument {
  const root = isDict(describe) ? describe : {};
  const behavior = isDict(root.behavior) ? root.behavior : {};

  const contentTypes = each<SurfaceContentType>(root.types, (raw) => {
    const name = text(raw.name);
    if (name === undefined) return undefined;
    return clean<SurfaceContentType>({
      name,
      directory: text(raw.directory),
      displayName: text(raw.displayName) ?? name,
      description: text(raw.description) ?? '',
      expressionForm: text(raw.expressionForm),
      flatFile: raw.flatFile === true ? true : undefined,
      view: viewFacet(raw.view),
      parameters: params(raw.parameters),
    });
  });

  const actions = each<SurfaceAction>(behavior.actions, (raw) => {
    const base = entry(raw);
    if (base === undefined) return undefined;
    return clean<SurfaceAction>({
      ...base,
      ...availability(raw),
      category: text(raw.category),
      shorthand: text(raw.shorthand),
      deferred: flag(raw.deferred),
      takesElse: flag(raw.takesElse),
      threading: text(raw.threading),
    });
  });

  const conditions = each<SurfaceCondition>(behavior.conditions, (raw) => {
    const base = entry(raw);
    if (base === undefined) return undefined;
    return clean<SurfaceCondition>({
      ...base,
      ...availability(raw),
      category: text(raw.category),
      shorthand: text(raw.shorthand),
    });
  });

  const triggers = each<SurfaceTrigger>(behavior.triggers, (raw) => {
    const base = entry(raw);
    if (base === undefined) return undefined;
    return {
      ...base,
      firedBy: strings(raw.firedBy),
      details: strings(raw.details),
      filters: params(raw.filters),
      context: context(raw.context),
      cancellable: flag(raw.cancellable),
    };
  });

  const mechanics = each<SurfaceMechanic>(behavior.mechanics, (raw) => {
    const base = entry(raw);
    return base === undefined ? undefined : { ...base, repeatable: flag(raw.repeatable) };
  });

  const functions = each<SurfaceFunction>(behavior.functions, (raw) => {
    const base = entry(raw);
    if (base === undefined) return undefined;
    return {
      ...base,
      returns: text(raw.returns) ?? 'raw',
      variadic: flag(raw.variadic),
      minArguments: num(raw.minArguments) ?? 0,
      maxArguments: num(raw.maxArguments) ?? -1,
    };
  });

  const recipes = each<SurfaceRecipe>(behavior.recipes, (raw) => {
    const id = text(raw.id);
    if (id === undefined) return undefined;
    const recipeParams = list(raw.params).flatMap((p) => {
      const name = text(p.name);
      const type = text(p.type);
      if (name === undefined || type === undefined) return [];
      const fallback = p.default;
      return [
        clean<SurfaceRecipeParam>({
          name,
          type,
          required: flag(p.required),
          default:
            typeof fallback === 'string' || typeof fallback === 'number' || typeof fallback === 'boolean'
              ? fallback
              : undefined,
          description: text(p.description),
          min: num(p.min),
          max: num(p.max),
          scope: text(p.scope),
          valueType: text(p.valueType),
        }),
      ];
    });
    return clean<SurfaceRecipe>({
      id,
      displayName: text(raw.displayName) ?? id,
      description: text(raw.description) ?? '',
      registeredBy: text(raw.registeredBy),
      params: recipeParams,
    });
  });

  const source = (raw: Dict): SurfaceSource | undefined => {
    const base = entry(raw);
    if (base === undefined) return undefined;
    return clean<SurfaceSource>({ ...base, shorthand: text(raw.shorthand), entry: context(raw.entry) });
  };

  const inputMethods = each<SurfaceInputMethod>(behavior.inputMethods, (raw) => {
    const base = entry(raw);
    return base === undefined ? undefined : clean<SurfaceInputMethod>({ ...base, ...availability(raw), closesWindow: flag(raw.closesWindow) });
  });

  const form = (raw: Dict): SurfaceStepKind | undefined => {
    const id = text(raw.id);
    if (id === undefined) return undefined;
    return clean<SurfaceStepKind>({
      id,
      displayName: text(raw.displayName) ?? id,
      description: text(raw.description) ?? '',
      supported: raw.supported !== false,
      parameters: params(raw.parameters),
      payload: text(raw.payload),
      siblings: strings(raw.siblings),
      oneOf: strings(raw.oneOf),
      registry: text(raw.registry),
    });
  };
  const steps = each<SurfaceStepKind>(behavior.steps, form);

  const assetRoot = isDict(root.assetReference) ? root.assetReference : undefined;
  const assets: SurfaceAssetReference | null =
    assetRoot === undefined || text(assetRoot.root) === undefined
      ? null
      : { root: text(assetRoot.root) as string, kindDirectories: strings(assetRoot.kindDirectories), extension: assetRoot.extension !== false };

  const categories = each<SurfaceCategory>(behavior.categories, (raw) => {
    const id = text(raw.id);
    return id === undefined ? undefined : { id, displayName: text(raw.displayName) ?? id };
  });

  const components = each<SurfaceGuiComponent>(root.guiComponents, (raw) => {
    const base = entry(raw);
    return base === undefined ? undefined : clean<SurfaceGuiComponent>({ ...base, target: text(raw.target) });
  });

  const containers = each<SurfaceGuiContainer>(root.guiContainers, (raw) => {
    const id = text(raw.id);
    if (id === undefined) return undefined;
    return clean<SurfaceGuiContainer>({
      id,
      label: text(raw.label) ?? id,
      group: text(raw.group) ?? '',
      slotCount: num(raw.slotCount),
    });
  });

  return {
    format: 1,
    capabilities: Array.isArray(capabilities) ? [...capabilities] : null,
    contentTypes,
    behavior: {
      actions,
      conditions,
      triggers,
      mechanics,
      functions,
      recipes,
      itemSources: each(behavior.itemSources, source),
      listSources: each(behavior.listSources, source),
      inputMethods,
      steps,
      categories,
      ruleOptions: params(behavior.ruleOptions),
      actionOptions: params(behavior.actionOptions),
      conditionForms: each(behavior.conditionForms, form),
      ruleBody: params(behavior.ruleBody),
      guardOptions: params(behavior.guardOptions),
    },
    gui: { components, containers },
    assets,
  };
}
