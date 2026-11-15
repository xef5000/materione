/**
 * The browser harness's stand-in for `authoring.transaction` and `authoring.revertTransaction` (codemode.md section 10,
 * `fixtures/protocol/editor-codemode-v1.json`), over the fixture's own documents. It answers in the real reply shapes
 * (documents with text and revisions, diagnostics with the operation index, `revertible` and `revertNote`, MTR-0034 for a stale
 * revision, MTR-0104 for a record that is gone) and implements only what the recorded corpus programs stage: `create`,
 * `setFields`, `logicEdit` with `addRule`, and `delete`. Any other kind is refused as the real server refuses an operation it
 * does not know. It is test infrastructure for the review UI and never ships in the editor build.
 */
import type { FixtureAuditEntry } from './contract';

export interface FixtureDoc {
  pack?: string;
  path?: string;
  rev: number;
  raw: string;
  parsed: Record<string, Record<string, unknown>>;
}

export interface TxHost {
  pack: string;
  get(path: string): FixtureDoc | undefined;
  /** Writes a document (null deletes it). */
  put(path: string, doc: FixtureDoc | null): void;
  serialize(parsed: Record<string, Record<string, unknown>>): string;
  audit(entry: FixtureAuditEntry): void;
}

interface Diag {
  code?: string;
  severity: 'error' | 'warning';
  message: string;
  operation: number;
  preexisting?: boolean;
}

type Kind = 'created' | 'changed' | 'deleted';

interface Touched {
  kind: Kind;
  operations: number[];
  existed: boolean;
}

interface Record_ {
  id: string;
  pack: string;
  /** The documents as the report listed them, in order. */
  docs: { path: string; kind: Kind }[];
  /** The stored document before (null: it did not exist) and its revision after (null: deleted). */
  before: Map<string, FixtureDoc | null>;
  after: Map<string, number | null>;
}

const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

function setPath(target: Record<string, unknown>, field: string[], value: unknown, remove: boolean): boolean {
  let current = target;
  for (const segment of field.slice(0, -1)) {
    let child = current[segment];
    if (!record(child)) {
      if (remove) return true;
      child = {};
      current[segment] = child;
    }
    current = child as Record<string, unknown>;
  }
  const last = field[field.length - 1];
  if (last === undefined) return false;
  if (remove) delete current[last];
  else current[last] = structuredClone(value);
  return true;
}

export class CodemodeFixture {
  #nextTx = 1;
  readonly #records = new Map<string, Record_>();
  /** Diagnostics added to the next transaction call (dry run and apply alike). */
  #nextDiagnostics: Diag[] = [];
  #unrevertibleNext = false;
  readonly calls: { op: string; dryRun: boolean }[] = [];

  constructor(private readonly host: TxHost) {}

  /** The next transaction call also reports these findings; an error that is not `preexisting` refuses it. */
  diagnoseNext(diagnostics: readonly Diag[]): void {
    this.#nextDiagnostics = [...diagnostics];
  }

  /** The next applied transaction is not recorded: `revertible: false` with the server's note. */
  makeNextUnrevertible(): void {
    this.#unrevertibleNext = true;
  }

  transaction(payload: Record<string, unknown>): unknown {
    const dryRun = payload.dryRun === true;
    this.calls.push({ op: 'authoring.transaction', dryRun });
    const operations = Array.isArray(payload.operations) ? (payload.operations as Record<string, unknown>[]) : [];
    const pack = typeof payload.pack === 'string' ? payload.pack : '';
    const injected = this.#nextDiagnostics;
    this.#nextDiagnostics = [];
    if (pack !== this.host.pack) {
      return this.refused(dryRun, [{ code: 'MTR-0009', severity: 'error', message: `no such pack '${pack}'`, operation: -1 }]);
    }
    const working = new Map<string, FixtureDoc | null>();
    const touched = new Map<string, Touched>();
    const note = (path: string, index: number, kind: Kind, existed: boolean): void => {
      const known = touched.get(path);
      if (known === undefined) touched.set(path, { kind, operations: [index], existed });
      else known.operations.push(index);
    };
    const current = (path: string): FixtureDoc | null => (working.has(path) ? (working.get(path) ?? null) : (this.host.get(path) ?? null));
    const failures: Diag[] = [];
    const fail = (index: number, code: string, message: string): void => {
      failures.push({ code, severity: 'error', message, operation: index });
    };
    for (let index = 0; index < operations.length && failures.length === 0; index++) {
      const operation = operations[index] as Record<string, unknown>;
      const kind = String(operation.kind);
      const path = typeof operation.path === 'string' ? operation.path : '';
      const existing = (): FixtureDoc | null => {
        const doc = current(path);
        if (doc === null) {
          fail(index, 'MTR-0009', `no document ${path}`);
          return null;
        }
        const stored = this.host.get(path);
        if (stored !== undefined && typeof operation.rev === 'number' && operation.rev !== stored.rev) {
          fail(index, 'MTR-0034', `${path}: document changed elsewhere: expected rev ${operation.rev}, current rev ${stored.rev}`);
          return null;
        }
        return doc;
      };
      if (kind === 'create') {
        const type = String(operation.type ?? 'items');
        const id = String(operation.id ?? '');
        const target = `${type}/${id}.yml`;
        if (current(target) !== null) {
          fail(index, 'MTR-0056', `${target} already exists`);
          continue;
        }
        const parsed: Record<string, Record<string, unknown>> = { [id]: {} };
        if (typeof operation.name === 'string') (parsed[id] as Record<string, unknown>).display_name = operation.name;
        for (const edit of Array.isArray(operation.edits) ? (operation.edits as Record<string, unknown>[]) : []) {
          if (Array.isArray(edit.field)) setPath(parsed, edit.field as string[], edit.value, edit.remove === true);
        }
        working.set(target, { rev: 1, parsed, raw: this.host.serialize(parsed) });
        note(target, index, 'created', false);
      } else if (kind === 'setFields') {
        const doc = existing();
        if (doc === null) continue;
        const parsed = structuredClone(doc.parsed);
        for (const edit of Array.isArray(operation.edits) ? (operation.edits as Record<string, unknown>[]) : []) {
          if (Array.isArray(edit.field)) setPath(parsed, edit.field as string[], edit.value, edit.remove === true);
        }
        working.set(path, { rev: doc.rev, parsed, raw: this.host.serialize(parsed) });
        note(path, index, 'changed', this.host.get(path) !== undefined);
      } else if (kind === 'logicEdit') {
        const doc = existing();
        if (doc === null) continue;
        const parsed = structuredClone(doc.parsed);
        const root = Object.keys(parsed)[0];
        const body = root === undefined ? undefined : parsed[root];
        if (body === undefined) {
          fail(index, 'MTR-0009', `${path} has no resource`);
          continue;
        }
        for (const edit of Array.isArray(operation.edits) ? (operation.edits as Record<string, unknown>[]) : []) {
          if (edit.op !== 'addRule') {
            fail(index, 'MTR-0008', `the browser fixture does not stage the logic edit '${String(edit.op)}'`);
            break;
          }
          const rules = Array.isArray(body.behavior) ? (body.behavior as unknown[]) : [];
          rules.splice(typeof edit.index === 'number' ? edit.index : rules.length, 0, structuredClone(edit.rule));
          body.behavior = rules;
        }
        if (failures.length > 0) continue;
        working.set(path, { rev: doc.rev, parsed, raw: this.host.serialize(parsed) });
        note(path, index, this.host.get(path) !== undefined ? 'changed' : 'created', this.host.get(path) !== undefined);
      } else if (kind === 'delete') {
        if (existing() === null) continue;
        working.set(path, null);
        note(path, index, 'deleted', true);
      } else {
        fail(index, 'MTR-0008', `unknown operation kind '${kind}'`);
      }
    }
    const diagnostics: Diag[] = [...failures, ...injected];
    const blocking = diagnostics.some((found) => found.severity === 'error' && found.preexisting !== true);
    if (blocking) return this.refused(dryRun, diagnostics);

    const documents = [...touched.entries()].map(([path, entry]) => {
      const after = working.get(path) ?? null;
      const stored = this.host.get(path);
      const kind: Kind = entry.kind === 'created' && stored !== undefined ? 'changed' : entry.kind;
      return {
        path,
        kind,
        operations: entry.operations,
        rev: after === null ? null : stored === undefined ? 1 : stored.rev + 1,
        previousRevision: stored === undefined ? null : stored.rev,
        text: after === null ? null : after.raw,
      };
    });
    if (dryRun) return this.report({ dryRun, applied: false, documents, diagnostics });

    const id = `tx-fixture-${this.#nextTx++}`;
    const before = new Map<string, FixtureDoc | null>();
    const after = new Map<string, number | null>();
    for (const document of documents) {
      const stored = this.host.get(document.path);
      before.set(document.path, stored === undefined ? null : structuredClone(stored));
      const next = working.get(document.path) ?? null;
      if (next === null) this.host.put(document.path, null);
      else this.host.put(document.path, { rev: document.rev as number, parsed: next.parsed, raw: next.raw });
      after.set(document.path, document.rev);
    }
    const unrevertible = this.#unrevertibleNext;
    this.#unrevertibleNext = false;
    if (!unrevertible) this.#records.set(id, { id, pack, docs: documents.map((d) => ({ path: d.path, kind: d.kind })), before, after });
    const program = typeof payload.program === 'string' ? payload.program : null;
    this.host.audit({
      ts: new Date().toISOString(),
      actor: 'Fixture Admin',
      op: 'authoring.transaction',
      target: `${pack} (${documents.length} documents, ${id})`,
      origin: typeof payload.origin === 'string' ? payload.origin : 'editor',
      approval: typeof payload.approval === 'string' ? payload.approval : 'not_needed',
      transaction: id,
      ...(program === null ? {} : { program: program.slice(0, 8192), programLength: program.length, digest: `sha256:${'0'.repeat(56)}${String(this.#nextTx).padStart(8, '0')}` }),
    });
    return this.report({
      dryRun,
      applied: true,
      documents,
      diagnostics,
      transactionId: id,
      revertible: !unrevertible,
      revertNote: unrevertible ? 'this transaction is larger than the 4 MiB the server keeps for a revert, so it was applied but cannot be reverted' : null,
    });
  }

  revert(payload: Record<string, unknown>): unknown {
    const dryRun = payload.dryRun === true;
    this.calls.push({ op: 'authoring.revertTransaction', dryRun });
    const target = typeof payload.transactionId === 'string' ? payload.transactionId : '';
    const found = this.#records.get(target);
    if (found === undefined) {
      return this.refused(dryRun, [
        { code: 'MTR-0104', severity: 'error', message: `no record of transaction ${target}: it is unknown or older than the server keeps`, operation: -1 },
      ]);
    }
    const stale: Diag[] = [];
    found.docs.forEach((document, index) => {
      const now = this.host.get(document.path);
      const expected = found.after.get(document.path) ?? null;
      const ok = expected === null ? now === undefined : now !== undefined && now.rev === expected;
      if (!ok) {
        stale.push({
          code: 'MTR-0034',
          severity: 'error',
          message: `${document.path}: document changed since the transaction (expected rev ${expected === null ? 'absent' : expected}, current rev ${now === undefined ? 'absent' : now.rev}), so it cannot be restored`,
          operation: index,
        });
      }
    });
    if (stale.length > 0) return this.refused(dryRun, stale, target);
    const documents = found.docs.map((document, index) => {
      const before = found.before.get(document.path) ?? null;
      const now = this.host.get(document.path);
      return {
        path: document.path,
        kind: (before === null ? 'deleted' : now === undefined ? 'created' : 'changed') as Kind,
        operations: [index],
        rev: before === null ? null : (now?.rev ?? found.after.get(document.path) ?? 0) + 1,
        previousRevision: now === undefined ? null : now.rev,
        text: before === null ? null : before.raw,
      };
    });
    if (dryRun) return this.report({ dryRun, applied: false, documents, diagnostics: [], revertOf: target });
    const id = `tx-fixture-${this.#nextTx++}`;
    for (const document of documents) {
      const before = found.before.get(document.path) ?? null;
      if (before === null) this.host.put(document.path, null);
      else this.host.put(document.path, { rev: document.rev as number, parsed: before.parsed, raw: before.raw });
    }
    this.host.audit({
      ts: new Date().toISOString(),
      actor: 'Fixture Admin',
      op: 'authoring.revertTransaction',
      target: `${found.pack} (${documents.length} documents, ${id}, reverts ${target})`,
      origin: 'editor',
      transaction: id,
    });
    return this.report({ dryRun, applied: true, documents, diagnostics: [], transactionId: id, revertOf: target, revertible: false });
  }

  private refused(dryRun: boolean, diagnostics: Diag[], revertOf: string | null = null): unknown {
    return this.report({ dryRun, applied: false, accepted: false, documents: [], diagnostics, revertOf });
  }

  private report(fields: {
    dryRun: boolean;
    applied: boolean;
    accepted?: boolean;
    documents: unknown[];
    diagnostics: Diag[];
    transactionId?: string;
    revertible?: boolean;
    revertNote?: string | null;
    revertOf?: string | null;
  }): unknown {
    return {
      accepted: fields.accepted ?? true,
      applied: fields.applied,
      dryRun: fields.dryRun,
      transactionId: fields.transactionId ?? null,
      documents: fields.documents,
      // the wire is the server's: the lowercased Severity enum (info, warn, error) and an always-present preexisting flag
      diagnostics: fields.diagnostics.map((found) => ({ ...found, severity: found.severity === 'warning' ? 'warn' : 'error', preexisting: found.preexisting === true })),
      stages: [],
      revertible: fields.revertible ?? false,
      revertNote: fields.revertNote ?? null,
      ...(fields.revertOf === undefined || fields.revertOf === null ? {} : { revertOf: fields.revertOf }),
    };
  }
}
