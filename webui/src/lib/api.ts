/** Typed helpers over the sealed channel — one place that knows the op names. */
import type { EditorChannel } from './protocol/session';
import type { Response } from './protocol/wire';

export interface TypeParam {
  id: string;
  type: string;
  required: boolean;
  default?: string;
  description?: string;
  allowedValues?: string[];
}

export interface TypeInfo {
  name: string;
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
  hint?: string;
}

export interface AssetRef {
  path: string;
  bytes: number;
  kind: string;
}

export function unwrap(response: Response): Record<string, unknown> {
  if (!response.ok) {
    throw new Error(`${response.error.code}: ${response.error.message}`);
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
  remove: (c: EditorChannel, pack: string, path: string, rev: number) =>
    c.call('edit.delete', { pack, path, rev }),
  createPack: (c: EditorChannel, pack: string) => c.call('edit.createPack', { pack }),
  types: (c: EditorChannel) => c.call('browse.types'),
  diagnostics: (c: EditorChannel) => c.call('admin.diagnostics.recent'),
  dryRun: (c: EditorChannel, content: string) => c.call('validate.dryRun', { content }),
};

export const assetOps = {
  list: (c: EditorChannel, pack: string) => c.call('assets.list', { pack }),
  put: async (c: EditorChannel, pack: string, path: string, bytes: Uint8Array) =>
    c.call('assets.put', { pack, path, data: b64fromBytes(bytes) }),
  get: (c: EditorChannel, pack: string, path: string) => c.call('assets.get', { pack, path }),
  remove: (c: EditorChannel, pack: string, path: string) => c.call('assets.delete', { pack, path }),
};

function b64fromBytes(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary);
}
