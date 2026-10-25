import { beforeEach, describe, expect, it } from 'vitest';
import type { RpcChannel } from '../src/lib/api';
import type { Response } from '../src/lib/protocol/wire';
import {
  discardDocumentDraft, draftDirty, ensureDocumentDraft, getDocumentDraft,
  MAX_DOCUMENT_DRAFTS, rawDraftDirty, reconcileDocumentDraft, resetDrafts,
  retainDocumentDraft, saveDocumentDraft, setDraftField,
} from '../src/lib/drafts.svelte';

function fixture() {
  let parsed: Record<string, Record<string, unknown>> = {
    first: { material: 'PAPER', display_name: 'First', unknown: { keep: true } },
    second: { material: 'GOLD_NUGGET', display_name: 'Second', lore: ['a', '', 'b'] },
  };
  let raw = '# keep CRLF\r\nfirst:\r\n  material: PAPER\r\n';
  let rev = 2;
  const calls: { op: string; payload: Record<string, unknown> }[] = [];
  let failWrite: 'conflict' | 'transport' | null = null;
  let failRead = false;
  const channel: RpcChannel = {
    async call(op, payload = {}): Promise<Response> {
      calls.push({ op, payload });
      if (op === 'browse.get') {
        if (failRead) throw new Error('read failed');
        return { op, id: 'read', ok: true, data: { raw, parsed, rev } };
      }
      if (failWrite === 'conflict') {
        return { op, id: 'write', ok: false, error: { code: 'MTR-0034', message: 'changed' } };
      }
      if (failWrite === 'transport') throw new Error('closed');
      if (op === 'authoring.setFields') {
        for (const edit of payload.edits as { field: string[]; value: unknown }[]) {
          parsed = structuredClone(parsed);
          parsed[edit.field[0]!]![edit.field[1]!] = edit.value;
        }
      } else if (op === 'edit.put') {
        raw = String(payload.content);
      } else {
        throw new Error(`unexpected ${op}`);
      }
      rev += 1;
      return { op, id: 'write', ok: true, data: { rev } };
    },
  };
  return {
    channel, calls,
    set failWrite(value: typeof failWrite) { failWrite = value; },
    set failRead(value: boolean) { failRead = value; },
    remote(root: string, field: string, value: unknown) {
      parsed = structuredClone(parsed);
      parsed[root]![field] = value;
      rev += 1;
    },
    set raw(value: string) { raw = value; rev += 1; },
  };
}

beforeEach(resetDrafts);

describe('session document drafts', () => {
  it('shares a file across resource surfaces and does not rewrite an untouched file', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    expect(getDocumentDraft('pack', 'items/shared.yml')).toBe(draft);
    await ensureDocumentDraft(draft, server.channel);
    const original = draft.raw;
    expect(await saveDocumentDraft(draft, server.channel)).toBe(false);
    expect(server.calls.map((call) => call.op)).toEqual(['browse.get']);
    expect(draft.raw).toBe(original);
  });

  it('stages multiple roots and fields until a single guarded transaction', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    setDraftField(draft, ['second', 'display_name'], 'New second');
    setDraftField(draft, ['first', 'material'], 'DIAMOND');
    expect(server.calls).toHaveLength(1);
    expect(draft.values.first).toMatchObject({ unknown: { keep: true } });
    expect(await saveDocumentDraft(draft, server.channel)).toBe(true);
    const writes = server.calls.filter((call) => call.op === 'authoring.setFields');
    expect(writes).toHaveLength(1);
    expect(writes[0]!.payload).toMatchObject({
      rev: 2,
      edits: [
        { field: ['second', 'display_name'], value: 'New second' },
        { field: ['first', 'material'], value: 'DIAMOND' },
      ],
    });
    expect(draftDirty(draft)).toBe(false);
    expect(draft.values.second).toMatchObject({ lore: ['a', '', 'b'] });
  });

  it('removes reverted patches without writing defaults or unknown fields', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    setDraftField(draft, ['first', 'material'], 'DIAMOND');
    setDraftField(draft, ['first', 'material'], 'PAPER');
    expect(draftDirty(draft)).toBe(false);
    expect(await saveDocumentDraft(draft, server.channel)).toBe(false);
  });

  it('treats prototype-like authored keys as ordinary document data', () => {
    const draft = getDocumentDraft('pack', 'items/keys.yml');
    setDraftField(draft, ['__proto__', 'display_name'], 'Authored');
    expect(Object.hasOwn(draft.values, '__proto__')).toBe(true);
    expect(draft.values.__proto__).toEqual({ display_name: 'Authored' });
    expect(Object.getPrototypeOf(draft.values)).toBe(Object.prototype);
  });

  it('never mixes a pending raw buffer with field patches', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    draft.raw += '# draft\r\n';
    expect(rawDraftDirty(draft)).toBe(true);
    expect(() => setDraftField(draft, ['first', 'material'], 'DIAMOND')).toThrow('YAML');
    discardDocumentDraft(draft);
    expect(rawDraftDirty(draft)).toBe(false);
  });

  it('keeps edits and blocks retry after a stale revision', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    setDraftField(draft, ['first', 'display_name'], 'Pending');
    server.failWrite = 'conflict';
    expect(await saveDocumentDraft(draft, server.channel)).toBe(false);
    expect(draft.stale).toBe(true);
    expect(draftDirty(draft)).toBe(true);
    expect(draft.values.first).toMatchObject({ display_name: 'Pending' });
    const count = server.calls.length;
    await saveDocumentDraft(draft, server.channel);
    expect(server.calls).toHaveLength(count);
  });

  it('keeps uncertain writes and reconciles without automatically replaying', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    setDraftField(draft, ['first', 'display_name'], 'Pending');
    server.failWrite = 'transport';
    await saveDocumentDraft(draft, server.channel);
    expect(draft.uncertain).toBe(true);
    server.failWrite = null;
    await reconcileDocumentDraft(draft, server.channel);
    expect(draft.uncertain).toBe(false);
    expect(draftDirty(draft)).toBe(true);
    expect(server.calls.filter((call) => call.op === 'authoring.setFields')).toHaveLength(1);
  });

  it('does not replay a successful write when only the subsequent read failed', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    setDraftField(draft, ['first', 'display_name'], 'Saved');
    server.failRead = true;
    expect(await saveDocumentDraft(draft, server.channel)).toBe(true);
    expect(draft.uncertain).toBe(false);
    expect(draft.stale).toBe(true);
    expect(draft.error).toContain('Saved to server files');
    expect(draftDirty(draft)).toBe(false);
    await saveDocumentDraft(draft, server.channel);
    expect(server.calls.filter((call) => call.op === 'authoring.setFields')).toHaveLength(1);
  });

  it('rebases asset attachment changes while preserving unrelated pending names', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    setDraftField(draft, ['second', 'display_name'], 'Still pending');
    server.remote('second', 'model', 'items/new_model');
    await reconcileDocumentDraft(draft, server.channel, [['second', 'model']]);
    expect(draft.values.second).toMatchObject({
      display_name: 'Still pending', model: 'items/new_model',
    });
    expect(draft.edits).toEqual([{ field: ['second', 'display_name'], value: 'Still pending' }]);
    expect(server.calls.every((call) => call.op === 'browse.get')).toBe(true);
  });

  it('does not guess a merge when server edits overlap pending values', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    setDraftField(draft, ['second', 'display_name'], 'Mine');
    server.remote('second', 'display_name', 'Theirs');
    await reconcileDocumentDraft(draft, server.channel);
    expect(draft.stale).toBe(true);
    expect(draft.values.second).toMatchObject({ display_name: 'Mine' });
    expect(draft.error).toContain('overlap');
  });

  it('recognizes current server YAML after an uncertain raw save without replay', async () => {
    const server = fixture();
    const draft = getDocumentDraft('pack', 'items/shared.yml');
    await ensureDocumentDraft(draft, server.channel);
    draft.raw += '# intended\r\n';
    draft.uncertain = true;
    server.raw = draft.raw;
    await reconcileDocumentDraft(draft, server.channel);
    expect(draft.uncertain).toBe(false);
    expect(draftDirty(draft)).toBe(false);
  });

  it('bounds memory without evicting dirty or retained editors', () => {
    const releases: (() => void)[] = [];
    for (let i = 0; i < MAX_DOCUMENT_DRAFTS; i++) {
      releases.push(retainDocumentDraft(getDocumentDraft('pack', `items/${i}.yml`)));
    }
    expect(() => getDocumentDraft('pack', 'items/extra.yml')).toThrow('limit');
    releases[0]!();
    expect(getDocumentDraft('pack', 'items/extra.yml').path).toBe('items/extra.yml');
  });
});
