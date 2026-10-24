import { describe, expect, it, vi } from 'vitest';
import { LongPollTransport } from '../src/lib/protocol/relay';

/**
 * The mailbox transport against a stubbed fetch implementing the relay
 * worker's contract (fixtures/protocol/relay-mailbox-v1.json): ordered
 * cursor-paged delivery, the detach-and-buffer handover contract, and
 * ordered serialized POSTs.
 */

interface Queued {
  id: number;
  text: string;
}

function stubMailbox() {
  const inbound: Queued[] = [];
  const posted: string[] = [];
  let closedChannels = 0;
  let nextId = 1;

  const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
    const url = String(input);
    const auth = (init?.headers as Record<string, string>)?.authorization ?? '';
    expect(auth).toContain('Bearer editor-token');
    if (url.endsWith('/close')) {
      closedChannels++;
      return new Response('{"closed":true}', { status: 200 });
    }
    if (init?.method === 'POST' && url.includes('/frames')) {
      const body = JSON.parse(String(init.body)) as { frames: unknown[] };
      posted.push(...body.frames.map((frame) => JSON.stringify(frame)));
      return new Response('{"accepted":1}', { status: 202 });
    }
    if (url.includes('/frames?')) {
      const after = Number(new URL(url).searchParams.get('after'));
      const frames = inbound
        .filter((entry) => entry.id > after)
        .map((entry) => ({ id: entry.id, frame: JSON.parse(entry.text) }));
      const body = {
        closed: false,
        frames,
        oldest: inbound.length > 0 ? inbound[0]!.id : null,
        more: false,
      };
      return new Response(JSON.stringify(body), { status: 200 });
    }
    return new Response('{"error":"not_found"}', { status: 404 });
  });

  return {
    fetchImpl,
    deliver: (text: string) => inbound.push({ id: nextId++, text }),
    posted: () => posted,
    closedChannels: () => closedChannels,
  };
}

describe('LongPollTransport', () => {
  it('delivers inbound messages in order with cursor paging', async () => {
    const mailbox = stubMailbox();
    vi.stubGlobal('fetch', mailbox.fetchImpl);
    try {
      const transport = new LongPollTransport('https://relay.test', 'chan-1', 'editor-token-0123456789abcdef');
      const received: string[] = [];
      // No handler yet: messages arriving mid-handover must buffer. (The
      // transport paces polls on a small floor, so wait past it.)
      mailbox.deliver('{"seq":0}');
      await settle();
      transport.onMessage((text) => received.push(text));
      expect(received).toEqual(['{"seq":0}']);

      mailbox.deliver('{"seq":1}');
      mailbox.deliver('{"seq":2}');
      await settle();
      expect(received).toEqual(['{"seq":0}', '{"seq":1}', '{"seq":2}']);

      transport.close();
      expect(mailbox.closedChannels()).toBe(1);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('serializes outbound posts in send order', async () => {
    const mailbox = stubMailbox();
    let release: (() => void) | null = null;
    const gate = new Promise<void>((resolve) => (release = resolve));
    let firstPost = true;
    const wrapper = vi.fn(async (input: string | URL, init?: RequestInit) => {
      if (String(input).includes('/frames?') === false && firstPost) {
        firstPost = false;
        await gate; // the first POST stalls; the second must queue behind it
      }
      return mailbox.fetchImpl(input, init);
    });
    vi.stubGlobal('fetch', wrapper);
    try {
      const transport = new LongPollTransport('https://relay.test', 'chan-1', 'editor-token-0123456789abcdef');
      transport.send('{"seq":0}');
      transport.send('{"seq":1}');
      await tick();
      expect(mailbox.posted()).toEqual([]); // still gated
      release!();
      await tick();
      await tick();
      expect(mailbox.posted()).toEqual(['{"seq":0}', '{"seq":1}']);
      transport.close();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('reports channel-closed through onClose exactly once', async () => {
    const fetchImpl = vi.fn(async (input: string | URL) =>
      new Response('{"closed":true,"frames":[],"oldest":null,"more":false}', { status: 200 }));
    vi.stubGlobal('fetch', fetchImpl);
    try {
      const transport = new LongPollTransport('https://relay.test', 'chan-1', 'editor-token-0123456789abcdef');
      const closes: number[] = [];
      transport.onClose((code) => closes.push(code));
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(closes).toEqual([1000]);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 10));
}

/** Long enough for one paced poll cycle (POLL_FLOOR + fetch microtasks). */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 180));
}
