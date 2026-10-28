/**
 * The browser half of the direct-mode reachability proof (web.md §4.4;
 * JVM half: dev.materione.web.protocol.ProbeMessage). A bare "the socket
 * opened" proves nothing — a different Materione instance, a reverse proxy
 * answering for a dead backend, or any WebSocket service on the same
 * IP:port would open too. So the probe sends a fresh challenge and accepts
 * the connection only if the reply is an Ed25519 signature, under the
 * plugin key from the end-to-end-encrypted session blob, over
 * `materione-probe/v1 \n sessionId \n challenge`.
 *
 * Everything that is not a verified reply resolves to `{ ok: false }` and is
 * deliberately silent: the caller stays on the relay. The probe never
 * throws, never retries, and holds the socket open only on success (the
 * caller adopts it as the session transport, so there is no second connect).
 */
import { decodeBase64Url as b64d, encodeBase64Url as b64e, importEd25519Public, randomBytes, utf8, verify } from './crypto';
import { canonicalJson } from './wire';
import type { WebSocketLike } from './session';

export const PROBE_DOMAIN = 'materione-probe/v1';
/** Whole-probe budget: connect + one round trip. A LAN answers in single-digit ms. */
export const PROBE_TIMEOUT_MS = 1500;
const CHALLENGE_BYTES = 16;

export type ProbeFailure = 'unreachable' | 'timeout' | 'declined' | 'bad-reply' | 'bad-signature';

export type ProbeResult = { ok: true; socket: WebSocketLike } | { ok: false; reason: ProbeFailure };

export interface ProbeDeps {
  /** Opens a WebSocket; may throw (mixed content, CSP, blocked permission). */
  openSocket: (url: string) => WebSocketLike;
  random: (length: number) => Uint8Array;
  timeoutMs: number;
}

const defaultDeps: ProbeDeps = {
  openSocket: (url) => new WebSocket(url) as unknown as WebSocketLike,
  random: randomBytes,
  timeoutMs: PROBE_TIMEOUT_MS,
};

/** The exact bytes signed by the plugin; mirrors ProbeMessage.signedBytes. */
export function probeSignedBytes(sessionId: string, challenge: string): Uint8Array {
  return utf8.encode(`${PROBE_DOMAIN}\n${sessionId}\n${challenge}`);
}

export async function probeDirect(
  wsUrl: string,
  blob: { sessionId: string; pluginSigningPubKey: Uint8Array },
  deps: Partial<ProbeDeps> = {},
): Promise<ProbeResult> {
  const { openSocket, random, timeoutMs } = { ...defaultDeps, ...deps };
  const challenge = b64e(random(CHALLENGE_BYTES));

  let socket: WebSocketLike;
  try {
    socket = openSocket(wsUrl);
  } catch {
    return { ok: false, reason: 'unreachable' };
  }

  return new Promise<ProbeResult>((resolve) => {
    let settled = false;
    const finish = (result: ProbeResult) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (!result.ok) {
        try {
          socket.close();
        } catch {
          // already gone
        }
      }
      resolve(result);
    };
    const timer = setTimeout(() => finish({ ok: false, reason: 'timeout' }), timeoutMs);

    const sendProbe = () => {
      try {
        socket.send(canonicalJson({ type: 'probe', sessionId: blob.sessionId, challenge }));
      } catch {
        finish({ ok: false, reason: 'unreachable' });
      }
    };

    socket.addEventListener('error', () => finish({ ok: false, reason: 'unreachable' }));
    socket.addEventListener('close', (event) =>
      finish({ ok: false, reason: event.code === 4404 ? 'declined' : 'unreachable' }),
    );
    socket.addEventListener('message', (event) => {
      if (settled) return; // after success the session transport owns this socket
      void verifyReply(event.data, blob, challenge).then((failure) =>
        finish(failure ? { ok: false, reason: failure } : { ok: true, socket }),
      );
    });
    if (socket.readyState === 1 /* OPEN */) sendProbe();
    else socket.addEventListener('open', sendProbe, { once: true });
  });
}

async function verifyReply(
  data: unknown,
  blob: { sessionId: string; pluginSigningPubKey: Uint8Array },
  challenge: string,
): Promise<ProbeFailure | null> {
  try {
    if (typeof data !== 'string') return 'bad-reply';
    const reply = JSON.parse(data) as Record<string, unknown>;
    if (reply.type !== 'probe-reply' || reply.sessionId !== blob.sessionId || typeof reply.sig !== 'string') {
      return 'bad-reply';
    }
    const key = await importEd25519Public(blob.pluginSigningPubKey);
    const valid = await verify(key, b64d(reply.sig), probeSignedBytes(blob.sessionId, challenge));
    return valid ? null : 'bad-signature';
  } catch {
    return 'bad-reply';
  }
}
