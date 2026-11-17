import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RpcChannel } from '../src/lib/api';
import type { Response } from '../src/lib/protocol/wire';
import {
  draftDirty, draftStore, ensureDocumentDraft, getDocumentDraft, persistableDrafts,
  reconcileDocumentDraft, resetDrafts, restoreDocumentDraft, saveDocumentDraft, setDraftField,
} from '../src/lib/drafts.svelte';
import {
  draftPersister, KEPT_DRAFT_TTL_MS, memoryDraftStorage, readKeptDrafts,
} from '../src/lib/draft-persistence';

/** A server that, like the plugin since #427, answers a write with the full view. */
function server(fullView = true) {
  let parsed: Record<string, Record<string, unknown>> = {
    item: { material: 'PAPER', display_name: 'Ruby' },
  };
  let rev = 4;
  const raw = () => `item:\n  material: ${String(parsed.item!.material)}\n  display_name: ${String(parsed.item!.display_name)}\n`;
  const computed = { inheritance: { item: { template: 'base' } } };
  const calls: string[] = [];
  const channel: RpcChannel = {
    async call(op, payload = {}): Promise<Response> {
      calls.push(op);
      if (op === 'browse.get') return { op, id: 'r', ok: true, data: { raw: raw(), parsed, rev, computed } };
      if (op === 'authoring.setFields') {
        if (payload.rev !== rev) {
          return { op, id: 'w', ok: false, error: { code: 'MTR-0034', message: 'changed' } };
        }
        parsed = structuredClone(parsed);
        for (const edit of payload.edits as { field: string[]; value: unknown }[]) {
          parsed[edit.field[0]!]![edit.field[1]!] = edit.value;
        }
        rev += 1;
        return {
          op, id: 'w', ok: true,
          data: fullView ? { rev, raw: raw(), parsed, computed } : { rev, raw: raw(), parsed },
        };
      }
      throw new Error(`unexpected ${op}`);
    },
  };
  return {
    channel, calls,
    remote(field: string, value: unknown) {
      parsed = structuredClone(parsed);
      parsed.item![field] = value;
      rev += 1;
    },
  };
}

beforeEach(resetDrafts);
afterEach(() => vi.useRealTimers());

describe('save round trips (#427)', () => {
  it('saves K edits in one RPC when the reply carries the full view', async () => {
    const remote = server();
    const draft = getDocumentDraft('pack', 'items/ruby.yml');
    await ensureDocumentDraft(draft, remote.channel);
    remote.calls.length = 0;
    setDraftField(draft, ['item', 'material'], 'DIAMOND');
    setDraftField(draft, ['item', 'display_name'], 'Gem');
    expect(await saveDocumentDraft(draft, remote.channel)).toBe(true);
    expect(remote.calls).toEqual(['authoring.setFields']);
    expect(draft.rev).toBe(5);
    expect(draft.values.item).toEqual({ material: 'DIAMOND', display_name: 'Gem' });
    expect(draft.computed).toEqual({ inheritance: { item: { template: 'base' } } });
    expect(draftDirty(draft)).toBe(false);
  });

  it('reads the document back from a server that does not return the full view', async () => {
    const remote = server(false);
    const draft = getDocumentDraft('pack', 'items/ruby.yml');
    await ensureDocumentDraft(draft, remote.channel);
    remote.calls.length = 0;
    setDraftField(draft, ['item', 'material'], 'DIAMOND');
    expect(await saveDocumentDraft(draft, remote.channel)).toBe(true);
    expect(remote.calls).toEqual(['authoring.setFields', 'browse.get']);
  });
});

describe('local drafts (#427)', () => {
  it('keeps only dirty drafts, and restores edits on their own base revision', async () => {
    const remote = server();
    const clean = getDocumentDraft('pack', 'items/clean.yml');
    await ensureDocumentDraft(clean, remote.channel);
    const draft = getDocumentDraft('pack', 'items/ruby.yml');
    await ensureDocumentDraft(draft, remote.channel);
    setDraftField(draft, ['item', 'material'], 'DIAMOND');
    const kept = persistableDrafts();
    expect(kept.map((entry) => entry.path)).toEqual(['items/ruby.yml']);
    expect(kept[0]).toMatchObject({ rev: 4, edits: [{ field: ['item', 'material'], value: 'DIAMOND' }] });

    resetDrafts();
    const restored = restoreDocumentDraft(kept[0]!)!;
    expect(restored.rev).toBe(4);
    expect(restored.values.item).toEqual({ material: 'DIAMOND', display_name: 'Ruby' });
    expect(draftDirty(restored)).toBe(true);
    expect(restoreDocumentDraft(kept[0]!)).toBeNull();
    expect(draftStore.documents).toHaveLength(1);
  });

  it('rebases a restored draft onto an unchanged server and saves it in one RPC', async () => {
    const remote = server();
    const draft = getDocumentDraft('pack', 'items/ruby.yml');
    await ensureDocumentDraft(draft, remote.channel);
    setDraftField(draft, ['item', 'material'], 'DIAMOND');
    const [kept] = persistableDrafts();
    resetDrafts();

    const restored = restoreDocumentDraft(kept!)!;
    await reconcileDocumentDraft(restored, remote.channel);
    expect(restored.stale).toBe(false);
    expect(restored.edits).toEqual([{ field: ['item', 'material'], value: 'DIAMOND' }]);
    remote.calls.length = 0;
    expect(await saveDocumentDraft(restored, remote.channel)).toBe(true);
    expect(remote.calls).toEqual(['authoring.setFields']);
  });

  it('never silently applies a restored draft whose base changed underneath it', async () => {
    const remote = server();
    const draft = getDocumentDraft('pack', 'items/ruby.yml');
    await ensureDocumentDraft(draft, remote.channel);
    setDraftField(draft, ['item', 'material'], 'DIAMOND');
    const [kept] = persistableDrafts();
    resetDrafts();
    remote.remote('material', 'EMERALD');

    const restored = restoreDocumentDraft(kept!)!;
    await reconcileDocumentDraft(restored, remote.channel);
    expect(restored.stale).toBe(true);
    expect(restored.values.item).toMatchObject({ material: 'DIAMOND' });
    remote.calls.length = 0;
    expect(await saveDocumentDraft(restored, remote.channel)).toBe(false);
    expect(remote.calls).toEqual([]);
  });

  it('coalesces snapshots into one write per pause and deletes the record when nothing is dirty', async () => {
    vi.useFakeTimers();
    const storage = memoryDraftStorage();
    const writes = vi.spyOn(storage, 'write');
    const persister = draftPersister(storage, 'server-a', 250);
    const entry = {
      pack: 'pack', path: 'items/ruby.yml', rev: 4, raw: '', savedRaw: '', base: {}, computed: {},
      edits: [{ field: ['item', 'material'], value: 'DIAMOND' }],
    };
    persister.save([entry]);
    persister.save([entry]);
    persister.save([{ ...entry, edits: [{ field: ['item', 'material'], value: 'GOLD' }] }]);
    await vi.advanceTimersByTimeAsync(300);
    expect(writes).toHaveBeenCalledTimes(1);
    expect((await readKeptDrafts(storage, 'server-a'))[0]!.edits[0]!.value).toBe('GOLD');
    expect(await readKeptDrafts(storage, 'server-b')).toEqual([]);

    persister.save([]);
    await persister.flush();
    expect(storage.records.has('server-a')).toBe(false);
  });

  it('drops kept drafts past their age limit', async () => {
    const storage = memoryDraftStorage();
    await storage.write('server-a', [{
      pack: 'pack', path: 'items/ruby.yml', rev: 4, raw: '', savedRaw: '', base: {}, computed: {}, edits: [],
    }]);
    const savedAt = storage.records.get('server-a')!.savedAt;
    expect(await readKeptDrafts(storage, 'server-a', savedAt + KEPT_DRAFT_TTL_MS + 1)).toEqual([]);
    expect(storage.records.has('server-a')).toBe(false);
  });
});
