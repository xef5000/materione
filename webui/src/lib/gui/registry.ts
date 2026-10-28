/**
 * The `gui-components` registry as the editor sees it (gui.md sections 3, 6
 * and 7). The palette, the inspector and a new layer's defaults all come from
 * this self-description, exactly like item modules come from the type schema:
 * the studio keeps no component list of its own, so an addon component with a
 * schema gets a palette entry and an inspector for free.
 */
import { guiComponentsOf, type GuiComponentInfo, type GuiRenderTarget, type TypeParam } from '../api';
import type { IconName } from '../components/icons';
import { icons } from '../components/icons';
import { uniqueId, type GuiLayer, type Json } from './model';

/** Where a component's output physically lands (gui.md section 4). */
export type RenderTarget = GuiRenderTarget;

export type { GuiComponentInfo };

export interface GuiRegistry {
  components: GuiComponentInfo[];
}

/**
 * The registry as an `authoring.describe` answer carries it (`guiComponents`,
 * parsed by `guiComponentsOf` in api.ts). `null` when the server describes no
 * components at all (an older jar), so the studio can say so.
 */
export function readRegistry(described: Record<string, unknown> | null | undefined): GuiRegistry | null {
  const components = described ? guiComponentsOf(described) : [];
  return components.length ? { components } : null;
}

/** A layer's `type:` is the registry id with the built-in namespace optional (`panel` is `materione:panel`). */
export function fullComponentId(type: string): string {
  return type.includes(':') ? type : `materione:${type}`;
}

export function componentOf(registry: GuiRegistry | null, type: string): GuiComponentInfo | undefined {
  const wanted = fullComponentId(type);
  return registry?.components.find((component) => component.id === wanted);
}

/** The unqualified name of a registry id (`materione:panel` -> `panel`). */
function bareName(id: string): string {
  return id.slice(id.lastIndexOf(':') + 1);
}

/**
 * Palette and layer icons. The registry description carries none, so this is
 * cosmetic knowledge only: a few well-known names, else the render target's icon.
 */
const ICONS: Record<string, IconName> = {
  panel: 'square',
  slots: 'grid-3x3',
  image: 'image',
  paint: 'pencil',
  button: 'rectangle-horizontal',
  text: 'type',
  item: 'package',
};
const TARGET_ICONS: Record<RenderTarget, IconName> = { raster: 'square', text: 'type', slot: 'package' };

export function componentIcon(info: GuiComponentInfo | undefined): IconName {
  if (!info) return 'square';
  const named = (info as { icon?: string }).icon as IconName | undefined;
  if (named && named in icons) return named;
  return ICONS[bareName(info.id)] ?? TARGET_ICONS[info.target];
}

/** What a registry id is called in the palette when it brings no display name of its own. */
export function shortName(info: GuiComponentInfo): string {
  return info.displayName || bareName(info.id);
}

export function paramOf(info: GuiComponentInfo | undefined, id: string): TypeParam | undefined {
  return info?.parameters.find((param) => param.id === id);
}

/** A param's authored default, typed. `undefined` when the schema declares none. */
export function defaultValue(param: TypeParam): unknown {
  if (param.default === undefined) return undefined;
  switch (param.type) {
    case 'integer':
    case 'decimal': {
      const n = Number(param.default);
      return Number.isFinite(n) ? n : undefined;
    }
    case 'boolean':
      return param.default === 'true';
    default:
      return param.default;
  }
}

/** What the studio may do to a layer with the mouse, derived from the component's fields. */
export interface Transformability {
  movable: boolean;
  resizable: boolean;
  /** Positioned by `slot` (an item), snapping to the slot grid rather than free pixels. */
  slotBound: boolean;
}

export function transformability(info: GuiComponentInfo | undefined, layer?: GuiLayer): Transformability {
  const ids = new Set(info?.parameters.map((param) => param.id));
  const has = (id: string): boolean => (info ? ids.has(id) : layer !== undefined && id in layer);
  const slotBound = has('slot');
  return {
    slotBound,
    movable: slotBound || (has('x') && has('y')),
    resizable: has('x') && has('y') && has('width') && has('height'),
  };
}

/**
 * Starting values for required fields the schema gives no default for: the
 * editor needs *some* visible size and a valid slot to put a new layer on the
 * canvas. Everything optional is left out so the file stays as small as the
 * author's intent.
 */
const REQUIRED_FALLBACKS: Record<string, unknown> = {
  width: 48,
  height: 24,
  slot: 0,
  text: 'Text',
  item: 'minecraft:stone',
};

/** The layer a palette click adds: required fields with their default (or a starting value), positioned by the caller. */
export function newLayer(
  info: GuiComponentInfo,
  taken: Iterable<string>,
  overrides: Json = {},
): GuiLayer {
  const bare = bareName(info.id);
  const id = uniqueId(bare, taken);
  const layer: Json = { id, type: bare, name: shortName(info) };
  for (const param of info.parameters) {
    if (!param.required && !(param.id === 'x' || param.id === 'y')) continue;
    const value = defaultValue(param) ?? (param.required ? REQUIRED_FALLBACKS[param.id] : undefined);
    if (value !== undefined) layer[param.id] = value;
  }
  return { ...layer, ...overrides, id, type: bare } as GuiLayer;
}
