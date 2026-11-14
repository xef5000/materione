/**
 * The operation table (codemode.md section 7, issue 339): the one place that says which editor op each SDK
 * read and write goes through. The core reaches an op only by looking its SDK name up here, so a ban test can
 * show there is no path to an op outside the table, and `scripts/compat/editor-compat.mjs` scans this file the
 * way it scans the editor's own calls: it fails when a supported release lacks an op named below, unless the
 * op is gated on a capability (`editor-op-gates.json`) that the entry names and the core checks.
 *
 * Every write is the same op, `authoring.transaction`, with a different `kind` of operation inside it; the
 * table keeps one row per kind so the mapping is complete and a kind the server does not know is a row to
 * delete. The transaction's operation kinds are `TransactionOps.operationOf` on the plugin side; a test reads
 * that source and compares.
 *
 * Literal strings on purpose: the compat gate reads them lexically.
 */

export interface OpEntry {
  /** The SDK member (or family of members) that uses the op. */
  readonly sdk: string;
  /** The editor op. */
  readonly op: string;
  /** The session capability that must be advertised for the member to exist; absent for an op every release has. */
  readonly capability?: string;
  /** For `authoring.transaction`: the operation kind the member stages. */
  readonly kind?: string;
  /**
   * `resolve` for a member whose answer the host gives at seal (codemode.md section 3.9): the core does not call the
   * op, the host's resolver does, and the row is the allowlist and the compat gate's record of it. Absent: the core
   * asks for the op itself, through `HostPort.read`, because the program awaits the answer.
   */
  readonly via?: 'resolve';
}

export const READS: readonly OpEntry[] = [
  { sdk: 'm.pack', op: 'browse.packs', via: 'resolve' },
  { sdk: 'm.read.packs', op: 'browse.packs' },
  { sdk: 'm.read.resources', op: 'authoring.summaries' },
  { sdk: 'm.read.get', op: 'browse.get' },
  { sdk: 'm.read.references', op: 'authoring.references' },
  { sdk: 'm.read.history', op: 'browse.history' },
  { sdk: 'm.read.diagnostics', op: 'admin.diagnostics.recent' },
  { sdk: 'm.ref', op: 'authoring.summaries' },
  { sdk: 'm.ref.asset', op: 'assets.list', via: 'resolve' },
  { sdk: 'm.edit', op: 'browse.get', via: 'resolve' },
  { sdk: 'rules.instantiate', op: 'logic.instantiate', capability: 'logic', via: 'resolve' },
];

export const WRITES: readonly OpEntry[] = [
  { sdk: 'collection.create', op: 'authoring.transaction', capability: 'codemode', kind: 'create' },
  { sdk: 'handle.duplicate', op: 'authoring.transaction', capability: 'codemode', kind: 'duplicate' },
  { sdk: 'handle.set', op: 'authoring.transaction', capability: 'codemode', kind: 'setFields' },
  { sdk: 'handle.unset', op: 'authoring.transaction', capability: 'codemode', kind: 'setFields' },
  { sdk: 'handle.delete', op: 'authoring.transaction', capability: 'codemode', kind: 'delete' },
  { sdk: 'handle.detachTemplate', op: 'authoring.transaction', capability: 'codemode', kind: 'detachTemplate' },
  { sdk: 'handle.saveAsTemplate', op: 'authoring.transaction', capability: 'codemode', kind: 'saveAsTemplate' },
  { sdk: 'rules.add', op: 'authoring.transaction', capability: 'codemode', kind: 'logicEdit' },
  { sdk: 'rules.instantiate', op: 'authoring.transaction', capability: 'codemode', kind: 'logicEdit' },
];

/** The entry of an SDK read; a name the table lacks is a bug in the core, never a program's doing. */
export function readEntry(sdk: string): OpEntry {
  const entry = READS.find((candidate) => candidate.sdk === sdk);
  if (entry === undefined) throw new Error(`the operation table has no read named ${sdk}`);
  return entry;
}

/** The entry of an SDK write. */
export function writeEntry(sdk: string, kind: string): OpEntry {
  const entry = WRITES.find((candidate) => candidate.sdk === sdk && candidate.kind === kind);
  if (entry === undefined) throw new Error(`the operation table has no write ${sdk} of kind ${kind}`);
  return entry;
}
