/**
 * The owner's actions (codemode.md section 5.6, D13; issue 347): the one place a DOM event becomes an `ApprovalGesture`.
 * The review UI calls these from its buttons' click handlers and from nowhere else: nothing here runs on load, on a
 * timer or from a store event, and every action that approves, applies or reverts takes the event that caused it.
 *
 * An event the browser did not dispatch itself (a script's `dispatchEvent` or `click()`: `isTrusted` is false) makes no
 * gesture, and the action then does nothing and says so. That is the editor page's half of "the model cannot press the
 * button": the sandbox has no DOM, the chat tool's port has no approve, and a script in the page cannot forge the event.
 *
 * Rejecting needs no gesture in the store (declining is always safe) but is still taken from a click here, so the UI has
 * one way to act on a proposal.
 */
import { gestureFromEvent } from './grants';
import type { ProposalStore, Proposal, RevertOutcome, RevertPreview } from './store';

/** The result of an action: `ignored` means the event was not a user action and nothing was done. */
export type ActionResult<T = undefined> = { done: true; value: T } | { done: false; reason: 'not-a-user-action' | 'unknown' | 'failed'; message: string };

const IGNORED = { done: false, reason: 'not-a-user-action', message: 'ignored: that was not a click or key press from you' } as const;

export class ReviewActions {
  constructor(private readonly store: ProposalStore) {}

  /** Approves one proposal's existing objects (for the rest of the session, C10) and applies it. */
  async approveProposal(event: Event, proposalId: string): Promise<ActionResult<Proposal | undefined>> {
    const gesture = gestureFromEvent(event);
    if (gesture === null) return IGNORED;
    try {
      const result = await this.store.approve({ proposal: proposalId }, gesture);
      return { done: true, value: result.proposal };
    } catch (error) {
      return failed(error);
    }
  }

  /**
   * Approves, once, every existing object a plan declared (C11), then applies each waiting proposal of that plan that this
   * covers, in the order proposed. Later programs of the plan that stay inside the set apply on their own as they arrive.
   */
  async approvePlan(event: Event, planId: string): Promise<ActionResult<{ granted: readonly string[]; applied: readonly string[] }>> {
    const gesture = gestureFromEvent(event);
    if (gesture === null) return IGNORED;
    try {
      const result = await this.store.approve({ plan: planId }, gesture);
      const applied: string[] = [];
      for (const proposal of this.store.list()) {
        if (proposal.plan !== planId || proposal.state !== 'waiting') continue;
        const after = await this.store.apply(proposal.id);
        if (after?.state === 'applied') applied.push(after.id);
      }
      return { done: true, value: { granted: result.granted, applied } };
    } catch (error) {
      return failed(error);
    }
  }

  /** Applies a proposal that waits with nothing left to approve (the store re-checks the policy before it sends). */
  async applyReady(event: Event, proposalId: string): Promise<ActionResult<Proposal | undefined>> {
    if (gestureFromEvent(event) === null) return IGNORED;
    try {
      return { done: true, value: await this.store.apply(proposalId) };
    } catch (error) {
      return failed(error);
    }
  }

  /** Declines a proposal that has not been applied. Nothing is sent. */
  reject(event: Event, proposalId: string): ActionResult<Proposal | undefined> {
    if (gestureFromEvent(event) === null) return IGNORED;
    return { done: true, value: this.store.reject(proposalId) };
  }

  /**
   * Shows what a revert would do: the server's dry run, nothing written. A read, so it needs no gesture and may be called
   * from a click handler or after one; the revert itself needs the owner's confirmation (`confirmRevert`).
   */
  previewRevert(transactionId: string): Promise<RevertPreview> {
    return this.store.previewRevert(transactionId);
  }

  /** Reverts a transaction after the owner saw its preview and confirmed. */
  async confirmRevert(event: Event, transactionId: string, pack: string | null): Promise<ActionResult<RevertOutcome>> {
    const gesture = gestureFromEvent(event);
    if (gesture === null) return IGNORED;
    try {
      return { done: true, value: await this.store.revertTransaction(transactionId, gesture, pack) };
    } catch (error) {
      return failed(error);
    }
  }
}

function failed(error: unknown): ActionResult<never> {
  return { done: false, reason: 'failed', message: error instanceof Error ? error.message : String(error) };
}
