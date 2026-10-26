import { beforeEach, describe, expect, it } from 'vitest';
import {
  connection, content, handlePush, hasUnsavedChanges, loadWorkspace, markConnectionLost,
  openDoc, openStudio, registerEditorGuard, requireSavedDocument,
  refreshPack, resetWorkspace, revealInAdvanced, session, ui, workspace,
} from '../src/lib/state.svelte';
import { getDocumentDraft, setDraftField } from '../src/lib/drafts.svelte';

beforeEach(resetWorkspace);

describe('workspace context and draft safety', () => {
  it('refreshes preserved pack listings without dropping the open resource', async () => {
    const resource = { pack: 'pack', path: 'items/shared.yml', type: 'item', id: 'second' };
    workspace.activePack = resource.pack;
    openStudio(resource);
    const calls: string[] = [];
    const responses: Record<string, Record<string, unknown>> = {
      'browse.packs': { packs: ['pack'] },
      'browse.resources': { resources: [resource] },
      'authoring.summaries': { resources: [{ ...resource, name: 'Second gem', health: 'unknown' }], total: 1 },
      'assets.list': { assets: [{ path: 'assets/new.png', kind: 'png', bytes: 1 }] },
      'browse.folders': { folders: ['items', 'items/new'] },
      'browse.types': { types: [] },
      'admin.diagnostics.recent': { diagnostics: [] },
      'session.info': {},
    };
    session.channel = { call: async (op) => {
      calls.push(op);
      if (!responses[op]) throw new Error(`Unexpected operation ${op}`);
      return { op, id: 'test', ok: true, data: responses[op] };
    } };
    await loadWorkspace();
    expect(calls).toContain('browse.resources');
    expect(workspace.resources).toEqual([resource]);
    expect(workspace.summaries[0]?.name).toBe('Second gem');
    expect(workspace.summariesError).toBe('');
    expect(workspace.folders).toContain('items/new');
    expect(workspace.assets[0]?.path).toBe('assets/new.png');
    expect(content.open).toEqual(resource);
  });

  it('keeps the resource browser available when summary metadata fails', async () => {
    const resource = { pack: 'pack', path: 'items/item.yml', type: 'item', id: 'item' };
    workspace.activePack = 'pack';
    session.channel = { call: async (op) => {
      if (op === 'authoring.summaries') throw new Error('Summary read failed');
      const data = op === 'browse.resources' ? { resources: [resource] } :
        op === 'assets.list' ? { assets: [] } : { folders: [] };
      return { op, id: 'test', ok: true, data };
    } };
    await refreshPack();
    expect(workspace.resources).toEqual([resource]);
    expect(workspace.summaries).toEqual([]);
    expect(workspace.summariesError).toContain('Summary read failed');
  });

  it('does not accept an older bulk snapshot after a newer refresh', async () => {
    workspace.activePack = 'pack';
    let finishFirst: ((value: { resources: []; total: number }) => void) | undefined;
    const first = new Promise<{ resources: []; total: number }>((resolve) => { finishFirst = resolve; });
    let summaryReads = 0;
    session.channel = { call: async (op) => {
      const data = op === 'authoring.summaries'
        ? (++summaryReads === 1 ? await first : { resources: [{ pack: 'pack', path: 'items/new.yml', type: 'item', id: 'new', name: 'New', health: 'unknown' }], total: 1 })
        : op === 'browse.resources' ? { resources: [] } :
          op === 'assets.list' ? { assets: [] } : { folders: [] };
      return { op, id: 'test', ok: true, data };
    } };
    const oldRefresh = refreshPack();
    await refreshPack();
    finishFirst!({ resources: [], total: 0 });
    await oldRefresh;
    expect(workspace.summaries[0]?.name).toBe('New');
  });

  it('retains the selected root and source location when reusing a shared file tab', async () => {
    const resource = { pack: 'pack', path: 'items/shared.yml', type: 'item', id: 'first' };
    workspace.activePack = resource.pack;
    openDoc(resource);
    openDoc({ ...resource, id: 'second' });
    expect(workspace.tabs).toHaveLength(1);
    expect(workspace.tabs[0]).toMatchObject({ kind: 'doc', id: 'second' });
    await revealInAdvanced({ ...resource, id: 'first' }, { line: 8, column: 3 });
    expect(ui.view).toBe('advanced');
    expect(workspace.tabs[0]).toMatchObject({ id: 'first', source: { line: 8, column: 3 } });
  });

  it('retains structured drafts when the Studio is closed or hidden', () => {
    const resource = { pack: 'pack', path: 'items/shared.yml', type: 'item', id: 'second' };
    workspace.activePack = resource.pack;
    openStudio(resource);
    const draft = getDocumentDraft(resource.pack, resource.path);
    setDraftField(draft, ['second', 'display_name'], 'Pending');
    revealInAdvanced(resource);
    expect(content.open).toEqual(resource);
    openStudio(null);
    expect(getDocumentDraft(resource.pack, resource.path).edits).toEqual([
      { field: ['second', 'display_name'], value: 'Pending' },
    ]);
    expect(hasUnsavedChanges()).toBe(true);
  });

  it('blocks destructive file and containing-folder operations before any RPC', () => {
    const draft = getDocumentDraft('pack', 'items/nested/item.yml');
    setDraftField(draft, ['item', 'material'], 'PAPER');
    expect(() => requireSavedDocument('pack', 'items/nested/item.yml')).toThrow('pending');
    expect(() => requireSavedDocument('pack', 'items', true)).toThrow('pending');
    expect(() => requireSavedDocument('another', 'items', true)).not.toThrow();
  });

  it('keeps the workbench and uncertain draft on disconnect', () => {
    const resource = { pack: 'pack', path: 'items/shared.yml', type: 'item', id: 'second' };
    openStudio(resource);
    const draft = getDocumentDraft(resource.pack, resource.path);
    setDraftField(draft, ['second', 'display_name'], 'Pending');
    draft.saving = true;
    connection.connected = true;
    connection.lastReload = 'old successful reload';
    markConnectionLost('closed');
    expect(connection.connected).toBe(false);
    expect(connection.lastReload).toBe('');
    expect(session.channel).toBeNull();
    expect(content.open).toEqual(resource);
    expect(draft.uncertain).toBe(true);
    expect(draft.edits).toHaveLength(1);
  });

  it('includes unsaved asset work in the unload guard', () => {
    const release = registerEditorGuard('studio', () => true, () => true);
    expect(hasUnsavedChanges()).toBe(true);
    release();
    expect(hasUnsavedChanges()).toBe(false);
  });

  it('reports global reload and quarantine without marking a document saved', () => {
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    setDraftField(draft, ['second', 'display_name'], 'Pending');
    handlePush({
      op: 'push.reload',
      payload: { accepted: true, noop: false, itemsLive: 3, quarantined: 1 },
    });
    expect(connection.lastReload).toContain('server-wide');
    expect(connection.lastReload).toContain('quarantined: 1');
    expect(draft.edits).toHaveLength(1);
  });
});
