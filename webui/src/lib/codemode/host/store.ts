/**
 * The proposal lifecycle in the editor host (codemode.md section 5, 5.5, 5.6; D13; issue 346): a sealed program result
 * goes to a dry run, is classified, and is applied when policy allows or waits for the owner. Session memory only:
 * nothing here is content (D1), nothing is persisted, and a session's end is the end of the store.
 *
 * Two faces, one store:
 *
 *  - `agent` (`AgentPort`): what the chat tool (#348) may reach. `propose`, `declarePlan`, reads and `subscribe`. It has
 *    no approve, no grant, no apply, no revert, and a test walks the object to prove it.
 *  - the store itself: what the review UI (#347) calls from a click. `approve` and `revert` need an `ApprovalGesture`.
 *
 * What is sent is decided in exactly one place, `send`, which re-evaluates the policy over the stored classification and
 * the current grants on every call. A proposal in any other state is never sent, and a stale one is never forced.
 *
 * Applies are serialised (one queue): stacked proposals (5.5) run their dry run and their apply in order, and an apply
 * marks the waiting proposals that touch what it changed stale, so a program is re-run against the new state and never
 * patched or forced.
 */
import type { ProgramResult, SourcePosition, StagedOperation } from '../core/types';
import { classify, type Classification } from './classify';
import { decide, GestureError, GrantSet, spendGesture, type ApprovalGesture } from './grants';
import { isObjectId, objectId, type ObjectId } from './objects';
import { MalformedReport, type ReportDiagnostic, type TransactionReport, type TransactionTransport } from './transport';

export type ProposalState =
  /** Queued or in its dry run. */
  | 'checking'
  /** A clean dry run that needs the owner (the proposal's `needs` and `needsProposalApproval` say what). */
  | 'waiting'
  | 'applying'
  | 'applied'
  /** Reverted by `revert`. */
  | 'reverted'
  /** The program is not a valid proposal, or its dry run or apply was refused: the diagnostics go back to the agent. */
  | 'refused'
  /** MTR-0034: a document changed after the program read it. Re-run the program; never applied as is. */
  | 'stale'
  /** The transport failed or the reply could not be read. Nothing was applied that the reply did not say was. */
  | 'failed'
  | 'rejected';

export type ProblemPhase = 'intake' | 'sdk' | 'dry-run' | 'apply' | 'revert';

/** One finding about a proposal, in one shape for every phase (codemode.md section 6). */
export interface Problem {
  phase: ProblemPhase;
  /** The `MTR-####` or `SDK-####` code, absent for a finding of the host itself. */
  code?: string;
  severity: 'error' | 'warning';
  message: string;
  hint?: string;
  /** The operation it is about, and where in the program that operation was staged. */
  operation?: number;
  source?: SourcePosition;
  resource?: string;
  /** A finding that predates the transaction: reported as a warning, never blocking (#372). */
  preexisting?: boolean;
}

export interface Proposal {
  readonly id: string;
  readonly state: ProposalState;
  readonly plan: string | null;
  readonly pack: string;
  readonly program: string;
  readonly summary: string | null;
  readonly operations: readonly StagedOperation[];
  readonly sources: readonly (SourcePosition | null)[];
  /** Known after a clean dry run. */
  readonly classification: Classification | null;
  /** The dry run's resulting documents (the diff is drawn from them). */
  readonly documents: readonly unknown[];
  readonly problems: readonly Problem[];
  /** The existing objects it touches that are not granted now. */
  readonly needs: readonly ObjectId[];
  /** Something in it can only be cleared by approving this proposal itself. */
  readonly needsProposalApproval: boolean;
  readonly transactionId: string | null;
  readonly revertible: boolean;
  readonly revertNote: string | null;
  /** The transaction of the revert, once reverted. */
  readonly revertedBy: string | null;
  readonly appliedAt: number | null;
}

/** An entry of the session's change log (5.6): what applied, by which program, and whether it can still be reverted. */
export interface ChangeLogEntry {
  readonly proposal: string;
  readonly transactionId: string;
  readonly appliedAt: number;
  readonly summary: string | null;
  readonly program: string;
  readonly approval: 'not_needed' | 'granted';
  readonly documents: readonly { path: string; kind: string }[];
  readonly revertible: boolean;
  readonly revertNote: string | null;
  readonly revertedBy: string | null;
  /** Why the last revert attempt was refused, if it was. */
  readonly revertProblems: readonly Problem[];
}

export interface Plan {
  readonly id: string;
  readonly title: string | null;
  /** The existing objects the plan declares. Declaring grants nothing. */
  readonly objects: readonly ObjectId[];
  /** Whether the owner approved the set. */
  readonly approved: boolean;
}

export interface Snapshot {
  readonly proposals: readonly Proposal[];
  readonly plans: readonly Plan[];
  readonly grants: readonly ObjectId[];
  readonly log: readonly ChangeLogEntry[];
}

export interface ProposeOptions {
  /** The program text the result came from (audited with its digest, size-bounded by the server). */
  program: string;
  /** A plan this proposal belongs to, from `declarePlan`. A label only: grants are per object. */
  plan?: string;
  /** Apply on its own when the policy allows (default true). */
  autoApply?: boolean;
}

export type ApproveTarget = { proposal: string } | { objects: readonly string[] } | { plan: string };

export interface ApproveResult {
  /** The objects this call added to the session's grants. */
  granted: ObjectId[];
  /** The proposal, after an `{ proposal }` approval applied it. */
  proposal?: Proposal;
}

/** What the chat tool may reach. There is no approve, grant, apply or revert here, by type and by a test. */
export interface AgentPort {
  propose(result: ProposalInput, options: ProposeOptions): Promise<Proposal>;
  declarePlan(declaration: { title?: string; objects: readonly string[] }): Plan;
  get(id: string): Proposal | undefined;
  list(): readonly Proposal[];
  snapshot(): Snapshot;
  subscribe(listener: () => void): () => void;
}

/** The part of a `ProgramResult` the lifecycle reads. Everything else a program or a model produced is not an input. */
export type ProposalInput = Pick<ProgramResult, 'ok' | 'pack' | 'operations' | 'sources' | 'diagnostics' | 'summary'>;

export interface StoreOptions {
  transport: TransactionTransport;
  now?: () => number;
}

const MAX_PLAN_OBJECTS = 500;
const STALE = 'MTR-0034';

interface Entry {
  id: string;
  plan: string | null;
  pack: string;
  program: string;
  summary: string | null;
  operations: StagedOperation[];
  sources: (SourcePosition | null)[];
  autoApply: boolean;
  state: ProposalState;
  problems: Problem[];
  classification: Classification | null;
  documents: unknown[];
  proposalApproved: boolean;
  transactionId: string | null;
  revertible: boolean;
  revertNote: string | null;
  revertedBy: string | null;
  appliedAt: number | null;
  approval: 'not_needed' | 'granted';
  appliedDocuments: { path: string; kind: string }[];
  revertProblems: Problem[];
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function deepFreeze<T>(value: T): T {
  if (typeof value === 'object' && value !== null && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}

export class ProposalStore {
  /** The face for the chat tool. */
  readonly agent: AgentPort;

  private readonly transport: TransactionTransport;
  private readonly now: () => number;
  private readonly grants = new GrantSet();
  private readonly sessionCreated = new Set<string>();
  private readonly entries: Entry[] = [];
  private readonly plans = new Map<string, { id: string; title: string | null; objects: ObjectId[]; approved: boolean }>();
  private readonly listeners = new Set<() => void>();
  private tail: Promise<unknown> = Promise.resolve();
  private counter = 0;
  private disposed = false;

  constructor(options: StoreOptions) {
    this.transport = options.transport;
    this.now = options.now ?? Date.now;
    this.agent = Object.freeze({
      propose: (result: ProposalInput, opts: ProposeOptions) => this.propose(result, opts),
      declarePlan: (declaration: { title?: string; objects: readonly string[] }) => this.declarePlan(declaration),
      get: (id: string) => this.get(id),
      list: () => this.list(),
      snapshot: () => this.snapshot(),
      subscribe: (listener: () => void) => this.subscribe(listener),
    });
  }

  // ---- reads ----

  get(id: string): Proposal | undefined {
    const entry = this.entries.find((candidate) => candidate.id === id);
    return entry === undefined ? undefined : this.view(entry);
  }

  list(): readonly Proposal[] {
    return this.entries.map((entry) => this.view(entry));
  }

  snapshot(): Snapshot {
    return deepFreeze({
      proposals: this.list(),
      plans: [...this.plans.values()].map((plan) => ({ id: plan.id, title: plan.title, objects: [...plan.objects], approved: plan.approved })),
      grants: this.grants.ids(),
      log: this.entries.filter((entry) => entry.transactionId !== null).map((entry) => this.logEntry(entry)),
    });
  }

  /** The session's change log, oldest first. */
  log(): readonly ChangeLogEntry[] {
    return this.snapshot().log;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  // ---- the agent's calls ----

  /**
   * Takes a sealed program result through the dry run and, when the policy allows, the apply. Resolves when the
   * proposal has settled: applied, waiting for the owner, refused, stale or failed. `ok: false` and an empty list are
   * refused here without a call, so a failed program never reaches the server.
   */
  propose(result: ProposalInput, options: ProposeOptions): Promise<Proposal> {
    const entry = this.intake(result, options);
    this.changed();
    if (entry.state !== 'checking') return Promise.resolve(this.view(entry));
    return this.enqueue(() => this.check(entry)).then(() => this.view(entry));
  }

  /**
   * Records the existing objects a plan will change or delete (C11). Declaring grants nothing: the declaration is
   * model-authored data, and only `approve({ plan })` from a user gesture turns it into grants.
   */
  declarePlan(declaration: { title?: string; objects: readonly string[] }): Plan {
    const objects = [...new Set(declaration.objects)];
    if (objects.length > MAX_PLAN_OBJECTS) throw new RangeError(`a plan declares at most ${MAX_PLAN_OBJECTS} objects`);
    const bad = objects.find((id) => !isObjectId(id));
    if (bad !== undefined) throw new TypeError(`'${String(bad)}' is not an object id (<pack>:<path>)`);
    const id = `plan-${++this.counter}`;
    this.plans.set(id, { id, title: declaration.title ?? null, objects: (objects as ObjectId[]).sort(), approved: false });
    this.changed();
    return this.planView(id);
  }

  // ---- the owner's calls ----

  /**
   * The owner's approval, from a click. Adds to the session's grants: the proposal's existing objects (`{ proposal }`,
   * and then applies it), the named objects, or every object a plan declared (one approval for the plan, C11).
   * Throws `GestureError` without a live gesture and changes nothing.
   */
  async approve(target: ApproveTarget, gesture: ApprovalGesture): Promise<ApproveResult> {
    if (this.disposed) throw new Error('the session has ended');
    // The one call that adds to the grants. It spends the gesture first, so without a live one nothing below runs.
    let ids: readonly string[] = [];
    let entry: Entry | undefined;
    if ('proposal' in target) {
      entry = this.entries.find((candidate) => candidate.id === target.proposal);
      if (entry !== undefined && entry.state === 'waiting' && entry.classification !== null) ids = entry.classification.existing;
      else entry = undefined;
    } else if ('plan' in target) {
      ids = this.plans.get(target.plan)?.objects ?? [];
    } else {
      ids = target.objects.filter((id) => isObjectId(id));
    }
    const granted = this.grants.grant(ids, gesture);
    if ('plan' in target) {
      const plan = this.plans.get(target.plan);
      if (plan !== undefined) plan.approved = true;
    }
    if (entry === undefined) {
      this.changed();
      return { granted };
    }
    entry.proposalApproved = true;
    this.changed();
    await this.enqueue(() => this.send(entry));
    return { granted, proposal: this.view(entry) };
  }

  /** Applies a waiting proposal the policy now allows (its objects granted). Otherwise it stays as it is. */
  async apply(id: string): Promise<Proposal | undefined> {
    const entry = this.entries.find((candidate) => candidate.id === id);
    if (entry === undefined) return undefined;
    if (entry.state === 'waiting') await this.enqueue(() => this.send(entry));
    return this.view(entry);
  }

  /** The owner declines. A proposal that has not been applied is dropped; nothing is sent. */
  reject(id: string): Proposal | undefined {
    const entry = this.entries.find((candidate) => candidate.id === id);
    if (entry === undefined) return undefined;
    if (entry.state === 'checking' || entry.state === 'waiting') {
      entry.state = 'rejected';
      this.changed();
    }
    return this.view(entry);
  }

  /**
   * Reverts an applied proposal through `authoring.revertTransaction`: a dry run first, then the apply, both refused
   * with the server's diagnostics when a document moved on (MTR-0034) or the record is gone (MTR-0104). A user
   * action: it needs a gesture, and the agent port has no way to call it.
   */
  async revert(id: string, gesture: ApprovalGesture): Promise<Proposal | undefined> {
    if (this.disposed) throw new Error('the session has ended');
    if (!spendGesture(gesture)) throw new GestureError('a revert needs a live user gesture');
    const entry = this.entries.find((candidate) => candidate.id === id);
    if (entry === undefined) return undefined;
    await this.enqueue(() => this.revertNow(entry));
    return this.view(entry);
  }

  /** The session ends: nothing is kept, nothing more is sent. */
  dispose(): void {
    this.disposed = true;
    this.entries.length = 0;
    this.plans.clear();
    this.sessionCreated.clear();
    this.listeners.clear();
  }

  // ---- internals ----

  private enqueue<T>(task: () => Promise<T>): Promise<T> {
    const run = this.tail.then(task, task);
    this.tail = run.catch(() => undefined);
    return run;
  }

  private changed(): void {
    for (const listener of [...this.listeners]) {
      try {
        listener();
      } catch {
        // a broken subscriber never breaks the lifecycle
      }
    }
  }

  private intake(result: ProposalInput, options: ProposeOptions): Entry {
    const entry: Entry = {
      id: `proposal-${++this.counter}`,
      plan: options.plan !== undefined && this.plans.has(options.plan) ? options.plan : null,
      pack: typeof result?.pack === 'string' ? result.pack : '',
      program: typeof options.program === 'string' ? options.program : '',
      summary: typeof result?.summary === 'string' ? result.summary : null,
      operations: [],
      sources: [],
      autoApply: options.autoApply !== false,
      state: 'checking',
      problems: [],
      classification: null,
      documents: [],
      proposalApproved: false,
      transactionId: null,
      revertible: false,
      revertNote: null,
      revertedBy: null,
      appliedAt: null,
      approval: 'not_needed',
      appliedDocuments: [],
      revertProblems: [],
    };
    this.entries.push(entry);

    const refuse = (message: string): Entry => {
      entry.problems.push({ phase: 'intake', severity: 'error', message });
      entry.state = 'refused';
      return entry;
    };
    if (this.disposed) return refuse('the session has ended');
    if (typeof result !== 'object' || result === null) return refuse('there is no program result');
    const operations = Array.isArray(result.operations) ? result.operations : [];
    const sources = Array.isArray(result.sources) ? result.sources : [];
    for (const found of Array.isArray(result.diagnostics) ? result.diagnostics : []) {
      entry.problems.push({
        phase: 'sdk',
        code: found.code,
        severity: found.severity === 'warning' ? 'warning' : 'error',
        message: found.message,
        hint: found.hint,
        source: found.source,
      });
    }
    if (result.ok !== true) return refuse('the program did not run cleanly; its diagnostics are attached');
    if (objectId(entry.pack, 'x') === null) return refuse('the program names no valid pack');
    if (operations.length === 0) return refuse('the program staged no operations');
    if (!operations.every((operation) => typeof operation === 'object' && operation !== null && typeof operation.kind === 'string')) {
      return refuse('the operation list holds an entry that is not an operation');
    }
    // What was checked is what is applied: a frozen deep copy, taken once, sent unchanged.
    entry.operations = deepFreeze(clone(operations as StagedOperation[]));
    entry.sources = deepFreeze(clone(sources as (SourcePosition | null)[]));
    return entry;
  }

  /** Dry run, classify, and (policy allowing) apply: one slot of the queue, so stacked proposals interleave with nothing. */
  private async check(entry: Entry): Promise<void> {
    if (entry.state !== 'checking') return;
    let report: TransactionReport;
    try {
      report = await this.transport.transaction({ pack: entry.pack, operations: entry.operations, dryRun: true });
    } catch (error) {
      if (entry.state === 'checking') this.failed(entry, 'dry-run', error);
      return;
    }
    if (entry.state !== 'checking') return;
    entry.problems.push(...this.problemsOf(entry, report.diagnostics, 'dry-run'));
    if (this.isStale(report)) return this.markStale(entry, 'dry-run');
    const blocking = report.diagnostics.some((found) => found.severity === 'error' && found.preexisting !== true);
    if (!report.accepted || blocking || report.dryRun !== true || report.applied) {
      entry.state = 'refused';
      if (!entry.problems.some((problem) => problem.severity === 'error' && problem.phase === 'dry-run')) {
        entry.problems.push({ phase: 'dry-run', severity: 'error', message: 'the dry run did not accept the proposal' });
      }
      this.changed();
      return;
    }
    entry.documents = report.documents;
    entry.classification = deepFreeze(
      classify({ pack: entry.pack, operations: entry.operations, documents: report.documents, sessionCreated: this.sessionCreated }),
    );
    entry.state = 'waiting';
    this.changed();
    if (entry.autoApply) await this.send(entry);
  }

  /**
   * The one place an apply is sent. It re-evaluates the policy over the stored classification and the grants as they
   * are now, and sends nothing unless the proposal is waiting, classified, and allowed.
   */
  private async send(entry: Entry): Promise<void> {
    if (this.disposed || entry.state !== 'waiting' || entry.classification === null) return;
    const decision = decide(entry.classification, this.grants, entry.proposalApproved);
    if (!decision.allowed) return;
    const approval = entry.classification.existing.length > 0 || entry.classification.blockers.length > 0 ? 'granted' : 'not_needed';
    entry.state = 'applying';
    this.changed();
    let report: TransactionReport;
    try {
      report = await this.transport.transaction({
        pack: entry.pack,
        operations: entry.operations,
        dryRun: false,
        origin: 'code-mode',
        approval,
        program: entry.program,
      });
    } catch (error) {
      this.failed(entry, 'apply', error);
      return;
    }
    if (this.isStale(report)) {
      entry.problems.push(...this.problemsOf(entry, report.diagnostics, 'apply'));
      return this.markStale(entry, 'apply');
    }
    if (!report.accepted || !report.applied || report.dryRun) {
      entry.problems.push(...this.problemsOf(entry, report.diagnostics, 'apply'));
      if (!entry.problems.some((problem) => problem.phase === 'apply' && problem.severity === 'error')) {
        entry.problems.push({ phase: 'apply', severity: 'error', message: 'the server did not apply the proposal' });
      }
      entry.state = 'refused';
      this.changed();
      return;
    }
    entry.problems.push(...this.problemsOf(entry, report.diagnostics, 'apply'));
    entry.state = 'applied';
    entry.approval = approval;
    entry.transactionId = report.transactionId;
    entry.revertible = report.revertible && report.transactionId !== null;
    entry.revertNote = report.revertNote;
    entry.appliedAt = this.now();
    entry.appliedDocuments = report.documents.flatMap((document) =>
      typeof document.path === 'string' ? [{ path: document.path, kind: String(document.kind) }] : [],
    );
    this.afterWrite(entry, report, entry.id);
    this.changed();
  }

  private async revertNow(entry: Entry): Promise<void> {
    if (entry.state !== 'applied' || entry.transactionId === null) return;
    if (!entry.revertible) {
      entry.revertProblems = [
        { phase: 'revert', severity: 'error', message: entry.revertNote ?? 'the server did not keep what a revert needs for this transaction' },
      ];
      this.changed();
      return;
    }
    const transactionId = entry.transactionId;
    try {
      const dry = await this.transport.revert({ transactionId, dryRun: true });
      const blocking = dry.diagnostics.some((found) => found.severity === 'error' && found.preexisting !== true);
      if (!dry.accepted || blocking) {
        entry.revertProblems = this.problemsOf(entry, dry.diagnostics, 'revert');
        if (entry.revertProblems.length === 0) {
          entry.revertProblems = [{ phase: 'revert', severity: 'error', message: 'the dry run of the revert did not accept it' }];
        }
        this.changed();
        return;
      }
      const done = await this.transport.revert({ transactionId, dryRun: false });
      if (!done.accepted || !done.applied) {
        entry.revertProblems = this.problemsOf(entry, done.diagnostics, 'revert');
        if (entry.revertProblems.length === 0) {
          entry.revertProblems = [{ phase: 'revert', severity: 'error', message: 'the server did not apply the revert' }];
        }
        this.changed();
        return;
      }
      entry.revertProblems = [];
      entry.revertedBy = done.transactionId;
      entry.state = 'reverted';
      this.afterWrite(entry, done, entry.id);
      this.changed();
    } catch (error) {
      entry.revertProblems = [{ phase: 'revert', severity: 'error', message: error instanceof Error ? error.message : String(error) }];
      this.changed();
    }
  }

  /** After a write: what the session created is never "existing"; waiting proposals over what changed are stale. */
  private afterWrite(written: Entry, report: TransactionReport, source: string): void {
    const changed = new Set<string>();
    for (const document of report.documents) {
      const id = objectId(written.pack, document.path);
      if (id === null) continue;
      changed.add(id);
      if (document.kind === 'created' && report.revertOf === null) this.sessionCreated.add(id);
    }
    for (const other of this.entries) {
      if (other.id === source || other.state !== 'waiting' || other.classification === null) continue;
      if (other.pack !== written.pack) continue;
      const touched = [...other.classification.existing, ...other.classification.created];
      if (touched.some((id) => changed.has(id))) {
        other.problems.push({
          phase: 'apply',
          code: STALE,
          severity: 'error',
          message: `a document this proposal touches changed after its dry run (${source}); re-run the program against the new state`,
        });
        other.state = 'stale';
      }
    }
  }

  private isStale(report: TransactionReport): boolean {
    return !report.accepted && report.diagnostics.some((found) => found.code === STALE);
  }

  private markStale(entry: Entry, phase: ProblemPhase): void {
    entry.state = 'stale';
    entry.problems.push({
      phase,
      code: STALE,
      severity: 'error',
      message: 'a document changed after the program read it (MTR-0034): re-run the program against the new state; nothing was applied',
    });
    this.changed();
  }

  private failed(entry: Entry, phase: ProblemPhase, error: unknown): void {
    const code = typeof (error as { code?: unknown })?.code === 'string' ? (error as { code: string }).code : undefined;
    if (code === STALE) return this.markStale(entry, phase);
    entry.state = 'failed';
    entry.problems.push({
      phase,
      code,
      severity: 'error',
      message:
        error instanceof MalformedReport
          ? error.message
          : `the request failed: ${error instanceof Error ? error.message : String(error)}`,
    });
    this.changed();
  }

  private problemsOf(entry: Entry, diagnostics: readonly ReportDiagnostic[], phase: ProblemPhase): Problem[] {
    return diagnostics.map((found) => {
      const index = found.operation !== undefined && found.operation >= 0 ? found.operation : undefined;
      const preexisting = found.preexisting === true;
      return {
        phase,
        code: found.code,
        // a finding that predates the transaction is shown, and never blocks
        severity: preexisting ? 'warning' : found.severity,
        message: found.message,
        hint: found.hint,
        operation: index,
        source: index === undefined ? undefined : (entry.sources[index] ?? undefined),
        resource: found.resource,
        preexisting: preexisting ? true : undefined,
      };
    });
  }

  private planView(id: string): Plan {
    const plan = this.plans.get(id);
    if (plan === undefined) throw new Error(`no plan ${id}`);
    return deepFreeze({ id: plan.id, title: plan.title, objects: [...plan.objects], approved: plan.approved });
  }

  private logEntry(entry: Entry): ChangeLogEntry {
    return {
      proposal: entry.id,
      transactionId: entry.transactionId ?? '',
      appliedAt: entry.appliedAt ?? 0,
      summary: entry.summary,
      program: entry.program,
      approval: entry.approval,
      documents: entry.appliedDocuments,
      revertible: entry.revertible && entry.state === 'applied',
      revertNote: entry.revertNote,
      revertedBy: entry.revertedBy,
      revertProblems: entry.revertProblems,
    };
  }

  private view(entry: Entry): Proposal {
    const decision = entry.classification === null ? null : decide(entry.classification, this.grants, entry.proposalApproved);
    return deepFreeze({
      id: entry.id,
      state: entry.state,
      plan: entry.plan,
      pack: entry.pack,
      program: entry.program,
      summary: entry.summary,
      operations: entry.operations,
      sources: entry.sources,
      classification: entry.classification,
      documents: clone(entry.documents),
      problems: clone(entry.problems),
      needs: decision === null ? [] : decision.missing,
      needsProposalApproval: decision?.needsProposalApproval ?? false,
      transactionId: entry.transactionId,
      revertible: entry.revertible && entry.state === 'applied',
      revertNote: entry.revertNote,
      revertedBy: entry.revertedBy,
      appliedAt: entry.appliedAt,
    });
  }
}

/** A store for one editor session. */
export function createProposalStore(options: StoreOptions): ProposalStore {
  return new ProposalStore(options);
}
