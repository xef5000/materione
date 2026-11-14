/**
 * The wire of the proposal lifecycle: the two ops code mode already has, `authoring.transaction` and
 * `authoring.revertTransaction` (codemode.md section 10, `fixtures/protocol/editor-codemode-v1.json`). Nothing here is a
 * new op, endpoint or capability. The store depends on the `TransactionTransport` interface; `createRpcTransport` is
 * the one place that names the ops.
 */
import { RpcError, unwrap, type RpcChannel } from '../../api';
import { hasCapability } from '../../protocol/session';
import type { StagedOperation } from '../core/types';
import type { ReportDocument } from './classify';

export const CODEMODE_CAPABILITY = 'codemode';

/** A diagnostic of a report. `preexisting` is what the server will send for a finding that predates the transaction (#372). */
export interface ReportDiagnostic {
  code?: string;
  severity: 'error' | 'warning';
  message: string;
  hint?: string;
  /** The operation index; -1 for a finding about no operation. */
  operation?: number;
  resource?: string;
  location?: string;
  preexisting?: boolean;
}

/** The reply of `authoring.transaction` and `authoring.revertTransaction`. A refused list is still a reply. */
export interface TransactionReport {
  accepted: boolean;
  applied: boolean;
  dryRun: boolean;
  transactionId: string | null;
  documents: ReportDocument[];
  diagnostics: ReportDiagnostic[];
  revertible: boolean;
  revertNote: string | null;
  revertOf: string | null;
}

export interface TransactionCall {
  pack: string;
  operations: readonly StagedOperation[];
  dryRun: boolean;
  /** An apply only: the audit's context (C10, C13). */
  origin?: 'code-mode';
  approval?: 'not_needed' | 'granted';
  program?: string;
}

export interface TransactionTransport {
  transaction(call: TransactionCall): Promise<TransactionReport>;
  revert(call: { transactionId: string; dryRun: boolean }): Promise<TransactionReport>;
}

/** A reply the editor cannot read. It is never taken for success. */
export class MalformedReport extends Error {
  constructor(message: string) {
    super(`unreadable transaction reply: ${message}`);
    this.name = 'MalformedReport';
  }
}

function record(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new MalformedReport(`${what} is not an object`);
  return value as Record<string, unknown>;
}

function flag(value: unknown, what: string): boolean {
  if (typeof value !== 'boolean') throw new MalformedReport(`${what} is not a boolean`);
  return value;
}

function text(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

/** Reads a reply's data strictly where a wrong reading could allow something (`accepted`, `applied`), leniently elsewhere. */
export function parseReport(data: unknown): TransactionReport {
  const body = record(data, 'the reply');
  const documents = Array.isArray(body.documents) ? body.documents : [];
  const diagnostics = Array.isArray(body.diagnostics) ? body.diagnostics : [];
  return {
    accepted: flag(body.accepted, 'accepted'),
    applied: flag(body.applied, 'applied'),
    dryRun: flag(body.dryRun, 'dryRun'),
    transactionId: text(body.transactionId),
    documents: documents.map((entry) => {
      const document = record(entry, 'a document');
      return { path: document.path, kind: document.kind, operations: document.operations };
    }),
    diagnostics: diagnostics.map((entry) => {
      const found = record(entry, 'a diagnostic');
      return {
        code: text(found.code) ?? undefined,
        severity: found.severity === 'warning' ? 'warning' : 'error',
        message: text(found.message) ?? '',
        hint: text(found.hint) ?? undefined,
        operation: typeof found.operation === 'number' ? found.operation : undefined,
        resource: text(found.resource) ?? undefined,
        location: text(found.location) ?? undefined,
        preexisting: found.preexisting === true ? true : undefined,
      };
    }),
    revertible: body.revertible === true,
    revertNote: text(body.revertNote),
    revertOf: text(body.revertOf),
  };
}

/** The transport over the sealed editor channel. Throws `RpcError` for an error frame (a malformed request). */
export function createRpcTransport(channel: RpcChannel): TransactionTransport {
  const supported = (): void => {
    if (channel.blob !== undefined && !hasCapability(channel.blob, CODEMODE_CAPABILITY)) {
      throw new Error('this server does not support code mode (capability codemode)');
    }
  };
  return {
    async transaction(call) {
      supported();
      const payload: Record<string, unknown> = { pack: call.pack, operations: call.operations, dryRun: call.dryRun };
      if (call.origin !== undefined) payload.origin = call.origin;
      if (call.approval !== undefined) payload.approval = call.approval;
      if (call.program !== undefined) payload.program = call.program;
      return parseReport(unwrap(await channel.call('authoring.transaction', payload)));
    },
    async revert(call) {
      supported();
      return parseReport(unwrap(await channel.call('authoring.revertTransaction', { transactionId: call.transactionId, dryRun: call.dryRun })));
    },
  };
}

export { RpcError };
