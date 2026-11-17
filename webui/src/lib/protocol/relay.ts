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
/** Soft cap on one POST's total message bytes (a single larger message
 * still goes alone; the worker caps each message at 1 MiB). */
const MAX_BATCH_BYTES = 1024 * 1024;

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
  /**
   * Messages not yet accepted by the relay, in send order. One POST is in
   * flight at a time (mailbox ids follow send order); whatever queues up
   * behind it goes out together in the next POST, so a burst of RPCs costs
   * a few requests instead of one each.
   */
  private readonly outbox: string[] = [];
  private flushing = false;

  constructor(
    private readonly base: string,
    private readonly channelId: string,
    private readonly token: string,
  ) {
    void this.pollLoop();
  }

  send(text: string): void {
    if (this.closed) {
      return;
    }
    this.outbox.push(text);
    if (!this.flushing) {
      void this.flush();
    }
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

  private async flush(): Promise<void> {
    this.flushing = true;
    let backoff = BACKOFF_MS;
    try {
      while (!this.closed && this.outbox.length > 0) {
        const batch = this.nextBatch();
        const outcome = await this.postBatch(batch);
        if (outcome === 'accepted') {
          // Removed only once accepted: a dropped message would break the
          // plugin's strict receive sequence.
          this.outbox.splice(0, batch.length);
          backoff = BACKOFF_MS;
        } else if (outcome === 'retry') {
          await new Promise((resolve) => setTimeout(resolve, backoff));
          backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        } else {
          return;
        }
      }
    } catch {
      this.transportDead('relay post failed');
    } finally {
      this.flushing = false;
    }
  }

  /** The head of the outbox, capped by count and total size (at least one). */
  private nextBatch(): string[] {
    let bytes = 0;
    let count = 0;
    while (count < this.outbox.length && count < MAX_MESSAGES_PER_BATCH) {
      bytes += this.outbox[count]!.length;
      if (count > 0 && bytes > MAX_BATCH_BYTES) {
        break;
      }
      count++;
    }
    return this.outbox.slice(0, count);
  }

  /**
   * One POST. 429 and 503 are retried: the worker answers both before
   * writing anything (rate limiter, daily quota), so a retry cannot
   * duplicate a message. Any other rejection is fatal — the message is
   * gone and the sequence with it.
   */
  private async postBatch(batch: string[]): Promise<'accepted' | 'retry' | 'dead'> {
    const response = await fetch(this.channelUrl('/frames'), {
      method: 'POST',
      headers: { authorization: `Bearer ${this.token}`, 'content-type': 'application/json' },
      body: `{"frames":[${batch.join(',')}]}`,
    });
    if (response.ok) {
      return 'accepted';
    }
    if (response.status === 429 || response.status === 503) {
      return 'retry';
    }
    this.transportDead(
      response.status === 410 || response.status === 404
        ? 'channel gone'
        : `relay post rejected: HTTP ${response.status}`,
    );
    return 'dead';
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

/** The mailbox fixture's batch cap, mirrored for tests. */
export const RELAY_MAX_MESSAGES_PER_BATCH = MAX_MESSAGES_PER_BATCH;
