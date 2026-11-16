/**
 * The SDK generator (codemode.md section 3.1): a pure function from one surface document to the
 * TypeScript declaration text that types the code-mode SDK for the connected server.
 *
 * It reads only self-description (D5, master section 5.6). It holds no list of content types, actions,
 * conditions, mechanics or triggers: every member it emits is a registry entry, so an entry an addon
 * registers becomes SDK surface with no change here (codemode.md section 3.6). A ban test keeps it so.
 * What it does know is the *schema vocabulary*, which is not registry content: the value kinds of a
 * parameter (`string`, `integer`, ...), the widget hints that carry meaning (`formatted-text`,
 * `behavior-rules`, `input-method`), and the two reference kinds that name a polymorphic registry
 * (`mechanic`, `gui-component`). The engine's closed grammar (the step kinds, the composite conditions, the flow
 * keys of a rule) is not known here either: it is read from the surface document like a registry (issue 355).
 *
 * The output is a global script (no top-level import or export) that merges into the fixed core's empty
 * interfaces (`core.d.ts`). It is deterministic: entries are sorted by id, parameters keep the order the
 * registry gives them, line endings are LF.
 *
 * Pure of the DOM and of the editor's state: it imports only the surface types.
 */
import type {
  SurfaceAction,
  SurfaceCondition,
  SurfaceContextVar,
  SurfaceDocument,
  SurfaceEntry,
  SurfaceFunction,
  SurfaceMechanic,
  SurfaceParam,
  SurfaceRecipe,
  SurfaceSource,
  SurfaceStepKind,
  SurfaceTrigger,
} from './surface';
import {
  guiLayerCommonParams,
  KIND_ARGUMENTS,
  KIND_CONDITION,
  KIND_CONDITIONS,
  KIND_FLOW,
  KIND_GUI_COMPONENT,
  KIND_STEPS,
} from './surface';
import { CAPABILITY_LOGIC, CAPABILITY_TRANSACTION, capabilityOn } from './capabilities';
import { contextFor, contextTree, type ContextNode } from './context';
import { camel, compare, memberNames, pascal, refMembers, sortedBy, writerMembers } from './names';

export { camel, pascal };

/** Enums with more members than this get a named alias instead of an inline union. */
const INLINE_ENUM_LIMIT = 16;

/** The widget hints that carry meaning in the schema vocabulary (codemode.md section 3.1). */
const HINT_FORMATTED_TEXT = 'formatted-text';
const HINT_FORMATTED_LINES = 'formatted-lines';
const HINT_RULES = 'behavior-rules';
const HINT_INPUT_METHOD = 'input-method';
/** The reference kinds that name a polymorphic registry rather than a resource or an asset. */
const KIND_MECHANIC = 'mechanic';
const KIND_ITEM_SOURCE = 'item-source';
const KIND_LIST_SOURCE = 'list-source';

/**
 * The words JavaScript reserves, which a parameter of a generated signature cannot be named. They are language
 * vocabulary, kept as one string so no word of it reads as a registry id to the ban test (some do: `if` is a
 * step, `break` a trigger).
 */
const RESERVED = new Set(
  ('break case catch class const continue debugger default delete do else enum export extends false finally for ' +
    'function if import in instanceof new null return super switch this throw true try typeof var void while with ' +
    'yield let static implements interface package private protected public await async').split(' '),
);

// ---- naming -----------------------------------------------------------------------------------------

function isIdentifier(name: string): boolean {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name);
}

/** A property key: bare when it is an identifier (a stored key such as `deposit-command` is quoted). */
function key(name: string): string {
  return isIdentifier(name) ? name : JSON.stringify(name);
}

/** A parameter name of a function signature: always an identifier, never a reserved word. */
function paramName(name: string): string {
  const id = camel(name);
  return RESERVED.has(id) ? `${id}_` : id;
}

function literal(value: string): string {
  return JSON.stringify(value);
}

function union(values: readonly string[]): string {
  return values.length === 0 ? 'never' : values.map(literal).join(' | ');
}

// ---- documentation ----------------------------------------------------------------------------------

function oneLine(text: string): string {
  return text.replace(/\*\//g, '*\\/').replace(/\s*\r?\n\s*/g, ' ').trim();
}

function indentOf(level: number): string {
  return '  '.repeat(level);
}

/** A TSDoc block at an indent level; nothing when there is nothing to say. */
function doc(lines: readonly (string | undefined)[], level: number): string[] {
  const body = lines.flatMap((line) => (line === undefined || oneLine(line) === '' ? [] : [oneLine(line)]));
  const pad = indentOf(level);
  if (body.length === 0) return [];
  if (body.length === 1) return [`${pad}/** ${body[0]} */`];
  return [`${pad}/**`, ...body.map((line) => `${pad} * ${line}`), `${pad} */`];
}

function sentence(text: string | undefined): string | undefined {
  if (text === undefined) return undefined;
  const trimmed = oneLine(text);
  if (trimmed === '') return undefined;
  return /[.!?:)]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

function paramDoc(param: SurfaceParam): string[] {
  const lines: (string | undefined)[] = [sentence(param.description)];
  if (param.min !== undefined && param.max !== undefined) lines.push(`Range: ${param.min} to ${param.max}${param.unit ? ` ${param.unit}` : ''}.`);
  else if (param.min !== undefined) lines.push(`Minimum: ${param.min}${param.unit ? ` ${param.unit}` : ''}.`);
  else if (param.max !== undefined) lines.push(`Maximum: ${param.max}${param.unit ? ` ${param.unit}` : ''}.`);
  else if (param.unit) lines.push(`Unit: ${param.unit}.`);
  if (param.expression === true) lines.push('Accepts an expression: `m.expr` or an `m.fn` result.');
  if (param.multiple === true) lines.push('One value or a list of values; a list matches any of them.');
  if (param.scope !== undefined && param.scope.length > 0) {
    lines.push(`Inside these steps: ${param.scope.map((variable) => `\`${variable.path}\` (${variable.type})${variable.description ? `, ${oneLine(variable.description).toLowerCase()}` : ''}`).join('; ')}.`);
  }
  if (param.default !== undefined && param.default !== '') lines.push(`@defaultValue ${param.default}`);
  if (param.example !== undefined) lines.push(`@example ${param.example}`);
  return lines.flatMap((line) => (line === undefined ? [] : [line]));
}

function entryDoc(entry: SurfaceEntry, extra: readonly (string | undefined)[] = [], unavailable?: string): (string | undefined)[] {
  return [
    sentence(entry.description) ?? sentence(entry.displayName),
    entry.activation ? `Activation: ${sentence(entry.activation)}` : undefined,
    // a fact about the server recorded, not a deprecation: a missing provider is a configuration, not a verdict
    unavailable === undefined ? undefined : `Unavailable on the connected server: ${sentence(unavailable)}`,
    // the registry's own word for what replaces the entry (a class F mechanic): it still loads and runs
    entry.supersededBy ? `Superseded by: ${sentence(entry.supersededBy)}` : undefined,
    ...extra,
    entry.sinceVersion ? `@since ${entry.sinceVersion}` : undefined,
    entry.registeredBy ? `@registeredBy ${entry.registeredBy}` : undefined,
  ];
}

// ---- generation context -----------------------------------------------------------------------------

interface Context {
  surface: SurfaceDocument;
  /** The content-type names: a string field whose hint names one accepts a `Ref` to it. */
  typeNames: ReadonlySet<string>;
  hasMechanics: boolean;
  hasGuiComponents: boolean;
  hasInputMethods: boolean;
  hasItemSources: boolean;
  hasListSources: boolean;
  /** Named unions extracted from large enums, in the order they were met. */
  aliases: string[];
}

function scalarType(kind: string): string {
  switch (kind) {
    case 'integer':
    case 'decimal':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'string':
      return 'string';
    default:
      return 'unknown';
  }
}

function nestedLayerKind(param: SurfaceParam): boolean {
  return (param.nested ?? []).some((child) => child.referenceKind === KIND_GUI_COMPONENT);
}

/** The type of a value whose grammar kind the registry names, or undefined when it names none the generator maps. */
function typeOfKind(kind: string | undefined): string | undefined {
  switch (kind) {
    case KIND_STEPS:
      return 'readonly Step[]';
    case KIND_CONDITION:
      return 'ConditionValue';
    case KIND_CONDITIONS:
      return 'readonly ConditionValue[]';
    case KIND_FLOW:
      return 'string';
    case KIND_ARGUMENTS:
      return '{ readonly [name: string]: JsonValue | Expr }';
    default:
      return undefined;
  }
}

/**
 * The TypeScript type of one parameter (the mapping table of codemode.md section 3.1). `owner` names the
 * enclosing declaration, so a large enum alias is named after the field it belongs to. The value's own type
 * comes first; the registry's markers then widen it: a value that accepts an expression also takes an `Expr`,
 * a value that accepts a list also takes an array of itself.
 */
function typeOfParam(param: SurfaceParam, owner: string, level: number, ctx: Context): string {
  const kind = typeOfKind(param.valueKind);
  let type = kind ?? baseTypeOfParam(param, owner, level, ctx);
  if (param.expression === true && kind === undefined) type = `${type} | Expr`;
  if (param.multiple === true && kind === undefined) type = `${type} | readonly (${type})[]`;
  return type;
}

function baseTypeOfParam(param: SurfaceParam, owner: string, level: number, ctx: Context): string {
  switch (param.type) {
    case 'enum': {
      const values = param.allowedValues ?? [];
      if (values.length === 0) return 'string';
      if (values.length <= INLINE_ENUM_LIMIT) return union(values);
      const name = `${owner}${pascal(param.id)}`;
      ctx.aliases.push(...doc([`The values \`${owner}.${param.id}\` accepts.`], 0), `type ${name} = ${union(values)};`);
      return name;
    }
    case 'integer':
    case 'decimal':
    case 'boolean':
      return scalarType(param.type);
    case 'reference':
      if (param.referenceKind !== undefined && !ctx.typeNames.has(param.referenceKind) && param.uiHint !== undefined && ctx.typeNames.has(param.uiHint)) {
        return `Ref<${literal(param.uiHint)}>`;
      }
      return param.referenceKind === undefined ? 'Ref' : `Ref<${literal(param.referenceKind)}>`;
    case 'string': {
      if (param.uiHint === HINT_FORMATTED_TEXT) return 'MiniMessage';
      if (param.uiHint === HINT_INPUT_METHOD && ctx.hasInputMethods) return 'InputMethodId';
      if (param.referenceKind === KIND_ITEM_SOURCE) {
        const base = param.uiHint !== undefined && ctx.typeNames.has(param.uiHint) ? `string | Ref<${literal(param.uiHint)}>` : 'string';
        return ctx.hasItemSources ? `${base} | ItemSourceSpec` : base;
      }
      if (param.uiHint !== undefined && ctx.typeNames.has(param.uiHint)) return `string | Ref<${literal(param.uiHint)}>`;
      if (param.referenceKind !== undefined) return `string | Ref<${literal(param.referenceKind)}>`;
      return 'string';
    }
    case 'list': {
      if (param.uiHint === HINT_FORMATTED_LINES) return 'MiniMessage[]';
      if (param.referenceKind === KIND_MECHANIC && ctx.hasMechanics) return 'MechanicEntry[]';
      if (nestedLayerKind(param) && ctx.hasGuiComponents) return 'GuiLayerEntry[]';
      if (param.nested !== undefined && param.nested.length > 0) {
        const lines = propertyLines(param.nested, `${owner}${pascal(param.id)}`, level + 1, ctx);
        return `Array<{\n${lines.join('\n')}\n${indentOf(level)}}>`;
      }
      return 'JsonValue[]';
    }
    case 'raw':
      if (param.referenceKind === KIND_LIST_SOURCE && ctx.hasListSources) return 'JsonValue | ListSourceSpec';
      return 'JsonValue';
    default:
      return 'unknown';
  }
}

/** The property lines of a parameter set: TSDoc, key, optionality and type. */
function propertyLines(params: readonly SurfaceParam[], owner: string, level: number, ctx: Context): string[] {
  const pad = indentOf(level);
  return params.flatMap((param) => [
    ...doc(paramDoc(param), level),
    `${pad}${key(param.id)}${param.required ? '' : '?'}: ${typeOfParam(param, owner, level, ctx)};`,
  ]);
}

function allParams(surface: SurfaceDocument): SurfaceParam[] {
  const b = surface.behavior;
  return [
    ...surface.contentTypes.flatMap((type) => type.parameters),
    ...b.actions.flatMap((entry) => entry.parameters),
    ...b.conditions.flatMap((entry) => entry.parameters),
    ...b.triggers.flatMap((entry) => [...entry.parameters, ...entry.filters]),
    ...b.mechanics.flatMap((entry) => entry.parameters),
    ...b.functions.flatMap((entry) => entry.parameters),
    ...b.itemSources.flatMap((entry) => entry.parameters),
    ...b.listSources.flatMap((entry) => entry.parameters),
    ...b.inputMethods.flatMap((entry) => entry.parameters),
    ...b.ruleOptions,
    ...b.actionOptions,
    ...b.ruleBody,
    ...b.guardOptions,
    ...b.steps.flatMap((entry) => entry.parameters),
    ...b.conditionForms.flatMap((entry) => entry.parameters),
    ...surface.gui.components.flatMap((entry) => entry.parameters),
  ];
}

function walkParams(params: readonly SurfaceParam[], visit: (param: SurfaceParam) => void): void {
  for (const param of params) {
    visit(param);
    if (param.nested !== undefined) walkParams(param.nested, visit);
  }
}

// ---- builders: the shape every polymorphic entry shares ---------------------------------------------

interface BuilderInput {
  /** The member name in its catalog. */
  member: string;
  /** The params interface name. */
  paramsName: string;
  params: readonly SurfaceParam[];
  /** The interface(s) the params interface extends. */
  extendsTypes: readonly string[];
  /** The bare-scalar form: the parameter a lone value fills. */
  shorthand?: SurfaceParam;
  /** The returned type. */
  returns: string;
  docLines: readonly (string | undefined)[];
  /** Whether the shorthand call also takes the common options. */
  shorthandOptions?: string;
}

function paramsInterface(input: BuilderInput, ctx: Context): string[] {
  const heritage = input.extendsTypes.length > 0 ? ` extends ${input.extendsTypes.join(', ')}` : '';
  const body = propertyLines(input.params, input.paramsName, 1, ctx);
  return [`interface ${input.paramsName}${heritage} {`, ...body, '}'];
}

function builderSignature(input: BuilderInput, ctx: Context): string[] {
  const allOptional = input.params.every((param) => !param.required);
  const others = input.params.filter((param) => input.shorthand === undefined || param.id !== input.shorthand.id);
  const shorthandOk = input.shorthand !== undefined && others.every((param) => !param.required);
  const lines = doc(input.docLines, 1);
  let first = input.paramsName;
  let rest = '';
  if (shorthandOk && input.shorthand !== undefined) {
    first = `${input.paramsName} | ${typeOfParam(input.shorthand, input.paramsName, 1, ctx)}`;
    if (input.shorthandOptions !== undefined) rest = `, options?: ${input.shorthandOptions}`;
  }
  const optional = allOptional ? '?' : '';
  return [...lines, `  ${key(input.member)}(params${optional}: ${first}${rest}): ${input.returns};`];
}

function catalogInterface(name: string, signatures: readonly string[][]): string[] {
  if (signatures.length === 0) return [];
  return [`interface ${name} {`, ...signatures.flat(), '}'];
}

// ---- sections ---------------------------------------------------------------------------------------

function header(surface: SurfaceDocument): string[] {
  const b = surface.behavior;
  return [
    '// Generated by the Materione SDK generator (webui/src/lib/codemode/generate). Do not edit.',
    '// One surface document in, these declarations out; they merge into the fixed core (core.d.ts).',
    `// Surface: ${surface.contentTypes.length} content types, ${b.actions.length} actions, ${b.conditions.length} conditions, ` +
      `${b.triggers.length} triggers, ${b.mechanics.length} mechanics, ${b.functions.length} functions, ` +
      `${b.recipes.length} recipes, ${surface.gui.components.length} GUI components.`,
  ];
}

function idUnions(ctx: Context): string[] {
  const { surface } = ctx;
  const b = surface.behavior;
  const kinds = new Set<string>(surface.contentTypes.map((type) => type.name));
  walkParams(allParams(surface), (param) => {
    if (param.referenceKind !== undefined) kinds.add(param.referenceKind);
  });
  const ids = (entries: readonly { id: string }[]): string[] => sortedBy(entries, (entry) => entry.id).map((entry) => entry.id);
  const lines: string[] = [];
  const add = (name: string, description: string, values: readonly string[]): void => {
    lines.push(...doc([description], 0), `type ${name} = ${union(values)};`);
  };
  lines.push(
    ...doc(['A capability the connected plugin advertises (`m.hasCapability`); any string when the plugin predates the list.'], 0),
    surface.capabilities === null ? 'type Capability = string;' : `type Capability = ${union([...surface.capabilities].sort(compare))};`,
  );
  add('ContentTypeName', 'The content types this server registers.', sortedBy(surface.contentTypes, (type) => type.name).map((type) => type.name));
  add('RefKind', 'What a `Ref` can point at: a content type, or an asset kind a field names.', [...kinds].sort(compare));
  add('ActionId', 'The action ids this server registers.', ids(b.actions));
  add('ConditionId', 'The condition ids this server registers.', ids(b.conditions));
  add('MechanicId', 'The mechanic ids this server registers.', ids(b.mechanics));
  add('TriggerId', 'The trigger ids this server registers.', ids(b.triggers));
  add('FunctionId', 'The expression functions this server registers.', ids(b.functions));
  add('RecipeId', 'The logic recipes this server registers.', ids(b.recipes));
  add('GuiComponentId', 'The GUI components this server registers.', ids(surface.gui.components));
  add('ItemSourceId', 'The item sources this server registers.', ids(b.itemSources));
  add('ListSourceId', 'The list sources this server registers.', ids(b.listSources));
  add('InputMethodId', 'The input methods a `prompt` can use on this server.', ids(b.inputMethods));
  add('StepKindId', 'The step kinds of the flow model this server supports.', sortedBy(b.steps.filter((step) => step.supported), (step) => step.id).map((step) => step.id));
  return lines;
}

function hasRules(type: SurfaceDocument['contentTypes'][number]): boolean {
  return type.parameters.some((param) => param.uiHint === HINT_RULES);
}

function contentTypes(ctx: Context): string[] {
  const types = sortedBy(ctx.surface.contentTypes, (type) => type.name);
  const lines: string[] = [];
  const writerNames = writerMembers(types);
  const refNames = refMembers(types);
  const writers: string[][] = [];
  const refs: string[][] = [];
  // a jar without these capabilities has no such members, in the types and at run time alike (codemode.md section 7)
  const writable = capabilityOn(ctx.surface.capabilities, CAPABILITY_TRANSACTION);
  const withLogic = capabilityOn(ctx.surface.capabilities, CAPABILITY_LOGIC);
  for (const type of types) {
    const base = pascal(type.name);
    const fields = `${base}Fields`;
    const handle = `${base}Handle`;
    const authored = type.parameters.filter((param) => param.uiHint !== HINT_RULES);
    lines.push(
      '',
      ...doc([sentence(type.description) ?? sentence(type.displayName), `Content type \`${type.name}\`${type.directory ? `, stored under \`${type.directory}/\`` : ''}.`], 0),
      `interface ${fields} {`,
      ...propertyLines(authored, fields, 1, ctx),
      '}',
    );
    if (hasRules(type) && withLogic) {
      lines.push(
        '',
        ...doc([`The handle \`create\` returns for a \`${type.name}\`: a \`Ref<${literal(type.name)}>\` that also holds its rules.`], 0),
        `interface ${handle} extends ResourceHandle<${literal(type.name)}, ${fields}> {`,
        ...doc([`The rules of this ${type.name}; \`add\` takes the trigger first and types the rest from it.`], 1),
        `  readonly rules: RuleSet<${literal(type.name)}>;`,
        '}',
      );
    } else {
      lines.push('', `interface ${handle} extends ResourceHandle<${literal(type.name)}, ${fields}> {}`);
    }
    const directory = type.directory ?? type.name;
    writers.push([
      ...doc([`Creates a \`${type.name}\` in this pack.`, `Documents live under \`${directory}/\`.`], 1),
      `  readonly ${key(writerNames.get(directory) ?? directory)}: Collection<${literal(type.name)}, ${fields}, ${handle}>;`,
    ]);
    refs.push([
      ...doc([`A typed reference to an existing \`${type.name}\` by its id (\`pack:id\`); resolved against the stored state when the program runs.`], 1),
      `  ${key(refNames.get(type.name) ?? type.name)}(id: string): Ref<${literal(type.name)}>;`,
    ]);
  }
  if (types.length > 0) {
    if (writable) lines.push('', ...catalogInterface('PackWriter', writers));
    lines.push('', ...catalogInterface('RefFactory', refs));
    // the maps `m.read.get` and `m.edit` type their results with: a content type name to its record and its handle
    lines.push(
      '',
      ...doc(['The record type of each content type, by name: what `m.read.get` returns for a `Ref` of that kind.'], 0),
      'interface ContentTypeFields {',
      ...types.map((type) => `  ${key(type.name)}: ${pascal(type.name)}Fields;`),
      '}',
    );
    if (writable) {
      lines.push(
        '',
        ...doc(['The handle type of each content type, by name: what `m.edit` returns for a `Ref` of that kind.'], 0),
        'interface ContentTypeHandles {',
        ...types.map((type) => `  ${key(type.name)}: ${pascal(type.name)}Handle;`),
        '}',
      );
    }
  }
  return lines;
}

function actions(ctx: Context): string[] {
  const { surface } = ctx;
  const b = surface.behavior;
  const lines: string[] = [];
  lines.push(
    '',
    ...doc(['The options any action may carry besides its own parameters.'], 0),
    'interface ActionOptions {',
    ...propertyLines(b.actionOptions, 'ActionOptions', 1, ctx),
    '}',
  );
  const guarded = b.guardOptions.length > 0;
  if (guarded) {
    lines.push(
      '',
      ...doc(['What the step of an action that guards what follows may carry besides the action options.'], 0),
      'interface GuardOptions {',
      ...propertyLines(b.guardOptions, 'GuardOptions', 1, ctx),
      '}',
    );
  }
  const entries = sortedBy(b.actions, (action) => action.id);
  const names = memberNames(entries.map((action) => action.id));
  const signatures: string[][] = [];
  for (const action of entries) {
    const member = names.get(action.id) ?? camel(action.id);
    const paramsName = `${pascal(member)}ActionParams`;
    const guards = guarded && action.takesElse;
    const input: BuilderInput = {
      member,
      paramsName,
      params: action.parameters,
      extendsTypes: guards ? ['ActionOptions', 'GuardOptions'] : ['ActionOptions'],
      shorthand: shorthandOf(action),
      shorthandOptions: guards ? 'ActionOptions & GuardOptions' : 'ActionOptions',
      returns: `ActionStep<${literal(action.id)}>`,
      docLines: [
        ...entryDoc(action, [action.takesElse ? 'It guards what follows: when it fails or goes unanswered the flow stops, after the steps its guard options name.' : undefined],
          action.available ? undefined : (action.unavailableReason ?? 'not available')),
        action.category ? `@category ${action.category}` : undefined,
      ],
    };
    lines.push('', ...doc([`Parameters of \`${action.id}\`.`], 0), ...paramsInterface(input, ctx));
    signatures.push(builderSignature(input, ctx));
  }
  lines.push('', ...catalogInterface('ActionCatalog', signatures));
  return lines;
}

// ---- the closed grammar: step kinds and composite conditions, from the surface like any registry ----

/**
 * The signature of one grammar form. A form with a `payload` takes that parameter's value first and its sibling
 * keys as a mapping after it; a form without one takes a mapping of its parameters. `oneOf` makes the mapping a
 * union in which each named key is required in turn.
 */
function formSignature(form: SurfaceStepKind, member: string, returns: string, owner: string, ctx: Context): string[] {
  const byId = new Map(form.parameters.map((param) => [param.id, param]));
  const payload = form.payload === undefined ? undefined : byId.get(form.payload);
  const members = (payload === undefined ? form.parameters : form.siblings.flatMap((id) => byId.get(id) ?? []));
  const object = (required: ReadonlySet<string>): string => {
    const props = members.map((param) => `readonly ${key(param.id)}${param.required || required.has(param.id) ? '' : '?'}: ${typeOfParam(param, owner, 2, ctx)}`);
    return `{ ${props.join('; ')} }`;
  };
  const oneOf = form.oneOf.filter((id) => members.some((param) => param.id === id));
  const optionsType = oneOf.length > 0 ? oneOf.map((id) => object(new Set([id]))).join(' | ') : object(new Set());
  const optional = oneOf.length === 0 && members.every((param) => !param.required);
  const memberDocs = members.map((param) => (param.description ? `\`${param.id}\`: ${sentence(param.description)}` : undefined));
  const args: string[] = [];
  if (payload !== undefined) args.push(`value: ${typeOfParam(payload, owner, 2, ctx)}`);
  if (members.length > 0) args.push(`options${optional ? '?' : ''}: ${optionsType}`);
  return [
    ...doc([
      sentence(form.description) ?? sentence(form.displayName),
      payload === undefined ? undefined : `@param value ${paramDoc(payload).join(' ')}`,
      memberDocs.some((line) => line !== undefined) ? `@param options ${memberDocs.flatMap((line) => (line === undefined ? [] : [line])).join(' ')}` : undefined,
    ], 1),
    `  ${key(member)}(${args.join(', ')}): ${returns};`,
  ];
}

/** The forms a builder is generated for: the ones the engine runs, written by their own key (not drawn from a registry). */
function ownForms(forms: readonly SurfaceStepKind[]): SurfaceStepKind[] {
  return sortedBy(forms.filter((form) => form.supported && form.registry === undefined), (form) => form.id);
}

function stepForms(ctx: Context): string[] {
  const forms = ownForms(ctx.surface.behavior.steps);
  if (forms.length === 0) return [];
  const signatures = forms.map((form) => formSignature(form, camel(form.id), 'Step', `${pascal(camel(form.id))}Step`, ctx));
  return ['', ...doc(['The structural steps of the flow model: the engine\'s closed set, as the server describes it.'], 0), ...catalogInterface('StepBuilders', signatures)];
}

function shorthandOf(entry: { shorthand?: string; parameters: readonly SurfaceParam[] }): SurfaceParam | undefined {
  if (entry.shorthand === undefined) return undefined;
  return entry.parameters.find((param) => param.id === entry.shorthand);
}

function conditions(ctx: Context): string[] {
  const entries = sortedBy(ctx.surface.behavior.conditions, (condition: SurfaceCondition) => condition.id);
  const names = memberNames(entries.map((condition) => condition.id));
  const lines: string[] = [];
  const signatures: string[][] = [];
  for (const condition of entries) {
    const member = names.get(condition.id) ?? camel(condition.id);
    const input: BuilderInput = {
      member,
      paramsName: `${pascal(member)}ConditionParams`,
      params: condition.parameters,
      extendsTypes: [],
      shorthand: shorthandOf(condition),
      returns: `Condition<${literal(condition.id)}>`,
      docLines: [
        ...entryDoc(condition, [], condition.available ? undefined : (condition.unavailableReason ?? 'not available')),
        condition.category ? `@category ${condition.category}` : undefined,
      ],
    };
    lines.push('', ...doc([`Parameters of the \`${condition.id}\` condition.`], 0), ...paramsInterface(input, ctx));
    signatures.push(builderSignature(input, ctx));
  }
  // the composite conditions are members of the same catalog: a registered condition of the same name keeps its own
  const taken = new Set(entries.map((condition) => names.get(condition.id) ?? camel(condition.id)));
  for (const form of ownForms(ctx.surface.behavior.conditionForms)) {
    if (taken.has(camel(form.id))) continue;
    signatures.push(formSignature(form, camel(form.id), `Condition<${literal(form.id)}>`, `${pascal(camel(form.id))}Condition`, ctx));
  }
  if (signatures.length > 0) lines.push('', ...catalogInterface('ConditionCatalog', signatures));
  return lines;
}

function mechanics(ctx: Context): string[] {
  const entries = sortedBy(ctx.surface.behavior.mechanics, (mechanic: SurfaceMechanic) => mechanic.id);
  const names = memberNames(entries.map((mechanic) => mechanic.id));
  const lines: string[] = [];
  const signatures: string[][] = [];
  for (const mechanic of entries) {
    const member = names.get(mechanic.id) ?? camel(mechanic.id);
    const input: BuilderInput = {
      member,
      paramsName: `${pascal(member)}MechanicParams`,
      params: mechanic.parameters,
      extendsTypes: [],
      returns: `MechanicEntry<${literal(mechanic.id)}>`,
      docLines: entryDoc(mechanic, [mechanic.repeatable ? 'May appear more than once on one item.' : 'At most once per item.']),
    };
    lines.push('', ...doc([`Parameters of the \`${mechanic.id}\` mechanic.`], 0), ...paramsInterface(input, ctx));
    signatures.push(builderSignature(input, ctx));
  }
  if (signatures.length > 0) lines.push('', ...catalogInterface('MechanicCatalog', signatures));
  return lines;
}

// ---- context schemas --------------------------------------------------------------------------------

function contextValueType(variable: SurfaceContextVar): string {
  return `ContextValue<${scalarType(variable.type)}>`;
}

function contextNodeType(node: ContextNode, level: number): string {
  const parts: string[] = [];
  if (node.leaf !== undefined) parts.push(contextValueType(node.leaf));
  if (node.wildcard !== undefined) parts.push(`{ readonly [name: string]: ${contextValueType(node.wildcard)} }`);
  if (node.children.size > 0) {
    const pad = indentOf(level + 1);
    const lines = [...node.children.entries()].flatMap(([name, child]) => [
      ...doc([child.leaf === undefined ? undefined : sentence(child.leaf.description)], level + 1),
      `${pad}readonly ${key(name)}: ${contextNodeType(child, level + 1)};`,
    ]);
    parts.push(`{\n${lines.join('\n')}\n${indentOf(level)}}`);
  }
  return parts.length === 0 ? '{}' : parts.join(' & ');
}

/** The properties of an interface that holds one context tree, one entry per top-level segment. */
function contextProperties(vars: readonly SurfaceContextVar[], level: number): string[] {
  const root = contextTree(vars);
  const pad = indentOf(level);
  return [...root.children.entries()].flatMap(([name, child]) => [
    ...doc([child.leaf === undefined ? undefined : sentence(child.leaf.description)], level),
    `${pad}readonly ${key(name)}: ${contextNodeType(child, level)};`,
  ]);
}

function triggers(ctx: Context): string[] {
  const entries = sortedBy(ctx.surface.behavior.triggers, (trigger: SurfaceTrigger) => trigger.id);
  const names = memberNames(entries.map((trigger) => trigger.id));
  const lines: string[] = [];
  const signatures: string[][] = [];
  for (const trigger of entries) {
    const member = names.get(trigger.id) ?? camel(trigger.id);
    const base = pascal(member);
    const argsName = `${base}TriggerArgs`;
    const contextName = `${base}Context`;
    const detail: SurfaceParam[] = trigger.details.length > 0
      ? [{ id: 'detail', type: 'enum', required: false, description: 'Only this variant of the trigger', allowedValues: trigger.details }]
      : [];
    const args: BuilderInput = {
      member,
      paramsName: argsName,
      params: [...detail, ...trigger.filters],
      extendsTypes: [],
      returns: `TriggerBinding<${literal(trigger.id)}, ${contextName}>`,
      docLines: [],
    };
    lines.push('', ...doc([`The detail and the filters of the \`${trigger.id}\` trigger.`], 0), ...paramsInterface(args, ctx));
    const hosts = [...trigger.firedBy].sort(compare);
    const hostLines = hosts.flatMap((host) => {
      const vars = contextFor(trigger.context, host);
      return [
        ...doc([`What a rule on the host \`${host}\` receives.`], 1),
        `  readonly ${key(host)}: {`,
        ...contextProperties(vars, 2),
        '  };',
      ];
    });
    lines.push(
      '',
      ...doc([
        `The context of \`${trigger.id}\` rules, by the host that fires it.`,
        hosts.length === 0 ? 'No host fires this trigger on this server, so no rule can use it.' : undefined,
      ], 0),
      hostLines.length === 0 ? `interface ${contextName} {}` : `interface ${contextName} {`,
      ...(hostLines.length === 0 ? [] : [...hostLines, '}']),
    );
    signatures.push(
      builderSignature(
        {
          ...args,
          docLines: [
            ...entryDoc(trigger, [
              hosts.length > 0 ? `Fired by: ${hosts.join(', ')}.` : undefined,
              trigger.cancellable ? 'Cancellable: a rule may cancel the underlying game event.' : undefined,
            ]),
          ],
        },
        ctx,
      ),
    );
  }
  lines.push('', ...catalogInterface('TriggerCatalog', signatures));
  const ruleOptions = ctx.surface.behavior.ruleOptions;
  lines.push(
    '',
    ...doc(['The options a rule may carry besides `on`, `if` and `do`.'], 0),
    'interface RuleOptions {',
    ...propertyLines(ruleOptions, 'RuleOptions', 1, ctx),
    '}',
  );
  const ruleBody = ctx.surface.behavior.ruleBody;
  if (ruleBody.length > 0) {
    lines.push(
      '',
      ...doc(['What a rule\'s builder returns besides its options: the flow keys of a rule.'], 0),
      'interface RuleBody {',
      ...propertyLines(ruleBody, 'RuleBody', 1, ctx),
      '}',
    );
  }
  return lines;
}

// ---- expression functions ---------------------------------------------------------------------------

function functions(ctx: Context): string[] {
  const entries = sortedBy(ctx.surface.behavior.functions, (fn: SurfaceFunction) => fn.id);
  const names = memberNames(entries.map((fn) => fn.id));
  const signatures: string[][] = [];
  for (const fn of entries) {
    const member = names.get(fn.id) ?? camel(fn.id);
    const result = scalarType(fn.returns);
    let args: string;
    if (fn.parameters.length === 0) {
      args = fn.maxArguments === 0 ? '' : '...args: ExprArg<unknown>[]';
    } else {
      args = fn.parameters
        .map((param, index) => {
          const type = `ExprArg<${scalarType(param.type)}>`;
          if (fn.variadic && index === fn.parameters.length - 1) return `...${paramName(param.id)}: ${type}[]`;
          return `${paramName(param.id)}${index >= fn.minArguments ? '?' : ''}: ${type}`;
        })
        .join(', ');
    }
    const argDocs = fn.parameters.map((param) => (param.description ? `@param ${paramName(param.id)} ${sentence(param.description)}` : undefined));
    signatures.push([
      ...doc([...entryDoc(fn, [`Returns ${fn.returns}.`]), ...argDocs], 1),
      `  ${key(member)}(${args}): Expr<${result}>;`,
    ]);
  }
  return signatures.length === 0 ? [] : ['', ...catalogInterface('FunctionCatalog', signatures)];
}

// ---- recipes ----------------------------------------------------------------------------------------

function recipeParamType(type: string, ctx: Context): string {
  switch (type) {
    case 'number':
    case 'integer':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'string':
      return 'string';
    default:
      return ctx.typeNames.has(type) ? `string | Ref<${literal(type)}>` : 'string';
  }
}

function recipes(ctx: Context): string[] {
  const entries = sortedBy(ctx.surface.behavior.recipes, (recipe: SurfaceRecipe) => recipe.id);
  const names = memberNames(entries.map((recipe) => recipe.id));
  const lines: string[] = [];
  const signatures: string[][] = [];
  for (const recipe of entries) {
    const member = names.get(recipe.id) ?? camel(recipe.id);
    const paramsName = `${pascal(member)}RecipeParams`;
    const props = recipe.params.flatMap((param) => {
      const rangeLine =
        param.min !== undefined && param.max !== undefined ? `Range: ${param.min} to ${param.max}.` :
        param.min !== undefined ? `Minimum: ${param.min}.` :
        param.max !== undefined ? `Maximum: ${param.max}.` : undefined;
      const variableLine = param.scope !== undefined ? `The variable's scope is ${param.scope}${param.valueType ? ` and its value type ${param.valueType}` : ''}.` : undefined;
      return [
        ...doc([sentence(param.description), rangeLine, variableLine, param.default !== undefined && String(param.default) !== '' ? `@defaultValue ${String(param.default)}` : undefined], 1),
        `  ${key(param.name)}${param.required ? '' : '?'}: ${recipeParamType(param.type, ctx)};`,
      ];
    });
    lines.push('', ...doc([`Parameters of the \`${recipe.id}\` recipe.`], 0), `interface ${paramsName} {`, ...props, '}');
    const allOptional = recipe.params.every((param) => !param.required);
    signatures.push([
      ...doc([sentence(recipe.description) ?? sentence(recipe.displayName), recipe.registeredBy ? `@registeredBy ${recipe.registeredBy}` : undefined], 1),
      `  ${key(member)}(params${allOptional ? '?' : ''}: ${paramsName}): RecipeInstance<${literal(recipe.id)}>;`,
    ]);
  }
  if (signatures.length > 0) lines.push('', ...catalogInterface('RecipeCatalog', signatures));
  return lines;
}

// ---- item and list sources --------------------------------------------------------------------------

function sources(ctx: Context): string[] {
  const lines: string[] = [];
  const emit = (entries: readonly SurfaceSource[], catalog: string, suffix: string, returnType: string, withEntry: boolean): void => {
    const sorted = sortedBy(entries, (source) => source.id);
    const names = memberNames(sorted.map((source) => source.id));
    const signatures: string[][] = [];
    for (const source of sorted) {
      const member = names.get(source.id) ?? camel(source.id);
      const base = pascal(member);
      const input: BuilderInput = {
        member,
        paramsName: `${base}${suffix}Params`,
        params: source.parameters,
        extendsTypes: [],
        shorthand: shorthandOf(source),
        returns: `${returnType}<${literal(source.id)}>`,
        docLines: entryDoc(source),
      };
      lines.push('', ...doc([`Parameters of the \`${source.id}\` ${suffix.toLowerCase()} source.`], 0), ...paramsInterface(input, ctx));
      if (withEntry) {
        lines.push(
          '',
          ...doc([`The \`entry.*\` variables a row of the \`${source.id}\` list carries.`], 0),
          `interface ${base}ListEntry {`,
          ...contextProperties(source.entry, 1),
          '}',
        );
      }
      signatures.push(builderSignature(input, ctx));
    }
    if (signatures.length > 0) lines.push('', ...catalogInterface(catalog, signatures));
  };
  emit(ctx.surface.behavior.itemSources, 'ItemSourceCatalog', 'Item', 'ItemSourceSpec', false);
  emit(ctx.surface.behavior.listSources, 'ListSourceCatalog', 'List', 'ListSourceSpec', true);
  return lines;
}

// ---- GUI --------------------------------------------------------------------------------------------

function gui(ctx: Context): string[] {
  const lines: string[] = [];
  const common = guiLayerCommonParams(ctx.surface);
  const components = sortedBy(ctx.surface.gui.components, (component) => component.id);
  const names = memberNames(components.map((component) => component.id));
  const signatures: string[][] = [];
  if (components.length > 0) {
    lines.push('', ...doc(['The properties every GUI layer carries, whatever its component.'], 0), 'interface GuiLayerCommon {', ...propertyLines(common, 'GuiLayerCommon', 1, ctx), '}');
  }
  for (const component of components) {
    const member = names.get(component.id) ?? camel(component.id);
    const input: BuilderInput = {
      member,
      paramsName: `${pascal(member)}LayerParams`,
      params: component.parameters,
      extendsTypes: ['GuiLayerCommon'],
      returns: `GuiLayerEntry<${literal(component.id)}>`,
      docLines: entryDoc(component, [component.target ? `Renders on: ${component.target}.` : undefined]),
    };
    lines.push('', ...doc([`Parameters of the \`${component.id}\` layer.`], 0), ...paramsInterface(input, ctx));
    // a layer always needs its id (a common property), so the params are required whatever the component says
    signatures.push(
      builderSignature({ ...input, params: [...common, ...component.parameters] }, ctx),
    );
  }
  if (signatures.length > 0) lines.push('', ...catalogInterface('GuiComponentCatalog', signatures));
  const containers = sortedBy(ctx.surface.gui.containers, (container) => container.id);
  if (containers.length > 0) {
    lines.push(
      '',
      ...doc(['The container profiles this server compiles and opens.', ...containers.map((container) => `${container.id}: ${container.label}${container.slotCount !== undefined ? ` (${container.slotCount} slots)` : ''}`)], 0),
      `type GuiContainerId = ${union(containers.map((container) => container.id))};`,
    );
  }
  return lines;
}

// ---- entry point ------------------------------------------------------------------------------------

/**
 * The declaration text that types the code-mode SDK for the server `surface` describes. Deterministic:
 * the same document always yields the same bytes.
 */
export function generateSdk(surface: SurfaceDocument): string {
  const ctx: Context = {
    surface,
    typeNames: new Set(surface.contentTypes.map((type) => type.name)),
    hasMechanics: surface.behavior.mechanics.length > 0,
    hasGuiComponents: surface.gui.components.length > 0,
    hasInputMethods: surface.behavior.inputMethods.length > 0,
    hasItemSources: surface.behavior.itemSources.length > 0,
    hasListSources: surface.behavior.listSources.length > 0,
    aliases: [],
  };
  const sections = [
    header(surface),
    idUnions(ctx),
    contentTypes(ctx),
    triggers(ctx),
    stepForms(ctx),
    actions(ctx),
    conditions(ctx),
    mechanics(ctx),
    functions(ctx),
    recipes(ctx),
    sources(ctx),
    gui(ctx),
  ];
  // the large-enum aliases are collected while the sections render, and printed once, after them
  const lines = [...sections.flat(), ...(ctx.aliases.length > 0 ? ['', ...ctx.aliases] : [])];
  return `${lines.join('\n')}\n`;
}
