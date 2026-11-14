/**
 * The code-mode sandbox's public face (codemode.md section 4.6): `run(checkedProgram, surface, host, limits?)`, the host
 * interface it is answered through, and the result vocabulary. The engine, the frame and the channel are behind it and are not
 * reachable from here; nothing here loads until the first run.
 */
export { run, createSandboxRunner, sandboxDiagnostic, type CheckedProgram, type RunMetrics, type RunnerOptions, type RunResult, type SandboxRunner } from './runner';
export { createOpHost, READ_ALLOWLIST, RESOLVE_OPS, type OpCall, type SandboxHost } from './ophost';
export { DEFAULT_SANDBOX_LIMITS, SANDBOX_CODES, VIOLATION_CODE, type SandboxCode, type SandboxLimits, type Violation } from './protocol';
export { loadSandboxAssets, sandboxBase, SandboxAssetError } from './assets';
export { iframeSpawn, type SandboxAssets, type Spawn, type Transport } from './sandbox';
