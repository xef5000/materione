/**
 * Classification (codemode.md section 5.6, C10): what a proposal touches, decided after a clean dry run from the
 * **operation list** and the **dry run's document list** and from nothing else. Nothing the model said, nothing the
 * program returned, no summary and no program text is an input: they are not even parameters here.
 *
 * Pure, and small on purpose: this and `grants.ts` are the client half of D13's approval rule.
 *
 * The rule, per operation kind (the wire kinds of `TransactionOps.operationOf`):
 *
 * | Kind | Creates | Touches an existing object |
 * |---|---|---|
 * | `create` | the new document | nothing |
 * | `duplicate` | the copy | nothing (the source is only read) |
 * | `saveAsTemplate` | the template | nothing (the source is untouched, api.md 3.3.1) |
 * | `setFields`, `logicEdit`, `guiEdit`, `detachTemplate` | | `path` |
 * | `delete` | | `path` |
 * | `move` | `toPath` | `fromPath` (it is removed) |
 * | any other kind | | **unknown: fail closed** |
 *
 * On top of the table, the dry run is the authority for what *changes*: any document the dry run reports as changed or
 * deleted (or any kind this code does not know) is touched, named by an operation or not. So a server that starts to
 * modify a source this table calls read-only is caught by the report, not by this file being up to date.
 *
 * An object is **new**, not existing, when this session created it before the operation (an applied proposal's
 * created documents, `sessionCreated`) or an earlier operation of this same proposal created it.
 */
import type { StagedOperation } from '../core/types';
import { objectId, type ObjectId } from './objects';

/** One document of a transaction report. `kind` is `created`, `changed` or `deleted` on the wire today. */
export interface ReportDocument {
  path: unknown;
  kind: unknown;
  /** The indexes of the operations that touched it. */
  operations?: unknown;
}

export type Effect = 'new' | 'existing' | 'unknown';

export interface OperationClass {
  index: number;
  kind: string;
  /** `new`: touches nothing that existed. `existing`: touches `objects`. `unknown`: not understood, see `blockers`. */
  effect: Effect;
  /** The existing objects this operation touches. */
  objects: ObjectId[];
}

export interface Blocker {
  /** The operation, or null for a document of the report no operation names. */
  operation: number | null;
  reason: string;
}

export interface Classification {
  operations: OperationClass[];
  /** Every existing object the proposal touches, sorted, without duplicates. What a grant must cover. */
  existing: ObjectId[];
  /** The documents the proposal creates (the dry run's created documents and move targets). */
  created: ObjectId[];
  /**
   * What cannot be a grant: an operation of an unknown kind, or one that names a document with a name that is not
   * canonical. Only the explicit approval of this one proposal clears them (grants.ts).
   */
  blockers: Blocker[];
}

export interface ClassifyInput {
  pack: string;
  operations: readonly StagedOperation[];
  documents: readonly ReportDocument[];
  /** The objects this session created and applied: never "existing" again within the session. */
  sessionCreated: ReadonlySet<string>;
}

/** The field each known kind names its modified document in. A kind absent from both tables is unknown. */
const MODIFIES = new Map<string, string>([
  ['setFields', 'path'],
  ['logicEdit', 'path'],
  ['guiEdit', 'path'],
  ['detachTemplate', 'path'],
  ['delete', 'path'],
  ['move', 'fromPath'],
]);
/** Kinds that only create (their source, if any, is read). */
const CREATES_ONLY = new Set(['create', 'duplicate', 'saveAsTemplate']);

function indexes(value: unknown): number[] {
  return Array.isArray(value) ? value.filter((entry): entry is number => Number.isInteger(entry) && entry >= 0) : [];
}

export function classify(input: ClassifyInput): Classification {
  const { pack, operations, documents, sessionCreated } = input;
  const blockers: Blocker[] = [];

  // Where in the list each document came to exist, so a later operation on it is "new".
  const createdAt = new Map<string, number>();
  const created = new Set<ObjectId>();
  const noteCreated = (id: ObjectId, at: number): void => {
    created.add(id);
    const known = createdAt.get(id);
    if (known === undefined || at < known) createdAt.set(id, at);
  };
  for (const document of documents) {
    if (document.kind !== 'created') continue;
    const id = objectId(pack, document.path);
    if (id === null) continue;
    const at = indexes(document.operations);
    noteCreated(id, at.length > 0 ? Math.min(...at) : Number.POSITIVE_INFINITY);
  }
  operations.forEach((operation, index) => {
    if (operation.kind !== 'move') return;
    const target = objectId(pack, operation.toPath);
    if (target !== null) noteCreated(target, index);
  });

  const isNew = (id: ObjectId, index: number): boolean =>
    sessionCreated.has(id) || (createdAt.get(id) ?? Number.POSITIVE_INFINITY) < index;

  // Documents the dry run says changed or were deleted (anything but `created` and an explicit `unchanged`),
  // by the operations that touched them.
  const reportedTouched: { id: ObjectId | null; operations: number[]; path: unknown }[] = [];
  for (const document of documents) {
    if (document.kind === 'created' || document.kind === 'unchanged') continue;
    reportedTouched.push({ id: objectId(pack, document.path), operations: indexes(document.operations), path: document.path });
  }

  const classes: OperationClass[] = operations.map((operation, index) => {
    const kind = typeof operation.kind === 'string' ? operation.kind : '';
    const objects = new Set<ObjectId>();
    let unknown = false;

    const field = MODIFIES.get(kind);
    if (field !== undefined) {
      const id = objectId(pack, operation[field]);
      if (id === null) {
        unknown = true;
        blockers.push({ operation: index, reason: `${kind}: the document name in '${field}' is missing or is not a canonical path` });
      } else if (!isNew(id, index)) {
        objects.add(id);
      }
    } else if (!CREATES_ONLY.has(kind)) {
      unknown = true;
      blockers.push({ operation: index, reason: `operation kind '${kind}' is not one this editor classifies` });
    }

    for (const touched of reportedTouched) {
      if (!touched.operations.includes(index)) continue;
      if (touched.id === null) {
        unknown = true;
        blockers.push({ operation: index, reason: 'the dry run names a document with a path that is not canonical' });
      } else if (!sessionCreated.has(touched.id)) {
        objects.add(touched.id);
      }
    }

    const sorted = [...objects].sort();
    return { index, kind, effect: unknown ? 'unknown' : sorted.length > 0 ? 'existing' : 'new', objects: sorted };
  });

  // A changed or deleted document no operation of this list claims is still a touched object.
  const existing = new Set<ObjectId>(classes.flatMap((entry) => entry.objects));
  for (const touched of reportedTouched) {
    const claimed = touched.operations.some((index) => index < operations.length);
    if (claimed) continue;
    if (touched.id === null) {
      blockers.push({ operation: null, reason: 'the dry run names a document with a path that is not canonical' });
    } else if (!sessionCreated.has(touched.id)) {
      existing.add(touched.id);
    }
  }

  return { operations: classes, existing: [...existing].sort(), created: [...created].sort(), blockers };
}
