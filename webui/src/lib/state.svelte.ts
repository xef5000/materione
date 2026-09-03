/**
 * The app-wide state stores (Svelte 5 runes) plus the actions that mutate
 * them. Stateless by design per web.md §6 — a refresh refetches everything
 * through loadWorkspace(); only unsaved editor content lives in tab state.
 */
import type { AssetRef, DiagnosticEntry, ResourceRef, TypeInfo } from './api';
import { unwrap, ops, assetOps } from './api';
import type { PushFrame } from './events';
import { Signal } from './events';
import type { EditorChannel } from './protocol/session';

/** One open document in the center area — keyed per FILE, not per resource. */
export interface DocTab {
  kind: 'doc';
  key: string; // `${pack}/${path}`
  pack: string;
  path: string;
  id: string; // representative resource id (files may hold several)
  type: string;
  dirty: boolean;
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
  content.open = resource;
}

/** Sends the author to a file in Advanced Files, tab opened and focused. */
export function revealInAdvanced(resource: ResourceRef): void {
  openDoc(resource);
  ui.view = 'advanced';
}

export const diagnostics = $state({
  entries: [] as DiagnosticEntry[],
});

export const activityLog = $state({
  entries: [] as ActivityEntry[],
});

export const connection = $state({
  lastReload: '' as string,
});

export const delivery = $state({
  publishedSha256: '',
  candidateSha256: '',
  loaded: false,
});

export const server = $state({
  version: '',
  protocolVersion: 0,
  editorRunning: false,
  editorPort: 0,
});

/** The live channel and its fan-out signals; wired once by App on connect. */
export const session: { channel: EditorChannel | null } = { channel: null };
export const bus: { push: Signal<PushFrame> } = { push: new Signal<PushFrame>() };

// ------------------------------------------------------------------ actions

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
    }
    workspace.types = ((unwrap(await ops.types(channel)).types as TypeInfo[]) ?? []);
    diagnostics.entries =
      ((unwrap(await ops.diagnostics(channel)).diagnostics as DiagnosticEntry[]) ?? []);
    const info = unwrap(await ops.sessionInfo(channel));
    server.version = String(info.serverVersion ?? '');
    server.protocolVersion = Number(info.protocolVersion ?? 0);
    server.editorRunning = Boolean(info.editorRunning);
    server.editorPort = Number(info.editorPort ?? 0);
  } catch (e) {
    logActivity('error', `metadata: ${errorMessage(e)}`);
  }
}

export async function selectPack(pack: string): Promise<void> {
  workspace.activePack = pack;
  workspace.resources = [];
  workspace.assets = [];
  workspace.folders = [];
  content.folder = '';
  content.selected = null;
  content.open = null;
  await refreshPack();
}

/** Re-fetches resources + assets + folders of the active pack after any mutation. */
export async function refreshPack(): Promise<void> {
  const channel = session.channel;
  const pack = workspace.activePack;
  if (!channel || !pack) return;
  try {
    const [resourceResult, assetResult, folderResult] = [
      (unwrap(await ops.resources(channel, pack)).resources as ResourceRef[]) ?? [],
      (unwrap(await assetOps.list(channel, pack)).assets as AssetRef[]) ?? [],
      (unwrap(await ops.folders(channel, pack)).folders as string[]) ?? [],
    ];
    workspace.resources = resourceResult.sort((a, b) => a.path.localeCompare(b.path));
    workspace.assets = assetResult.sort((a, b) => a.path.localeCompare(b.path));
    workspace.folders = folderResult.sort((a, b) => a.localeCompare(b));
  } catch (e) {
    logActivity('error', `browse: ${errorMessage(e)}`);
  }
}

export async function refreshDelivery(): Promise<void> {
  if (!session.channel) return;
  delivery.loaded = false;
  try {
    const status = unwrap(await ops.deliveryStatus(session.channel));
    delivery.publishedSha256 = String(status.publishedSha256 ?? '');
    delivery.candidateSha256 = String(status.candidateSha256 ?? '');
  } catch (e) {
    logActivity('error', `delivery status: ${errorMessage(e)}`);
  } finally {
    delivery.loaded = true;
  }
}

/** Opens (or focuses) the tab for a resource's FILE and activates it. */
export function openDoc(resource: ResourceRef): void {
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
  context: { pack: string; path: string; rev: number; modelRef: string },
): void {
  const key = `blockbench:${context.pack}/${context.path}`;
  const existing = workspace.tabs.find((t) => t.kind === 'blockbench' && t.key === key);
  if (!existing) {
    workspace.tabs.push({ kind: 'blockbench', key, ...context });
  } else if (existing.kind === 'blockbench') {
    existing.rev = context.rev;
    existing.modelRef = context.modelRef;
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
}

/** Clears the on-disk flag once the tab reloaded the authoritative bytes. */
export function clearDocStale(key: string): void {
  delete workspace.staleDocs[key];
}

export function closeTab(key: string): void {
  const index = workspace.tabs.findIndex((t) => t.key === key);
  if (index < 0) return;
  const tab = workspace.tabs[index]!;
  const isDirty = tab.kind === 'doc' || tab.kind === 'settings' ? tab.dirty : false;
  if (isDirty && !confirm('Discard unsaved changes in this tab?')) {
    return;
  }
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
  workspace.packs = [];
  workspace.activePack = null;
  workspace.resources = [];
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
  diagnostics.entries = [];
  activityLog.entries = [];
  connection.lastReload = '';
  delivery.publishedSha256 = '';
  delivery.candidateSha256 = '';
  delivery.loaded = false;
  server.version = '';
  server.protocolVersion = 0;
  session.channel = null;
  bus.push.clear();
}
