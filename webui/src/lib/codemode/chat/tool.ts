/**
 * The agent's one tool, `run_program(text)` (issue 348; codemode.md sections 2.2, 5, 6).
 *
 * One call is the whole path of a program: type-check (5.1), run in the sandbox (5.2), and, when the run staged
 * operations, `propose` on the agent port of the proposal store, which makes the server's dry run and then applies or waits
 * as the store's policy decides (5.6). This file re-implements none of that policy: it reads the proposal's state and
 * reports it.
 *
 * The tool has ONE parameter, `text`. There is no apply, no grant, no approve and no revert here, by schema, by validation
 * (`readInput` refuses any other key) and by construction: the only thing this module can reach that writes is
 * `AgentPort.propose`, and the port has no approval of any kind.
 *
 * What goes back to the model is bounded (4.2: "bounds what reaches the model, and what could leave the page"): the result
 * is one JSON text of at most `TOOL_RESULT_MAX_CHARS` characters, built from parts that are each cut to a fixed size.
 */
import type { JsonValue, SdkDiagnostic, SourcePosition } from '../core/types';
import type { SurfaceDocument } from '../generate/surface';
import type { AgentPort, Problem, Proposal } from '../host';
import type { CheckedProgram, RunResult } from '../sandbox';
import type { CheckDiagnostic, CheckResult, MissingPrimitive } from '../typecheck';
import { RUN_PROGRAM, type ToolSpec } from './provider';

/** The tool as a provider is told about it. Frozen: one property, nothing else accepted. */
export const RUN_PROGRAM_TOOL: ToolSpec = Object.freeze({
  name: RUN_PROGRAM,
  description:
    'Type-checks and runs one TypeScript program against the Materione SDK (the global `m`) in a sandbox. Reads execute; writes are staged, dry-run by the server, and then applied (new content) or left waiting for the owner\'s approval (changes to existing content). Returns the diagnostics and the bounded result.',
  inputSchema: Object.freeze({
    type: 'object' as const,
    properties: Object.freeze({
      text: Object.freeze({ type: 'string', description: 'The program: the body of an async function. `m` is the only global; top-level await and return are allowed.' }),
    }),
    required: Object.freeze(['text']),
    additionalProperties: false as const,
  }),
});

/** The most a tool result may be, in characters of JSON. Fixed by `codemode-chat-tool.spec.ts`. */
export const TOOL_RESULT_MAX_CHARS = 12_000;
const MAX_DIAGNOSTICS = 20;
const MESSAGE_CHARS = 400;
const HINT_CHARS = 300;
const VALUE_CHARS = 4_000;
const LOG_CHARS = 2_000;
const MAX_OPERATIONS = 40;
const MAX_DOCUMENTS = 40;

/** Where a tool call ended. */
export type ToolStatus =
  /** The call's input was not `{ text: string }`. */
  | 'invalid'
  /** The type-check found errors: nothing ran. */
  | 'type-error'
  /** The sandbox or the SDK refused the run: nothing was staged. */
  | 'run-error'
  /** A run that staged nothing: a read. Its value and logs are the answer. */
  | 'read'
  /** The server's dry run (or the store's intake) refused the staged list. */
  | 'refused'
  /** MTR-0034: a document changed after the program read it. */
  | 'stale'
  /** The transport failed or the reply could not be read. */
  | 'failed'
  /** Clean dry run; it changes existing content, so it waits for the owner. */
  | 'waiting'
  | 'applied';

/** One diagnostic as the model gets it: the project's shape plus the phase and the program line (codemode.md section 6). */
export interface ToolDiagnostic {
  phase: 'input' | 'type-check' | 'sandbox' | 'dry-run' | 'apply';
  code?: string;
  severity: 'error' | 'warning';
  message: string;
  line?: number;
  column?: number;
  hint?: string;
  /** The server lacks this primitive: report it, do not work around it (codemode.md section 6, rule 2). */
  missingPrimitive?: MissingPrimitive;
  /** A finding that was there before this change; it does not block. */
  preexisting?: true;
}

export interface ToolOutcome {
  status: ToolStatus;
  /**
   * Whether the call counts against the repair budget: it reached neither a read's answer nor an accepted dry run
   * (`applied` or `waiting`).
   */
  failed: boolean;
  /** The bounded JSON text the model receives. */
  content: string;
  /** The proposal the call made, when it made one (the panel shows its card). */
  proposalId: string | null;
  diagnostics: ToolDiagnostic[];
  /** The gaps the type-check named, for the panel and the loop's report. */
  missingPrimitives: MissingPrimitive[];
}

/** What the tool needs of the editor. All injected, so the loop is tested without a browser. */
export interface ToolDeps {
  surface: SurfaceDocument;
  check(program: string, surface: SurfaceDocument): Promise<CheckResult>;
  run(program: CheckedProgram, surface: SurfaceDocument): Promise<RunResult>;
  /** The agent port of the session's proposal store: propose, declarePlan and reads. Nothing that approves. */
  agent: Pick<AgentPort, 'propose'>;
}

const cut = (text: string, max: number): string => (text.length <= max ? text : `${text.slice(0, max)}... [cut, ${text.length - max} more characters]`);

/** The call's input, or why it is not one. Only `{ text: string }` is a call; any other key is refused, not ignored. */
export function readInput(input: unknown): { text: string } | { error: string } {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return { error: 'run_program takes an object with one property, text (the program).' };
  const keys = Object.keys(input);
  const extra = keys.filter((key) => key !== 'text');
  if (extra.length > 0) {
    return { error: `run_program takes exactly one property, text. It has no ${extra.map((key) => `'${cut(key, 40)}'`).join(', ')}: a program cannot apply, approve, grant or revert; the owner does that in the editor.` };
  }
  const text = (input as { text?: unknown }).text;
  if (typeof text !== 'string' || text.trim() === '') return { error: 'run_program needs text: the program, as a non-empty string.' };
  return { text };
}

function fromCheck(found: CheckDiagnostic): ToolDiagnostic {
  const out: ToolDiagnostic = { phase: 'type-check', code: found.code, severity: found.severity, message: cut(found.message, MESSAGE_CHARS) };
  if (found.line > 0) {
    out.line = found.line;
    out.column = found.column;
  }
  if (found.hint !== undefined) out.hint = cut(found.hint, HINT_CHARS);
  if (found.missingPrimitive !== undefined) {
    out.missingPrimitive = { member: cut(found.missingPrimitive.member, 80), catalog: cut(found.missingPrimitive.catalog, 80), nearest: found.missingPrimitive.nearest.slice(0, 5).map((name) => cut(name, 80)) };
  }
  return out;
}

function at(out: ToolDiagnostic, source: SourcePosition | null | undefined): ToolDiagnostic {
  if (source !== null && source !== undefined) {
    out.line = source.line;
    out.column = source.column;
  }
  return out;
}

function fromSdk(found: SdkDiagnostic): ToolDiagnostic {
  const out: ToolDiagnostic = { phase: 'sandbox', code: found.code, severity: found.severity, message: cut(found.message, MESSAGE_CHARS) };
  if (found.hint !== undefined) out.hint = cut(found.hint, HINT_CHARS);
  return at(out, found.source);
}

function fromProblem(found: Problem): ToolDiagnostic {
  const phase: ToolDiagnostic['phase'] = found.phase === 'apply' ? 'apply' : found.phase === 'sdk' ? 'sandbox' : 'dry-run';
  const out: ToolDiagnostic = { phase, severity: found.severity, message: cut(found.resource !== undefined ? `${found.resource}: ${found.message}` : found.message, MESSAGE_CHARS) };
  if (found.code !== undefined) out.code = found.code;
  if (found.hint !== undefined) out.hint = cut(found.hint, HINT_CHARS);
  if (found.preexisting === true) out.preexisting = true;
  return at(out, found.source);
}

/** A JSON value as text of at most `max` characters; a larger one is replaced by a note and its head (never half a structure the model would parse). */
function boundedValue(value: JsonValue | null, max: number): { value: JsonValue | null } | { valueCut: string; valueChars: number; valueHint: string } {
  const text = JSON.stringify(value) ?? 'null';
  if (text.length <= max) return { value };
  return { valueCut: text.slice(0, max), valueChars: text.length, valueHint: VALUE_HINT };
}

/** What a cut value tells the model, so it narrows the next read instead of asking for the same bundle again. */
const VALUE_HINT = 'The value was too large and is cut. Return less: one or two entries per program, e.g. m.docs.describe("m.actions.setVar"), or only the fields you need: m.docs.type("item").fields.filter((f) => ["material", "display_name"].includes(f.id)).';

function boundedLogs(logs: readonly string[]): { logs: string[]; logsCut?: true } {
  const kept: string[] = [];
  let used = 0;
  for (const line of logs) {
    if (used + line.length > LOG_CHARS) {
      const room = LOG_CHARS - used;
      if (room > 20) kept.push(cut(line, room));
      return { logs: kept, logsCut: true };
    }
    kept.push(line);
    used += line.length;
  }
  return { logs: kept };
}

const NOTES: Record<ToolStatus, string> = {
  invalid: 'The call was not made. Call run_program with { text } only.',
  'type-error': 'The program did not type-check, so it did not run. Fix it and run it again; a diagnostic with missingPrimitive is a gap of the server to report to the owner, not to work around.',
  'run-error': 'The program ran and failed; nothing was staged. Fix it and run it again.',
  read: 'The program staged nothing: this is a read. Its value and logs are below.',
  refused: 'The server refused the staged changes in its dry run; nothing was written. Fix the program and run it again.',
  stale: 'A document changed after the program read it; nothing was written. Run the program again against the current state.',
  failed: 'The editor could not reach the server or read its reply; nothing was written that the reply did not confirm. Tell the owner.',
  waiting: 'The dry run is clean. The change touches existing content, so it WAITS for the owner\'s approval in the editor. You cannot approve it. Tell the owner what waits; do not run the program again.',
  applied: 'The dry run was clean and the change is applied. It is in the owner\'s change log, where they can revert it.',
};

interface Body {
  status: ToolStatus;
  note: string;
  diagnostics: ToolDiagnostic[];
  diagnosticsOmitted?: number;
  result?: Record<string, unknown>;
  proposal?: Record<string, unknown>;
}

/** Serialises a body within the cap. The parts are already cut, so the fallbacks are for a pathological mix only. */
function seal(body: Body): string {
  let text = JSON.stringify(body);
  if (text.length <= TOOL_RESULT_MAX_CHARS) return text;
  const lean: Body = { ...body };
  if (lean.result !== undefined) lean.result = { ...lean.result, logs: [], logsCut: true, value: undefined, valueCut: undefined, valueHint: undefined, valueOmitted: true, operations: undefined };
  text = JSON.stringify(lean);
  if (text.length <= TOOL_RESULT_MAX_CHARS) return text;
  const least: Body = { status: body.status, note: body.note, diagnostics: body.diagnostics.slice(0, 5), diagnosticsOmitted: (body.diagnosticsOmitted ?? 0) + Math.max(body.diagnostics.length - 5, 0) };
  text = JSON.stringify(least);
  return text.length <= TOOL_RESULT_MAX_CHARS ? text : JSON.stringify({ status: body.status, note: body.note, diagnostics: [], diagnosticsOmitted: body.diagnostics.length + (body.diagnosticsOmitted ?? 0) });
}

function outcome(status: ToolStatus, diagnostics: ToolDiagnostic[], extra: Pick<Body, 'result' | 'proposal'> = {}, proposalId: string | null = null): ToolOutcome {
  // errors first, so a cut list still leads with what blocks
  const ordered = [...diagnostics].sort((a, b) => Number(a.severity !== 'error') - Number(b.severity !== 'error'));
  const kept = ordered.slice(0, MAX_DIAGNOSTICS);
  const body: Body = { status, note: NOTES[status], diagnostics: kept, ...extra };
  if (ordered.length > kept.length) body.diagnosticsOmitted = ordered.length - kept.length;
  return {
    status,
    failed: status !== 'read' && status !== 'applied' && status !== 'waiting',
    content: seal(body),
    proposalId,
    diagnostics: kept,
    missingPrimitives: kept.flatMap((found) => (found.missingPrimitive === undefined ? [] : [found.missingPrimitive])),
  };
}

function describeRun(run: RunResult): Record<string, unknown> {
  const operations = run.operations.slice(0, MAX_OPERATIONS).map((operation, index) => {
    const target = typeof operation.path === 'string' ? operation.path : typeof operation.type === 'string' && typeof operation.id === 'string' ? `${operation.type}/${operation.id}` : undefined;
    const line = run.sources[index]?.line;
    return { kind: cut(operation.kind, 40), ...(target === undefined ? {} : { target: cut(target, 160) }), ...(line === undefined ? {} : { line }) };
  });
  return {
    ok: run.ok,
    sdkVersion: run.sdkVersion,
    pack: run.pack,
    summary: run.summary === null ? null : cut(run.summary, 500),
    staged: run.operations.length,
    operations,
    ...(run.operations.length > operations.length ? { operationsOmitted: run.operations.length - operations.length } : {}),
    ...boundedValue(run.value, VALUE_CHARS),
    ...boundedLogs(run.logs),
    stats: run.stats,
  };
}

function describeProposal(proposal: Proposal): Record<string, unknown> {
  const documents = (proposal.documents as { path?: unknown; kind?: unknown }[])
    .slice(0, MAX_DOCUMENTS)
    .map((document) => ({ path: cut(String(document.path), 160), kind: cut(String(document.kind), 20) }));
  return {
    id: proposal.id,
    state: proposal.state,
    documents,
    ...(proposal.documents.length > documents.length ? { documentsOmitted: proposal.documents.length - documents.length } : {}),
    needsApprovalOf: proposal.needs.slice(0, MAX_DOCUMENTS).map((object) => cut(object, 200)),
    ...(proposal.needsProposalApproval ? { needsApprovalAsAWhole: true } : {}),
    transactionId: proposal.transactionId,
  };
}

const STATUS_OF: Partial<Record<Proposal['state'], ToolStatus>> = {
  applied: 'applied',
  waiting: 'waiting',
  refused: 'refused',
  stale: 'stale',
  failed: 'failed',
};

/**
 * Runs one `run_program` call to its end and never throws: a failure of any step is an outcome the model can read.
 * `input` is the call's raw arguments.
 */
export async function runProgram(input: unknown, deps: ToolDeps): Promise<ToolOutcome> {
  const read = readInput(input);
  if ('error' in read) return outcome('invalid', [{ phase: 'input', severity: 'error', message: read.error }]);
  const program = read.text;

  let checked: CheckResult;
  try {
    checked = await deps.check(program, deps.surface);
  } catch (error) {
    return outcome('type-error', [{ phase: 'type-check', code: 'CHK-0004', severity: 'error', message: cut(`The checker could not run: ${error instanceof Error ? error.message : String(error)}`, MESSAGE_CHARS) }]);
  }
  const checkDiagnostics = checked.diagnostics.map(fromCheck);
  if (!checked.ok || checked.js === undefined || checked.map === undefined) {
    if (checked.truncated) checkDiagnostics.push({ phase: 'type-check', severity: 'warning', message: 'More type errors exist than are listed; fix these first.' });
    return outcome('type-error', checkDiagnostics);
  }

  let run: RunResult;
  try {
    run = await deps.run({ js: checked.js, map: checked.map }, deps.surface);
  } catch (error) {
    return outcome('run-error', [...checkDiagnostics, { phase: 'sandbox', severity: 'error', message: cut(`The sandbox could not run the program: ${error instanceof Error ? error.message : String(error)}`, MESSAGE_CHARS) }]);
  }
  const runDiagnostics = [...checkDiagnostics, ...run.diagnostics.map(fromSdk), ...run.late.map((found) => ({ ...fromSdk(found), severity: 'warning' as const }))];
  if (!run.ok) return outcome('run-error', runDiagnostics, { result: describeRun(run) });
  if (run.operations.length === 0) return outcome('read', runDiagnostics, { result: describeRun(run) });

  let proposal: Proposal;
  try {
    proposal = await deps.agent.propose(
      { ok: run.ok, pack: run.pack, operations: run.operations, sources: run.sources, diagnostics: run.diagnostics, summary: run.summary },
      { program },
    );
  } catch (error) {
    return outcome('failed', [...runDiagnostics, { phase: 'dry-run', severity: 'error', message: cut(`The proposal could not be made: ${error instanceof Error ? error.message : String(error)}`, MESSAGE_CHARS) }], { result: describeRun(run) });
  }
  // The store's SDK-phase problems repeat the run's own diagnostics; the dry run's and the apply's are new.
  const serverFindings = proposal.problems.filter((problem) => problem.phase !== 'sdk').map(fromProblem);
  const status = STATUS_OF[proposal.state] ?? 'failed';
  return outcome(status, [...runDiagnostics, ...serverFindings], { result: describeRun(run), proposal: describeProposal(proposal) }, proposal.id);
}
