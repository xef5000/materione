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
  /** Every document as the fixture holds it (saved YAML assertions). */
  documents(): readonly FixtureDocumentSnapshot[];
}

declare global {
  interface Window {
    __materioneTestControls?: BrowserTestControls;
  }
}
