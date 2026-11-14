/**
 * The SDK core's entry (codemode.md section 3.1): `createSession` builds the program's `m` from a surface
 * document and an injected port. This module is the bundle's root: the sandbox (issue 343) loads the bundle, calls
 * `createSession`, runs the program with `session.m`, and reads `session.finish(...)`.
 */
export { createSession, positionIn, SDK_VERSION } from './session';
export { SDK_CODES, SdkError, isSdkError, type SdkCode } from './diagnostics';
export { READS, WRITES, readEntry, writeEntry, type OpEntry } from './ops';
export {
  DEFAULT_LIMITS,
  type DiagnosticSeverity,
  type HostPort,
  type JsonValue,
  type Limits,
  type Outcome,
  type ProgramResult,
  type ReadReply,
  type ResolveRef,
  type ResolveReply,
  type ResolveRequest,
  type SdkDiagnostic,
  type Session,
  type SessionConfig,
  type SourcePosition,
  type StagedOperation,
} from './types';
