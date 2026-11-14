/**
 * The review panel's state (issue 347): one `ProposalStore` per editor session, a reactive copy of its snapshot, and the audit
 * rows of recent transactions. Session memory only (D1): nothing here is persisted, there is no editor-only content state,
 * and a session's end (the workbench unmounting on disconnect) is the end of the store and its grants.
 *
 * Nothing here approves, applies or reverts. The panel's buttons call `ReviewActions` with the click that caused them
 * (host/actions.ts); this module only holds what they show and the store they act on.
 */
import { ops, unwrap, type RpcChannel } from '../../api';
import { createProposalStore, createRpcTransport, ReviewActions, CODEMODE_CAPABILITY, type AgentPort, type ProposalStore, type Snapshot } from '../host';

/** One recent transaction as the audit lists it (`admin.audit.recent`). Who, when, from where, and the program text kept. */
export interface AuditRow {
  /** Unique within a listing (the audit keeps no id of its own). */
  key: string;
  at: string | number | null;
  actor: string;
  op: string;
  target: string | undefined;
  origin: string;
  approval: string | null;
  transaction: string;
  program: string | null;
  programLength: number | null;
  digest: string | null;
}

const EMPTY: Snapshot = Object.freeze({ proposals: [], plans: [], grants: [], log: [] });

class ReviewState {
  /** The server advertises code mode and a session is open: the rail shows the entry. */
  ready = $state(false);
  open = $state(false);
  tab = $state<'proposals' | 'log'>('proposals');
  /** The store's snapshot (deeply frozen: held raw, never proxied). */
  snapshot = $state.raw<Snapshot>(EMPTY);
  audit = $state.raw<readonly AuditRow[]>([]);
  auditStatus = $state<'idle' | 'loading' | 'ready' | 'error'>('idle');
  auditError = $state('');
  /** A polite status line for assistive technology: what changed, in words. */
  announcement = $state('');
  /** Proposals waiting for the owner: the rail badge. */
  get waiting(): number {
    return this.snapshot.proposals.filter((proposal) => proposal.state === 'waiting' && (proposal.needs.length > 0 || proposal.needsProposalApproval)).length;
  }
}

export const review = new ReviewState();

interface Handle {
  channel: RpcChannel;
  store: ProposalStore;
  actions: ReviewActions;
  off: () => void;
}

let handle: Handle | null = null;
const seen = new Set<string>();

/** True when the connected server advertises code mode. A channel with no session blob (the demo) does not. */
export function reviewAvailable(channel: RpcChannel | null): channel is RpcChannel {
  return channel?.blob?.capabilities?.includes(CODEMODE_CAPABILITY) === true;
}

/** Starts (or keeps) the session's store for this channel. A different channel is a different session: the old one ends. */
export function connectReview(channel: RpcChannel | null): void {
  if (!reviewAvailable(channel)) {
    disposeReview();
    return;
  }
  if (handle?.channel === channel) return;
  disposeReview();
  const store = createProposalStore({
    transport: createRpcTransport(channel),
    readDocument: async (pack, path) => {
      const data = unwrap(await ops.get(channel, pack, path));
      return typeof data.raw === 'string' && typeof data.rev === 'number' ? { text: data.raw, revision: data.rev } : null;
    },
  });
  const off = store.subscribe(() => onChange(store));
  handle = { channel, store, actions: new ReviewActions(store), off };
  review.ready = true;
  review.snapshot = store.snapshot();
}

/** Ends the session: the store, its grants and its log are dropped. */
export function disposeReview(): void {
  if (handle !== null) {
    handle.off();
    handle.store.dispose();
    handle = null;
  }
  seen.clear();
  review.ready = false;
  review.open = false;
  review.snapshot = EMPTY;
  review.audit = [];
  review.auditStatus = 'idle';
  review.announcement = '';
}

/** The stored text of a document now (a read: the revert preview draws what a revert would restore against it). Null when unreadable. */
export async function readStored(pack: string, path: string): Promise<string | null> {
  const current = handle;
  if (current === null) return null;
  try {
    const data = unwrap(await ops.get(current.channel, pack, path));
    return typeof data.raw === 'string' ? data.raw : null;
  } catch {
    return null;
  }
}

/** The owner's actions for the panel's buttons; null when there is no session. */
export function reviewActions(): ReviewActions | null {
  return handle?.actions ?? null;
}

/**
 * The agent's face of the store: `propose`, `declarePlan` and reads, and no approval of any kind. The chat tool will get this
 * (issue 348); today only the browser harness reaches it, to feed a recorded program in.
 */
export function reviewAgentPort(): AgentPort | null {
  return handle?.store.agent ?? null;
}

function onChange(store: ProposalStore): void {
  const snapshot = store.snapshot();
  review.snapshot = snapshot;
  // A proposal that needs the owner brings the panel into view and says so. It moves no focus and approves nothing.
  for (const proposal of snapshot.proposals) {
    const needsOwner = proposal.state === 'waiting' && (proposal.needs.length > 0 || proposal.needsProposalApproval);
    if (needsOwner && !seen.has(proposal.id)) {
      seen.add(proposal.id);
      review.open = true;
      review.tab = 'proposals';
      review.announcement = `A proposal needs your approval: ${proposal.id}.`;
    }
  }
}

/** Reads the recent transactions of the audit. Fail soft: a failure is a message, never a crash. */
export async function loadAudit(): Promise<void> {
  const current = handle;
  if (current === null) return;
  review.auditStatus = 'loading';
  try {
    const data = unwrap(await ops.audit(current.channel, 100));
    const entries = Array.isArray(data.entries) ? (data.entries as Record<string, unknown>[]) : [];
    const rows: AuditRow[] = [];
    entries.forEach((entry, index) => {
      const op = typeof entry.op === 'string' ? entry.op : '';
      const transaction = typeof entry.transaction === 'string' ? entry.transaction : null;
      if ((op !== 'authoring.transaction' && op !== 'authoring.revertTransaction') || transaction === null) return;
      rows.push({
        key: `${index}:${transaction}`,
        at: typeof entry.ts === 'string' || typeof entry.ts === 'number' ? entry.ts : null,
        actor: typeof entry.actor === 'string' ? entry.actor : 'unknown',
        op,
        target: typeof entry.target === 'string' ? entry.target : undefined,
        origin: typeof entry.origin === 'string' ? entry.origin : 'editor',
        approval: typeof entry.approval === 'string' ? entry.approval : null,
        transaction,
        program: typeof entry.program === 'string' ? entry.program : null,
        programLength: typeof entry.programLength === 'number' ? entry.programLength : null,
        digest: typeof entry.digest === 'string' ? entry.digest : null,
      });
    });
    if (handle !== current) return;
    review.audit = rows;
    review.auditStatus = 'ready';
    review.auditError = '';
  } catch (error) {
    if (handle !== current) return;
    review.auditStatus = 'error';
    review.auditError = error instanceof Error ? error.message : String(error);
  }
}
