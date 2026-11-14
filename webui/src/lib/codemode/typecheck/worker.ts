/**
 * The type-check Worker (codemode.md section 5.1): a module Worker of the editor's own origin that holds the TypeScript
 * compiler. It receives program text and the generated declarations, answers with diagnostics and, for a clean program,
 * the transpiled JavaScript. It parses untrusted text and never runs it, fetches nothing and keeps no state beyond its
 * parsed declaration files.
 *
 * Started on the first agent use by `client.ts`; the editor's own bundle never contains this file.
 */
import coreDeclarations from '../generate/core.d.ts?raw';
import { createChecker } from './checker';
import { LIB_FILES } from './libs';
import type { FromWorker, ToWorker } from './types';

const checker = createChecker({ libs: LIB_FILES, core: coreDeclarations });
const post = (message: FromWorker): void => (self as unknown as { postMessage(message: FromWorker): void }).postMessage(message);

self.onmessage = (event: MessageEvent<ToWorker>): void => {
  const request = event.data;
  if (request === null || typeof request !== 'object' || request.type !== 'check' || typeof request.program !== 'string' || typeof request.sdk !== 'string') return;
  const started = performance.now();
  try {
    post({ type: 'result', id: request.id, result: checker.check({ program: request.program, sdk: request.sdk }), checkMs: performance.now() - started });
  } catch (error) {
    post({ type: 'failed', id: request.id, message: error instanceof Error ? error.message : String(error) });
  }
};

post({ type: 'ready' });
