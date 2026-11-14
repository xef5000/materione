/**
 * The type-check Worker's public face (codemode.md section 5.1, 11): the client and the result vocabulary. The compiler
 * itself is behind `new Worker(...)` in `client.ts` and is not reachable from here.
 */
export { check, createTypecheckClient, type ClientOptions, type ClientStats, type TypecheckClient, type WorkerLike } from './client';
export {
  CHECK_CODES,
  DEFAULT_CHECK_LIMITS,
  type CheckCode,
  type CheckDiagnostic,
  type CheckLimits,
  type CheckResult,
  type MissingPrimitive,
  type RawSourceMap,
} from './types';
