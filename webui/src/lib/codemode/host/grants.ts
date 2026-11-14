/**
 * The grants (codemode.md section 5.6, C10, C11): the set of objects the owner approved in this session, and the one
 * function that decides whether a classified proposal may be sent.
 *
 * Properties, each held by a test:
 *
 *  - **Monotonic.** There is no revoke, and no operation but `grant` changes the set. A session's end is the end of
 *    the `GrantSet` (a new session makes a new one); nothing persists it.
 *  - **Only a user gesture adds to it.** `grant` takes an `ApprovalGesture`, which only `gestureFromEvent` makes, from
 *    an event the browser itself dispatched (`isTrusted`: a script's `dispatchEvent` or `click()` is not trusted), and
 *    which is **single use**. The sandbox has no DOM, the chat tool has no parameter of that type, and the
 *    agent-facing port (`store.ts`) does not expose `grant` or anything that reaches it.
 *  - **Exact.** Only canonical object ids are granted and an id covers only itself (objects.ts).
 *  - **Fail closed.** `decide` allows a proposal only when every existing object it touches is granted *and* it has no
 *    blocker (an unknown operation kind, a non-canonical name) unless that proposal itself was approved.
 */
import type { Classification } from './classify';
import { isObjectId, type ObjectId } from './objects';

declare const gestureBrand: unique symbol;

/** The proof that an approval came from a click or a key press in the editor. Made only by `gestureFromEvent`. */
export interface ApprovalGesture {
  readonly [gestureBrand]: true;
}

const live = new WeakSet<object>();

export class GestureError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GestureError';
  }
}

/** A gesture for a trusted DOM event (the click on an approve button), or null for anything a script made. */
export function gestureFromEvent(event: { readonly isTrusted?: unknown } | null | undefined): ApprovalGesture | null {
  if (event === null || event === undefined || event.isTrusted !== true) return null;
  const gesture = Object.freeze({}) as ApprovalGesture;
  live.add(gesture);
  return gesture;
}

/** Spends a gesture: true once, false for a forged object, a spent gesture or anything else. */
export function spendGesture(value: unknown): boolean {
  if (typeof value !== 'object' || value === null || !live.has(value)) return false;
  live.delete(value);
  return true;
}

export interface GrantView {
  has(id: string): boolean;
  /** The granted ids, sorted. */
  ids(): ObjectId[];
}

export class GrantSet implements GrantView {
  private readonly granted = new Set<ObjectId>();

  has(id: string): boolean {
    return this.granted.has(id as ObjectId);
  }

  ids(): ObjectId[] {
    return [...this.granted].sort();
  }

  /**
   * Adds canonical ids and returns the ones it added. Anything that is not a canonical id is ignored (it could not be
   * matched anyway). The gesture is spent first: a call without a live one throws and changes nothing.
   */
  grant(ids: Iterable<string>, gesture: ApprovalGesture): ObjectId[] {
    if (!spendGesture(gesture)) throw new GestureError('a grant needs a live user gesture');
    const added: ObjectId[] = [];
    for (const id of ids) {
      if (!isObjectId(id) || this.granted.has(id)) continue;
      this.granted.add(id);
      added.push(id);
    }
    return added;
  }
}

export interface Decision {
  /** True when the proposal may be sent. */
  allowed: boolean;
  /** The existing objects it touches that no grant covers: what to ask for, and nothing else. */
  missing: ObjectId[];
  /** True when something in it cannot be a grant and only the approval of this proposal would let it through. */
  needsProposalApproval: boolean;
}

/**
 * The policy of codemode.md section 5.6, over a **clean** dry run's classification: creating, and changing what this
 * session created, applies; an existing object needs its grant; what is not understood needs the proposal's own approval.
 */
export function decide(classification: Classification, grants: GrantView, proposalApproved: boolean): Decision {
  const missing = classification.existing.filter((id) => !grants.has(id));
  const needsProposalApproval = classification.blockers.length > 0 && !proposalApproved;
  return { allowed: missing.length === 0 && !needsProposalApproval, missing, needsProposalApproval };
}
