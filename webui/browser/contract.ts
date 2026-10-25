export interface RecordedWrite {
  op: string;
  payload: Record<string, unknown>;
  outcome: 'pending' | 'saved' | 'conflict' | 'rejected' | 'response-lost';
}

export interface BrowserTestControls {
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
}

declare global {
  interface Window {
    __materioneTestControls?: BrowserTestControls;
  }
}
