import { describe, expect, it } from 'vitest';
import { WebSocketTransport } from '../src/lib/protocol/session';
import type { WebSocketLike } from '../src/lib/protocol/session';
/**
 * The handover gap regression: the server may push frames (the MTR-0032
 * diagnostic broadcast fires the instant the hello-reply is written) while
 * the SPA is still deriving keys — between the one-shot handshake handler
 * and the channel's permanent one. Any frame lost in that window desyncs
 * the strict receive sequence and kills the channel on the next RPC
 * ("closed (1000)"). Nothing may be lost; order must hold.
 */
class FakeSocket implements WebSocketLike {
  readyState = 1; // OPEN
  sent: string[] = [];
  #listeners = new Map<string, ((event: { data?: unknown; code?: number }) => void)[]>();

  send(text: string): void {
    this.sent.push(text);
  }

  close(): void {
    this.emit('close', { code: 1000 });
  }

  addEventListener(type: string, listener: (event: { data?: unknown }) => void): void {
    this.#listeners.set(type, [...(this.#listeners.get(type) ?? []), listener]);
  }

  /** Test seam: server sends a text frame. */
  serverSends(text: string): void {
    this.emit('message', { data: text });
  }

  private emit(type: string, event: { data?: unknown; code?: number }): void {
    for (const listener of this.#listeners.get(type) ?? []) {
      listener(event);
    }
  }
}

function wired(): { transport: WebSocketTransport; socket: FakeSocket } {
  const socket = new FakeSocket();
  const transport = WebSocketTransport.adopt(socket);
  return { transport, socket };
}

describe('WebSocketTransport handover', () => {
  it('buffers frames arriving while no handler is attached, drains in order', async () => {
    const { transport, socket } = wired();
    const got: string[] = [];

    // Handshake phase: one-shot handler for the reply.
    const reply = new Promise<string>((resolve) => {
      transport.onMessage((text) => {
        transport.onMessage(null); // detach — this is the fix under test
        resolve(text);
      });
    });

    socket.serverSends('reply');
    // Pushes racing key derivation: after detach, before the channel handler.
    socket.serverSends('push-a');
    socket.serverSends('push-b');

    await expect(reply).resolves.toBe('reply');

    transport.onMessage((text) => got.push(text));
    expect(got).toEqual(['push-a', 'push-b']);

    socket.serverSends('rpc-response');
    expect(got).toEqual(['push-a', 'push-b', 'rpc-response']);
  });

  it('buffers frames that arrive before any handler exists at all', () => {
    const { transport, socket } = wired();
    socket.serverSends('early');
    const got: string[] = [];
    transport.onMessage((text) => got.push(text));
    expect(got).toEqual(['early']);
  });

  it('a stale handler is NOT left swallowing frames after detach', () => {
    const { transport, socket } = wired();
    const swallowed: string[] = [];
    transport.onMessage((text) => {
      transport.onMessage(null);
      swallowed.push(text);
    });
    socket.serverSends('first');
    socket.serverSends('second'); // must buffer, not hit the stale handler
    expect(swallowed).toEqual(['first']);

    const got: string[] = [];
    transport.onMessage((text) => got.push(text));
    expect(got).toEqual(['second']);
  });
});
