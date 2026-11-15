/**
 * Severity on the wire: the server sends the lowercased `Severity` enum (`info`, `warn`, `error`; api/diagnostics/Severity.java),
 * not "warning". Reading it wrongly turned every ordinary server warning into a blocking error, so these tests use a recorded
 * frame (`fixtures/golden/codemode/replay/t04-daily-kit.json`, which carries `"severity": "warn"`) and not hand-built ones.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { createProposalStore, createRpcTransport, parseReport } from '../src/lib/codemode/host';
import type { StagedOperation } from '../src/lib/codemode/core/types';
import type { Response } from '../src/lib/protocol/wire';
import { op, sealed } from './helpers/host';

const replay = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../fixtures/golden/codemode/replay/t04-daily-kit.json', import.meta.url)), 'utf8'),
) as Record<string, unknown> & { diagnostics: { severity: string; code: string; operation: number }[] };

describe('severity on the wire', () => {
  it('the recorded replay really carries "warn"', () => {
    expect(replay.diagnostics.map((found) => found.severity)).toEqual(['warn']);
  });

  it('reads warn and info as warnings, error as an error, and anything else as an error (fail closed)', () => {
    const read = (severity: unknown) =>
      parseReport({ accepted: true, applied: false, dryRun: true, diagnostics: [{ severity, message: 'm' }] }).diagnostics[0]!.severity;
    expect(read('warn')).toBe('warning');
    expect(read('info')).toBe('warning');
    expect(read('error')).toBe('error');
    expect(read('WARN')).toBe('error');
    expect(read('fatal')).toBe('error');
    expect(read(undefined)).toBe('error');
    expect(read(7)).toBe('error');
  });

  it('a dry run accepted with the recorded warning is not blocked: it applies, and the warning stays a warning', async () => {
    const wire = {
      blob: undefined as never,
      async call(opName: string, payload: Record<string, unknown> = {}): Promise<Response> {
        const dryRun = payload.dryRun === true;
        return {
          op: opName,
          id: 'r',
          ok: true,
          data: { ...replay, dryRun, applied: !dryRun, transactionId: dryRun ? null : 'tx-replay', revertible: !dryRun },
        } as Response;
      },
    };
    const store = createProposalStore({ transport: createRpcTransport(wire) });
    const proposal = await store.propose(sealed([op('create', { type: 'variables', id: 'kit_daily_at' }) as StagedOperation]), { program: 'p' });
    expect(proposal.state).toBe('applied');
    expect(proposal.problems.find((problem) => problem.code === 'MTR-0022')).toMatchObject({ severity: 'warning', phase: 'dry-run', operation: 2 });
  });
});
