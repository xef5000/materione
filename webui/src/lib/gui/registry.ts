/**
 * The `gui-components` registry as the editor sees it (gui.md sections 3, 6
 * and 7). The palette, the inspector and a new layer's defaults all come from
 * this self-description, exactly like item modules come from the type schema:
 * the studio keeps no component list of its own, so an addon component with a
 * schema gets a palette entry and an inspector for free.
 */
import type { TypeParam } from '../api';
import type { IconName } from '../components/icons';
import { icons } from '../components/icons';
import { isRecord, uniqueId, type GuiLayer, type Json } from './model';

/** Where a component's output physically lands (gui.md section 4). */
export type RenderTarget = 'RASTER' | 'TEXT' | 'SLOT';

export interface GuiComponentInfo {
  /** The `type:` value of a layer. */
  id: string;
  displayName: string;
  description: string;
  target: RenderTarget;
  icon?: string;
  /** The component's own fields; the common ones (id, name, visible, locked, opacity) are the gui type's. */
  parameters: TypeParam[];
}

export interface GuiRegistry {
  components: GuiComponentInfo[];
}

function targetOf(value: unknown): RenderTarget {
  const upper = typeof value === 'string' ? value.toUpperCase() : '';
  return upper === 'TEXT' || upper === 'SLOT' ? upper : 'RASTER';
}

/**
 * Reads the component descriptions out of an `authoring.describe` answer. The
 * registry is described like `mechanics`: a list of `{ id, displayName,
 * description, target, parameters }` entries. Tolerant of where it sits (the
 * issue 183 wire shape), strict about entry shape, and `null` when the server
 * does not describe GUI components at all (an older jar).
 */
export function readRegistry(described: unknown): GuiRegistry | null {
  if (!isRecord(described)) return null;
  const gui = isRecord(described.gui) ? described.gui : null;
  const behavior = isRecord(described.behavior) ? described.behavior : null;
  const source = described.guiComponents ?? described['gui-components'] ?? gui?.components ??
    behavior?.guiComponents;
  if (!Array.isArray(source)) return null;
  const components: GuiComponentInfo[] = [];
  for (const entry of source) {
    if (!isRecord(entry) || typeof entry.id !== 'string' || !entry.id) continue;
    components.push({
      id: entry.id,
      displayName: typeof entry.displayName === 'string' && entry.displayName ? entry.displayName : entry.id,
      description: typeof entry.description === 'string' ? entry.description : '',
      target: targetOf(entry.target),
      ...(typeof entry.icon === 'string' ? { icon: entry.icon } : {}),
      parameters: Array.isArray(entry.parameters) ? (entry.parameters as TypeParam[]) : [],
    });
  }
  return { components };
}

export function componentOf(registry: GuiRegistry | null, type: string): GuiComponentInfo | undefined {
  return registry?.components.find((component) => component.id === type);
}

/** An icon the editor's set has, else a neutral one: a registry may name any string. */
export function componentIcon(info: GuiComponentInfo | undefined): IconName {
  const named = info?.icon as IconName | undefined;
  return named && named in icons ? named : 'square';
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
 * The layer a palette click adds: every required field with its declared
 * default, positioned by the caller. Optional fields (styles, opacity) are
 * left out so the file stays as small as the author's intent.
 */
export function newLayer(
  info: GuiComponentInfo,
  taken: Iterable<string>,
  overrides: Json = {},
): GuiLayer {
  const id = uniqueId(info.id, taken);
  const layer: Json = { id, type: info.id, name: info.displayName };
  for (const param of info.parameters) {
    if (!param.required) continue;
    const value = defaultValue(param);
    if (value !== undefined) layer[param.id] = value;
  }
  return { ...layer, ...overrides, id, type: info.id } as GuiLayer;
}
