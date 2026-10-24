import { beforeEach, describe, expect, it } from "vitest";
import { env as cloudflareEnv } from "cloudflare:test";
import worker from "../src/index";
import type { Env } from "../src/http";

const env = cloudflareEnv as unknown as Env;

interface Capabilities {
  channelId: string;
  pluginToken: string;
  editorToken: string;
}

let caps: Capabilities;

beforeEach(async () => {
  const response = await worker.fetch(
    new Request("https://relay.test/v1/channels", { method: "POST" }),
    env,
  );
  expect(response.status).toBe(201);
  caps = (await response.json()) as Capabilities;
});

function sealedFrame(seq: number): Record<string, string | number> {
  return { seq, nonce: `nonce-${seq}`, ct: `ct-${seq}`, sig: `sig-${seq}` };
}

function postFrames(
  token: string,
  frames: Record<string, string | number>[],
  channelId = caps.channelId,
): Promise<Response> {
  return worker.fetch(
    new Request(`https://relay.test/v1/channels/${channelId}/frames`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ frames }),
    }),
    env,
  );
}

function poll(token: string, after = 0, wait = 0): Promise<Response> {
  return worker.fetch(
    new Request(
      `https://relay.test/v1/channels/${caps.channelId}/frames?after=${after}&wait=${wait}`,
      { headers: { authorization: `Bearer ${token}` } },
    ),
    env,
  );
}

interface PollBody {
  closed: boolean;
  frames: Array<{ id: number; frame: { seq: number } }>;
  oldest: number | null;
  more: boolean;
}

describe("channel lifecycle", () => {
  it("creates a channel with capability-shaped response", async () => {
    expect(caps.channelId).toMatch(/^[A-Za-z0-9_-]{16,}$/);
    expect(caps.pluginToken).toMatch(/^[A-Za-z0-9_-]{40,}$/);
    expect(caps.editorToken).toMatch(/^[A-Za-z0-9_-]{40,}$/);
  });

  it("routes frames by caller identity and delivers ordered cursor pages", async () => {
    const posted = await postFrames(caps.pluginToken, [sealedFrame(0), sealedFrame(1)]);
    expect(posted.status).toBe(202);
    expect((await postFrames(caps.pluginToken, [sealedFrame(2)])).status).toBe(202);

    // The plugin reads only editor->plugin frames; none yet.
    const pluginView = (await (await poll(caps.pluginToken)).json()) as PollBody;
    expect(pluginView.frames).toEqual([]);
    expect(pluginView.closed).toBe(false);

    // The editor reads plugin->editor frames, strictly ordered.
    const editorFirst = (await (await poll(caps.editorToken)).json()) as PollBody;
    expect(editorFirst.frames.map((f: { frame: { seq: number } }) => f.frame.seq)).toEqual([0, 1, 2]);
    expect(editorFirst.oldest).toBe(editorFirst.frames[0]!.id);

    // Cursor paging: resume after the second frame.
    const cursor = editorFirst.frames[1]!.id;
    const editorSecond = (await (await poll(caps.editorToken, cursor)).json()) as PollBody;
    expect(editorSecond.frames.map((f: { frame: { seq: number } }) => f.frame.seq)).toEqual([2]);
  });

  it("delivers editor->plugin frames to the plugin", async () => {
    await postFrames(caps.editorToken, [sealedFrame(0)]);
    const pluginView = (await (await poll(caps.pluginToken)).json()) as PollBody;
    expect(pluginView.frames.map((f: { frame: { seq: number } }) => f.frame.seq)).toEqual([0]);
  });

  it("long-poll waits and returns a frame posted mid-wait", async () => {
    const waiting = poll(caps.editorToken, 0, 3);
    await new Promise((resolve) => setTimeout(resolve, 300));
    await postFrames(caps.pluginToken, [sealedFrame(7)]);
    const body = (await (await waiting).json()) as PollBody;
    expect(body.frames.map((f: { frame: { seq: number } }) => f.frame.seq)).toEqual([7]);
  });

  it("rejects wrong tokens, bad batches, and oversized frames", async () => {
    expect((await poll("not-a-real-token-0123456789abcdef")).status).toBe(403);
    // A syntactically-short bearer is rejected before any lookup.
    const shortToken = await worker.fetch(
      new Request(
        `https://relay.test/v1/channels/${caps.channelId}/frames?after=0&wait=0`,
        { headers: { authorization: "Bearer short" } },
      ),
      env,
    );
    expect(shortToken.status).toBe(401);

    const noFrames = await worker.fetch(
      new Request(`https://relay.test/v1/channels/${caps.channelId}/frames`, {
        method: "POST",
        headers: { authorization: `Bearer ${caps.pluginToken}` },
        body: JSON.stringify({ frames: [] }),
      }),
      env,
    );
    expect(noFrames.status).toBe(400);

    const malformed = await worker.fetch(
      new Request(`https://relay.test/v1/channels/${caps.channelId}/frames`, {
        method: "POST",
        headers: { authorization: `Bearer ${caps.pluginToken}` },
        body: JSON.stringify({ frames: [{ seq: "no" }] }),
      }),
      env,
    );
    expect(malformed.status).toBe(400);

    const big = "x".repeat(1024 * 1024 + 1);
    const oversized = await postFrames(caps.pluginToken, [
      { seq: 0, nonce: big, ct: "c", sig: "s" },
    ]);
    expect(oversized.status).toBe(413);

    expect((await poll(caps.pluginToken, -5)).status).toBe(400);
    expect((await worker.fetch(new Request("https://relay.test/v1/channels/nope"), env)).status).toBe(405);
  });

  it("close makes both pollers observe closed", async () => {
    const close = await worker.fetch(
      new Request(`https://relay.test/v1/channels/${caps.channelId}/close`, {
        method: "POST",
        headers: { authorization: `Bearer ${caps.editorToken}` },
      }),
      env,
    );
    expect(close.status).toBe(200);
    const view = (await (await poll(caps.pluginToken)).json()) as PollBody;
    expect(view.closed).toBe(true);
    // Posting into a closed channel fails.
    expect((await postFrames(caps.pluginToken, [sealedFrame(0)])).status).toBe(410);
  });
});
