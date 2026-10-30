/**
 * The logic authoring calls (logic.md §8): the protocol layer the node canvas (issue 223) and the Script tab
 * build on. Every decision here is the server's: the structure of an edit, the refusals, the compile.
 * This module only shapes requests and reads replies, so the studio never keeps a second copy of the
 * rules.
 *
 * Capability-gated: a connection whose session blob does not advertise `logic` gets no answer, and the
 * caller says so instead of guessing (`logicApiAvailable`).
 *
 * Pure of the DOM. The wire shapes are the ones `LogicAuthoringOps` (web/rpc/ops) reads and writes; the
 * golden frames in `fixtures/protocol/editor-logic-v1.json` pin them (test/logic-ops.spec.ts).
 */
import { unwrap, type RpcChannel } from '../api';
import { hasCapability } from '../protocol/session';

/** Whether the connected server answers the logic API (the `logic` capability). */
export function logicApiAvailable(channel: RpcChannel | null | undefined): boolean {
  return !!channel && channel.blob !== undefined && hasCapability(channel.blob, 'logic');
}

/** A position on the canvas, as the file records it (`node: {x, y}`). */
export interface LogicNode {
  x: number;
  y: number;
}

/** One step as the server reads it. `branches` are keyed by the path segments that reach them (`then`, `else`, `choose.0.then`). */
export interface LogicStep {
  path: string;
  kind: string;
  value?: unknown;
  options: Record<string, unknown>;
  node: LogicNode | null;
  branches: Record<string, LogicStep[]>;
}

/** One rule of a resource. */
export interface LogicRule {
  index: number;
  path: string;
  on?: string;
  options: Record<string, unknown>;
  condition?: unknown;
  node: LogicNode | null;
  steps: LogicStep[];
}

/** One named flow. */
export interface LogicFlow {
  id: string;
  steps: LogicStep[];
}

/** A resource's logic as `logic.get` and `logic.edit` return it. */
export interface LogicView {
  pack: string;
  path: string;
  id: string;
  rev: number;
  rules: LogicRule[];
  flows: LogicFlow[];
  warnings: LogicDiagnostic[];
}

/** A diagnostic as the server sends it: its code, severity, message, resource, step-path field and hint. */
export interface LogicDiagnostic {
  code: string;
  severity: 'error' | 'warn' | 'info' | string;
  message: string;
  resource?: string;
  field?: string[];
  hint?: string;
}

/**
 * One structural edit, as `logic.edit` takes it. Paths are step paths (`behavior.0.do.1`,
 * `flows.purchase.0`); an insertion path names the index it inserts at.
 */
export type LogicEdit =
  | { op: 'addRule'; index: number; rule: Record<string, unknown> }
  | { op: 'removeRules'; indices: number[] }
  | { op: 'reorderRules'; order: number[] }
  | { op: 'patchRule'; index: number; set?: Record<string, unknown>; unset?: string[] }
  | { op: 'insertStep'; path: string; step: unknown }
  | { op: 'patchStep'; path: string; set?: Record<string, unknown>; unset?: string[] }
  | { op: 'moveStep'; from: string; to: string }
  | { op: 'removeStep'; path: string }
  | { op: 'setNodePosition'; path: string; x: number | null; y: number | null }
  | { op: 'addFlow'; id: string; steps: unknown[] }
  | { op: 'removeFlow'; id: string }
  | { op: 'renameFlow'; from: string; to: string };

/** A draft of a resource's logic, as `logic.validate` takes it (not saved). */
export interface LogicDraftRequest {
  pack: string;
  resource?: string;
  behavior?: unknown[];
  flows?: Record<string, unknown>;
  trigger?: string;
}

/** The behavior vocabulary as `logic.vocabulary` returns it: the palette, the inspector and the trigger schemas. */
export type LogicVocabulary = Record<string, unknown>;

/** `logic.get`: a resource's rules and named flows as the canvas reads them. */
export async function logicGet(channel: RpcChannel, pack: string, path: string): Promise<LogicView> {
  return unwrap(await channel.call('logic.get', { pack, path })) as unknown as LogicView;
}

/**
 * `logic.edit`: the edits in order, all or none, at `rev`. Returns the view after the edits; a refused edit
 * rejects with the server's code, and its `target` names the step it refused.
 */
export async function logicEdit(
  channel: RpcChannel,
  pack: string,
  path: string,
  rev: number,
  edits: readonly LogicEdit[],
): Promise<LogicView> {
  const response = await channel.call('logic.edit', { pack, path, rev, edits: [...edits] });
  return unwrap(response) as unknown as LogicView;
}

/** `logic.validate`: the step-path diagnostics the compiler reports for a draft. Nothing is written. */
export async function logicValidate(channel: RpcChannel, draft: LogicDraftRequest): Promise<LogicDiagnostic[]> {
  const response = await channel.call('logic.validate', { ...draft });
  const data = unwrap(response);
  return (data.diagnostics as LogicDiagnostic[] | undefined) ?? [];
}

/** `logic.instantiate`: the steps a recipe expands to. Refused until the recipe registry lands (issue 222). */
export async function logicInstantiate(
  channel: RpcChannel,
  recipe: string,
  params: Record<string, unknown> = {},
): Promise<unknown[]> {
  const response = await channel.call('logic.instantiate', { recipe, params });
  const data = unwrap(response);
  return (data.steps as unknown[] | undefined) ?? [];
}

/** `logic.vocabulary`: the behavior vocabulary, built-in and addon alike. */
export async function logicVocabulary(channel: RpcChannel): Promise<LogicVocabulary> {
  return unwrap(await channel.call('logic.vocabulary', {})) as unknown as LogicVocabulary;
}
