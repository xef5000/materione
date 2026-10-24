/**
 * Transport selection (web.md §4.4): fetch the session blob, then pick the
 * pipe — a reachable local listener (LAN auto-preference) wins over the
 * relay; the relay wins whenever no local listener answers. Manual override
 * via ?mode=relay|direct on the capability URL.
 */
import { EditorChannel, fetchSessionBlob, WebSocketTransport } from './session';
import type { ChannelState, SessionBlob } from './session';
import { LongPollTransport } from './relay';

export type EditorMode = 'cloud' | 'direct';

export interface OpenedChannel {
  channel: EditorChannel;
  mode: EditorMode;
}

const DIRECT_PROBE_MS = 700;

export async function openChannel(
  code: string,
  fragmentKey: string,
  browserLabel: string,
  onState: (state: ChannelState) => void,
  originOverride?: string,
): Promise<OpenedChannel> {
  const origin = originOverride ?? location.origin;
  const override = new URLSearchParams(location.search).get('mode');
  const blob = await fetchSessionBlob(origin, code, fragmentKey);

  if (!blob.relay) {
    // Direct-mode blob: the origin that served it is the plugin listener.
    const wsProtocol = origin.startsWith('https') ? 'wss' : 'ws';
    const wsUrl = `${wsProtocol}://${origin.replace(/^https?:\/\//, '')}/editor/ws`;
    const channel = await EditorChannel.open(origin, wsUrl, code, fragmentKey, browserLabel, {
      onState,
      blob,
    });
    return { channel, mode: 'direct' };
  }

  if (override !== 'relay' && blob.direct) {
    const direct = await probeDirect(blob.direct.url);
    if (direct) {
      const channel = await EditorChannel.open(
        origin, direct, code, fragmentKey, browserLabel, { onState, blob });
      return { channel, mode: 'direct' };
    }
  }

  const transport = new LongPollTransport(
    trimSlash(blob.relay.base),
    blob.relay.channelId,
    blob.relay.editorToken,
  );
  const channel = await EditorChannel.open(origin, null, code, fragmentKey, browserLabel, {
    onState,
    blob,
    transport,
  });
  return { channel, mode: 'cloud' };
}

/** Resolves the /editor/ws URL when the local listener answers in time. */
async function probeDirect(directBase: string): Promise<string | null> {
  const wsProtocol = directBase.startsWith('https') ? 'wss' : 'ws';
  const wsUrl = `${wsProtocol}://${directBase.replace(/^https?:\/\//, '')}/editor/ws`;
  try {
    const socket = await withTimeout(
      new Promise<WebSocket>((resolve, reject) => {
        const probe = new WebSocket(wsUrl);
        probe.onopen = () => resolve(probe);
        probe.onerror = () => reject(new Error('unreachable'));
      }),
      DIRECT_PROBE_MS,
    );
    // The probe socket cannot carry the session (the handshake socket is
    // created inside WebSocketTransport); close it and reconnect cleanly.
    socket.close();
    return wsUrl;
  } catch {
    return null;
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

function trimSlash(text: string): string {
  return text.endsWith('/') ? text.slice(0, -1) : text;
}
