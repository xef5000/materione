/**
 * The app-wide state stores (Svelte 5 runes) plus the actions that mutate
 * them. Stateless by design per web.md §6 — a refresh refetches everything
 * through loadWorkspace(); only unsaved editor content lives in tab state.
 */
import type { AssetRef, DiagnosticEntry, ItemLink, ResourceRef, ResourceSummary, RpcChannel, TemplateEntry, TypeInfo } from './api';
import { RpcError, unwrap, ops, assetOps } from './api';
import type { BehaviorVocabulary } from './behavior';
import type { PushFrame } from './events';
import { Signal } from './events';
import { documentChangedEffect } from './external';
import { invalidateThumbnails, resetThumbnailCache } from './thumbnails';
import {
  discardDocumentDraft, draftDirty, draftStore, getDocumentDraft,
  markDraftsDisconnected, resetDrafts, syncDraftsWithServer,
  type ServerChange,
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
  /** Stable across inserts at the top of the log, so the dock keys rows and animates only the new one. */
  id: number;
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
  /** Every template of every pack, as the server derives them (`authoring.templates`). */
  templates: [] as TemplateEntry[],
  /** Every item's authored `template:` link, so the picker can keep an item from closing a loop. */
  itemLinks: [] as ItemLink[],
  visualVersion: 0,
  types: [] as TypeInfo[],
  /** Registered events, actions and mechanics (`authoring.describe`); null on jars that predate it. */
  behavior: null as BehaviorVocabulary | null,
  assets: [] as AssetRef[],
  /** Pack-relative organisational folders under the type roots. */
  folders: [] as string[],
  tabs: [] as Tab[],
  activeKey: null as string | null,
  /** Directory paths the explorer has collapsed (default is expanded). */
  collapsed: {} as Record<string, boolean>,
});

/**
 * Which rail view owns the main area. Lives in the store so any view can send
 * the author somewhere else ("reveal in Advanced Files") without prop drilling.
 */
export const ui = $state({
  view: 'content' as 'start' | 'content' | 'assets' | 'advanced' | 'diagnostics' | 'server',
  railCollapsed: false,
  activityOpen: false,
  /** The command palette (Ctrl+K). */
  paletteOpen: false,
  /** Where "Back" returns to after a hop out of Assets into a resource's studio. */
  returnTo: null as { view: 'assets'; label: string } | null,
});

/** The Assets workspace's standing: which asset is selected. */
export const assetsUi = $state({ selected: null as string | null });

/** Opens Assets on one asset ("where is this used?" from a studio's texture slot). */
export function showAssetUsage(path: string): void {
  if (!canLeaveEditor('studio')) return;
  assetsUi.selected = path;
  ui.returnTo = null;
  ui.view = 'assets';
}

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
  /** A field the studio should bring into view once the entry is open (Problems → Fix). */
  focus: null as { pack: string; path: string; id: string; field: string } | null,
  /** A type whose "New …" dialog Content should open (Start's starters). */
  createRequest: null as string | null,
});

/** Moves the browser to a folder inside the current type tab. */
export function browseTo(type: string, folder = ''): void {
  content.type = type;
  content.folder = folder;
  content.selected = null;
}

/** Opens an entry in its studio; the browser stays where it was. */
export function openStudio(resource: ResourceRef | null, field?: string): void {
  content.focus = resource && field
    ? { pack: resource.pack, path: resource.path, id: resource.id, field }
    : null;
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

export async function revealInStudio(resource: ResourceRef, field?: string): Promise<void> {
  if (workspace.activePack !== resource.pack) {
    await selectPack(resource.pack);
    if (workspace.activePack !== resource.pack) return;
  }
  openStudio(resource, field);
}

/** Opens Content on a type tab with its "New …" dialog (Start's create starters). */
export function startCreate(type: string): void {
  if (!canLeaveEditor('studio')) return;
  content.open = null;
  browseTo(type);
  content.createRequest = type;
  ui.view = 'content';
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
  /** Which pipe this session rides (web.md §4.4); '' until a channel opens. */
  transport: '' as '' | 'cloud' | 'direct',
  /** Plain-words why (Server view); informational, never an error. */
  transportNote: '',
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
    if (pushRefresh.fallback) clearTimeout(pushRefresh.fallback);
    pushRefresh.fallback = null;
    // A document save reloads just that file set ("files:N"); asset writes,
    // folder moves and manual reloads are pack-wide. Only the latter can
    // change image bytes, so only they re-read every visible thumbnail.
    if (!String(payload.files ?? '').startsWith('files:')) {
      invalidateThumbnails();
      workspace.visualVersion++;
    }
    // A reload no write of this tab explains (another editor session, a hand edit, a manual run) may have moved a
    // document an open surface still holds: the burst's one refresh also probes every cached document by revision.
    scheduleRefresh({ pack: true, delivery: true, drafts: true });
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
    // The watcher reports documents only (never assets), so thumbnails stay.
    // A different pack's document can be referenced by the active pack.
    if (workspace.activePack) scheduleRefresh({ pack: true });
    if (session.channel) void syncDraftsWithServer(session.channel, [change]);
    logActivity('info', effect.log);
  }
  bus.push.emit(frame);
}

/**
 * The documents a write of this tab changed behind the open editors' backs (an approved code-mode transaction or its
 * revert: the server's report lists them). The one entry point beside the watcher's push: the catalog refreshes once
 * (through the write's reload push, or alone when none comes) and every cached copy of a listed document is
 * invalidated or reconciled through the draft store (`syncDraftsWithServer`), so no surface keeps the old bytes.
 */
export function noteDocumentsChanged(changes: readonly ServerChange[]): void {
  if (changes.length === 0) return;
  expectReloadRefresh();
  if (session.channel) void syncDraftsWithServer(session.channel, changes);
}

/** Quiet period that lets one burst of pushes (a git pull, a reload) share one refresh. */
export const PUSH_REFRESH_DELAY_MS = 150;
/** How long a write waits for its reload push before refreshing on its own. */
export const RELOAD_PUSH_FALLBACK_MS = 10_000;

const pushRefresh = {
  pack: false,
  delivery: false,
  drafts: false,
  timer: null as ReturnType<typeof setTimeout> | null,
  fallback: null as ReturnType<typeof setTimeout> | null,
  running: false,
};

/**
 * Every write reloads on the server, and the reload's push refreshes the
 * catalog — so a write does not refresh on its own as well. Only an accepted
 * no-op reload announces nothing; the fallback covers that case.
 */
export function expectReloadRefresh(): void {
  if (pushRefresh.fallback) clearTimeout(pushRefresh.fallback);
  pushRefresh.fallback = setTimeout(() => {
    pushRefresh.fallback = null;
    scheduleRefresh({ pack: true, delivery: true });
  }, RELOAD_PUSH_FALLBACK_MS);
}

/**
 * Pushes ask for refreshes; this answers each burst with at most one
 * `refreshPack` and one `refreshDelivery`. A request that lands while one is
 * running is folded into a single trailing refresh, never a parallel one.
 */
function scheduleRefresh(what: { pack?: boolean; delivery?: boolean; drafts?: boolean }): void {
  pushRefresh.pack ||= what.pack === true;
  pushRefresh.delivery ||= what.delivery === true;
  pushRefresh.drafts ||= what.drafts === true;
  if (pushRefresh.timer || pushRefresh.running) return;
  pushRefresh.timer = setTimeout(() => void runScheduledRefresh(), PUSH_REFRESH_DELAY_MS);
}

async function runScheduledRefresh(): Promise<void> {
  pushRefresh.timer = null;
  const { pack, delivery, drafts } = pushRefresh;
  pushRefresh.pack = false;
  pushRefresh.delivery = false;
  pushRefresh.drafts = false;
  pushRefresh.running = true;
  try {
    await Promise.all([
      pack ? refreshPack() : undefined,
      delivery ? refreshDelivery() : undefined,
      drafts && session.channel ? syncDraftsWithServer(session.channel) : undefined,
    ]);
  } finally {
    pushRefresh.running = false;
    if (pushRefresh.pack || pushRefresh.delivery || pushRefresh.drafts) scheduleRefresh({});
  }
}

function cancelScheduledRefresh(): void {
  if (pushRefresh.timer) clearTimeout(pushRefresh.timer);
  if (pushRefresh.fallback) clearTimeout(pushRefresh.fallback);
  pushRefresh.timer = null;
  pushRefresh.fallback = null;
  pushRefresh.pack = false;
  pushRefresh.delivery = false;
  pushRefresh.drafts = false;
}

function sortedInsert<T>(list: T[], value: T, key: (entry: T) => string): T[] {
  return [...list.filter((entry) => key(entry) !== key(value)), value]
    .sort((a, b) => key(a).localeCompare(key(b)));
}

/**
 * A create's reply already names the new resource: list it now instead of
 * spending a catalog round. The reload push the write causes brings the
 * server's full view (health, visuals) through the scheduled refresh.
 */
export function noteCreatedResource(resource: ResourceRef, name: string): void {
  if (resource.pack !== workspace.activePack) return;
  workspace.resources = sortedInsert(workspace.resources, resource,
    (entry) => `${entry.path}\u0000${entry.id}`);
  workspace.summaries = [
    ...workspace.summaries.filter((entry) => entry.path !== resource.path || entry.id !== resource.id),
    { ...resource, name, health: 'unknown' },
  ];
  expectReloadRefresh();
  const segments = resource.path.split('/').slice(0, -1);
  let folders = workspace.folders;
  for (let depth = 1; depth <= segments.length; depth++) {
    const folder = segments.slice(0, depth).join('/');
    if (!folders.includes(folder)) folders = sortedInsert(folders, folder, (entry) => entry);
  }
  workspace.folders = folders;
}

let activitySeq = 0;

export function logActivity(kind: ActivityKind, text: string): void {
  activityLog.entries.unshift({ id: ++activitySeq, ts: Date.now(), kind, text });
  if (activityLog.entries.length > 200) {
    activityLog.entries.length = 200;
  }
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * Initial metadata fetch after the handshake; safe to call again anytime.
 *
 * Every request that does not depend on the pack list is issued at once, so
 * the transport carries them as one round trip (one batched relay post); a
 * known active pack's catalog joins that round. Only a first load, which must
 * learn the pack list before it can pick a pack, spends a second round.
 * Each part fails on its own: one bad answer does not blank the others.
 */
export async function loadWorkspace(): Promise<void> {
  const channel = session.channel;
  if (!channel) return;
  const metadata = (task: Promise<unknown>) =>
    task.catch((e: unknown) => logActivity('error', `metadata: ${errorMessage(e)}`));
  const packList = metadata((async () => {
    workspace.packs = (unwrap(await ops.packs(channel)).packs as string[]) ?? [];
  })());
  const knownPack = workspace.activePack;
  await Promise.all([
    packList,
    knownPack ? refreshPack() : Promise.resolve(),
    metadata(loadCapabilities(channel)),
    metadata((async () => {
      diagnostics.entries =
        ((unwrap(await ops.diagnostics(channel)).diagnostics as DiagnosticEntry[]) ?? []);
    })()),
    metadata((async () => {
      const info = unwrap(await ops.sessionInfo(channel));
      server.version = String(info.serverVersion ?? '');
      server.minecraftVersion = String(info.minecraftVersion ?? '');
      server.protocolVersion = Number(info.protocolVersion ?? 0);
      server.editorRunning = Boolean(info.editorRunning);
      server.editorPort = Number(info.editorPort ?? 0);
    })()),
  ]);
  if (!knownPack && !workspace.activePack && workspace.packs.length) {
    await selectPack(workspace.packs[0]!);
  }
}

/**
 * Registry-driven capability discovery: `authoring.describe` returns content
 * types and the behaviour vocabulary together. A jar that predates it answers
 * with an error; then types come from `browse.types` and behaviour authoring
 * says it is unavailable rather than guessing.
 */
async function loadCapabilities(channel: RpcChannel): Promise<void> {
  try {
    const described = unwrap(await ops.describe(channel));
    workspace.types = (described.types as TypeInfo[]) ?? [];
    workspace.behavior = (described.behavior as BehaviorVocabulary | undefined) ?? null;
    return;
  } catch (e) {
    if (!(e instanceof RpcError)) throw e;
    workspace.behavior = null;
  }
  workspace.types = ((unwrap(await ops.types(channel)).types as TypeInfo[]) ?? []);
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

/** Templates are a convenience layer: a server without the op simply offers none. */
async function loadTemplates(channel: RpcChannel): Promise<{ templates: TemplateEntry[]; itemLinks: ItemLink[] }> {
  try {
    const data = unwrap(await ops.templates(channel));
    return {
      templates: Array.isArray(data.templates) ? data.templates as TemplateEntry[] : [],
      // absent on a plugin that predates items as templates: the picker then offers no items
      itemLinks: Array.isArray(data.itemLinks) ? data.itemLinks as ItemLink[] : [],
    };
  } catch {
    return { templates: [], itemLinks: [] };
  }
}

/** Refresh read-only catalog metadata; an older response must not replace a newer snapshot. */
export async function refreshPack(): Promise<void> {
  const channel = session.channel;
  const pack = workspace.activePack;
  if (!channel || !pack) return;
  const generation = ++packRefresh;
  try {
    const [resources, assets, folders, summaries, templates] = await Promise.all([
      ops.resources(channel, pack), assetOps.list(channel, pack), ops.folders(channel, pack),
      loadResourceSummaries(channel, pack), loadTemplates(channel),
    ]);
    if (generation !== packRefresh || pack !== workspace.activePack || channel !== session.channel) return;
    workspace.resources = ((unwrap(resources).resources as ResourceRef[]) ?? []).sort((a, b) => a.path.localeCompare(b.path));
    workspace.assets = ((unwrap(assets).assets as AssetRef[]) ?? []).sort((a, b) => a.path.localeCompare(b.path));
    workspace.folders = ((unwrap(folders).folders as string[]) ?? []).sort((a, b) => a.localeCompare(b));
    workspace.summaries = summaries.resources;
    workspace.summariesError = summaries.error;
    workspace.templates = templates.templates;
    workspace.itemLinks = templates.itemLinks;
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
  cancelScheduledRefresh();
  resetThumbnailCache();
  workspace.packs = [];
  workspace.activePack = null;
  workspace.resources = [];
  workspace.summaries = [];
  workspace.templates = [];
  workspace.itemLinks = [];
  workspace.summariesError = '';
  workspace.visualVersion = 0;
  workspace.types = [];
  workspace.behavior = null;
  workspace.assets = [];
  workspace.folders = [];
  workspace.tabs = [];
  workspace.activeKey = null;
  workspace.collapsed = {};
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
  connection.transport = '';
  connection.transportNote = '';
  server.version = '';
  server.minecraftVersion = '';
  server.protocolVersion = 0;
  session.channel = null;
  bus.push.clear();
  resetDrafts();
  editorGuards.clear();
}
