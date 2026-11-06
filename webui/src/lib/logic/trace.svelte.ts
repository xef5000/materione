/**
 * The Logic tab's live trace (logic.md section 7.6, 32): an opt-in toggle, per GUI and per session, that asks the
 * server for the stream of what the GUI's rules do and folds it into the picture the canvas draws. Off by default.
 * While it is on the session holds a lease on the server, renewed here; turning it off, leaving the tab or losing the
 * connection lets it go. The picture fades by itself once the clicking stops.
 *
 * The server decides what a trace holds and how much of it we hear (rate, per-run cap, drop counts); this class only
 * subscribes, listens for `push.logic.trace` frames of its own GUI, and keeps the run on screen.
 */
import type { RpcChannel } from '../api';
import type { PushFrame, Signal } from '../events';
import type { Graph } from './project';
import {
  applyEntries, emptyTrace, hasRun, litEdges, parseTracePush, type NodeTrace, type TraceView,
} from './trace';
import { logicApiAvailable, logicTraceSubscribe, logicTraceUnsubscribe } from './server';

/** How long a run stays on screen after its last entry. */
export const TRACE_FADE_MS = 8000;
/** The lease is renewed this often, relative to the server's `leaseSeconds`. */
const RENEW_FRACTION = 0.4;
const MIN_RENEW_MS = 15_000;

export type TraceStatus = 'off' | 'starting' | 'live' | 'unavailable' | 'failed';

export interface TraceDeps {
  /** The GUI's id on the server (`pack:name`); undefined when this canvas has none to trace. */
  resource: string | undefined;
  /** The graph the canvas draws now: step paths are mapped onto it as they arrive. */
  graph: () => Graph;
  /** The pushes the connection delivers. */
  pushes: Signal<PushFrame>;
}

export class TraceState {
  readonly #deps: TraceDeps;
  #channel: RpcChannel | null = null;
  #removePushListener: (() => void) | null = null;
  #renewTimer: ReturnType<typeof setInterval> | undefined;
  #fadeTimer: ReturnType<typeof setTimeout> | undefined;
  /** Bumped whenever a subscription starts or stops, so a late reply to an old one is ignored. */
  #epoch = 0;

  /** The author's choice. Survives leaving the tab and reconnecting; the subscription follows it. */
  enabled = $state(false);
  status = $state<TraceStatus>('off');
  /** Why the trace is not live, or what it is following. */
  message = $state('');
  scope = $state<'player' | 'all' | ''>('');
  view = $state.raw<TraceView>(emptyTrace());
  /** The wires the run on screen went along, with the order they were reached in. */
  lit: ReadonlyMap<string, number>;
  /** Whether a run is on screen: the canvas dims what it did not reach. */
  showing: boolean;

  constructor(deps: TraceDeps) {
    this.#deps = deps;
    this.lit = $derived(litEdges(this.#deps.graph(), this.view));
    this.showing = $derived(this.enabled && hasRun(this.view));
  }

  /** Whether this canvas has a GUI to trace at all. */
  get available(): boolean {
    return this.#deps.resource !== undefined;
  }

  /** What a node did in the run on screen. */
  of(id: string): NodeTrace | undefined {
    return this.view.nodes.get(id);
  }

  /** Turns the trace on or off (the toolbar's toggle). */
  async set(on: boolean): Promise<void> {
    this.enabled = on;
    if (on) await this.#start();
    else await this.#stop(true);
  }

  /** The Logic tab is showing and the connection is `channel`: subscribe if the author wants the trace. */
  async attach(channel: RpcChannel | null): Promise<void> {
    this.#channel = channel;
    if (!this.enabled) return;
    if (channel) await this.#start();
    else {
      await this.#stop(false);
      this.status = 'failed';
      this.message = 'Disconnected from the server. The trace resumes when the connection returns.';
    }
  }

  /** The tab is leaving: let go of the server-side lease but keep the author's choice. */
  detach(): void {
    void this.#stop(this.enabled && this.#channel !== null);
  }

  async #start(): Promise<void> {
    const resource = this.#deps.resource;
    const channel = this.#channel;
    const epoch = ++this.#epoch;
    this.#teardown();
    if (!resource || !channel || !logicApiAvailable(channel)) {
      this.status = 'unavailable';
      this.message = 'This server does not stream logic traces (it needs a Materione plugin with the logic API).';
      return;
    }
    this.status = 'starting';
    this.message = '';
    try {
      const subscribed = await logicTraceSubscribe(channel, resource);
      if (epoch !== this.#epoch) return;
      this.scope = subscribed.scope;
      this.status = 'live';
      this.message = subscribed.scope === 'player'
        ? 'Click in game: your runs of this GUI light up here.'
        : 'Click in game: every player\'s runs of this GUI light up here.';
      this.#removePushListener = this.#deps.pushes.add((frame) => this.#onPush(frame));
      const renewEvery = Math.max(MIN_RENEW_MS, subscribed.leaseSeconds * 1000 * RENEW_FRACTION);
      this.#renewTimer = setInterval(() => void this.#renew(epoch), renewEvery);
    } catch (error) {
      if (epoch !== this.#epoch) return;
      this.status = 'failed';
      this.message = error instanceof Error ? error.message : String(error);
    }
  }

  async #renew(epoch: number): Promise<void> {
    const resource = this.#deps.resource;
    const channel = this.#channel;
    if (epoch !== this.#epoch || !resource || !channel) return;
    try {
      await logicTraceSubscribe(channel, resource);
    } catch (error) {
      if (epoch !== this.#epoch) return;
      this.status = 'failed';
      this.message = error instanceof Error ? error.message : String(error);
      this.#teardown();
    }
  }

  async #stop(tellServer: boolean): Promise<void> {
    this.#epoch++;
    this.#teardown();
    this.view = emptyTrace();
    if (this.status !== 'unavailable') this.status = 'off';
    this.message = '';
    this.scope = '';
    const resource = this.#deps.resource;
    const channel = this.#channel;
    if (tellServer && resource && channel && logicApiAvailable(channel)) {
      try {
        await logicTraceUnsubscribe(channel, resource);
      } catch {
        // the lease lapses on its own; a trace that cannot be closed is not worth an error
      }
    }
  }

  #teardown(): void {
    this.#removePushListener?.();
    this.#removePushListener = null;
    clearInterval(this.#renewTimer);
    clearTimeout(this.#fadeTimer);
  }

  #onPush(frame: PushFrame): void {
    if (frame.op !== 'push.logic.trace') return;
    const push = parseTracePush(frame.payload);
    if (!push || push.resource !== this.#deps.resource) return;
    this.view = applyEntries(this.view, this.#deps.graph(), push.entries, push.dropped);
    clearTimeout(this.#fadeTimer);
    this.#fadeTimer = setTimeout(() => (this.view = emptyTrace()), TRACE_FADE_MS);
  }
}
