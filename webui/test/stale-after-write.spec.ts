/**
 * Issue 461: after an approved `authoring.transaction` (or its revert) changed an existing document, every editor surface
 * shows the server's bytes with no reload. The surfaces all read a document through the draft store, so the spec drives the
 * whole path (review store -> report's changed-documents list -> draft store) over a fake server, and then the other
 * sources of the same staleness (the watcher's push, a reload push no write of this tab explains).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RpcChannel } from '../src/lib/api';
import type { Response } from '../src/lib/protocol/wire';
import { connectReview, disposeReview, reviewActions, reviewAgentPort } from '../src/lib/codemode/review/state.svelte';
import {
  draftDirty, draftStore, ensureDocumentDraft, getDocumentDraft, keepDraftOverServer, readServerVersion, resetDrafts,
  retainDocumentDraft, saveDocumentDraft, setDraftField, syncDraftsWithServer,
} from '../src/lib/drafts.svelte';
import { handlePush, PUSH_REFRESH_DELAY_MS, resetWorkspace, session } from '../src/lib/state.svelte';
import { op, sealed } from './helpers/host';

const PACK = 'demo';
const PATH = 'items/coin.yml';
const trusted = { isTrusted: true } as unknown as Event;

interface Doc {
  rev: number;
  parsed: Record<string, Record<string, unknown>>;
}

/** A server with one stored document, `authoring.transaction` for setFields, and a revert that restores the old copy. */
function server() {
  const docs = new Map<string, Doc>([[PATH, { rev: 1, parsed: { coin: { display_name: 'Coin', material: 'PAPER', lore: ['old'] } } }]]);
  const records = new Map<string, { path: string; before: Doc; after: number }>();
  const calls: string[] = [];
  let next = 1;
  const raw = (doc: Doc): string => JSON.stringify(doc.parsed, null, 2);
  const reply = (op: string, data: Record<string, unknown>): Response => ({ op, id: 'r', ok: true, data });
  const report = (fields: Record<string, unknown>): Record<string, unknown> => ({
    accepted: true, applied: false, dryRun: true, transactionId: null, documents: [], diagnostics: [], stages: [],
    revertible: false, revertNote: null, ...fields,
  });
  const channel: RpcChannel = {
    blob: { sessionId: 's', protocolVersions: [1], capabilities: ['codemode'], pluginSigningPubKey: new Uint8Array(32) },
    async call(name, payload = {}): Promise<Response> {
      calls.push(name);
      if (name === 'browse.get') {
        const doc = docs.get(String(payload.path));
        if (!doc) return { op: name, id: 'r', ok: false, error: { code: 'MTR-0009', message: 'no such document' } };
        return reply(name, { raw: raw(doc), parsed: structuredClone(doc.parsed), rev: doc.rev, computed: {} });
      }
      if (name === 'authoring.setFields') {
        const stored = docs.get(String(payload.path))!;
        if (payload.rev !== stored.rev) {
          return { op: name, id: 'r', ok: false, error: { code: 'MTR-0034', message: 'document changed elsewhere' } };
        }
        const parsed = structuredClone(stored.parsed);
        for (const edit of payload.edits as { field: string[]; value: unknown }[]) (parsed[edit.field[0]!] as Record<string, unknown>)[edit.field[1]!] = edit.value;
        docs.set(String(payload.path), { rev: stored.rev + 1, parsed });
        return reply(name, { rev: stored.rev + 1 });
      }
      if (name === 'authoring.transaction') {
        const operations = payload.operations as { path: string; edits: { field: string[]; value: unknown }[] }[];
        const path = operations[0]!.path;
        const stored = docs.get(path)!;
        const parsed = structuredClone(stored.parsed);
        for (const edit of operations[0]!.edits) (parsed[edit.field[0]!] as Record<string, unknown>)[edit.field[1]!] = edit.value;
        const after: Doc = { rev: stored.rev + 1, parsed };
        const document = { path, kind: 'changed', operations: [0], rev: after.rev, previousRevision: stored.rev, text: raw(after) };
        if (payload.dryRun === true) return reply(name, report({ documents: [document] }));
        docs.set(path, after);
        const id = `tx-${next++}`;
        records.set(id, { path, before: stored, after: after.rev });
        return reply(name, report({ dryRun: false, applied: true, transactionId: id, documents: [document], revertible: true }));
      }
      if (name === 'authoring.revertTransaction') {
        const record = records.get(String(payload.transactionId))!;
        const now = docs.get(record.path)!;
        const restored: Doc = { rev: now.rev + 1, parsed: structuredClone(record.before.parsed) };
        const document = { path: record.path, kind: 'changed', operations: [0], rev: restored.rev, previousRevision: now.rev, text: raw(restored) };
        if (payload.dryRun === true) return reply(name, report({ documents: [document], revertOf: String(payload.transactionId) }));
        docs.set(record.path, restored);
        return reply(name, report({ dryRun: false, applied: true, transactionId: `tx-${next++}`, documents: [document], revertOf: String(payload.transactionId) }));
      }
      return reply(name, {});
    },
  };
  return {
    channel, calls, docs,
    /** Another editor session, a hand edit: the file moves without any report this tab received. */
    external(field: string, value: unknown) {
      const stored = docs.get(PATH)!;
      const parsed = structuredClone(stored.parsed);
      (parsed.coin as Record<string, unknown>)[field] = value;
      docs.set(PATH, { rev: stored.rev + 1, parsed });
    },
  };
}

const retention: Array<() => void> = [];
/** A draft as the detail view or the Files tab holds it: loaded from the server, and retained while shown. */
async function shown(channel: RpcChannel) {
  const draft = getDocumentDraft(PACK, PATH);
  retention.push(retainDocumentDraft(draft));
  await ensureDocumentDraft(draft, channel);
  return draft;
}

async function transact(channel: RpcChannel, edits: { field: string[]; value: unknown }[]): Promise<string> {
  connectReview(channel);
  const stored = (await channel.call('browse.get', { pack: PACK, path: PATH })) as { data: { rev: number } };
  const proposal = await reviewAgentPort()!.propose(
    sealed([op('setFields', { path: PATH, rev: stored.data.rev, edits })], { pack: PACK }),
    { program: 'm.items.setFields(...)' },
  );
  expect(proposal.state).toBe('waiting');
  const result = await reviewActions()!.approveProposal(trusted, proposal.id);
  expect(result.done).toBe(true);
  const transactionId = result.done ? result.value?.transactionId : null;
  if (!transactionId) throw new Error('the proposal did not apply');
  return transactionId;
}

const COIN = [{ field: ['coin', 'display_name'], value: 'Gold Coin' }, { field: ['coin', 'material'], value: 'GOLD_NUGGET' }];

beforeEach(() => {
  resetWorkspace();
  resetDrafts();
});

afterEach(() => {
  retention.splice(0).forEach((release) => release());
  disposeReview();
  resetWorkspace();
  session.channel = null;
});

describe('an approved transaction and its revert reach every open copy of the document', () => {
  it('a document open in a view shows the new name and material, then the old ones after a revert', async () => {
    const fake = server();
    session.channel = fake.channel;
    const draft = await shown(fake.channel);
    expect(draft.values.coin).toMatchObject({ display_name: 'Coin', material: 'PAPER' });
    expect(draft.rev).toBe(1);

    const transactionId = await transact(fake.channel, COIN);
    await vi.waitFor(() => expect(draft.values.coin).toMatchObject({ display_name: 'Gold Coin', material: 'GOLD_NUGGET' }));
    expect(draft.rev).toBe(2);
    expect(draft.raw).toContain('Gold Coin');
    expect(draft.savedRaw).toBe(draft.raw);
    expect(draft.stale).toBe(false);

    const reverted = await reviewActions()!.confirmRevert(trusted, transactionId, PACK);
    expect(reverted.done).toBe(true);
    await vi.waitFor(() => expect(draft.values.coin).toMatchObject({ display_name: 'Coin', material: 'PAPER' }));
    expect(draft.rev).toBe(3);
    expect(draft.raw).toContain('"Coin"');
  });

  it('a copy no view shows is dropped, so the next view to open it reads the new bytes', async () => {
    const fake = server();
    session.channel = fake.channel;
    const draft = getDocumentDraft(PACK, PATH);
    await ensureDocumentDraft(draft, fake.channel); // loaded once, then its view closed (not retained)
    expect(draft.loaded).toBe(true);

    await transact(fake.channel, COIN);
    await vi.waitFor(() => expect(draft.loaded).toBe(false));
    await ensureDocumentDraft(draft, fake.channel);
    expect(draft.values.coin).toMatchObject({ display_name: 'Gold Coin' });
    expect(draft.rev).toBe(2);
  });

  it('a draft that already holds the new revision is left alone (no read)', async () => {
    const fake = server();
    const draft = await shown(fake.channel);
    fake.external('display_name', 'Same');
    await syncDraftsWithServer(fake.channel, [{ pack: PACK, path: PATH, rev: 2 }]);
    expect(draft.rev).toBe(2);
    fake.calls.length = 0;
    await syncDraftsWithServer(fake.channel, [{ pack: PACK, path: PATH, rev: 2 }]);
    expect(fake.calls).toEqual([]);
  });
});

describe('an unsaved draft of a document the server changed is a conflict, never silently overwritten', () => {
  it('keeps the draft and flags it when the same field moved, and the owner can keep it over the server copy', async () => {
    const fake = server();
    session.channel = fake.channel;
    const draft = await shown(fake.channel);
    setDraftField(draft, ['coin', 'display_name'], 'My Coin');

    await transact(fake.channel, COIN);
    await vi.waitFor(() => expect(draft.stale).toBe(true));
    // neither side was overwritten: the edit is there, and so is the old base the edit was made against
    expect(draft.edits).toHaveLength(1);
    expect(draft.values.coin).toMatchObject({ display_name: 'My Coin' });
    expect(draft.rev).toBe(1);
    expect(await saveDocumentDraft(draft, fake.channel)).toBe(false); // blocked until the owner chooses
    expect(fake.docs.get(PATH)!.parsed.coin).toMatchObject({ display_name: 'Gold Coin' });

    // both can be read
    expect((await readServerVersion(draft, fake.channel)).raw).toContain('Gold Coin');

    await keepDraftOverServer(draft, fake.channel);
    expect(draft.stale).toBe(false);
    expect(draft.rev).toBe(2);
    expect(draft.values.coin).toMatchObject({ display_name: 'My Coin', material: 'GOLD_NUGGET' });
    expect(draftDirty(draft)).toBe(true);
    expect(await saveDocumentDraft(draft, fake.channel)).toBe(true);
    expect(fake.docs.get(PATH)!.parsed.coin).toMatchObject({ display_name: 'My Coin', material: 'GOLD_NUGGET' });
  });

  it('keeps edits on other fields on top of the new server copy without flagging a conflict', async () => {
    const fake = server();
    session.channel = fake.channel;
    const draft = await shown(fake.channel);
    setDraftField(draft, ['coin', 'lore'], ['mine']);

    await transact(fake.channel, COIN);
    await vi.waitFor(() => expect(draft.rev).toBe(2));
    expect(draft.stale).toBe(false);
    expect(draft.values.coin).toMatchObject({ display_name: 'Gold Coin', lore: ['mine'] });
    expect(draft.edits).toHaveLength(1);
  });

  it('YAML typed over a file that moved is kept and flagged, and "keep my draft" keeps the text', async () => {
    const fake = server();
    session.channel = fake.channel;
    const draft = await shown(fake.channel);
    draft.raw = `${draft.raw}\n# mine`;

    await transact(fake.channel, COIN);
    await vi.waitFor(() => expect(draft.stale).toBe(true));
    expect(draft.raw.endsWith('# mine')).toBe(true);

    await keepDraftOverServer(draft, fake.channel);
    expect(draft.stale).toBe(false);
    expect(draft.rev).toBe(2);
    expect(draft.savedRaw).toContain('Gold Coin');
    expect(draft.raw.endsWith('# mine')).toBe(true);
    expect(draftDirty(draft)).toBe(true);
  });
});

describe('the same mechanism covers the other ways a document moves outside this tab', () => {
  it('the watcher push (a hand edit) refreshes a clean open draft and flags a conflicting one', async () => {
    const fake = server();
    session.channel = fake.channel;
    const draft = await shown(fake.channel);
    fake.external('display_name', 'Hand edit');
    handlePush({ op: 'push.document.changed', payload: { pack: PACK, path: PATH, deleted: false } });
    await vi.waitFor(() => expect(draft.values.coin).toMatchObject({ display_name: 'Hand edit' }));
    expect(draft.rev).toBe(2);

    setDraftField(draft, ['coin', 'display_name'], 'Mine');
    fake.external('display_name', 'Hand edit again');
    handlePush({ op: 'push.document.changed', payload: { pack: PACK, path: PATH, deleted: false } });
    await vi.waitFor(() => expect(draft.stale).toBe(true));
    expect(draft.values.coin).toMatchObject({ display_name: 'Mine' });
  });

  it('a reload push no write of this tab explains probes the open documents (another editor session wrote)', async () => {
    vi.useFakeTimers();
    try {
      const fake = server();
      session.channel = fake.channel;
      const draft = await shown(fake.channel);
      fake.external('display_name', 'From another session');
      handlePush({ op: 'push.reload', payload: { files: 'files:1', accepted: true, noop: false } });
      await vi.advanceTimersByTimeAsync(PUSH_REFRESH_DELAY_MS + 50);
      expect(draft.values.coin).toMatchObject({ display_name: 'From another session' });
      expect(draft.rev).toBe(2);

      // a reload when nothing moved costs one read per cached document and changes nothing
      fake.calls.length = 0;
      handlePush({ op: 'push.reload', payload: { files: 'files:1', accepted: true, noop: false } });
      await vi.advanceTimersByTimeAsync(PUSH_REFRESH_DELAY_MS + 50);
      expect(fake.calls.filter((call) => call === 'browse.get')).toHaveLength(1);
      expect(draft.notice).toBe('');
    } finally {
      vi.useRealTimers();
    }
  });

  it('a deleted document is reported, not shown as current', async () => {
    const fake = server();
    session.channel = fake.channel;
    const draft = await shown(fake.channel);
    fake.docs.delete(PATH);
    handlePush({ op: 'push.document.changed', payload: { pack: PACK, path: PATH, deleted: true } });
    await vi.waitFor(() => expect(draft.error).toContain('no such document'));
    expect(draftStore.documents).toContain(draft);
  });
});
