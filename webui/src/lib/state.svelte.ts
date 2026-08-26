/**
 * The app-wide state stores (Svelte 5 runes) plus the actions that mutate
 * them. Stateless by design per web.md §6 — a refresh refetches everything
 * through loadWorkspace(); only unsaved editor content lives in tab state.
 */
import type { DiagnosticEntry, ResourceRef, TypeInfo } from './api';
import { unwrap, ops } from './api';
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

export type Tab = DocTab;

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
  tabs: [] as Tab[],
  activeKey: null as string | null,
});

export const diagnostics = $state({
  entries: [] as DiagnosticEntry[],
});

export const activityLog = $state({
  entries: [] as ActivityEntry[],
});

export const connection = $state({
  lastReload: '' as string,
});

export const server = $state({
  version: '',
  protocolVersion: 0,
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
  } catch (e) {
    logActivity('error', `metadata: ${errorMessage(e)}`);
  }
}

export async function selectPack(pack: string): Promise<void> {
  workspace.activePack = pack;
  workspace.resources = [];
  const channel = session.channel;
  if (!channel) return;
  try {
    const resources =
      (unwrap(await ops.resources(channel, pack)).resources as ResourceRef[]) ?? [];
    workspace.resources = resources.sort((a, b) => a.path.localeCompare(b.path));
  } catch (e) {
    logActivity('error', `browse.resources: ${errorMessage(e)}`);
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

export function setTabDirty(key: string, dirty: boolean): void {
  const tab = workspace.tabs.find((t) => t.key === key);
  if (tab && tab.dirty !== dirty) {
    tab.dirty = dirty;
  }
}

export function closeTab(key: string): void {
  const index = workspace.tabs.findIndex((t) => t.key === key);
  if (index < 0) return;
  if (workspace.tabs[index]!.dirty && !confirm('Discard unsaved changes in this tab?')) {
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
  workspace.tabs = [];
  workspace.activeKey = null;
  diagnostics.entries = [];
  activityLog.entries = [];
  connection.lastReload = '';
  server.version = '';
  server.protocolVersion = 0;
  session.channel = null;
  bus.push.clear();
}
