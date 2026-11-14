/**
 * One sandbox (codemode.md section 4.3, 4.6): the sandboxed frame, the engine in it, and the host's side of their channel.
 *
 * The host owns every number that matters. It answers the guest's calls (`read`, `resolve`) through the injected `SandboxHost`
 * after checking each against the allowlist and the budgets; it counts reads and bytes itself; it arms the wall-clock backstop
 * and the per-call deadlines, and when any of them (or a message it refuses) ends a run it DESTROYS the frame, so a runaway or
 * a compromised engine is gone and the next run boots a fresh one. Nothing the frame sends is read before `parseEngineMessage`
 * has validated its shape and size.
 */
import { READ_ALLOWLIST, type SandboxHost } from './ophost';
import { parseEngineMessage, resultBytesOf, type EngineResult, type HostToEngine, type SandboxLimits, type Violation } from './protocol';
import type { ReadReply, ResolveReply, ResolveRequest } from '../core/types';

/** The static files of the sandbox (see `vite.sandbox.ts`): the frame document loads itself; the rest the host fetches and hands in. */
export interface SandboxAssets {
  frameUrl: string;
  wasm: ArrayBuffer;
  engineSrc: string;
  coreSrc: string;
}

/** The host's end of a sandbox: its private port, and the way to destroy it. */
export interface Transport {
  port: MessagePort;
  destroy(): void;
}

/** Starts a sandbox and sends it its one `init`. The default is the sandboxed iframe (`iframeSpawn`); a test may start the engine in-process. */
export type Spawn = (assets: SandboxAssets, memoryMb: number) => Promise<Transport>;

/**
 * The production spawn: an iframe with `sandbox="allow-scripts"` and NEVER `allow-same-origin` (an opaque origin), whose document
 * carries its own CSP. `postMessage` to it needs target `'*'`: an opaque origin cannot be named; the frame checks `event.source`
 * instead and from then on only the private port is used.
 */
export const iframeSpawn: Spawn = async (assets, memoryMb) => {
  const frame = document.createElement('iframe');
  frame.setAttribute('sandbox', 'allow-scripts');
  frame.setAttribute('aria-hidden', 'true');
  frame.tabIndex = -1;
  frame.style.display = 'none';
  frame.src = assets.frameUrl;
  const loaded = new Promise<void>((resolve, reject) => {
    frame.addEventListener('load', () => resolve(), { once: true });
    frame.addEventListener('error', () => reject(new Error('the sandbox document did not load')), { once: true });
  });
  document.body.appendChild(frame);
  try {
    await loaded;
    const channel = new MessageChannel();
    const copy = assets.wasm.slice(0); // the frame gets its own copy; the host keeps its cached bytes
    const target = frame.contentWindow;
    if (target === null) throw new Error('the sandbox frame has no window');
    target.postMessage({ t: 'init', wasm: copy, engineSrc: assets.engineSrc, coreSrc: assets.coreSrc, memoryMb }, '*', [channel.port2, copy]);
    return {
      port: channel.port1,
      destroy: () => {
        channel.port1.close();
        frame.remove();
      },
    };
  } catch (error) {
    frame.remove();
    throw error;
  }
};

/** How one run ended, before the host has looked at the program's result. */
export interface RunEnd {
  violation?: Violation;
  message?: string;
  /** The engine's JSON of `{ result, late }`, when the program finished. Still untrusted. */
  resultJson: string | null;
  cpuMs: number;
  wallMs: number;
  setupMs: number;
  /** What the HOST counted (the engine's counts are never believed). */
  hostReads: number;
  hostReadBytes: number;
  /** The frame was destroyed: the next run boots a fresh one. */
  destroyed: boolean;
}

export interface RunRequest {
  /** The wrapped, transpiled program. */
  js: string;
  surfaceJson: string;
  coreLimitsJson: string;
  host: SandboxHost;
  limits: SandboxLimits;
  fixedTime: number;
  seed: number;
}

type Dict = Record<string, unknown>;
const isDict = (value: unknown): value is Dict => typeof value === 'object' && value !== null && !Array.isArray(value);
const reply = (code: string | undefined, message: string): ReadReply => ({ ok: false, error: code === undefined ? { message } : { code, message } });

export class Sandbox {
  private runId = 0;
  private active: ((message: unknown) => void) | undefined;
  private dead = false;
  /** Milliseconds from spawn to `ready`. */
  bootMs = 0;

  private constructor(
    private readonly transport: Transport,
    private readonly limits: SandboxLimits,
  ) {}

  get destroyed(): boolean {
    return this.dead;
  }

  /** Starts a sandbox and waits for its engine to be ready, within `limits.bootMs`. */
  static async boot(spawn: Spawn, assets: SandboxAssets, limits: SandboxLimits): Promise<Sandbox> {
    const started = performance.now();
    const transport = await spawn(assets, limits.memoryMb);
    const sandbox = new Sandbox(transport, limits);
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        sandbox.destroy();
        reject(new Error(`the sandbox did not start within ${limits.bootMs} ms`));
      }, limits.bootMs);
      transport.port.onmessage = (event: MessageEvent) => {
        const message = parseEngineMessage(event.data, limits.payloadBytes, resultBytesOf(limits));
        if (message?.t === 'ready') {
          clearTimeout(timer);
          resolve();
        } else if (message?.t === 'boot-error') {
          clearTimeout(timer);
          sandbox.destroy();
          reject(new Error(message.message));
        } else if (message === undefined) {
          clearTimeout(timer);
          sandbox.destroy();
          reject(new Error('the sandbox sent a message the host refuses'));
        }
      };
    });
    sandbox.bootMs = performance.now() - started;
    transport.port.onmessage = (event: MessageEvent) => sandbox.active?.(event.data);
    return sandbox;
  }

  destroy(): void {
    if (this.dead) return;
    this.dead = true;
    this.active = undefined;
    this.transport.destroy();
  }

  /** Runs one program. Never rejects: every way a run can end is a `RunEnd`. One run at a time (the caller queues). */
  run(request: RunRequest): Promise<RunEnd> {
    const { limits, host } = request;
    const runId = ++this.runId;
    const port = this.transport.port;
    const maxResult = resultBytesOf(limits);
    const started = performance.now();
    return new Promise<RunEnd>((resolve) => {
      let settled = false;
      let hostReads = 0;
      let hostReadBytes = 0;
      let resolves = 0;
      let inFlight = 0;
      let wallUsed = 0;
      let wallSince: number | undefined;
      let wallTimer: ReturnType<typeof setTimeout> | undefined;
      const seen = new Set<number>();
      const timers = new Set<ReturnType<typeof setTimeout>>();

      const clearAll = (): void => {
        if (wallTimer !== undefined) clearTimeout(wallTimer);
        for (const timer of timers) clearTimeout(timer);
        timers.clear();
      };
      const end = (partial: Omit<RunEnd, 'hostReads' | 'hostReadBytes' | 'destroyed'>, destroy: boolean): void => {
        if (settled) return;
        settled = true;
        clearAll();
        this.active = undefined;
        if (destroy) this.destroy();
        resolve({ ...partial, hostReads, hostReadBytes, destroyed: destroy });
      };
      const violate = (violation: Violation, message: string): void =>
        end({ violation, message, resultJson: null, cpuMs: 0, wallMs: performance.now() - started, setupMs: 0 }, true);

      // The wall-clock backstop counts the time the frame holds the run, not the time the host takes to answer.
      const armWall = (): void => {
        if (settled || inFlight > 0) return;
        wallSince = performance.now();
        wallTimer = setTimeout(() => violate('wall-timeout', `the sandbox took more than ${limits.wallMs} ms and was destroyed`), Math.max(1, limits.wallMs - wallUsed));
      };
      const pauseWall = (): void => {
        if (wallTimer !== undefined) clearTimeout(wallTimer);
        wallTimer = undefined;
        if (wallSince !== undefined) wallUsed += performance.now() - wallSince;
        wallSince = undefined;
      };
      const total = setTimeout(() => violate('wall-timeout', `the run took more than ${limits.totalMs} ms and the sandbox was destroyed`), limits.totalMs);
      timers.add(total);

      const answer = (rid: number, ok: boolean, json: string): void => {
        if (settled || this.dead) return;
        const message: HostToEngine = { t: 'answer', runId, rid, ok, json };
        port.postMessage(message);
      };

      /** A call that has a host answer coming: its deadline, then the answer through `finishCall`. */
      const startCall = (rid: number, deadlineMs: number, what: string, work: () => Promise<{ ok: boolean; json: string } | undefined>): void => {
        inFlight += 1;
        pauseWall();
        const deadline = setTimeout(() => violate('read-deadline', `${what} was not answered within ${deadlineMs} ms`), deadlineMs);
        timers.add(deadline);
        void work().then((outcome) => {
          clearTimeout(deadline);
          timers.delete(deadline);
          inFlight -= 1;
          if (settled) return;
          if (outcome !== undefined) answer(rid, outcome.ok, outcome.json);
          armWall();
        });
      };

      this.active = (raw: unknown) => {
        if (settled) return;
        const message = parseEngineMessage(raw, limits.payloadBytes, maxResult);
        if (message === undefined) return violate('protocol', 'the sandbox sent a message the host refuses');
        if (message.t === 'ready' || message.t === 'boot-error') return violate('protocol', 'the sandbox sent a boot message during a run');
        if (message.runId !== runId) return; // a straggler of an earlier run
        if (message.t === 'result') return this.finish(message, end);
        // a call
        if (seen.has(message.rid)) return violate('protocol', `call ${message.rid} was made twice`);
        seen.add(message.rid);
        let payload: unknown;
        try {
          payload = JSON.parse(message.payload);
        } catch {
          return violate('protocol', 'a call carried a payload that is not JSON');
        }
        if (message.kind === 'read') {
          // The allowlist is the host's: an op outside the operation table's reads is never made, whatever the guest asks.
          if (!READ_ALLOWLIST.has(message.op)) return violate('protocol', `the sandbox asked for ${message.op}, which is not an SDK read`);
          if (!isDict(payload)) return violate('protocol', 'a read carried a payload that is not an object');
          hostReads += 1;
          if (hostReads > limits.reads) return violate('read-limit', `the program made more than ${limits.reads} reads`);
          const op = message.op;
          startCall(message.rid, limits.readMs, `read ${op}`, async () => {
            let answered: ReadReply;
            try {
              answered = shapeRead(await host.read(op, payload as Record<string, unknown>));
            } catch (error) {
              answered = reply('SBX-0010', `the host could not answer ${op}: ${String((error as Error)?.message ?? error)}`);
            }
            const json = JSON.stringify(answered);
            hostReadBytes += json.length;
            if (hostReadBytes > limits.readBytes) {
              violate('read-limit', `the reads returned more than ${limits.readBytes} bytes`);
              return undefined;
            }
            return { ok: true, json };
          });
          return;
        }
        // resolve: once, at the seal, and well-formed
        if (message.op !== 'resolve') return violate('protocol', 'a resolve call named an op');
        resolves += 1;
        const requestShape = shapeResolveRequest(payload);
        if (resolves > 1 || requestShape === undefined) return violate('protocol', resolves > 1 ? 'the seal resolved twice' : 'the seal sent a malformed request');
        startCall(message.rid, limits.readMs * 4, 'the seal', async () => {
          try {
            const answered = shapeResolveReply(await host.resolve(requestShape), requestShape);
            return answered === undefined ? { ok: false, json: 'the host answered the seal with a malformed reply' } : { ok: true, json: JSON.stringify(answered) };
          } catch (error) {
            return { ok: false, json: `the host could not resolve: ${String((error as Error)?.message ?? error)}` };
          }
        });
      };

      armWall();
      const message: HostToEngine = {
        t: 'run',
        runId,
        js: request.js,
        surfaceJson: request.surfaceJson,
        coreLimitsJson: request.coreLimitsJson,
        limits: {
          cpuMs: limits.cpuMs,
          stackBytes: limits.stackBytes,
          reads: limits.reads,
          readBytes: limits.readBytes,
          resultBytes: maxResult,
          // QuickJS's own heap limit sits below the WebAssembly.Memory maximum, so a program ends with "out of memory" in QuickJS
          // before the memory itself refuses to grow; the Memory maximum is the cap that actually holds
          memoryBytes: Math.floor(limits.memoryMb * 1024 * 1024 * 0.75),
        },
        fixedTime: request.fixedTime,
        seed: request.seed,
      };
      port.postMessage(message);
    });
  }

  private finish(message: EngineResult, end: (partial: Omit<RunEnd, 'hostReads' | 'hostReadBytes' | 'destroyed'>, destroy: boolean) => void): void {
    const partial: Omit<RunEnd, 'hostReads' | 'hostReadBytes' | 'destroyed'> = {
      resultJson: message.resultJson,
      cpuMs: message.cpuMs,
      wallMs: message.wallMs,
      setupMs: message.setupMs,
    };
    if (message.violation !== undefined) partial.violation = message.violation;
    if (message.message !== undefined) partial.message = message.message;
    // An engine that aborted, ran out of anything or lost its stack is discarded: a fresh one boots for the next run.
    end(partial, message.violation !== undefined || message.poisoned);
  }
}

/** A read reply from the injected host, or a refusal: anything else is the host's bug, answered as a refused read. */
function shapeRead(value: unknown): ReadReply {
  if (!isDict(value) || typeof value.ok !== 'boolean') return reply('SBX-0010', 'the host answered a read with something that is not a reply');
  if (value.ok) return { ok: true, data: value.data };
  const error = isDict(value.error) ? value.error : {};
  return reply(typeof error.code === 'string' ? error.code : undefined, typeof error.message === 'string' ? error.message : 'the read was refused');
}

const MAX_RESOLVE_ENTRIES = 10_000;

/** The seal's request as the core builds it, checked for shape: four arrays of bounded length. */
function shapeResolveRequest(value: unknown): ResolveRequest | undefined {
  if (!isDict(value)) return undefined;
  const { packs, refs, instantiate, assets } = value;
  for (const list of [packs, refs, instantiate, assets]) if (!Array.isArray(list) || list.length > MAX_RESOLVE_ENTRIES) return undefined;
  if (!(packs as unknown[]).every((entry) => typeof entry === 'string')) return undefined;
  if (!(refs as unknown[]).every((entry) => isDict(entry) && typeof entry.kind === 'string' && typeof entry.directory === 'string' && typeof entry.pack === 'string' && typeof entry.id === 'string' && typeof entry.needsDocument === 'boolean')) return undefined;
  if (!(instantiate as unknown[]).every((entry) => isDict(entry) && typeof entry.recipe === 'string')) return undefined;
  if (!(assets as unknown[]).every((entry) => isDict(entry) && typeof entry.pack === 'string' && typeof entry.path === 'string')) return undefined;
  return value as unknown as ResolveRequest;
}

/** The host's reply to the seal: each list parallel to the request's, or undefined. */
function shapeResolveReply(value: ResolveReply, request: ResolveRequest): ResolveReply | undefined {
  if (!isDict(value)) return undefined;
  const parallel = (list: unknown, length: number): boolean => Array.isArray(list) && list.length === length;
  if (!parallel(value.packs, request.packs.length) || !parallel(value.refs, request.refs.length) || !parallel(value.instantiate, request.instantiate.length) || !parallel(value.assets, request.assets.length)) return undefined;
  return value;
}
