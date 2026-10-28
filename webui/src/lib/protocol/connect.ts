/**
 * Transport selection (web.md §4.4). The cloud relay is the default and the
 * only pipe that needs no setup; a direct local connection replaces it ONLY
 * when this browser has proven — right now, by an authenticated challenge
 * bound to this very session — that the plugin's local listener is reachable
 * from here (probe.ts). Anything short of that proof (no hint, mixed-content
 * block, timeout, wrong server on the port, bad signature) leaves the relay
 * in place, silently and harmlessly: the probe happens before the first
 * hello, so a failed probe costs nothing and consumes nothing.
 *
 * Manual override on the capability URL: `?mode=relay` forces the cloud,
 * `?mode=direct` ignores the stored "prefer cloud" switch (a failed probe
 * still stays on the cloud — the override can never produce a broken page).
 */
import {
  EditorChannel,
  fetchSessionBlob,
  negotiateProtocolVersion,
  WebSocketTransport,
} from './session';
import type { ChannelState, SessionBlob, Transport } from './session';
import { LongPollTransport } from './relay';
import { probeDirect } from './probe';
import type { ProbeDeps, ProbeFailure } from './probe';
import {
  isLoopbackHost,
  parseOverride,
  planDirect,
  readPreference,
} from './transport-plan';
import type { SkipReason, TransportOverride, TransportPreference } from './transport-plan';

export type EditorMode = 'cloud' | 'direct';

/** Why this transport was chosen — surfaced in the Server view, never as an error. */
export type DecisionReason =
  | 'direct-proven'
  | 'served-by-plugin'
  | SkipReason
  | `probe-${ProbeFailure}`;

export interface TransportDecision {
  mode: EditorMode;
  reason: DecisionReason;
  /** Present exactly when `mode` is 'direct' and the socket was proven. */
  transport?: Transport;
}

export interface OpenedChannel {
  channel: EditorChannel;
  mode: EditorMode;
  reason: DecisionReason;
}

export interface DecisionEnv {
  pageProtocol: string;
  override: TransportOverride;
  preference: TransportPreference;
}

/**
 * The state machine: skip | probe → (proven → direct) | (anything else →
 * cloud). Pure apart from the probe's socket, which tests inject.
 */
export async function decideTransport(
  blob: SessionBlob,
  env: DecisionEnv,
  probeDeps: Partial<ProbeDeps> = {},
): Promise<TransportDecision> {
  const plan = planDirect({
    pageProtocol: env.pageProtocol,
    hint: blob.direct?.url,
    override: env.override,
    preference: env.preference,
  });
  if (plan.kind === 'skip') return { mode: 'cloud', reason: plan.reason };

  const result = await probeDirect(plan.wsUrl, blob, probeDeps);
  if (!result.ok) return { mode: 'cloud', reason: `probe-${result.reason}` };
  return {
    mode: 'direct',
    reason: 'direct-proven',
    transport: WebSocketTransport.adopt(result.socket),
  };
}

/** Human wording for the Server view; the 'cloud' reasons are informational, not faults. */
export function describeDecision(mode: EditorMode, reason: DecisionReason): string {
  if (mode === 'direct') {
    return reason === 'served-by-plugin'
      ? 'direct — this page is served by the plugin'
      : 'direct — your browser reached the server\'s local editor (verified), so it skips the cloud relay';
  }
  switch (reason) {
    case 'override-relay':
      return 'cloud relay — forced by ?mode=relay';
    case 'preference-cloud':
      return 'cloud relay — this browser is set to always use the cloud';
    case 'no-hint':
      return 'cloud relay';
    case 'mixed-content':
    case 'bad-hint':
      return 'cloud relay — a direct connection is not possible from this page';
    default:
      return 'cloud relay — a direct connection to the server was not reachable from this browser';
  }
}

/**
 * A relay that is down must not look like a broken editor, and must never
 * silently turn into a connection to a port nobody proved reachable. This
 * names what happened and the one deliberate escape hatch.
 */
export function relayOutageGuidance(error: unknown, origin: string): string | null {
  let host = '';
  try {
    host = new URL(origin).hostname;
  } catch {
    return null;
  }
  if (isLoopbackHost(host)) return null; // plugin-served: not a relay problem
  const message = error instanceof Error ? error.message : String(error);
  const status = /HTTP (\d{3})/.exec(message)?.[1];
  const networkFailure = error instanceof TypeError;
  const overloaded = status !== undefined && (status === '429' || status.startsWith('5'));
  if (!networkFailure && !overloaded) return null;
  return (
    'The cloud relay is not answering right now' +
    (status ? ` (HTTP ${status})` : '') +
    '. Nothing was switched to a local connection automatically. Try again in a moment. ' +
    'If your server\'s editor port is reachable from this computer, you can run ' +
    '/materione editor local in game and open that link instead — on most hosting panels ' +
    'and behind NAT that port is not reachable.'
  );
}

export async function openChannel(
  code: string,
  fragmentKey: string,
  browserLabel: string,
  onState: (state: ChannelState) => void,
  originOverride?: string,
  override: TransportOverride = parseOverride(location.search),
): Promise<OpenedChannel> {
  const origin = originOverride ?? location.origin;
  let blob: SessionBlob;
  try {
    blob = await fetchSessionBlob(origin, code, fragmentKey);
  } catch (error) {
    const guidance = relayOutageGuidance(error, origin);
    throw guidance ? new Error(guidance) : error;
  }

  if (!blob.relay) {
    // Direct-mode blob: the origin that served it is the plugin listener.
    const wsProtocol = origin.startsWith('https') ? 'wss' : 'ws';
    const wsUrl = `${wsProtocol}://${origin.replace(/^https?:\/\//, '')}/editor/ws`;
    const channel = await EditorChannel.open(origin, wsUrl, code, fragmentKey, browserLabel, {
      onState,
      blob,
    });
    return { channel, mode: 'direct', reason: 'served-by-plugin' };
  }

  // Fail on an incompatible jar BEFORE any probe opens a socket.
  negotiateProtocolVersion(blob);

  const decision = await decideTransport(blob, {
    pageProtocol: location.protocol,
    override,
    preference: readPreference(),
  });

  if (decision.mode === 'direct' && decision.transport) {
    try {
      const channel = await EditorChannel.open(origin, null, code, fragmentKey, browserLabel, {
        onState,
        blob,
        transport: decision.transport,
      });
      return { channel, mode: 'direct', reason: decision.reason };
    } catch (error) {
      decision.transport.close();
      throw error;
    }
  }

  const transport = new LongPollTransport(
    trimSlash(blob.relay.base),
    blob.relay.channelId,
    blob.relay.editorToken,
  );
  try {
    const channel = await EditorChannel.open(origin, null, code, fragmentKey, browserLabel, {
      onState,
      blob,
      transport,
    });
    return { channel, mode: 'cloud', reason: decision.reason };
  } catch (error) {
    const guidance = relayOutageGuidance(error, origin);
    throw guidance ? new Error(guidance) : error;
  }
}

function trimSlash(text: string): string {
  return text.endsWith('/') ? text.slice(0, -1) : text;
}
