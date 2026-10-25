/** Typed helpers over the sealed channel — one place that knows the op names. */
import type { EditorChannel as SealedEditorChannel } from './protocol/session';
import type { Response } from './protocol/wire';

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

export interface DiagnosticEntry {
  code: string;
  severity: string;
  message: string;
  resource?: string;
  /** Source coordinates ("file:line[:column]") when the failure came from a file. */
  location?: string;
  hint?: string;
}

export interface AssetRef {
  path: string;
  bytes: number;
  kind: string;
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
  ) => c.call('authoring.createResource', {
    pack, id, name, createPack,
    ...(type ? { type } : {}),
    ...(folder ? { folder } : {}),
  }),
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
  /** One studio save: many fields, one revision, one reload. */
  setFields: (
    c: EditorChannel,
    pack: string,
    path: string,
    rev: number,
    edits: { field: string[]; value: unknown }[],
  ) => c.call('authoring.setFields', { pack, path, rev, edits }),
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
