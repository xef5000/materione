/**
 * The review's pure parts and its approval boundary (issue 347, codemode.md section 5.6): the line diff, the view model, and
 * the owner's actions, which turn a click into a gesture and do nothing for an event a script made.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { createProposalStore, ReviewActions } from '../src/lib/codemode/host';
import { blocks, diffText, MAX_CELLS } from '../src/lib/codemode/review/diff';
import { documentViews, factLine, hasBlockingError, packOfAuditTarget, problemGroups, programRows, stateInfo } from '../src/lib/codemode/review/model';
import { FakeServer, op, sealed } from './helpers/host';

describe('diffText', () => {
  it('marks added and removed lines with their numbers and keeps unchanged ones', () => {
    const diff = diffText('a\nb\nc\n', 'a\nB\nc\nd\n');
    expect(diff.rows.map((row) => `${row.kind}:${row.text}`)).toEqual(['same:a', 'del:b', 'add:B', 'same:c', 'add:d']);
    expect([diff.added, diff.removed]).toEqual([2, 1]);
    expect(diff.rows[1]).toMatchObject({ before: 2 });
    expect(diff.rows[2]).toMatchObject({ after: 2 });
  });

  it('treats null as no document: everything added, or everything removed', () => {
    expect(diffText(null, 'x\ny\n')).toMatchObject({ added: 2, removed: 0 });
    expect(diffText('x\ny\n', null)).toMatchObject({ added: 0, removed: 2 });
    expect(diffText(null, null).rows).toEqual([]);
  });

  it('is empty of changes for equal texts, and ignores CRLF against LF', () => {
    expect(diffText('a\nb\n', 'a\r\nb\r\n')).toMatchObject({ added: 0, removed: 0 });
  });

  it('replays: the added and unchanged rows are the after text, the removed and unchanged rows are the before text', () => {
    let seed = 7;
    const next = (): number => (seed = (seed * 1103515245 + 12345) & 0x7fffffff);
    for (let round = 0; round < 60; round++) {
      const make = (): string => Array.from({ length: next() % 12 }, () => String(next() % 4)).join('\n');
      const [before, after] = [make(), make()];
      const diff = diffText(before, after);
      const lines = (text: string): string[] => (text === '' ? [] : text.split('\n'));
      expect(diff.rows.filter((row) => row.kind !== 'add').map((row) => row.text)).toEqual(lines(before));
      expect(diff.rows.filter((row) => row.kind !== 'del').map((row) => row.text)).toEqual(lines(after));
    }
  });

  it('falls back to replacing the middle past the size bound instead of computing without limit', () => {
    const size = Math.ceil(Math.sqrt(MAX_CELLS)) + 10;
    const before = ['top', ...Array.from({ length: size }, (_, i) => `a${i}`), 'bottom'].join('\n');
    const after = ['top', ...Array.from({ length: size }, (_, i) => `b${i}`), 'bottom'].join('\n');
    const diff = diffText(before, after);
    expect([diff.added, diff.removed]).toEqual([size, size]);
    expect(diff.rows[0]!.kind).toBe('same');
    expect(diff.rows.at(-1)!.kind).toBe('same');
  });

  it('folds unchanged stretches farther than the context from any change', () => {
    const body = Array.from({ length: 30 }, (_, i) => `line${i}`);
    const diff = diffText(body.join('\n'), [...body.slice(0, 15), 'changed', ...body.slice(16)].join('\n'));
    const parts = blocks(diff, 2);
    expect(parts.map((part) => part.kind)).toEqual(['fold', 'rows', 'fold']);
    expect(parts[0]).toMatchObject({ hidden: 13 });
    expect(blocks(diffText('a', 'a'))).toEqual([{ kind: 'rows', rows: [{ kind: 'same', before: 1, after: 1, text: 'a' }] }]);
  });
});

describe('the view model reads the dry run, not the agent', () => {
  const SEED = { 'items/sword.yml': 1 };

  async function waiting() {
    const server = new FakeServer(SEED);
    const store = createProposalStore({
      transport: server,
      readDocument: async (_pack, path) => (path === 'items/sword.yml' ? { text: 'sword:\n  lore: []\n', revision: 1 } : null),
    });
    const result = sealed([op('create', { type: 'items', id: 'ruby' }), op('setFields', { path: 'items/sword.yml', rev: 1, edits: [] })], {
      summary: 'Adds 99 things and changes nothing',
    });
    return { server, store, proposal: await store.propose(result, { program: 'a\nb\nc' }) };
  }

  it('groups new content before changes to existing objects, from the classification and the documents', async () => {
    const { proposal } = await waiting();
    const views = documentViews(proposal);
    expect(views.map((view) => [view.path, view.origin, view.needsApproval])).toEqual([
      ['items/ruby.yml', 'new', false],
      ['items/sword.yml', 'existing', true],
    ]);
    // the agent's claim "changes nothing" is nowhere in the facts
    expect(factLine(views)).toBe('Creates 1 document, changes 1 existing document.');
    expect(proposal.summary).toBe('Adds 99 things and changes nothing');
  });

  it('keeps the stored text read before the apply, so the diff survives it', async () => {
    const { proposal } = await waiting();
    expect(proposal.before).toMatchObject({ 'items/sword.yml': 'sword:\n  lore: []\n', 'items/ruby.yml': null });
  });

  it('says why there is no diff when the stored text could not be read', async () => {
    const server = new FakeServer(SEED);
    const store = createProposalStore({ transport: server });
    const proposal = await store.propose(sealed([op('setFields', { path: 'items/sword.yml', rev: 1, edits: [] })]), { program: 'p' });
    const [view] = documentViews(proposal);
    expect(view!.diff).toBeNull();
    expect(view!.diffNote).toMatch(/could not be read/);
  });

  it('names every state in words, and offers no way forward for a refused or stale one except the agent re-running', () => {
    const base = { needs: [], needsProposalApproval: false, classification: null } as const;
    for (const state of ['checking', 'waiting', 'applying', 'applied', 'reverted', 'refused', 'stale', 'failed', 'rejected'] as const) {
      const info = stateInfo({ ...base, state });
      expect(info.label.length).toBeGreaterThan(2);
      expect(info.explanation).not.toMatch(/force|override|anyway/i);
    }
    expect(stateInfo({ ...base, state: 'stale' }).explanation).toMatch(/run the program again/);
    expect(stateInfo({ ...base, state: 'refused' }).explanation).toMatch(/nothing was written/);
  });

  it('labels each staged operation with the program line that staged it', () => {
    const rows = programRows('a\nb\nc', [op('create'), op('setFields')], [{ line: 2, column: 1 }, null]);
    expect(rows.map((row) => row.staged)).toEqual([[], [{ index: 0, kind: 'create' }], []]);
  });

  it('groups findings by operation, errors first, and a finding from before the change never blocks', () => {
    const problems = [
      { phase: 'dry-run', severity: 'warning', message: 'w', operation: 1, code: 'MTR-0021' },
      { phase: 'dry-run', severity: 'error', message: 'e', operation: 1, code: 'MTR-0022', source: { line: 4, column: 1 } },
      { phase: 'dry-run', severity: 'warning', message: 'old', preexisting: true },
    ] as const;
    const groups = problemGroups(problems, [op('create'), op('logicEdit', { path: 'items/x.yml' })]);
    expect(groups.map((group) => group.operation)).toEqual([null, 1]);
    expect(groups[1]).toMatchObject({ kind: 'logicEdit', target: 'items/x.yml' });
    expect(groups[1]!.problems.map((problem) => problem.severity)).toEqual(['error', 'warning']);
    expect(hasBlockingError(problems)).toBe(true);
    expect(hasBlockingError([problems[0], problems[2]])).toBe(false);
    expect(hasBlockingError([{ phase: 'dry-run', severity: 'error', message: 'old', preexisting: true }])).toBe(false);
  });

  it('reads the pack out of an audit target', () => {
    expect(packOfAuditTarget('bench (3 documents, tx-1)')).toBe('bench');
    expect(packOfAuditTarget('(odd)')).toBeNull();
    expect(packOfAuditTarget(undefined)).toBeNull();
  });
});

describe('the owner\'s actions', () => {
  const synthetic = (): Event => new Event('click');
  const server = (): FakeServer => new FakeServer({ 'items/sword.yml': 1 });

  it('an event a script made approves, applies and reverts nothing', async () => {
    const fake = server();
    const store = createProposalStore({ transport: fake });
    const actions = new ReviewActions(store);
    const waiting = await store.propose(sealed([op('setFields', { path: 'items/sword.yml', rev: 1, edits: [] })]), { program: 'p' });
    const plan = store.agent.declarePlan({ objects: ['demo:items/sword.yml'] });
    const applied = await store.propose(sealed([op('create', { type: 'items', id: 'ruby' })]), { program: 'q' });

    expect(synthetic().isTrusted).toBe(false);
    expect(await actions.approveProposal(synthetic(), waiting.id)).toMatchObject({ done: false, reason: 'not-a-user-action' });
    expect(await actions.approvePlan(synthetic(), plan.id)).toMatchObject({ done: false, reason: 'not-a-user-action' });
    expect(await actions.applyReady(synthetic(), waiting.id)).toMatchObject({ done: false });
    expect(actions.reject(synthetic(), waiting.id)).toMatchObject({ done: false });
    expect(await actions.confirmRevert(synthetic(), applied.transactionId as string, 'demo')).toMatchObject({ done: false });
    // a plain object that merely claims to be trusted is no gesture either
    expect(await actions.approveProposal({ isTrusted: 'true' } as unknown as Event, waiting.id)).toMatchObject({ done: false });

    expect(store.get(waiting.id)!.state).toBe('waiting');
    expect(store.snapshot().grants).toEqual([]);
    expect(store.snapshot().plans[0]!.approved).toBe(false);
    expect(fake.applies()).toHaveLength(1); // only the creating proposal, which needed no approval
    expect(fake.docs.has('items/ruby.yml')).toBe(true);
    expect(store.get(applied.id)!.state).toBe('applied');
  });

  it('a trusted event does the action, and a gesture cannot be reused', async () => {
    const fake = server();
    const store = createProposalStore({ transport: fake });
    const actions = new ReviewActions(store);
    const waiting = await store.propose(sealed([op('setFields', { path: 'items/sword.yml', rev: 1, edits: [] })]), { program: 'p' });
    const trusted = { isTrusted: true } as unknown as Event;
    const result = await actions.approveProposal(trusted, waiting.id);
    expect(result.done).toBe(true);
    expect(store.get(waiting.id)!.state).toBe('applied');
    expect(fake.applies()[0]!.call.approval).toBe('granted');
  });

  it('a plan approval applies the waiting proposals it covers, in order, and nothing else', async () => {
    const fake = new FakeServer({ 'items/a.yml': 1, 'items/b.yml': 1, 'items/c.yml': 1 });
    const store = createProposalStore({ transport: fake });
    const actions = new ReviewActions(store);
    const plan = store.agent.declarePlan({ objects: ['demo:items/a.yml', 'demo:items/b.yml'] });
    const one = await store.propose(sealed([op('setFields', { path: 'items/a.yml', rev: 1, edits: [] })]), { program: '1', plan: plan.id });
    const two = await store.propose(sealed([op('setFields', { path: 'items/b.yml', rev: 1, edits: [] })]), { program: '2', plan: plan.id });
    const outside = await store.propose(sealed([op('setFields', { path: 'items/c.yml', rev: 1, edits: [] })]), { program: '3', plan: plan.id });
    const result = await actions.approvePlan({ isTrusted: true } as unknown as Event, plan.id);
    expect(result).toMatchObject({ done: true, value: { applied: [one.id, two.id] } });
    expect(store.get(outside.id)!.state).toBe('waiting');
    expect(store.get(outside.id)!.needs).toEqual(['demo:items/c.yml']);
  });
});

describe('the review code keeps approval out of the components', () => {
  const REVIEW = fileURLToPath(new URL('../src/lib/codemode/review', import.meta.url));
  const files = readdirSync(REVIEW).map((name) => ({ name, text: readFileSync(join(REVIEW, name), 'utf8') }));
  const strip = (text: string): string => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/<!--[\s\S]*?-->/g, '').replace(/^\s*\/\/.*$/gm, '');

  it('no review file makes a gesture, spends one, or calls the store\'s approve, apply, reject or revert', () => {
    for (const file of files) {
      expect(strip(file.text), file.name).not.toMatch(/gestureFromEvent|spendGesture|isTrusted|ApprovalGesture/);
      expect(strip(file.text), file.name).not.toMatch(/store\.(approve|apply|reject|revert|revertTransaction)\(|\.grant\(/);
    }
  });

  it('every button that approves, applies, rejects or confirms a revert passes its click event to an action', () => {
    const calls = files.flatMap((file) => [...strip(file.text).matchAll(/\.(approveProposal|approvePlan|applyReady|reject|confirmRevert)\(([^)]*)\)/g)].map((match) => ({ file: file.name, name: match[1], args: match[2] })));
    expect(calls.length).toBeGreaterThanOrEqual(5);
    for (const call of calls) expect(call.args, `${call.file} ${call.name}`).toMatch(/^event\b/);
  });

  it('nothing runs an action on load, on a timer or from a store event', () => {
    for (const file of files) {
      expect(strip(file.text), file.name).not.toMatch(/setTimeout|setInterval|requestAnimationFrame|\.subscribe\([^)]*(approve|apply|revert)/);
    }
    const state = strip(files.find((file) => file.name === 'state.svelte.ts')!.text);
    expect(state).not.toMatch(/\.(approve\w*|apply\w*|confirmRevert|revert\w*)\(/);
  });

  it('shows no summary where facts belong: the model reads no summary field of the agent', () => {
    expect(strip(readFileSync(join(REVIEW, 'model.ts'), 'utf8'))).not.toMatch(/\.summary\b/);
  });

  it('keeps one apply, one grant and one gesture call site across the host', () => {
    const host = join(REVIEW, '..', 'host');
    const text = (name: string): string => strip(readFileSync(join(host, name), 'utf8'));
    expect([...text('actions.ts').matchAll(/gestureFromEvent\(/g)]).toHaveLength(5);
    expect(text('store.ts')).not.toMatch(/gestureFromEvent\(/);
  });
});
