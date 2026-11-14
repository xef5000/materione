/**
 * Test doubles for the code-mode host (issue 346): a small model of `authoring.transaction` that answers a dry run and an
 * apply the way the server's report is shaped (`fixtures/protocol/editor-codemode-v1.json`), so the lifecycle and the
 * classification are tested against documents and operation indexes and not against hand-written replies.
 */
import type { ProgramResult, StagedOperation } from '../../src/lib/codemode/core/types';
import { gestureFromEvent, type ApprovalGesture } from '../../src/lib/codemode/host/grants';
import type {
  ReportDiagnostic,
  TransactionCall,
  TransactionReport,
  TransactionTransport,
} from '../../src/lib/codemode/host/transport';

/** A gesture, as a real click would give one. */
export const click = (): ApprovalGesture => {
  const gesture = gestureFromEvent({ isTrusted: true });
  if (gesture === null) throw new Error('no gesture');
  return gesture;
};

export const op = (kind: string, fields: Record<string, unknown> = {}): StagedOperation =>
  ({ kind, ...fields }) as StagedOperation;

export function sealed(operations: StagedOperation[], extra: Partial<ProgramResult> = {}): ProgramResult {
  return {
    ok: true,
    sdkVersion: '1',
    pack: 'demo',
    operations,
    sources: operations.map((_, index) => ({ line: index + 1, column: 1 })),
    diagnostics: [],
    summary: null,
    logs: [],
    returned: null,
    stats: { reads: 0, readBytes: 0, staged: operations.length, documents: 0 },
    ...extra,
  };
}

export interface Call {
  call: TransactionCall;
  report: TransactionReport;
}

const report = (fields: Partial<TransactionReport>): TransactionReport => ({
  accepted: true,
  applied: false,
  dryRun: true,
  transactionId: null,
  documents: [],
  diagnostics: [],
  revertible: false,
  revertNote: null,
  revertOf: null,
  ...fields,
});

const refused = (diagnostics: ReportDiagnostic[], dryRun: boolean): TransactionReport =>
  report({ accepted: false, dryRun, diagnostics });

/**
 * A model of the store behind the transaction: documents at revisions, the list applied in order in memory, the report
 * the server gives. It implements only what the host's tests exercise, with the server's refusals (MTR-0034, MTR-0009,
 * MTR-0056) at the point the server gives them.
 */
export class FakeServer implements TransactionTransport {
  readonly docs = new Map<string, number>();
  readonly calls: Call[] = [];
  private nextTx = 1;
  readonly records = new Map<string, TransactionReport>();
  private readonly after = new Map<string, Map<string, number | null>>();
  /** Set to make the next call throw. */
  failNext: Error | null = null;
  /** Runs once, before the next call is answered (a human edit between dry run and apply). */
  beforeNext: (() => void) | null = null;
  /** Extra diagnostics for the next dry run. */
  dryRunDiagnostics: ReportDiagnostic[] = [];
  /** Hold the next call until released, to interleave. */
  gate: Promise<void> | null = null;

  constructor(seed: Record<string, number> = {}) {
    for (const [path, rev] of Object.entries(seed)) this.docs.set(path, rev);
  }

  /** A human edit: bumps a document's revision. */
  edit(path: string): void {
    this.docs.set(path, (this.docs.get(path) ?? 0) + 1);
  }

  applies(): Call[] {
    return this.calls.filter((entry) => 'operations' in entry.call && !entry.call.dryRun);
  }

  async transaction(call: TransactionCall): Promise<TransactionReport> {
    const gate = this.gate;
    this.gate = null;
    if (gate) await gate;
    this.calls.push({ call, report: report({}) });
    if (this.failNext) {
      const error = this.failNext;
      this.failNext = null;
      throw error;
    }
    this.beforeNext?.();
    this.beforeNext = null;
    const result = this.run(call);
    this.calls[this.calls.length - 1] = { call, report: result };
    return result;
  }

  async revert(call: { transactionId: string; dryRun: boolean }): Promise<TransactionReport> {
    const record = this.records.get(call.transactionId);
    this.calls.push({ call: { pack: '', operations: [], dryRun: call.dryRun } as TransactionCall, report: report({}) });
    if (!record) {
      return refused([{ code: 'MTR-0104', severity: 'error', message: 'no record', operation: -1 }], call.dryRun);
    }
    const after = this.after.get(call.transactionId) as Map<string, number | null>;
    for (const [path, rev] of after) {
      if ((this.docs.get(path) ?? null) !== rev) {
        return refused([{ code: 'MTR-0034', severity: 'error', message: `${path} moved on`, operation: 0 }], call.dryRun);
      }
    }
    if (!call.dryRun) {
      for (const document of record.documents) {
        const path = document.path as string;
        if (document.kind === 'created') this.docs.delete(path);
        else this.docs.set(path, (this.docs.get(path) ?? 1) + 1);
      }
    }
    return report({
      dryRun: call.dryRun,
      applied: !call.dryRun,
      transactionId: call.dryRun ? null : `tx-revert-${this.nextTx++}`,
      revertOf: call.transactionId,
      documents: record.documents.map((document) => ({ ...document })),
    });
  }

  private run(call: TransactionCall): TransactionReport {
    const working = new Map(this.docs);
    const touched = new Map<string, { kind: 'created' | 'changed' | 'deleted'; operations: number[]; existed: boolean }>();
    const note = (path: string, index: number, kind: 'created' | 'changed' | 'deleted'): void => {
      const known = touched.get(path);
      if (!known) {
        touched.set(path, { kind, operations: [index], existed: this.docs.has(path) });
        return;
      }
      known.operations.push(index);
      if (kind === 'deleted') known.kind = known.kind === 'created' ? 'created' : 'deleted';
      if (kind === 'created' && known.kind === 'deleted') known.kind = 'changed';
    };
    let failure: ReportDiagnostic | null = null;
    call.operations.forEach((operation, index) => {
      if (failure) return;
      const fail = (code: string, message: string): void => {
        failure = { code, severity: 'error', message, operation: index };
      };
      const pathOf = operation.path as string;
      const needsExisting = (path: string): boolean => {
        if (!working.has(path)) {
          fail('MTR-0009', `no document ${path}`);
          return false;
        }
        const rev = operation.rev;
        if (this.docs.has(path) && rev !== undefined && rev !== null && rev !== this.docs.get(path)) {
          fail('MTR-0034', `${path}: document changed elsewhere: expected rev ${String(rev)}, current rev ${this.docs.get(path)}`);
          return false;
        }
        return true;
      };
      const make = (path: string): void => {
        if (working.has(path)) return fail('MTR-0056', `${path} already exists`);
        working.set(path, 1);
        note(path, index, 'created');
      };
      switch (operation.kind) {
        case 'create':
          make(`${(operation.type as string | undefined) ?? 'items'}/${operation.id as string}.yml`);
          break;
        case 'duplicate':
          if (needsExisting(operation.fromPath as string)) make(`items/${operation.id as string}.yml`);
          break;
        case 'saveAsTemplate':
          if (needsExisting(operation.fromPath as string)) make(`templates/${operation.templateId as string}.yml`);
          break;
        case 'move':
          if (needsExisting(operation.fromPath as string)) {
            working.delete(operation.fromPath as string);
            note(operation.fromPath as string, index, 'deleted');
            make(operation.toPath as string);
          }
          break;
        case 'delete':
          if (needsExisting(pathOf)) {
            working.delete(pathOf);
            note(pathOf, index, 'deleted');
          }
          break;
        case 'setFields':
        case 'logicEdit':
        case 'guiEdit':
        case 'detachTemplate':
          if (needsExisting(pathOf)) note(pathOf, index, 'changed');
          break;
        default:
          fail('MTR-0008', `unknown operation kind '${String(operation.kind)}'`);
      }
    });
    if (failure) return refused([failure], call.dryRun);
    // a document created and deleted in the list is not in the result
    const documents = [...touched.entries()]
      .filter(([path, entry]) => !(entry.kind === 'deleted' && !entry.existed) && (working.has(path) || entry.existed))
      .map(([path, entry]) => ({
        path,
        kind: entry.kind === 'deleted' && !entry.existed ? 'created' : entry.kind,
        operations: entry.operations,
      }));
    if (call.dryRun) return report({ documents, diagnostics: [...this.dryRunDiagnostics] });
    for (const [path, entry] of touched) {
      if (entry.kind === 'deleted') this.docs.delete(path);
      else this.docs.set(path, (this.docs.get(path) ?? 0) + 1);
    }
    const transactionId = `tx-${this.nextTx++}`;
    const applied = report({ applied: true, dryRun: false, transactionId, documents, revertible: true });
    this.records.set(transactionId, applied);
    this.after.set(transactionId, new Map([...touched.keys()].map((path) => [path, this.docs.get(path) ?? null])));
    return applied;
  }
}
