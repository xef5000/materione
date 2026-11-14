/**
 * The host over the recorded frames (`fixtures/protocol/editor-codemode-v1.json`, issue 346): the transport sends exactly
 * the payloads the fixture records, reads its replies, and the whole lifecycle runs end to end on them, so the client and
 * the server are tested against the same frames.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { createProposalStore, createRpcTransport, MalformedReport, parseReport } from '../src/lib/codemode/host';
import type { Response } from '../src/lib/protocol/wire';
import type { StagedOperation } from '../src/lib/codemode/core/types';
import { click, sealed } from './helpers/host';

interface Frame {
  request: { id: string; op: string; payload: Record<string, unknown> };
  response: Response;
}
const fixture = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../fixtures/protocol/editor-codemode-v1.json', import.meta.url)), 'utf8'),
) as Record<'dryRun' | 'apply' | 'refused' | 'stale' | 'revert' | 'revertDryRun' | 'revertStale', Frame>;

/** A channel that answers each op from the recorded frames, in order, and records what it was asked. */
function channel(frames: Frame[], blob?: { capabilities?: string[] }) {
  const asked: { op: string; payload: Record<string, unknown> }[] = [];
  const queue = [...frames];
  return {
    asked,
    blob: blob as never,
    async call(op: string, payload: Record<string, unknown> = {}): Promise<Response> {
      asked.push({ op, payload });
      const frame = queue.shift();
      if (!frame || frame.request.op !== op) throw new Error(`unexpected ${op}`);
      return frame.response;
    },
  };
}

const operations = fixture.dryRun.request.payload.operations as StagedOperation[];

describe('the transport', () => {
  it('sends the recorded dry run and reads the recorded report', async () => {
    const wire = channel([fixture.dryRun]);
    const report = await createRpcTransport(wire).transaction({ pack: 'demo', operations, dryRun: true });
    expect(wire.asked).toEqual([{ op: 'authoring.transaction', payload: fixture.dryRun.request.payload }]);
    expect(report).toMatchObject({ accepted: true, applied: false, dryRun: true, transactionId: null, revertible: false });
    expect(report.documents.map((document) => [document.path, document.kind])).toEqual([
      ['items/ruby_blade.yml', 'created'],
      ['guis/shop.yml', 'created'],
      ['items/sword.yml', 'changed'],
    ]);
  });

  it('adds origin, approval and program to an apply, and nothing else', async () => {
    const wire = channel([fixture.apply]);
    const report = await createRpcTransport(wire).transaction({
      pack: 'demo',
      operations,
      dryRun: false,
      origin: 'code-mode',
      approval: 'granted',
      program: 'text',
    });
    expect(wire.asked[0]!.payload).toEqual({ ...fixture.apply.request.payload, origin: 'code-mode', approval: 'granted', program: 'text' });
    expect(report).toMatchObject({ applied: true, transactionId: 'tx-fixture', revertible: true });
  });

  it('reads a refusal and a stale reply as replies, and sends revert with dryRun mandatory', async () => {
    const wire = channel([fixture.refused, fixture.stale, fixture.revertDryRun, fixture.revert]);
    const transport = createRpcTransport(wire);
    expect((await transport.transaction({ pack: 'demo', operations, dryRun: true })).diagnostics[0]!.code).toBe('MTR-0008');
    expect((await transport.transaction({ pack: 'demo', operations, dryRun: true })).diagnostics[0]!.code).toBe('MTR-0034');
    await transport.revert({ transactionId: 'tx-fixture', dryRun: true });
    const done = await transport.revert({ transactionId: 'tx-fixture', dryRun: false });
    expect(wire.asked.slice(2).map((entry) => entry.payload)).toEqual([
      { transactionId: 'tx-fixture', dryRun: true },
      { transactionId: 'tx-fixture', dryRun: false },
    ]);
    expect(done.revertOf).toBe('tx-fixture');
  });

  it('refuses to call a server without the codemode capability', async () => {
    const wire = channel([], { capabilities: ['logic'] });
    await expect(createRpcTransport(wire).transaction({ pack: 'demo', operations, dryRun: true })).rejects.toThrow('does not support code mode');
    expect(wire.asked).toEqual([]);
    expect(
      await createRpcTransport(channel([fixture.dryRun], { capabilities: ['codemode'] })).transaction({ pack: 'demo', operations, dryRun: true }),
    ).toMatchObject({ accepted: true });
  });

  it('an error frame is an error, and an unreadable reply is never taken for success', async () => {
    const failing = channel([{ request: { id: 'x', op: 'authoring.transaction', payload: {} }, response: { op: 'authoring.transaction', id: 'x', ok: false, error: { code: 'MTR-0008', message: 'bad' } } as Response }]);
    await expect(createRpcTransport(failing).transaction({ pack: 'demo', operations, dryRun: true })).rejects.toThrow('MTR-0008');
    for (const data of [null, [], {}, { accepted: 'yes', applied: false, dryRun: true }, { accepted: true, applied: 1, dryRun: true }]) {
      expect(() => parseReport(data), JSON.stringify(data)).toThrow(MalformedReport);
    }
  });
});

describe('the lifecycle over the recorded frames', () => {
  it('the Bloodfang-shaped list: dry run, waits for the sword, approval applies it with the recorded payload', async () => {
    const wire = channel([fixture.dryRun, fixture.apply, fixture.revertDryRun, fixture.revert]);
    const store = createProposalStore({ transport: createRpcTransport(wire), now: () => 5 });
    const proposal = await store.propose(sealed(operations, { summary: 'adds 2, changes the sword' }), { program: 'the program text' });
    expect(proposal.state).toBe('waiting');
    expect(proposal.classification?.created).toEqual(['demo:guis/shop.yml', 'demo:items/ruby_blade.yml']);
    expect(proposal.needs).toEqual(['demo:items/sword.yml']);
    expect(proposal.classification?.operations.map((entry) => entry.effect)).toEqual(['new', 'new', 'new', 'new', 'new', 'existing']);
    expect(wire.asked).toHaveLength(1);

    const approved = await store.approve({ proposal: proposal.id }, click());
    expect(approved.proposal?.state).toBe('applied');
    expect(wire.asked[1]!.payload).toMatchObject({ ...fixture.apply.request.payload, origin: 'code-mode', approval: 'granted', program: 'the program text' });

    const reverted = await store.revert(proposal.id, click());
    expect(reverted?.state).toBe('reverted');
    expect(reverted?.revertedBy).toBe('tx-fixture-revert');
    expect(wire.asked.slice(2).map((entry) => [entry.op, entry.payload.dryRun])).toEqual([
      ['authoring.revertTransaction', true],
      ['authoring.revertTransaction', false],
    ]);
  });

  it('the recorded stale reply marks the proposal stale and applies nothing', async () => {
    const wire = channel([fixture.stale]);
    const store = createProposalStore({ transport: createRpcTransport(wire) });
    const proposal = await store.propose(sealed(fixture.stale.request.payload.operations as StagedOperation[]), { program: 'p' });
    expect(proposal.state).toBe('stale');
    expect(wire.asked.filter((entry) => entry.payload.dryRun === false)).toEqual([]);
  });

  it('the recorded refusal returns its diagnostic to the agent with the operation and line', async () => {
    const wire = channel([fixture.refused]);
    const store = createProposalStore({ transport: createRpcTransport(wire) });
    const proposal = await store.propose(sealed(fixture.refused.request.payload.operations as StagedOperation[]), { program: 'p' });
    expect(proposal.state).toBe('refused');
    expect(proposal.problems[0]).toMatchObject({ code: 'MTR-0008', operation: 1, source: { line: 2, column: 1 } });
  });

  it('the recorded revert refusals reach the change log', async () => {
    const wire = channel([fixture.dryRun, fixture.apply, fixture.revertStale]);
    const store = createProposalStore({ transport: createRpcTransport(wire) });
    const proposal = await store.propose(sealed(operations), { program: 'p' });
    await store.approve({ proposal: proposal.id }, click());
    await store.revert(proposal.id, click());
    expect(store.get(proposal.id)?.state).toBe('applied');
    expect(store.log()[0]!.revertProblems.map((problem) => problem.code)).toEqual(['MTR-0034', 'MTR-0034', 'MTR-0034']);
  });
});
