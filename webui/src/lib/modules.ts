/**
 * Module derivation for module-based studios (issue 152): a resource is a
 * display card plus a collection of modules, and every module is one thing a
 * user wants. The catalog is derived — never hand-written — from the type's
 * self-description (schema parameters marked `module`, plus the mechanic
 * self-descriptions the server registers), so there is still exactly one
 * schema source per content type (§5.6, D5). Type-agnostic by design: any
 * content type that marks its fields as modules gets the same layout (issue 153).
 */
import type { TypeInfo, TypeParam } from './api';
import type { BehaviorVocabulary, MechanicEntry, MechanicInfo } from './behavior';
import { fullMechanicId, mechanicEntries } from './behavior';
import { icons, type IconName } from './components/icons';
import { isRenderable } from './studio';

export type ModuleKind = 'field' | 'mechanic' | 'rules';

export interface ModuleDescriptor {
  /** Field id, `mechanic:<full mechanic id>`, or the rules field id. */
  id: string;
  label: string;
  /** One line for the picker; the declared summary or the field description. */
  summary: string;
  /** Category id from the schema; drives the picker's chips. */
  category: string;
  icon: IconName;
  kind: ModuleKind;
  /** The schema parameter a field/rules module writes. */
  field?: TypeParam;
  /** The mechanic a mechanic module writes (already-expanded source field). */
  mechanic?: MechanicInfo;
  /** The collection field mechanic modules write into ("mechanics"). */
  sourceField?: string;
  /** Required field modules are always present and cannot be removed. */
  required?: boolean;
}

export interface DisplayFields {
  /** Rich-text display name (widget formatted-text). */
  name?: TypeParam;
  /** Rich-text lore lines (widget formatted-lines). */
  lore?: TypeParam;
  /** Texture/model slots (the display card's visual slot). */
  slots: TypeParam[];
}

/** A type uses the display + modules layout as soon as it declares one module. */
export function usesModules(schema: TypeInfo | undefined): boolean {
  return (schema?.parameters ?? []).some((field) => field.module === true && isRenderable(field));
}

const CATEGORY_LABELS: Record<string, string> = {
  identity: 'Identity',
  appearance: 'Appearance',
  gameplay: 'Gameplay',
  behaviour: 'Behaviour',
  behavior: 'Behaviour',
  placement: 'Placement',
  audio: 'Audio',
  crafting: 'Crafting',
};

const CATEGORY_ICONS: Record<string, IconName> = {
  identity: 'package',
  appearance: 'image',
  gameplay: 'sparkles',
  behaviour: 'zap',
  behavior: 'zap',
  placement: 'armchair',
  audio: 'volume-2',
  crafting: 'chef-hat',
};

export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? category;
}

export function categoryIcon(category: string): IconName {
  return CATEGORY_ICONS[category] ?? 'circle';
}

function moduleIcon(field: TypeParam): IconName {
  const named = field.icon as IconName | undefined;
  // An icon the registry does not name would render blank — fall back to the
  // category's icon instead of guessing.
  return named && named in icons ? named : categoryIcon(field.category ?? field.section ?? '');
}

function categoryOf(field: TypeParam): string {
  return field.category || field.section || 'general';
}

function summaryOf(field: TypeParam): string {
  return field.summary || field.description || '';
}

/**
 * The module catalog: one descriptor per schema-declared field module, the
 * rules module, and — for a `mechanics` source field — one per mechanic the
 * server registers (built-in or add-on). Mechanics the server does not
 * register are not offered, but authored entries still render as cards.
 */
export function moduleCatalog(
  schema: TypeInfo | undefined,
  vocabulary: BehaviorVocabulary | null,
): ModuleDescriptor[] {
  const out: ModuleDescriptor[] = [];
  for (const field of schema?.parameters ?? []) {
    if (field.module !== true || !isRenderable(field)) continue;
    const widget = field.uiHint ?? 'text';
    if (widget === 'mechanics') {
      for (const mechanic of vocabulary?.mechanics ?? []) {
        out.push({
          id: `mechanic:${mechanic.id}`,
          label: mechanic.displayName,
          summary: mechanic.description,
          category: categoryOf(field),
          icon: moduleIcon(field),
          kind: 'mechanic',
          mechanic,
          sourceField: field.id,
        });
      }
      continue;
    }
    out.push({
      id: field.id,
      label: field.label || field.id,
      summary: summaryOf(field),
      category: categoryOf(field),
      icon: moduleIcon(field),
      kind: widget === 'behavior-rules' ? 'rules' : 'field',
      field,
      required: widget === 'behavior-rules' ? false : field.required,
    });
  }
  return out;
}

/**
 * The display card's fields, derived from the same schema: the rich-text
 * name/lore controls and the texture/model slots. Fields already shown on the
 * display card are never also modules.
 */
export function displayFieldsOf(schema: TypeInfo | undefined): DisplayFields {
  const display: DisplayFields = { slots: [] };
  for (const field of schema?.parameters ?? []) {
    if (field.module === true || !isRenderable(field) || field.advanced) continue;
    const widget = field.uiHint ?? 'text';
    if (widget === 'formatted-text' && !display.name) display.name = field;
    else if (widget === 'formatted-lines' && !display.lore) display.lore = field;
    else if (widget === 'texture' || widget === 'model') display.slots.push(field);
  }
  return display;
}

/**
 * Renderable, non-module fields that are neither display-card fields nor
 * advanced — a type may describe more than the display card holds. They keep
 * their classic labelled controls under the modules.
 */
export function extraFieldsOf(schema: TypeInfo | undefined): TypeParam[] {
  const display = displayFieldsOf(schema);
  const displayIds = new Set(
    [display.name?.id, display.lore?.id, ...display.slots.map((slot) => slot.id)].filter(
      (id): id is string => Boolean(id),
    ),
  );
  return (schema?.parameters ?? []).filter((field) =>
    field.module !== true &&
    !field.advanced &&
    isRenderable(field) &&
    !displayIds.has(field.id) &&
    (field.uiHint ?? 'text') !== 'mechanics' &&
    (field.uiHint ?? 'text') !== 'behavior-rules');
}

/** Advanced fields stay reachable through one disclosure (the issue's Advanced path). */
export function advancedFieldsOf(schema: TypeInfo | undefined): TypeParam[] {
  return (schema?.parameters ?? []).filter((field) =>
    field.advanced === true &&
    isRenderable(field) &&
    (field.uiHint ?? 'text') !== 'mechanics' &&
    (field.uiHint ?? 'text') !== 'behavior-rules');
}

export interface ModulePresence {
  /** The module's value is in the resource (or staged as an edit). */
  present: boolean;
  /** Mechanic modules: the authored entries writing this mechanic. */
  entries: MechanicEntry[];
}

/**
 * Whether a module is on the resource, read from the draft's values the same
 * way the save will write them: a field module is present when its key exists
 * (a pending removal edit hides the card and stages the lossless removal), a
 * mechanic module when an authored entry names it, the rules module when the
 * behavior list has any rule.
 */
export function presenceOf(
  descriptor: ModuleDescriptor,
  values: Record<string, unknown>,
): ModulePresence {
  if (descriptor.kind === 'mechanic') {
    const entries = mechanicEntries(values[descriptor.sourceField ?? 'mechanics'])
      .filter((entry) => entry.name && fullMechanicId(entry.name) === descriptor.mechanic?.id);
    return { present: entries.length > 0, entries };
  }
  if (descriptor.kind === 'rules') {
    const rules = values[descriptor.id];
    return { present: Array.isArray(rules) && rules.length > 0, entries: [] };
  }
  return { present: Object.hasOwn(values, descriptor.id), entries: [] };
}

/**
 * The value an "Add" writes for a field module: the schema's declared default,
 * else the first allowed value. Modules without a value to write (a reference
 * the author has not chosen yet) are opened as empty cards instead — the
 * write happens when the author sets a value, never as a placeholder.
 */
export function defaultFor(field: TypeParam): unknown {
  // A switch module is "turn this on": adding it writes true, whatever the
  // schema's resting default (false) means for the key being absent.
  if (field.type === 'boolean') return true;
  if (field.default !== undefined && field.default !== '') {
    return defaultValue(field);
  }
  if (field.allowedValues?.length) return field.allowedValues[0];
  return undefined;
}

function defaultValue(field: TypeParam): unknown {
  const fallback = field.default ?? '';
  if (field.type === 'integer' || field.type === 'decimal') return Number(fallback) || 0;
  if (field.type === 'boolean') return fallback === 'true';
  return fallback;
}

/** Picker categories in reading order; only categories that have modules. */
export function categoriesOf(catalog: ModuleDescriptor[]): string[] {
  const order = ['identity', 'appearance', 'placement', 'crafting', 'audio', 'gameplay', 'behaviour'];
  const present = new Set(catalog.map((module) => module.category));
  return [...order.filter((category) => present.has(category)),
    ...[...present].filter((category) => !order.includes(category)).sort()];
}

/** A module the resource has through its template and does not set itself. */
export interface InheritedModule {
  key: string;
  label: string;
  icon: IconName;
  /** Full id of the template that supplies it. */
  source: string;
  /** One line of what it holds. */
  summary: string;
  /** The field "Override here" writes, and the value it copies into the resource. */
  field: string;
  value: unknown;
}

function brief(value: unknown): string {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return `${value.length} entr${value.length === 1 ? 'y' : 'ies'}`;
  if (value && typeof value === 'object') return Object.keys(value).join(', ') || 'set';
  return String(value);
}

/**
 * The modules a resource gets from its template (issue 154): every module whose
 * field the resource itself does not set but the template chain supplies. They
 * are shown, so inheritance is visible as modules rather than only as a line
 * under a field; "Override here" copies the inherited value into the resource.
 * Mechanics are inherited as the whole `mechanics` list (the merge is shallow),
 * one entry per ability so each reads as its own module.
 */
export function inheritedModules(
  catalog: ModuleDescriptor[],
  values: Record<string, unknown>,
  inherited: Record<string, unknown>,
  sources: Record<string, string>,
): InheritedModule[] {
  const out: InheritedModule[] = [];
  for (const descriptor of catalog) {
    if (descriptor.kind === 'mechanic') {
      const source = descriptor.sourceField ?? 'mechanics';
      if (Object.hasOwn(values, source) || !Array.isArray(inherited[source])) continue;
      for (const entry of mechanicEntries(inherited[source])) {
        if (!entry.name || fullMechanicId(entry.name) !== descriptor.mechanic?.id) continue;
        const params = Object.entries(entry.params).map(([key, value]) => `${key} ${brief(value)}`).join(', ');
        out.push({
          key: `${descriptor.id}#${entry.index}`,
          label: descriptor.label,
          icon: descriptor.icon,
          source: sources[source] ?? '',
          summary: params || 'no settings',
          field: source,
          value: inherited[source],
        });
      }
      continue;
    }
    const field = descriptor.field?.id;
    if (!field || Object.hasOwn(values, field) || !Object.hasOwn(inherited, field)) continue;
    const value = inherited[field];
    if (descriptor.kind === 'rules' && !(Array.isArray(value) && value.length > 0)) continue;
    out.push({
      key: descriptor.id,
      label: descriptor.label,
      icon: descriptor.icon,
      source: sources[field] ?? '',
      summary: brief(value),
      field,
      value,
    });
  }
  return out;
}
