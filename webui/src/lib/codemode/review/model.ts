/**
 * The review's view model (codemode.md section 5.6, D13): pure functions from a proposal or a change-log entry to what the
 * panel draws. **The facts come from the dry run and the operation list only** (the documents and their text, the
 * classification, the diagnostics, the staged operations and their source positions). The agent's summary is returned
 * separately and the panel labels it as the agent's words: nothing here reads it to decide what is new, what is changed or
 * what needs approval.
 */
import type { SourcePosition, StagedOperation } from '../core/types';
import type { AppliedDocument, ChangeLogEntry, Problem, Proposal, ProposalState } from '../host/store';
import { diffText, type Diff } from './diff';

export type Tone = 'neutral' | 'accent' | 'ok' | 'warn' | 'error';

export interface StateInfo {
  /** The word that names the state: always shown as text, never by colour alone. */
  label: string;
  tone: Tone;
  /** One sentence: what happened to the files, and what the owner can do. */
  explanation: string;
}

/** What each state says, in plain words (never offers to force anything: a stale or refused proposal is re-run by the agent). */
export function stateInfo(proposal: Pick<Proposal, 'state' | 'needs' | 'needsProposalApproval' | 'classification'> & { approval?: 'not_needed' | 'granted' }): StateInfo {
  switch (proposal.state) {
    case 'checking':
      return { label: 'Checking', tone: 'neutral', explanation: 'The server is dry-running this proposal. Nothing is written yet.' };
    case 'waiting':
      return proposal.needs.length > 0 || proposal.needsProposalApproval
        ? { label: 'Needs your approval', tone: 'warn', explanation: 'It changes content that already exists. Nothing is written until you approve.' }
        : { label: 'Ready to apply', tone: 'accent', explanation: 'Nothing here needs your approval. Applying writes exactly the documents below.' };
    case 'applying':
      return { label: 'Applying', tone: 'neutral', explanation: 'The server is writing the documents below.' };
    case 'applied':
      return proposal.approval === 'granted'
        ? { label: 'Applied', tone: 'ok', explanation: 'Applied after your approval. You can revert it from the change log.' }
        : { label: 'Applied', tone: 'ok', explanation: 'Applied on its own: it only creates new content, so nothing needed your approval. You can revert it from the change log.' };
    case 'reverted':
      return { label: 'Reverted', tone: 'neutral', explanation: 'You reverted it: each document went back to the text it had before.' };
    case 'refused':
      return { label: 'Refused', tone: 'error', explanation: 'The dry run refused it, so nothing was written. The agent has to write a new program; this one cannot be applied.' };
    case 'stale':
      return { label: 'Out of date', tone: 'warn', explanation: 'A document changed after the program read it. Nothing was written. Ask the agent to run the program again against the current files.' };
    case 'failed':
      return { label: 'Failed', tone: 'error', explanation: 'The request did not complete, so nothing was applied that the server did not confirm. Ask the agent to try again.' };
    case 'rejected':
      return { label: 'Rejected', tone: 'neutral', explanation: 'You rejected it. Nothing was written.' };
  }
}

/** The states in which the owner still has something to decide. */
export function isOpen(state: ProposalState): boolean {
  return state === 'waiting' || state === 'checking' || state === 'applying';
}

/** A document of a proposal, as the review draws it. */
export interface DocumentView {
  path: string;
  /** `created`, `changed`, `deleted`, `moved`, `unchanged` as the server says; anything else is shown as given. */
  kind: string;
  /** New content (this session or this proposal creates it) or an existing object. */
  origin: 'new' | 'existing';
  /** The object id `<pack>:<path>`, the unit a grant covers. */
  object: string;
  /** An existing object whose grant this proposal still lacks. */
  needsApproval: boolean;
  /** An existing object already granted (this proposal or an earlier one). */
  approved: boolean;
  /** The operations that touched it (indexes into the proposal's operation list). */
  operations: number[];
  text: string | null;
  /** The stored text before: a string, null when nothing was stored, undefined when it could not be read. */
  before: string | null | undefined;
  /** Null when the before-text is unavailable and the document is not new: no diff can be drawn honestly. */
  diff: Diff | null;
  /** The reason there is no diff, when there is none. */
  diffNote: string | null;
  /** Where a moved document came from. */
  movedFrom: string | null;
}

function numbers(value: unknown): number[] {
  return Array.isArray(value) ? value.filter((entry): entry is number => typeof entry === 'number' && Number.isInteger(entry)) : [];
}

/** The proposal's documents grouped as the review lists them: new content first, then changes to existing objects. */
export function documentViews(proposal: Pick<Proposal, 'pack' | 'documents' | 'classification' | 'needs' | 'before'>): DocumentView[] {
  const created = new Set<string>(proposal.classification?.created ?? []);
  const existing = new Set<string>(proposal.classification?.existing ?? []);
  const needs = new Set<string>(proposal.needs);
  const views = proposal.documents.flatMap((raw): DocumentView[] => {
    const document = raw as Record<string, unknown>;
    if (typeof document.path !== 'string') return [];
    const path = document.path;
    const object = `${proposal.pack}:${path}`;
    const kind = typeof document.kind === 'string' ? document.kind : 'changed';
    const isNew = created.has(object) || (kind === 'created' && !existing.has(object));
    const text = typeof document.text === 'string' ? document.text : null;
    const before = Object.prototype.hasOwnProperty.call(proposal.before, path) ? (proposal.before[path] as string | null) : undefined;
    let diff: Diff | null = null;
    let diffNote: string | null = null;
    if (isNew || kind === 'created') diff = diffText(null, text);
    else if (before === undefined) diffNote = 'The stored text could not be read, so the changes cannot be shown line by line. The resulting text is below.';
    else if (kind === 'unchanged') diffNote = 'The operations leave this document as it is.';
    else diff = diffText(before, text);
    return [
      {
        path,
        kind,
        origin: isNew ? 'new' : 'existing',
        object,
        needsApproval: !isNew && needs.has(object),
        approved: !isNew && !needs.has(object),
        operations: numbers(document.operations),
        text,
        before,
        diff,
        diffNote,
        movedFrom: typeof document.movedFrom === 'string' ? document.movedFrom : null,
      },
    ];
  });
  // new content first, then existing; stable inside each group
  return [...views.filter((view) => view.origin === 'new'), ...views.filter((view) => view.origin === 'existing')];
}

/** The count line: facts from the documents, never from the agent's summary. */
export function factLine(views: readonly DocumentView[]): string {
  if (views.length === 0) return 'The dry run reports no documents.';
  const created = views.filter((view) => view.kind === 'created').length;
  const changed = views.filter((view) => view.kind === 'changed' || view.kind === 'moved').length;
  const deleted = views.filter((view) => view.kind === 'deleted').length;
  const parts: string[] = [];
  if (created > 0) parts.push(`creates ${created} ${created === 1 ? 'document' : 'documents'}`);
  if (changed > 0) parts.push(`changes ${changed} existing ${changed === 1 ? 'document' : 'documents'}`);
  if (deleted > 0) parts.push(`deletes ${deleted} existing ${deleted === 1 ? 'document' : 'documents'}`);
  const unchanged = views.length - created - changed - deleted;
  if (unchanged > 0) parts.push(`leaves ${unchanged} as ${unchanged === 1 ? 'it is' : 'they are'}`);
  const text = parts.join(', ');
  return text.charAt(0).toUpperCase() + text.slice(1) + '.';
}

export interface ProgramRow {
  /** One-based. */
  line: number;
  text: string;
  /** The operations this line staged (their index and kind), from the operations' recorded source positions. */
  staged: { index: number; kind: string }[];
}

/** The program text by line, each line carrying the operations it staged. */
export function programRows(
  program: string,
  operations: readonly StagedOperation[],
  sources: readonly (SourcePosition | null)[],
): ProgramRow[] {
  const rows: ProgramRow[] = program.replace(/\r\n/g, '\n').split('\n').map((text, index) => ({ line: index + 1, text, staged: [] }));
  operations.forEach((operation, index) => {
    const position = sources[index];
    if (position === null || position === undefined) return;
    const row = rows[position.line - 1];
    if (row !== undefined) row.staged.push({ index, kind: operation.kind });
  });
  return rows;
}

export interface ProblemGroup {
  /** The operation the findings are about, or null for findings about the list as a whole. */
  operation: number | null;
  kind: string | null;
  /** The target the operation names (a path, or the created id). */
  target: string | null;
  /** The program line that staged it. */
  line: number | null;
  problems: Problem[];
}

function targetOf(operation: StagedOperation | undefined): string | null {
  if (operation === undefined) return null;
  const record = operation as unknown as Record<string, unknown>;
  for (const key of ['path', 'fromPath']) if (typeof record[key] === 'string') return record[key] as string;
  if (typeof record.type === 'string' && typeof record.id === 'string') return `${record.type}/${record.id}.yml`;
  return null;
}

/** Findings grouped by the operation that caused them, errors before warnings before older findings, whole-list ones first. */
export function problemGroups(problems: readonly Problem[], operations: readonly StagedOperation[]): ProblemGroup[] {
  const groups = new Map<number | null, ProblemGroup>();
  for (const problem of problems) {
    const key = problem.operation === undefined ? null : problem.operation;
    let group = groups.get(key);
    if (group === undefined) {
      const operation = key === null ? undefined : operations[key];
      group = { operation: key, kind: operation?.kind ?? null, target: targetOf(operation), line: problem.source?.line ?? null, problems: [] };
      groups.set(key, group);
    }
    group.problems.push(problem);
  }
  const rank = (problem: Problem): number => (problem.severity === 'error' ? 0 : problem.preexisting === true ? 2 : 1);
  for (const group of groups.values()) group.problems.sort((a, b) => rank(a) - rank(b));
  return [...groups.values()].sort((a, b) => (a.operation ?? -1) - (b.operation ?? -1));
}

/** A finding's kind, as the review names it: text, so it is never colour alone. */
export function problemLabel(problem: Problem): string {
  if (problem.preexisting === true) return 'Already there before this change';
  return problem.severity === 'error' ? 'Error' : 'Warning';
}

/** Whether the proposal has an error that blocks it. A finding that predates the change is a warning and never blocks. */
export function hasBlockingError(problems: readonly Problem[]): boolean {
  return problems.some((problem) => problem.severity === 'error' && problem.preexisting !== true);
}

/** A change-log entry's documents drawn as diffs (the stored text before it applied against the text it wrote). */
export function logDocumentViews(entry: Pick<ChangeLogEntry, 'documents' | 'before'>): { document: AppliedDocument; diff: Diff | null; note: string | null }[] {
  return entry.documents.map((document) => {
    const known = Object.prototype.hasOwnProperty.call(entry.before, document.path);
    const before = known ? (entry.before[document.path] as string | null) : undefined;
    if (document.kind === 'created') return { document, diff: diffText(null, document.text), note: null };
    if (before === undefined) return { document, diff: null, note: 'The text before this change was not read, so only the result is available.' };
    return { document, diff: diffText(before, document.text), note: null };
  });
}

/** `pack` out of an audit target of the form `<pack> (<n> documents, <tx>)`; null when it does not look like one. */
export function packOfAuditTarget(target: string | undefined): string | null {
  if (target === undefined) return null;
  const match = /^([a-z0-9_-]{2,64})(?:\s|$)/.exec(target);
  return match === null ? null : (match[1] as string);
}

/** `<n> documents` out of an audit target, or null. */
export function documentCountOfAuditTarget(target: string | undefined): number | null {
  const match = target === undefined ? null : /\((\d+) documents?/.exec(target);
  return match === null ? null : Number(match[1]);
}
