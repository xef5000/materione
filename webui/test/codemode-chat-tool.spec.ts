/**
 * The agent's one tool, `run_program(text)` (issue 348; codemode.md sections 2.2, 5, 6): input validation, type-check
 * errors and missing-primitive extraction, sandbox failures, read-only runs, dry-run outcomes (`refused`, `stale`,
 * `failed`, `waiting`, `applied`), and the `TOOL_RESULT_MAX_CHARS` bound on what goes back to the model.
 */
import { describe, expect, it, vi } from 'vitest';
import { readInput, runProgram, TOOL_RESULT_MAX_CHARS, type ToolDeps } from '../src/lib/codemode/chat/tool';
import type { StagedOperation } from '../src/lib/codemode/core/types';
import type { ObjectId, Proposal } from '../src/lib/codemode/host';
import type { RunResult } from '../src/lib/codemode/sandbox';
import type { CheckResult } from '../src/lib/codemode/typecheck';
import { surfaceNamed } from './helpers/codemode';

const surface = surfaceNamed('plugin-current');

const okCheck = (): CheckResult => ({
  ok: true,
  js: 'return 1;',
  map: { version: 3, sources: ['program.ts'], names: [], mappings: 'AAAA' },
  diagnostics: [],
  truncated: false,
});

const baseRun = (overrides: Partial<RunResult> = {}): RunResult => ({
  ok: true,
  sdkVersion: '1',
  pack: 'rpg',
  operations: [],
  sources: [],
  diagnostics: [],
  late: [],
  value: null,
  logs: [],
  summary: null,
  stats: { reads: 0, readBytes: 0, staged: 0, documents: 0 },
  metrics: { cpuMs: 1, engineWallMs: 1, setupMs: 1, totalMs: 1, hostReads: 0, hostReadBytes: 0, booted: false, bootMs: 0 },
  ...overrides,
});

const baseProposal = (overrides: Partial<Proposal> = {}): Proposal => ({
  id: 'prop-1',
  state: 'applied',
  plan: null,
  pack: 'rpg',
  program: 'p.items.create("a", {})',
  summary: 'Create item a',
  operations: [{ kind: 'create', type: 'items', id: 'a', path: 'items/a.yml', fields: {} }],
  sources: [{ line: 1, column: 1 }],
  classification: null,
  documents: [{ path: 'items/a.yml', kind: 'create' }],
  problems: [],
  needs: [],
  needsProposalApproval: false,
  transactionId: 'tx-1',
  revertible: true,
  revertNote: null,
  revertedBy: null,
  appliedAt: 1,
  before: {},
  ...overrides,
});

function makeDeps(overrides: Partial<ToolDeps> = {}): ToolDeps {
  return {
    surface,
    check: async () => okCheck(),
    run: async () => baseRun(),
    agent: { propose: async () => baseProposal() },
    ...overrides,
  };
}

describe('readInput', () => {
  it('accepts { text } with a non-empty string', () => {
    expect(readInput({ text: 'return 1;' })).toEqual({ text: 'return 1;' });
  });

  it('refuses non-objects, arrays, missing text, empty text, and extra keys', () => {
    expect('error' in readInput(null)).toBe(true);
    expect('error' in readInput(['return 1;'])).toBe(true);
    expect('error' in readInput('return 1;')).toBe(true);
    expect('error' in readInput({})).toBe(true);
    expect('error' in readInput({ text: '   ' })).toBe(true);
    const withExtra = readInput({ text: 'return 1;', approve: true });
    expect('error' in withExtra && withExtra.error).toContain('approve');
  });
});

describe('runProgram', () => {
  it('returns invalid without calling check, run, or propose when input is bad', async () => {
    const check = vi.fn();
    const run = vi.fn();
    const propose = vi.fn();
    const outcome = await runProgram({ text: '', apply: true }, makeDeps({ check, run, agent: { propose } }));
    expect(outcome.status).toBe('invalid');
    expect(outcome.failed).toBe(true);
    expect(outcome.proposalId).toBeNull();
    expect(check).not.toHaveBeenCalled();
    expect(run).not.toHaveBeenCalled();
    expect(propose).not.toHaveBeenCalled();
  });

  it('returns type-error and extracts missingPrimitive diagnostics without running', async () => {
    const run = vi.fn();
    const outcome = await runProgram(
      { text: 'm.actions.potion({ target: "self" })' },
      makeDeps({
        run,
        check: async () => ({
          ok: false,
          truncated: true,
          diagnostics: [
            {
              code: 'TS2353',
              severity: 'error',
              message: "Object literal may only specify known properties, and 'target' does not exist in type 'PotionActionParams'.",
              line: 1,
              column: 20,
              missingPrimitive: { member: 'target', catalog: 'PotionActionParams', nearest: ['amplifier', 'duration'] },
            },
          ],
        }),
      }),
    );
    expect(outcome.status).toBe('type-error');
    expect(outcome.failed).toBe(true);
    expect(outcome.missingPrimitives).toEqual([{ member: 'target', catalog: 'PotionActionParams', nearest: ['amplifier', 'duration'] }]);
    expect(outcome.diagnostics.some((d) => d.severity === 'warning' && d.message.includes('More type errors'))).toBe(true);
    expect(run).not.toHaveBeenCalled();
  });

  it('returns type-error when the checker throws', async () => {
    const outcome = await runProgram(
      { text: 'return 1;' },
      makeDeps({
        check: async () => {
          throw new Error('worker crashed');
        },
      }),
    );
    expect(outcome.status).toBe('type-error');
    expect(outcome.failed).toBe(true);
    expect(outcome.diagnostics[0]?.code).toBe('CHK-0004');
    expect(outcome.diagnostics[0]?.message).toContain('worker crashed');
  });

  it('returns run-error when the sandbox reports ok: false or throws', async () => {
    const propose = vi.fn();
    const failedRun = await runProgram(
      { text: 'throw new Error("boom");' },
      makeDeps({
        run: async () =>
          baseRun({
            ok: false,
            diagnostics: [{ code: 'SDK-0001', severity: 'error', message: 'boom', source: { line: 1, column: 1 } }],
          }),
        agent: { propose },
      }),
    );
    expect(failedRun.status).toBe('run-error');
    expect(failedRun.failed).toBe(true);
    expect(propose).not.toHaveBeenCalled();

    const threwRun = await runProgram(
      { text: 'return 1;' },
      makeDeps({
        run: async () => {
          throw new Error('sandbox died');
        },
        agent: { propose },
      }),
    );
    expect(threwRun.status).toBe('run-error');
    expect(threwRun.failed).toBe(true);
    expect(threwRun.diagnostics[0]?.message).toContain('sandbox died');
    expect(propose).not.toHaveBeenCalled();
  });

  it('returns read with failed: false for a read-only program and bounds oversized results', async () => {
    const propose = vi.fn();
    const bigValue = 'x'.repeat(20_000);
    const bigLogs = Array.from({ length: 100 }, (_, i) => `log-${i}-${'y'.repeat(100)}`);
    const outcome = await runProgram(
      { text: 'return m.read.items();' },
      makeDeps({
        run: async () => baseRun({ value: bigValue, logs: bigLogs }),
        agent: { propose },
      }),
    );
    expect(outcome.status).toBe('read');
    expect(outcome.failed).toBe(false);
    expect(outcome.proposalId).toBeNull();
    expect(propose).not.toHaveBeenCalled();
    expect(outcome.content.length).toBeLessThanOrEqual(TOOL_RESULT_MAX_CHARS);
    const parsed = JSON.parse(outcome.content) as { result: { valueCut?: string; valueChars?: number; logsCut?: boolean } };
    expect(parsed.result.valueCut?.length).toBeLessThanOrEqual(4_000);
    expect(parsed.result.valueChars).toBe(JSON.stringify(bigValue).length);
    expect(parsed.result.logsCut).toBe(true);
  });

  it('maps proposal states refused, stale, failed, waiting, and applied, and deduplicates sdk-phase problems', async () => {
    const op: StagedOperation = { kind: 'create', type: 'items', id: 'a', path: 'items/a.yml', fields: {} };
    const stagedRun = baseRun({
      operations: [op],
      sources: [{ line: 1, column: 1 }],
      diagnostics: [{ code: 'SDK-0016', severity: 'warning', message: 'economy unavailable', source: { line: 1, column: 1 } }],
    });

    const cases: Array<{ state: Proposal['state']; expectedStatus: string; expectedFailed: boolean }> = [
      { state: 'refused', expectedStatus: 'refused', expectedFailed: true },
      { state: 'stale', expectedStatus: 'stale', expectedFailed: true },
      { state: 'failed', expectedStatus: 'failed', expectedFailed: true },
      { state: 'waiting', expectedStatus: 'waiting', expectedFailed: false },
      { state: 'applied', expectedStatus: 'applied', expectedFailed: false },
    ];

    for (const c of cases) {
      const outcome = await runProgram(
        { text: 'p.items.create("a", {})' },
        makeDeps({
          run: async () => stagedRun,
          agent: {
            propose: async () =>
              baseProposal({
                id: `prop-${c.state}`,
                state: c.state,
                needs: c.state === 'waiting' ? ['rpg:items/a.yml' as ObjectId] : [],
                problems: [
                  { phase: 'sdk', code: 'SDK-0016', severity: 'warning', message: 'economy unavailable' },
                  ...(c.state === 'refused'
                    ? [{ phase: 'dry-run' as const, code: 'MTR-0100', severity: 'error' as const, message: 'bad field', resource: 'items/a.yml', source: { line: 1, column: 1 } }]
                    : []),
                ],
              }),
          },
        }),
      );
      expect(outcome.status).toBe(c.expectedStatus);
      expect(outcome.failed).toBe(c.expectedFailed);
      expect(outcome.proposalId).toBe(`prop-${c.state}`);
      // SDK-0016 appears once (from run.diagnostics), not twice (filtered out of proposal.problems)
      expect(outcome.diagnostics.filter((d) => d.code === 'SDK-0016')).toHaveLength(1);
    }
  });

  it('returns failed when agent.propose throws', async () => {
    const op: StagedOperation = { kind: 'create', type: 'items', id: 'a', path: 'items/a.yml', fields: {} };
    const outcome = await runProgram(
      { text: 'p.items.create("a", {})' },
      makeDeps({
        run: async () => baseRun({ operations: [op], sources: [{ line: 1, column: 1 }] }),
        agent: {
          propose: async () => {
            throw new Error('channel closed');
          },
        },
      }),
    );
    expect(outcome.status).toBe('failed');
    expect(outcome.failed).toBe(true);
    expect(outcome.proposalId).toBeNull();
    expect(outcome.diagnostics[0]?.message).toContain('channel closed');
  });
});
