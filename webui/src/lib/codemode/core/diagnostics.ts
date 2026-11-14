/**
 * The SDK diagnostic codes (codemode.md section 6, phase 2). Stable API like `MTR-####`: documented in
 * plugin.md section 5 (a test compares this table with that one), never reused. A diagnostic a program causes
 * is structured data; the core throws it as an `SdkError` so the run ends, and records it before throwing so a
 * program that swallows the error cannot hide it.
 */
import type { DiagnosticSeverity, SdkDiagnostic } from './types';

export interface CodeInfo {
  severity: DiagnosticSeverity;
  title: string;
}

export const SDK_CODES = {
  'SDK-0001': { severity: 'error', title: 'The program threw' },
  'SDK-0002': { severity: 'error', title: 'The program called m.fail' },
  'SDK-0003': { severity: 'error', title: 'The run has ended' },
  'SDK-0004': { severity: 'error', title: 'More than one pack' },
  'SDK-0005': { severity: 'error', title: 'A limit of the run was exceeded' },
  'SDK-0006': { severity: 'error', title: 'The connected server lacks what this member needs' },
  'SDK-0007': { severity: 'error', title: 'No such resource' },
  'SDK-0008': { severity: 'error', title: 'No such pack, or a built-in pack' },
  'SDK-0009': { severity: 'error', title: 'A value cannot be written to a document' },
  'SDK-0010': { severity: 'error', title: 'A read was refused' },
  'SDK-0011': { severity: 'error', title: 'No such asset' },
  'SDK-0012': { severity: 'error', title: 'An argument is not valid' },
  'SDK-0013': { severity: 'error', title: 'The resource is already staged' },
  'SDK-0014': { severity: 'error', title: 'The return value cannot be returned' },
  'SDK-0015': { severity: 'warning', title: 'Malformed formatted text' },
  'SDK-0016': { severity: 'warning', title: 'Not available on the connected server' },
  'SDK-0017': { severity: 'warning', title: 'The server warned while expanding a recipe' },
} as const satisfies Record<string, CodeInfo>;

export type SdkCode = keyof typeof SDK_CODES;

/** What the core throws: the run ends with this diagnostic. */
export class SdkError extends Error {
  readonly diagnostic: SdkDiagnostic;

  constructor(diagnostic: SdkDiagnostic) {
    super(`${diagnostic.code}: ${diagnostic.message}`);
    this.name = 'SdkError';
    this.diagnostic = diagnostic;
  }
}

export function isSdkError(value: unknown): value is SdkError {
  return value instanceof SdkError;
}
