/**
 * The relay-mode browser transport (web.md §3.2): the editor's sealed frames
 * and handshake messages ride the mailbox over plain HTTPS — POST batches
 * out, long-poll in. Contract-identical to {@link WebSocketTransport}
 * (detach-and-buffer onMessage, ordered delivery); the wire bytes on the
 * channel are unchanged, only the pipes differ.
 *
 * Shapes pinned by fixtures/protocol/relay-mailbox-v1.json — the same file
 * the worker and the plugin's RelayClient test against.
 */
import type { Transport } from './session';

const POLL_WAIT_SECONDS = 20;
const POLL_TIMEOUT_MS = (POLL_WAIT_SECONDS + 10) * 1000;
/** Floor between polls: the server holds each poll ~20 s, so this is
 * invisible in production — it only stops hot-looping against a broken
 * endpoint (and keeps the request quota honest). */
const POLL_FLOOR_MS = 100;
const BACKOFF_MS = 500;
const MAX_BACKOFF_MS = 8000;
const MAX_MESSAGES_PER_BATCH = 32;

interface Delivered {
  id: number;
  frame: Record<string, unknown>;
}

interface PollBody {
  closed: boolean;
  frames: Delivered[];
  oldest: number | null;
  more: boolean;
}

export class LongPollTransport implements Transport {
  private messageHandler: ((text: string) => void) | null = null;
  private closeHandler: ((code: number) => void) | null = null;
  private readonly buffered: string[] = [];
  private closed = false;
  private cursor = 0;
  /** POSTs serialize through this chain: mailbox ids follow send order. */
  private postChain: Promise<void> = Promise.resolve();

  constructor(
    private readonly base: string,
    private readonly channelId: string,
    private readonly token: string,
  ) {
    void this.pollLoop();
  }

  send(text: string): void {
    this.postChain = this.postChain.then(() => this.postOne(text)).catch(() => {
      this.transportDead('relay post failed');
    });
  }

  onMessage(handler: ((text: string) => void) | null): void {
    this.messageHandler = handler;
    if (!handler) {
      return;
    }
    const backlog = [...this.buffered];
    this.buffered.length = 0;
    for (const text of backlog) {
      handler(text);
    }
  }

  onClose(handler: (code: number) => void): void {
    this.closeHandler = handler;
  }

  close(): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    // Best-effort channel close; the relay's TTL sweep reaps regardless.
    void fetch(this.channelUrl('/close'), {
      method: 'POST',
      headers: { authorization: `Bearer ${this.token}` },
      keepalive: true,
    }).catch(() => {});
  }

  // ------------------------------------------------------------------

  private channelUrl(path: string): string {
    return `${this.base}/v1/channels/${this.channelId}${path}`;
  }

  private async postOne(text: string): Promise<void> {
    const response = await fetch(this.channelUrl('/frames'), {
      method: 'POST',
      headers: { authorization: `Bearer ${this.token}`, 'content-type': 'application/json' },
      body: JSON.stringify({ frames: [JSON.parse(text)] }),
    });
    if (!response.ok && (response.status === 410 || response.status === 404)) {
      this.transportDead('channel gone');
    }
  }

  private async pollLoop(): Promise<void> {
    let backoff = BACKOFF_MS;
    while (!this.closed) {
      try {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), POLL_TIMEOUT_MS);
        const response = await fetch(
          `${this.channelUrl('/frames')}?after=${this.cursor}&wait=${POLL_WAIT_SECONDS}`,
          { headers: { authorization: `Bearer ${this.token}` }, signal: controller.signal },
        );
        clearTimeout(timer);
        if (this.closed) {
          return;
        }
        if (response.status === 410 || response.status === 404) {
          this.transportDead('channel gone');
          return;
        }
        if (!response.ok) {
          throw new Error(`poll failed: HTTP ${response.status}`);
        }
        const body = JSON.parse(await response.text()) as PollBody;
        if (body.closed) {
          this.transportDead('channel closed');
          return;
        }
        for (const delivered of body.frames) {
          this.cursor = delivered.id;
          this.deliver(JSON.stringify(delivered.frame));
        }
        backoff = BACKOFF_MS;
        await new Promise((resolve) => setTimeout(resolve, POLL_FLOOR_MS));
      } catch {
        if (this.closed) {
          return;
        }
        // Transient hiccup: back off and resume from the cursor — the
        // retained frames are the catch-up buffer (web.md §3.2).
        await new Promise((resolve) => setTimeout(resolve, backoff));
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
      }
    }
  }

  private deliver(text: string): void {
    if (this.messageHandler) {
      this.messageHandler(text);
    } else {
      this.buffered.push(text);
    }
  }

  private transportDead(reason: string): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.closeHandler?.(1000);
    // Nothing async may follow; the reason is observable via the channel
    // state (EditorChannel reports close through onState).
    void reason;
  }
}

/** The mailbox fixture's batch cap, mirrored for tests and future batching. */
export const RELAY_MAX_MESSAGES_PER_BATCH = MAX_MESSAGES_PER_BATCH;
