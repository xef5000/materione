/** Typed helpers over the sealed channel — one place that knows the op names. */
import type { EditorChannel as SealedEditorChannel } from './protocol/session';
import type { AssetUpload, Response } from './protocol/wire';

export type { AssetUpload } from './protocol/wire';

export type RpcChannel = Pick<SealedEditorChannel, 'call'>;
type EditorChannel = RpcChannel;

export class RpcError extends Error {
  constructor(readonly code: string, message: string) {
    super(`${code}: ${message}`);
    this.name = 'RpcError';
  }
}

export interface TypeParam {
  id: string;
  type: string;
  required: boolean;
  default?: string;
  description?: string;
  allowedValues?: string[];
  /** Declared shape of each entry, for list fields the studio can edit. */
  nested?: TypeParam[];
  /** Schema-derived affordance: 'texture' | 'model' pickers, else absent. */
  uiHint?: string;
  label?: string;
  section?: string;
  order?: number;
  advanced?: boolean;
  referenceKind?: string;
  example?: string;
  /** Module metadata (schema-declared): this parameter is an add/remove module card. */
  module?: boolean;
  /** Picker category id; falls back to `section`. */
  category?: string;
  /** Icon registry name in the editor's one icon set. */
  icon?: string;
  /** One line the module picker shows; falls back to `description`. */
  summary?: string;
  /** Labelled choices: the stored value stays `value`, the control shows `label`. */
  options?: TypeOption[];
  /** Number control range (inclusive) and unit suffix. */
  min?: number;
  max?: number;
  unit?: string;
}

export interface TypeOption {
  value: string;
  label: string;
  description?: string;
  icon?: string;
}

export interface TypeInfo {
  name: string;
  /** Pack directory this type's documents live in ("items", …). */
  directory?: string;
  displayName: string;
  description: string;
  parameters: TypeParam[];
}

export interface ResourceRef {
  pack: string;
  type: string;
  path: string;
  id: string;
}

export interface ResourceSummary extends ResourceRef {
  name: string;
  material?: string;
  visual?: {
    pack: string;
    path: string;
    kind: 'texture' | 'model-thumbnail';
  };
  /** Live-render input: a readable model asset, or the model the type compiles inline. */
  render?: {
    pack: string;
    path?: string;
    model?: Record<string, unknown>;
  };
  health: 'ok' | 'warning' | 'error' | 'unknown';
  healthMessage?: string;
}

export interface DiagnosticEntry {
  code: string;
  severity: string;
  message: string;
  resource?: string;
  /** Source coordinates ("file:line[:column]") when the failure came from a file. */
  location?: string;
  hint?: string;
  /** The field inside `resource` the problem is about (`["texture"]`). */
  target?: { field: string[] };
}

/** One field edit in a studio save: a value, or an explicit lossless key removal. */
export type FieldEditPayload =
  | { field: string[]; value: unknown }
  | { field: string[]; remove: true };

/** Read-only inheritance view per resource root (`computed.inheritance`). */
export interface InheritanceView {
  template: string;
  /** Full template ids, nearest first. */
  chain: string[];
  /** Effective values the root does not set itself. */
  inherited: Record<string, unknown>;
  /** Which template supplies each key (inherited or overridden). */
  sources: Record<string, string>;
  templates: Record<string, { pack: string; path: string }>;
  failure?: string;
}

/**
 * One template as `authoring.templates` lists it. Derived by the server from
 * the authored documents: `serves` are the template-capable type directories
 * it fits, `usedBy` the resources that name it in `template:`.
 */
export interface TemplateEntry {
  pack: string;
  path: string;
  id: string;
  /** The template this one itself inherits from, as a full id. */
  parent?: string;
  /** Top-level fields the template sets (authored values). */
  definition: Record<string, unknown>;
  serves: string[];
  usedBy: ResourceRef[];
}

export interface AssetRef {
  path: string;
  bytes: number;
  kind: string;
}

/** The render targets a GUI component can draw on (gui.md §4). */
export type GuiRenderTarget = 'raster' | 'text' | 'slot';

/**
 * One entry of the `gui-components` registry as `authoring.describe` reports it
 * (`guiComponents`, next to `behavior`). The studio's inspector for a layer is
 * built from `parameters`, so an addon component needs no editor change.
 */
export interface GuiComponentInfo {
  /** Full registry id: `materione:panel`, or an addon's `example:progress`. */
  id: string;
  displayName: string;
  description: string;
  parameters: TypeParam[];
  sinceVersion?: string;
  /** Present only when the component declared when it acts. */
  activation?: string;
  target: GuiRenderTarget;
  registeredBy: string;
}

const GUI_TARGETS: readonly GuiRenderTarget[] = ['raster', 'text', 'slot'];

/**
 * The gui-components entries from an `authoring.describe` payload. Entries
 * that lack the fields the studio relies on are dropped, never guessed at.
 */
export function guiComponentsOf(describe: Record<string, unknown>): GuiComponentInfo[] {
  const list = describe.guiComponents;
  if (!Array.isArray(list)) return [];
  return list.filter((entry): entry is GuiComponentInfo => {
    if (typeof entry !== 'object' || entry === null) return false;
    const component = entry as Partial<GuiComponentInfo>;
    return typeof component.id === 'string'
      && typeof component.displayName === 'string'
      && Array.isArray(component.parameters)
      && GUI_TARGETS.includes(component.target as GuiRenderTarget);
  });
}

export function unwrap(response: Response): Record<string, unknown> {
  if (!response.ok) {
    throw new RpcError(response.error.code, response.error.message);
  }
  return response.data as Record<string, unknown>;
}

export const ops = {
  sessionInfo: (c: EditorChannel) => c.call('session.info'),
  packs: (c: EditorChannel) => c.call('browse.packs'),
  resources: (c: EditorChannel, pack?: string) =>
    c.call('browse.resources', pack ? { pack } : {}),
  summaries: async (
    c: EditorChannel,
    pack: string,
  ): Promise<Response> => {
    const resources: unknown[] = [];
    let first: Extract<Response, { ok: true }> | undefined;
    let offset = 0;
    let total: number | undefined;
    for (;;) {
      const response = await c.call(
        'authoring.summaries',
        offset === 0 ? { pack } : { pack, offset },
      );
      if (!response.ok) return response;
      first ??= response;
      const data = response.data as Record<string, unknown>;
      if (!Array.isArray(data.resources)) {
        throw new Error('authoring.summaries returned no resource page');
      }
      if (typeof data.total !== 'number' || !Number.isSafeInteger(data.total) || data.total < 0) {
        throw new Error('authoring.summaries returned an invalid total');
      }
      if (total !== undefined && data.total !== total) {
        throw new Error('The resource catalog changed while loading summaries. Refresh the catalog.');
      }
      total = data.total;
      resources.push(...data.resources);
      if (data.nextOffset === undefined) {
        if (total !== resources.length) {
          throw new Error('authoring.summaries returned an incomplete catalog');
        }
        break;
      }
      if (data.resources.length === 0 || typeof data.nextOffset !== 'number'
        || !Number.isSafeInteger(data.nextOffset) || data.nextOffset !== resources.length
        || data.nextOffset <= offset || data.nextOffset >= total) {
        throw new Error('authoring.summaries returned an invalid page cursor');
      }
      offset = data.nextOffset;
    }
    if (!first) throw new Error('authoring.summaries returned no response');
    return {
      ...first,
      data: {
        resources,
        total,
      },
    };
  },
  summaryPage: (
    c: EditorChannel,
    pack: string,
    options: { type?: string; query?: string; offset?: number; limit?: number } = {},
  ) => c.call('authoring.summaries', { pack, ...options }),
  get: (c: EditorChannel, pack: string, path: string) => c.call('browse.get', { pack, path }),
  put: (c: EditorChannel, pack: string, path: string, rev: number, content: string) =>
    c.call('edit.put', { pack, path, rev, content }),
  setScalar: (
    c: EditorChannel,
    pack: string,
    path: string,
    rev: number,
    field: string[],
    value: unknown,
  ) => c.call('edit.setScalar', { pack, path, rev, field, value }),
  commitDraft: (c: EditorChannel, pack: string, path: string, rev: number, content: string) =>
    c.call('authoring.commit', { pack, path, rev, content }),
  remove: (c: EditorChannel, pack: string, path: string, rev: number) =>
    c.call('edit.delete', { pack, path, rev }),
  create: (c: EditorChannel, pack: string, path: string, id?: string) =>
    c.call('edit.create', id ? { pack, path, id } : { pack, path }),
  move: (c: EditorChannel, pack: string, from: string, to: string, rev: number) =>
    c.call('edit.move', { pack, from, to, rev }),
  createPack: (c: EditorChannel, pack: string) => c.call('edit.createPack', { pack }),
  createResource: (
    c: EditorChannel,
    pack: string,
    id: string,
    name: string,
    createPack: boolean,
    type?: string,
    folder?: string,
    template?: string,
    serves?: string,
  ) => c.call('authoring.createResource', {
    pack, id, name, createPack,
    ...(type ? { type } : {}),
    ...(folder ? { folder } : {}),
    ...(template ? { template } : {}),
    ...(serves ? { serves } : {}),
  }),
  /** Every template of every pack, with what it sets, who it serves and who uses it. */
  templates: (c: EditorChannel) => c.call('authoring.templates'),
  /** Materialises the inherited values into the resource and drops its `template:` link. */
  detachTemplate: (c: EditorChannel, pack: string, path: string, rev: number, resource: string) =>
    c.call('authoring.detachTemplate', { pack, path, rev, resource }),
  /** Writes `templates/<id>.yml` holding the chosen authored fields of a resource. */
  saveAsTemplate: (
    c: EditorChannel,
    pack: string,
    path: string,
    resource: string,
    id: string,
    fields: string[],
  ) => c.call('authoring.saveAsTemplate', { pack, path, resource, id, fields }),
  duplicateResource: (
    c: EditorChannel,
    pack: string,
    path: string,
    id: string,
    folder?: string,
  ) => c.call('authoring.duplicateResource', {
    pack, path, id, ...(folder ? { folder } : {}),
  }),
  folders: (c: EditorChannel, pack: string) => c.call('browse.folders', { pack }),
  createFolder: (c: EditorChannel, pack: string, path: string) =>
    c.call('edit.createFolder', { pack, path }),
  deleteFolder: (c: EditorChannel, pack: string, path: string) =>
    c.call('edit.deleteFolder', { pack, path }),
  moveFolder: (c: EditorChannel, pack: string, from: string, to: string) =>
    c.call('edit.moveFolder', { pack, from, to }),
  types: (c: EditorChannel) => c.call('browse.types'),
  /** Content types plus the behaviour vocabulary; older jars answer with an unknown-op error. */
  describe: (c: EditorChannel) => c.call('authoring.describe'),
  diagnostics: (c: EditorChannel) => c.call('admin.diagnostics.recent'),
  dryRun: (c: EditorChannel, content: string) => c.call('validate.dryRun', { content }),
  reloadScope: (c: EditorChannel, scope: 'everything' | 'one-pack', pack?: string) =>
    c.call('pipeline.reload', pack ? { scope, pack } : { scope }),
  audit: (c: EditorChannel, limit = 100) => c.call('admin.audit.recent', { limit }),
  settingsGet: (c: EditorChannel) => c.call('admin.settings.get'),
  settingsPut: (c: EditorChannel, content: string, sha256: string) =>
    c.call('admin.settings.put', { content, sha256 }),
  deliveryStatus: (c: EditorChannel) => c.call('delivery.status'),
  publishCandidate: (c: EditorChannel, expectedCandidateSha256: string) =>
    c.call('delivery.publish', { expectedCandidateSha256 }),
  saveModel: (
    c: EditorChannel,
    payload: {
      pack: string;
      ownerPath: string;
      rev?: number;
      field?: string[];
      modelRef?: string;
      assets: { path: string; data: string }[];
      createOwner?: boolean;
      ownerId?: string;
      ownerName?: string;
      ownerType?: string;
      sourcePath?: string;
    },
  ) => c.call('authoring.saveModel', payload),
  /**
   * One studio save: many fields, one revision, one reload. `assets` are committed in the
   * same transaction as the edits (a paint layer's PNG): all of them land, or none does.
   * `edits` may be empty only when `assets` is not (a paint-only save still takes a revision).
   */
  setFields: (
    c: EditorChannel,
    pack: string,
    path: string,
    rev: number,
    edits: FieldEditPayload[],
    assets: AssetUpload[] = [],
  ) => c.call('authoring.setFields', {
    pack, path, rev, edits, ...(assets.length > 0 ? { assets } : {}),
  }),
  /** Where is an asset or resource used? Bounded; the reply carries what it did not read. */
  references: (c: EditorChannel, pack: string, kind: 'asset' | 'resource', target: string) =>
    c.call('authoring.references', { pack, kind, target }),
  /**
   * Revision-checked all-or-none authoring over several documents. `dryRun`
   * returns the impact summary and writes nothing.
   */
  bulk: (c: EditorChannel, pack: string, operations: unknown[], dryRun: boolean) =>
    c.call('authoring.bulk', { pack, operations, dryRun }),
};

export const assetOps = {
  list: (c: EditorChannel, pack: string) => c.call('assets.list', { pack }),
  put: async (c: EditorChannel, pack: string, path: string, bytes: Uint8Array) =>
    c.call('assets.put', { pack, path, data: b64fromBytes(bytes) }),
  /** Base64 already in hand (bridge textures) — skips a re-encode. */
  putBase64: (c: EditorChannel, pack: string, path: string, base64: string) =>
    c.call('assets.put', { pack, path, data: base64 }),
  get: (c: EditorChannel, pack: string, path: string) => c.call('assets.get', { pack, path }),
  remove: (c: EditorChannel, pack: string, path: string) => c.call('assets.delete', { pack, path }),
  move: (c: EditorChannel, pack: string, from: string, to: string) =>
    c.call('assets.move', { pack, from: from, to: to }),
};

function b64fromBytes(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary);
}
