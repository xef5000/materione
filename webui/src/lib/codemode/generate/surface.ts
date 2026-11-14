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
}

/** The self-description every registry entry carries (`SelfDescription`, api registry). */
export interface SurfaceEntry {
  id: string;
  displayName: string;
  description: string;
  activation?: string;
  sinceVersion?: string;
  registeredBy?: string;
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
  parameters: SurfaceParam[];
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

export interface SurfaceStepKind {
  id: string;
  displayName: string;
  description: string;
  supported: boolean;
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

  const steps = each<SurfaceStepKind>(behavior.steps, (raw) => {
    const id = text(raw.id);
    if (id === undefined) return undefined;
    return {
      id,
      displayName: text(raw.displayName) ?? id,
      description: text(raw.description) ?? '',
      supported: raw.supported !== false,
    };
  });

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
    },
    gui: { components, containers },
  };
}
