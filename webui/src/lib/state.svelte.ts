/**
 * The app-wide state stores (Svelte 5 runes) plus the actions that mutate
 * them. Stateless by design per web.md §6 — a refresh refetches everything
 * through loadWorkspace(); only unsaved editor content lives in tab state.
 */
import type { AssetRef, DiagnosticEntry, ResourceRef, ResourceSummary, RpcChannel, TypeInfo } from './api';
import { unwrap, ops, assetOps } from './api';
import type { PushFrame } from './events';
import { Signal } from './events';
import { documentChangedEffect } from './external';
import { invalidateThumbnails, resetThumbnailCache } from './thumbnails';
import {
  discardDocumentDraft, draftDirty, draftStore, getDocumentDraft,
  markDraftsDisconnected, resetDrafts,
} from './drafts.svelte';

/** One open document in the center area — keyed per FILE, not per resource. */
export interface DocTab {
  kind: 'doc';
  key: string; // `${pack}/${path}`
  pack: string;
  path: string;
  id: string; // representative resource id (files may hold several)
  type: string;
  dirty: boolean;
  source?: { line: number; column: number };
}

export type Tab = DocTab | AssetTab | SettingsTab | BlockbenchTab;

/** The embedded Blockbench workbench (pinned web build in an iframe). */
export interface BlockbenchTab {
  kind: 'blockbench';
  key: string;
  pack: string;
  path: string;
  rev: number;
  modelRef: string;
  rootId: string;
}

/** A binary asset opened as a read-only preview tab. */
export interface AssetTab {
  kind: 'asset';
  key: string; // `a:${pack}/${path}`
  pack: string;
  path: string;
  name: string;
}

/** The plugin settings editor — a singleton tab. */
export interface SettingsTab {
  kind: 'settings';
  key: 'settings';
  dirty: boolean;
}

export type ActivityKind = 'save' | 'reload' | 'error' | 'info';

export interface ActivityEntry {
  ts: number;
  kind: ActivityKind;
  text: string;
}

export const workspace = $state({
  packs: [] as string[],
  activePack: null as string | null,
  resources: [] as ResourceRef[],
  summaries: [] as ResourceSummary[],
  summariesError: '',
  visualVersion: 0,
  types: [] as TypeInfo[],
  assets: [] as AssetRef[],
  /** Pack-relative organisational folders under the type roots. */
  folders: [] as string[],
  tabs: [] as Tab[],
  activeKey: null as string | null,
  /** Directory paths the explorer has collapsed (default is expanded). */
  collapsed: {} as Record<string, boolean>,
  /** Documents changed on disk (push.document.changed) — keyed like doc tabs. */
  staleDocs: {} as Record<string, boolean>,
});

/**
 * Which rail view owns the main area. Lives in the store so any view can send
 * the author somewhere else ("reveal in Advanced Files") without prop drilling.
 */
export const ui = $state({
  view: 'content' as 'content' | 'advanced' | 'diagnostics' | 'server',
  railCollapsed: false,
  activityOpen: false,
});

/**
 * Where the Content tab is standing: which type tab, which folder inside it,
 * what is selected, and which entry (if any) is open in its studio. Purely
 * navigational — the documents themselves stay on disk (D1).
 */
export const content = $state({
  type: 'items',
  folder: '',
  selected: null as string | null,
  layout: 'details' as 'details' | 'grid',
  search: '',
  open: null as ResourceRef | null,
});

/** Moves the browser to a folder inside the current type tab. */
export function browseTo(type: string, folder = ''): void {
  content.type = type;
  content.folder = folder;
  content.selected = null;
}

/** Opens an entry in its studio; the browser stays where it was. */
export function openStudio(resource: ResourceRef | null): void {
  if (resource?.pack === content.open?.pack && resource?.path === content.open?.path &&
      resource?.id === content.open?.id) {
    ui.view = 'content';
    return;
  }
  if (!canLeaveEditor('studio')) return;
  if (resource && !prepareDraft(resource.pack, resource.path)) return;
  content.open = resource;
  if (resource) {
    content.type = resource.path.split('/')[0] ?? resource.type;
    ui.view = 'content';
  }
}

/** Sends the author to a file in Advanced Files, tab opened and focused. */
export async function revealInAdvanced(
  resource: ResourceRef,
  source?: DocTab['source'],
): Promise<void> {
  if (workspace.activePack !== resource.pack) {
    await selectPack(resource.pack);
    if (workspace.activePack !== resource.pack) return;
  }
  openDoc(resource);
  const tab = workspace.tabs.find((entry) =>
    entry.kind === 'doc' && entry.pack === resource.pack && entry.path === resource.path);
  if (tab?.kind !== 'doc') return;
  tab.source = source;
  ui.view = 'advanced';
}

export async function revealInStudio(resource: ResourceRef): Promise<void> {
  if (workspace.activePack !== resource.pack) {
    await selectPack(resource.pack);
    if (workspace.activePack !== resource.pack) return;
  }
  openStudio(resource);
}

export const diagnostics = $state({
  entries: [] as DiagnosticEntry[],
});

export const activityLog = $state({
  entries: [] as ActivityEntry[],
});

export const connection = $state({
  lastReload: '' as string,
  connected: false,
  error: '',
});

export const delivery = $state({
  publishedSha256: '',
  candidateSha256: '',
  loaded: false,
  error: '',
});

export const server = $state({
  version: '',
  /** Probed Minecraft version; empty from plugins that predate the field. */
  minecraftVersion: '',
  protocolVersion: 0,
  editorRunning: false,
  editorPort: 0,
});

/** The live channel and its fan-out signals; wired once by App on connect. */
export const session: { channel: RpcChannel | null } = { channel: null };
export const bus: { push: Signal<PushFrame> } = { push: new Signal<PushFrame>() };

// ------------------------------------------------------------------ actions

const editorGuards = new Map<string, { leave: () => boolean; pending: () => boolean }>();

export function registerEditorGuard(
  key: string,
  guard: () => boolean,
  pending: () => boolean = () => false,
): () => void {
  const entry = { leave: guard, pending };
  editorGuards.set(key, entry);
  return () => { if (editorGuards.get(key) === entry) editorGuards.delete(key); };
}

export function canLeaveEditor(key: string): boolean {
  return editorGuards.get(key)?.leave() ?? true;
}

export function requireSavedDocument(pack: string, path: string, folder = false): void {
  const affected = draftStore.documents.filter((draft) => draft.pack === pack &&
    (draft.path === path || (folder && draft.path.startsWith(`${path}/`))));
  if (affected.some((draft) => draftDirty(draft) || draft.saving || draft.uncertain)) {
    throw new Error('Apply or explicitly discard pending document edits before moving or deleting this content.');
  }
}

export function hasUnsavedChanges(): boolean {
  return draftStore.documents.some((draft) => draftDirty(draft) || draft.saving || draft.uncertain) ||
    workspace.tabs.some((tab) => (tab.kind === 'settings' && tab.dirty) || tab.kind === 'blockbench') ||
    [...editorGuards.values()].some((entry) => entry.pending());
}

export function confirmDisconnect(): boolean {
  if ([...editorGuards.values()].some((entry) => !entry.leave())) return false;
  const pendingDocuments = draftStore.documents.some((draft) =>
    draftDirty(draft) || draft.saving || draft.uncertain) ||
    workspace.tabs.some((tab) => tab.kind === 'settings' && tab.dirty);
  return !pendingDocuments ||
    confirm('Disconnect and discard local drafts? Server writes are not undone, including uncertain saves. Cancel to return and Save & apply first.');
}

function prepareDraft(pack: string, path: string): boolean {
  try {
    getDocumentDraft(pack, path);
    return true;
  } catch (error) {
    logActivity('error', errorMessage(error));
    return false;
  }
}

export function markConnectionLost(reason: string): void {
  connection.connected = false;
  connection.error = reason;
  connection.lastReload = '';
  delivery.loaded = false;
  markDraftsDisconnected();
  session.channel = null;
  resetThumbnailCache();
  workspace.visualVersion++;
  logActivity('error', 'Disconnected. Drafts were kept in this tab; runtime and pack status are unknown.');
}

export function handlePush(frame: PushFrame): void {
  const payload = frame.payload;
  if (frame.op === 'push.reload') {
    const outcome = payload.accepted === true
      ? payload.noop === true ? 'no changes' : 'completed'
      : 'rejected';
    const line = `Latest server-wide reload: ${outcome}; live items: ${String(payload.itemsLive ?? 'unknown')}; quarantined: ${String(payload.quarantined ?? 'unknown')}`;
    connection.lastReload = line;
    logActivity('reload', line);
    void refreshDelivery();
    invalidateThumbnails();
    workspace.visualVersion++;
    void refreshPack();
  } else if (frame.op === 'push.diagnostic') {
    if (typeof payload.code === 'string' && typeof payload.severity === 'string' &&
        typeof payload.message === 'string') {
      diagnostics.entries = [{
        code: payload.code, severity: payload.severity, message: payload.message,
        resource: typeof payload.resource === 'string' ? payload.resource : undefined,
        location: typeof payload.location === 'string' ? payload.location : undefined,
        hint: typeof payload.hint === 'string' ? payload.hint : undefined,
      }, ...diagnostics.entries].slice(0, 200);
    } else {
      logActivity('error', 'The server sent an invalid diagnostic.');
    }
  } else if (frame.op === 'push.document.changed') {
    const change = {
      pack: String(payload.pack ?? ''), path: String(payload.path ?? ''),
      deleted: Boolean(payload.deleted),
    };
    const effect = documentChangedEffect(change, workspace.activePack,
      draftStore.documents.map((draft) => `${draft.pack}/${draft.path}`));
    invalidateThumbnails();
    workspace.visualVersion++;
    // A different pack's asset can be referenced by the active pack.
    if (workspace.activePack) void refreshPack();
    for (const _key of effect.staleKeys) markDocStale(change.pack, change.path);
    logActivity('info', effect.log);
  }
  bus.push.emit(frame);
}

export function logActivity(kind: ActivityKind, text: string): void {
  activityLog.entries.unshift({ ts: Date.now(), kind, text });
  if (activityLog.entries.length > 200) {
    activityLog.entries.length = 200;
  }
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** Initial metadata fetch after the handshake; safe to call again anytime. */
export async function loadWorkspace(): Promise<void> {
  const channel = session.channel;
  if (!channel) return;
  try {
    const packs = unwrap(await ops.packs(channel)).packs as string[];
    workspace.packs = packs ?? [];
    if (workspace.packs.length && !workspace.activePack) {
      await selectPack(workspace.packs[0]!);
    } else if (workspace.activePack) {
      await refreshPack();
    }
    workspace.types = ((unwrap(await ops.types(channel)).types as TypeInfo[]) ?? []);
    diagnostics.entries =
      ((unwrap(await ops.diagnostics(channel)).diagnostics as DiagnosticEntry[]) ?? []);
    const info = unwrap(await ops.sessionInfo(channel));
    server.version = String(info.serverVersion ?? '');
    server.minecraftVersion = String(info.minecraftVersion ?? '');
    server.protocolVersion = Number(info.protocolVersion ?? 0);
    server.editorRunning = Boolean(info.editorRunning);
    server.editorPort = Number(info.editorPort ?? 0);
  } catch (e) {
    logActivity('error', `metadata: ${errorMessage(e)}`);
  }
}

export async function selectPack(pack: string): Promise<void> {
  if (pack === workspace.activePack) return;
  if (!canLeaveEditor('studio')) return;
  workspace.activePack = pack;
  workspace.resources = [];
  workspace.summaries = [];
  workspace.summariesError = '';
  workspace.assets = [];
  workspace.folders = [];
  content.folder = '';
  content.selected = null;
  content.open = null;
  await refreshPack();
}

let packRefresh = 0;

async function loadResourceSummaries(channel: RpcChannel, pack: string) {
  try {
    const data = unwrap(await ops.summaries(channel, pack));
    if (!Array.isArray(data.resources)) throw new Error('The server returned an invalid resource summary response.');
    return { resources: data.resources as ResourceSummary[], error: '' };
  } catch (error) {
    return { resources: [] as ResourceSummary[], error: `Resource summaries unavailable: ${errorMessage(error)}` };
  }
}

/** Refresh read-only catalog metadata; an older response must not replace a newer snapshot. */
export async function refreshPack(): Promise<void> {
  const channel = session.channel;
  const pack = workspace.activePack;
  if (!channel || !pack) return;
  const generation = ++packRefresh;
  try {
    const [resources, assets, folders, summaries] = await Promise.all([
      ops.resources(channel, pack), assetOps.list(channel, pack), ops.folders(channel, pack),
      loadResourceSummaries(channel, pack),
    ]);
    if (generation !== packRefresh || pack !== workspace.activePack || channel !== session.channel) return;
    workspace.resources = ((unwrap(resources).resources as ResourceRef[]) ?? []).sort((a, b) => a.path.localeCompare(b.path));
    workspace.assets = ((unwrap(assets).assets as AssetRef[]) ?? []).sort((a, b) => a.path.localeCompare(b.path));
    workspace.folders = ((unwrap(folders).folders as string[]) ?? []).sort((a, b) => a.localeCompare(b));
    workspace.summaries = summaries.resources;
    workspace.summariesError = summaries.error;
    if (summaries.error) logActivity('error', summaries.error);
  } catch (e) {
    logActivity('error', `browse: ${errorMessage(e)}`);
  }
}

export async function refreshDelivery(): Promise<void> {
  const channel = session.channel;
  if (!channel) return;
  delivery.loaded = false;
  delivery.error = '';
  try {
    const status = unwrap(await ops.deliveryStatus(channel));
    if (channel !== session.channel) return;
    delivery.publishedSha256 = String(status.publishedSha256 ?? '');
    delivery.candidateSha256 = String(status.candidateSha256 ?? '');
    delivery.loaded = true;
  } catch (e) {
    if (channel !== session.channel) return;
    delivery.error = errorMessage(e);
    logActivity('error', `delivery status: ${errorMessage(e)}`);
  }
}

/** Opens (or focuses) the tab for a resource's FILE and activates it. */
export function openDoc(resource: ResourceRef): void {
  if (!prepareDraft(resource.pack, resource.path)) return;
  const key = `${resource.pack}/${resource.path}`;
  let tab = workspace.tabs.find((t) => t.kind === 'doc' && t.key === key);
  if (!tab) {
    tab = {
      kind: 'doc',
      key,
      pack: resource.pack,
      path: resource.path,
      id: resource.id,
      type: resource.type,
      dirty: false,
    };
    workspace.tabs.push(tab);
  } else if (tab.kind === 'doc') {
    tab.id = resource.id;
    tab.type = resource.type;
  }
  workspace.activeKey = key;
}

/** Opens a read-only asset preview tab. */
export function openAsset(pack: string, path: string, name: string): void {
  const key = `a:${pack}/${path}`;
  if (!workspace.tabs.some((t) => t.kind === 'asset' && t.key === key)) {
    workspace.tabs.push({ kind: 'asset', key, pack, path, name });
  }
  workspace.activeKey = key;
}

/** Opens the singleton plugin-settings tab. */
export function openSettings(): void {
  if (!workspace.tabs.some((t) => t.kind === 'settings')) {
    workspace.tabs.push({ kind: 'settings', key: 'settings', dirty: false });
  }
  workspace.activeKey = 'settings';
}

/** Opens the singleton embedded-Blockbench tab. */
export function openBlockbench(
  context: { pack: string; path: string; rev: number; modelRef: string; rootId?: string },
): void {
  const key = `blockbench:${context.pack}/${context.path}`;
  const previous = workspace.tabs.find((tab) => tab.kind === 'blockbench' && tab.key !== key);
  if (previous) {
    closeTab(previous.key);
    if (workspace.tabs.includes(previous)) return;
  }
  let existing = workspace.tabs.find((t) => t.kind === 'blockbench' && t.key === key);
  if (existing?.kind === 'blockbench' && existing.rootId !== (context.rootId ?? '')) {
    closeTab(existing.key);
    if (workspace.tabs.includes(existing)) return;
    existing = undefined;
  }
  if (!existing) {
    workspace.tabs.push({ kind: 'blockbench', key, ...context, rootId: context.rootId ?? '' });
  }
  workspace.activeKey = key;
}

export function setTabDirty(key: string, dirty: boolean): void {
  const tab = workspace.tabs.find((t) => t.key === key);
  if (!tab || (tab.kind !== 'doc' && tab.kind !== 'settings')) {
    return;
  }
  if (tab.dirty !== dirty) {
    tab.dirty = dirty;
  }
}

/** Flags one open document as changed on disk (never auto-clobbers edits). */
export function markDocStale(pack: string, path: string): void {
  workspace.staleDocs[`${pack}/${path}`] = true;
  const draft = draftStore.documents.find((item) => item.pack === pack && item.path === path);
  if (draft) draft.stale = true;
}

/** Clears the on-disk flag once the tab reloaded the authoritative bytes. */
export function clearDocStale(key: string): void {
  delete workspace.staleDocs[key];
  const draft = draftStore.documents.find((item) => `${item.pack}/${item.path}` === key);
  if (draft) draft.stale = false;
}

export function closeTab(key: string): void {
  const index = workspace.tabs.findIndex((t) => t.key === key);
  if (index < 0) return;
  const tab = workspace.tabs[index]!;
  if (!canLeaveEditor(key)) return;
  const draft = tab.kind === 'doc'
    ? draftStore.documents.find((item) => item.pack === tab.pack && item.path === tab.path)
    : undefined;
  if (draft?.saving || draft?.uncertain) {
    logActivity('error', 'Keep this tab open until the pending or uncertain save is reconciled.');
    return;
  }
  const isDirty = draft ? draftDirty(draft) : tab.kind === 'settings' ? tab.dirty : false;
  if (isDirty && !confirm('Discard pending changes for this document in all views? Cancel to return and Save & apply first.')) {
    return;
  }
  if (draft && isDirty) discardDocumentDraft(draft);
  workspace.tabs.splice(index, 1);
  if (workspace.activeKey === key) {
    const neighbour = workspace.tabs[Math.min(index, workspace.tabs.length - 1)];
    workspace.activeKey = neighbour?.key ?? null;
  }
}

export function activeTab(): Tab | undefined {
  return workspace.tabs.find((t) => t.key === workspace.activeKey);
}

/** Clears every store — used when the channel closes or the user disconnects. */
export function resetWorkspace(): void {
  packRefresh++;
  resetThumbnailCache();
  workspace.packs = [];
  workspace.activePack = null;
  workspace.resources = [];
  workspace.summaries = [];
  workspace.summariesError = '';
  workspace.visualVersion = 0;
  workspace.types = [];
  workspace.assets = [];
  workspace.folders = [];
  workspace.tabs = [];
  workspace.activeKey = null;
  workspace.collapsed = {};
  workspace.staleDocs = {};
  content.type = 'items';
  content.folder = '';
  content.selected = null;
  content.search = '';
  content.open = null;
  ui.view = 'content';
  ui.activityOpen = false;
  diagnostics.entries = [];
  activityLog.entries = [];
  connection.lastReload = '';
  delivery.publishedSha256 = '';
  delivery.candidateSha256 = '';
  delivery.loaded = false;
  delivery.error = '';
  connection.connected = false;
  connection.error = '';
  server.version = '';
  server.minecraftVersion = '';
  server.protocolVersion = 0;
  session.channel = null;
  bus.push.clear();
  resetDrafts();
  editorGuards.clear();
}
