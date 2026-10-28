import { ops, RpcError, unwrap, type RpcChannel } from './api';

export const MAX_DOCUMENT_DRAFTS = 32;
export interface FieldEdit {
  field: string[];
  value: unknown;
  /** Delete the key (reset to inherited) instead of writing `value`. */
  remove?: true;
}

export interface DocumentDraft {
  pack: string;
  path: string;
  rev: number;
  raw: string;
  savedRaw: string;
  base: Record<string, unknown>;
  values: Record<string, unknown>;
  computed: Record<string, unknown>;
  edits: FieldEdit[];
  loaded: boolean;
  loading: boolean;
  saving: boolean;
  uncertain: boolean;
  stale: boolean;
  error: string;
  notice: string;
}

export const draftStore = $state({ documents: [] as DocumentDraft[] });
const loads = new Map<DocumentDraft, Promise<void>>();
const retained = new Map<DocumentDraft, number>();

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

export function fieldValue(values: Record<string, unknown>, path: string[]): unknown {
  let value: unknown = values;
  for (const key of path) {
    const parent = record(value);
    value = Object.hasOwn(parent, key) ? parent[key] : undefined;
  }
  return value;
}

function assignOwn(parent: Record<string, unknown>, key: string, value: unknown): void {
  // Authored YAML keys are data, including names such as "__proto__".
  Object.defineProperty(parent, key, { value, writable: true, enumerable: true, configurable: true });
}

function assign(values: Record<string, unknown>, path: string[], value: unknown): void {
  let parent = values;
  for (const key of path.slice(0, -1)) {
    assignOwn(parent, key, { ...record(Object.hasOwn(parent, key) ? parent[key] : undefined) });
    parent = record(parent[key]);
  }
  assignOwn(parent, path[path.length - 1]!, clone(value));
}

function equal(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

function samePath(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((key, index) => key === b[index]);
}

export function rawDraftDirty(draft: DocumentDraft): boolean {
  return draft.raw !== draft.savedRaw;
}

export function draftDirty(draft: DocumentDraft): boolean {
  return rawDraftDirty(draft) || draft.edits.length > 0;
}

export function getDocumentDraft(pack: string, path: string): DocumentDraft {
  const existing = draftStore.documents.find((draft) => draft.pack === pack && draft.path === path);
  if (existing) return existing;
  if (draftStore.documents.length >= MAX_DOCUMENT_DRAFTS) {
    const clean = draftStore.documents.findIndex((draft) =>
      !retained.has(draft) && !draftDirty(draft) && !draft.saving && !draft.uncertain && !draft.loading);
    if (clean < 0) {
      throw new Error(`The ${MAX_DOCUMENT_DRAFTS}-document draft limit is reached. Close a saved editor or apply/discard a draft before opening another file.`);
    }
    draftStore.documents.splice(clean, 1);
  }
  draftStore.documents.push({
    pack, path, rev: 0, raw: '', savedRaw: '', base: {}, values: {}, computed: {},
    edits: [], loaded: false, loading: false, saving: false, uncertain: false,
    stale: false, error: '', notice: '',
  });
  return draftStore.documents[draftStore.documents.length - 1]!;
}

export function retainDocumentDraft(draft: DocumentDraft): () => void {
  retained.set(draft, (retained.get(draft) ?? 0) + 1);
  return () => {
    const count = (retained.get(draft) ?? 1) - 1;
    if (count > 0) retained.set(draft, count);
    else retained.delete(draft);
  };
}

function snapshot(draft: DocumentDraft, payload: Record<string, unknown>): void {
  draft.rev = Number(payload.rev ?? 0);
  draft.raw = String(payload.raw ?? '');
  draft.savedRaw = draft.raw;
  draft.base = clone(record(payload.parsed));
  draft.values = clone(draft.base);
  draft.computed = clone(record(payload.computed));
  draft.edits = [];
  draft.loaded = true;
  draft.uncertain = false;
  draft.stale = false;
  draft.error = '';
}

export function discardDocumentDraft(draft: DocumentDraft): void {
  if (draft.saving) throw new Error('Wait for this save to finish before discarding its draft.');
  draft.raw = draft.savedRaw;
  draft.values = clone(draft.base);
  draft.edits = [];
  draft.notice = '';
  // An uncertain write can only be resolved by reading the server again.
  if (!draft.uncertain && !draft.stale) draft.error = '';
}

export async function ensureDocumentDraft(draft: DocumentDraft, channel: RpcChannel): Promise<void> {
  if (draft.loaded) return;
  await reloadDocumentDraft(draft, channel);
}

export async function reloadDocumentDraft(
  draft: DocumentDraft,
  channel: RpcChannel,
  discard = false,
): Promise<void> {
  const pending = loads.get(draft);
  if (pending) return pending;
  if (draft.saving) {
    draft.error = 'Wait for the pending save before reloading.';
    return;
  }
  if (!discard && (draftDirty(draft) || draft.uncertain)) {
    draft.error = 'Unsaved or uncertain changes were kept. Apply them or explicitly discard and reload.';
    return;
  }
  draft.loading = true;
  const load = (async () => {
    try {
      snapshot(draft, unwrap(await ops.get(channel, draft.pack, draft.path)));
      draft.notice = '';
    } catch (error) {
      draft.error = error instanceof Error ? error.message : String(error);
    } finally {
      draft.loading = false;
      loads.delete(draft);
    }
  })();
  loads.set(draft, load);
  return load;
}

export function setDraftField(draft: DocumentDraft, field: string[], value: unknown): void {
  if (draft.saving || draft.uncertain || rawDraftDirty(draft)) {
    throw new Error('Apply or discard the pending YAML/uncertain write before editing fields.');
  }
  if (!field.length || value === undefined) throw new Error('A field path and value are required.');
  draft.values = { ...draft.values };
  assign(draft.values, field, value);
  draft.edits = draft.edits.filter((edit) => !samePath(edit.field, field));
  if (!equal(fieldValue(draft.base, field), value)) {
    draft.edits.push({ field: [...field], value: clone(value) });
  }
  draft.notice = '';
}

/**
 * Stages removal of a key — "reset to inherited value". The key disappears
 * from the draft values; Save & apply sends one revision-checked removal
 * edit, which deletes exactly the key's lines on disk.
 */
export function removeDraftField(draft: DocumentDraft, field: string[]): void {
  if (draft.saving || draft.uncertain || rawDraftDirty(draft)) {
    throw new Error('Apply or discard the pending YAML/uncertain write before editing fields.');
  }
  if (!field.length) throw new Error('A field path is required.');
  draft.values = withoutPath(draft.values, field);
  draft.edits = draft.edits.filter((edit) => !samePath(edit.field, field));
  if (fieldValue(draft.base, field) !== undefined) {
    draft.edits.push({ field: [...field], value: undefined, remove: true });
  }
  draft.notice = '';
}

/** Restores one field to its saved value, dropping any pending edit for it. */
export function revertDraftField(draft: DocumentDraft, field: string[]): void {
  const saved = fieldValue(draft.base, field);
  if (saved === undefined) removeDraftField(draft, field);
  else setDraftField(draft, field, saved);
}

export function pendingRemoval(draft: DocumentDraft, field: string[]): boolean {
  return draft.edits.some((edit) => edit.remove && samePath(edit.field, field));
}

/**
 * The values without the key at `path`, copied along the way. A `delete` on a
 * Svelte state proxy is not enough: Svelte 5 keeps the key's descriptor, so
 * `Object.hasOwn` still reports a removed key and a removed module stayed on
 * screen. A copy has no such residue, and every level it touches is new, so the
 * studio's derived values see the change.
 */
function withoutPath(values: Record<string, unknown>, path: string[]): Record<string, unknown> {
  const [head, ...rest] = path;
  if (head === undefined || !Object.hasOwn(values, head)) return values;
  const copy = { ...values };
  if (rest.length === 0) {
    delete copy[head];
    return copy;
  }
  const child = values[head];
  if (record(child) !== child) return values;
  assignOwn(copy, head, withoutPath(record(child), rest));
  return copy;
}

export async function saveDocumentDraft(draft: DocumentDraft, channel: RpcChannel): Promise<boolean> {
  if (!draftDirty(draft)) return false;
  if (draft.saving || draft.loading || draft.uncertain || draft.stale) {
    draft.error = 'This file needs reconciliation or a reload before it can be applied.';
    return false;
  }
  if (rawDraftDirty(draft) && draft.edits.length) {
    draft.error = 'YAML and field edits cannot be applied together. Keep one representation and discard the other explicitly.';
    return false;
  }
  const raw = rawDraftDirty(draft);
  const submittedRaw = draft.raw;
  const edits = clone(draft.edits);
  draft.saving = true;
  draft.error = '';
  draft.notice = '';
  let acknowledged = false;
  try {
    const result = unwrap(await (raw
      ? ops.put(channel, draft.pack, draft.path, draft.rev, submittedRaw)
      : ops.setFields(channel, draft.pack, draft.path, draft.rev, edits.map((edit) => edit.remove
        ? { field: edit.field, remove: true as const }
        : { field: edit.field, value: edit.value }))));
    acknowledged = true;
    draft.rev = Number(result.rev ?? draft.rev);
    draft.edits = [];
    draft.savedRaw = submittedRaw;
    draft.base = clone(draft.values);
    draft.uncertain = false;
    draft.notice = 'Saved to server files; reload requested. Runtime status is reported separately.';
    const fresh = unwrap(await ops.get(channel, draft.pack, draft.path));
    snapshot(draft, fresh);
    return true;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (acknowledged) {
      draft.stale = true;
      draft.error = `Saved to server files, but the refreshed document could not be read: ${message}. Reload before further editing.`;
      return true;
    }
    if (error instanceof RpcError && !draft.uncertain) {
      draft.stale = error.code === 'MTR-0034';
      draft.error = draft.stale
        ? 'This file changed elsewhere. Your draft was kept; inspect it before discarding and reloading.'
        : message;
    } else {
      draft.uncertain = true;
      draft.error = 'The save outcome is unknown. Your draft was kept. Reconnect and reconcile before applying anything again.';
    }
    return false;
  } finally {
    draft.saving = false;
  }
}

/** Rebase only untouched paths after an explicit asset transaction or reconnect. */
export async function reconcileDocumentDraft(
  draft: DocumentDraft,
  channel: RpcChannel,
  committedFields: string[][] = [],
): Promise<void> {
  if (draft.saving) return;
  draft.loading = true;
  try {
    const fresh = unwrap(await ops.get(channel, draft.pack, draft.path));
    const values = record(fresh.parsed);
    if (rawDraftDirty(draft)) {
      if (String(fresh.raw ?? '') === draft.raw) {
        snapshot(draft, fresh);
        draft.notice = 'Server files match the submitted YAML. Runtime status is reported separately.';
      } else if (String(fresh.raw ?? '') === draft.savedRaw) {
        draft.uncertain = false;
        draft.stale = false;
        draft.error = '';
      } else {
        draft.uncertain = false;
        draft.stale = true;
        draft.error = 'Server files changed while this YAML draft was open. The draft was kept; inspect it before discarding and reloading.';
      }
      return;
    }
    const pending = clone(draft.edits).filter((edit) =>
      !committedFields.some((field) => samePath(field, edit.field)) &&
      !equal(fieldValue(values, edit.field), edit.value));
    const conflicts = pending.filter((edit) =>
      !equal(fieldValue(draft.base, edit.field), fieldValue(values, edit.field)));
    if (conflicts.length) {
      draft.uncertain = false;
      draft.stale = true;
      draft.error = 'Saved files overlap your pending field changes. Your draft was kept; resolve it before applying again.';
      return;
    }
    snapshot(draft, fresh);
    for (const edit of pending) {
      if (edit.remove) removeDraftField(draft, edit.field);
      else setDraftField(draft, edit.field, edit.value);
    }
    draft.notice = pending.length
      ? 'Saved files refreshed; unrelated pending fields were kept and have not been applied.'
      : 'Server files refreshed. Runtime status is reported separately.';
  } catch (error) {
    draft.error = `Could not reconcile server files: ${error instanceof Error ? error.message : String(error)}`;
    draft.stale = true;
  } finally {
    draft.loading = false;
  }
}

export function markDraftsDisconnected(): void {
  for (const draft of draftStore.documents) {
    if (draft.saving) {
      draft.uncertain = true;
      draft.error = 'Connection lost during save; the outcome is unknown. Your draft was kept.';
    }
  }
}

export function resetDrafts(): void {
  draftStore.documents = [];
  loads.clear();
  retained.clear();
}
