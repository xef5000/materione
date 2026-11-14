// The sandbox without a browser: the REAL engine (QuickJS-WASM, the bundled core, the real host and runner) run in this process
// over a MessageChannel, standing in for the frame and its Worker. What it cannot prove is the browser's boundary (the frame's
// CSP, the opaque origin, the blob Worker, a wall-clock backstop that needs a thread the guest does not block): those are the
// Playwright specs' (`browser/specs/codemode-sandbox*.pw.ts`).
import { buildSandbox } from '../../vite.sandbox';
import { createOpHost, createSandboxRunner, type CheckedProgram, type RunnerOptions, type SandboxHost, type SandboxRunner } from '../../src/lib/codemode/sandbox';
import { startEngine } from '../../src/lib/codemode/sandbox/engine';
import type { SandboxAssets, Spawn } from '../../src/lib/codemode/sandbox/sandbox';
import { transpileWithMap } from './sdk-runner';
import { fakePort, type World } from './world';

export const inProcessSpawn: Spawn = async (assets, memoryMb) => {
  const channel = new MessageChannel();
  void startEngine(channel.port2 as unknown as MessagePort, { wasm: assets.wasm.slice(0), coreSource: assets.coreSrc, memoryMb });
  return {
    port: channel.port1 as unknown as MessagePort,
    destroy: () => {
      channel.port1.close();
      channel.port2.close();
    },
  };
};

/** The built sandbox files as the assets the host would have fetched, optionally with a different core (a test's lying core). */
export async function nodeAssets(coreSrc?: string): Promise<SandboxAssets> {
  const built = await buildSandbox();
  const bytes = built.files['sandbox/quickjs.wasm'] as Uint8Array;
  const wasm = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  return { frameUrl: 'about:blank', wasm, engineSrc: built.files['sandbox/engine.js'] as string, coreSrc: coreSrc ?? (built.files['sandbox/core.js'] as string) };
}

export async function nodeRunner(options: RunnerOptions & { coreSrc?: string } = {}): Promise<SandboxRunner> {
  const assets = await nodeAssets(options.coreSrc);
  return createSandboxRunner({ spawn: options.spawn ?? inProcessSpawn, loadAssets: options.loadAssets ?? (async () => assets) });
}

/** A program as the type-check Worker would hand it over: its JavaScript and the map to the program as written. */
export function checked(program: string): CheckedProgram {
  const { js, map } = transpileWithMap(program);
  return { js, map };
}

/** The fake editor of a recorded world, as the host a run is given: the op host over the world's read ops (every call recorded). */
export function hostOf(world: World): SandboxHost & { calls: { op: string }[] } {
  const port = fakePort(world);
  const inner = createOpHost(async (op, payload) => port.read(op, JSON.parse(JSON.stringify(payload)) as Record<string, unknown>));
  return { calls: port.calls, read: (op, payload) => inner.read(op, payload), resolve: (request) => inner.resolve(request) };
}
