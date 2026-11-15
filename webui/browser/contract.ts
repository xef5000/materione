export interface RecordedWrite {
  op: string;
  payload: Record<string, unknown>;
  outcome: 'pending' | 'saved' | 'conflict' | 'rejected' | 'response-lost';
}

export interface RecordedRpcCall {
  op: string;
  payload: Record<string, unknown>;
}

export interface FixtureAuditEntry {
  ts: string;
  actor: string;
  op: string;
  target?: string;
  /** The code-mode context the server's audit adds (codemode.md section 10). */
  origin?: string;
  approval?: string;
  transaction?: string;
  program?: string;
  programLength?: number;
  digest?: string;
}

export interface FixtureDocumentSnapshot {
  pack: string;
  path: string;
  rev: number;
  raw: string;
  parsed: Record<string, Record<string, unknown>>;
}

export interface BrowserTestControls {
  calls(): readonly RecordedRpcCall[];
  writes(): readonly RecordedWrite[];
  clearWrites(): void;
  delayNextWrite(milliseconds: number): void;
  conflictNextWrite(): void;
  externalEdit(pack: string, path: string): void;
  disconnectAfterNextWrite(): void;
  simulateDisconnect(): void;
  setCandidateSha256(value: string): void;
  deliveryState(): { candidateSha256: string; publishedSha256: string };
  emitPush(op: string, payload: Record<string, unknown>): void;
  emitReload(payload: Record<string, unknown>): void;
  /** The GUIs whose live trace is open on the fixture (logic.trace.subscribe, until logic.trace.unsubscribe). */
  tracedResources(): readonly string[];
  /** Refuse the next live trace subscription, as a server at its cap does. */
  refuseNextTrace(): void;
  setAuditEntries(entries: readonly FixtureAuditEntry[]): void;
  /** The `?codemode` fixture only: stage findings on, or make unrevertible, the next transaction. */
  codemode?: import('./codemode-fixture').CodemodeFixture;
  /** Every document as the fixture holds it (saved YAML assertions). */
  documents(): readonly FixtureDocumentSnapshot[];
}

/**
 * The `?codemode` harness only (never in the editor build): feeds a recorded corpus program in as a proposal, through the
 * agent port of the review store, which has propose and declarePlan and no approval of any kind.
 */
export interface CodemodeHarness {
  /** Proposes the recorded sealed result of a corpus program (`fixtures/golden/codemode/ops/<name>.json`), retargeted to the fixture pack. `slice` keeps only those operations. Resolves with the proposal id once it settled. */
  propose(name: string, options?: { slice?: [number, number]; plan?: string; autoApply?: boolean }): Promise<string>;
  /** Declares a plan (existing objects as `<pack>:<path>`); returns its id. */
  declarePlan(objects: string[], title?: string): string;
  /** The names of the corpus programs the harness can feed. */
  programs(): string[];
  /** Installs the scripted Bloodfang provider into the chat session and returns its recorded prompts. */
  connectBloodfangChat(paceMs?: number): readonly string[];
  /** Disconnects the active chat provider. */
  disconnectChatProvider(): void;
}

declare global {
  interface Window {
    __materioneTestControls?: BrowserTestControls;
    __materioneCodemode?: CodemodeHarness;
  }
}
