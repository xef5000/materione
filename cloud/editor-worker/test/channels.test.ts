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
  targetEnv: Env = env,
): Promise<Response> {
  return worker.fetch(
    new Request(`https://relay.test/v1/channels/${channelId}/frames`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ frames }),
    }),
    targetEnv,
  );
}

function poll(token: string, after = 0, wait = 0, targetEnv: Env = env): Promise<Response> {
  return worker.fetch(
    new Request(
      `https://relay.test/v1/channels/${caps.channelId}/frames?after=${after}&wait=${wait}`,
      { headers: { authorization: `Bearer ${token}` } },
    ),
    targetEnv,
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
        body: JSON.stringify({ frames: ["not an object"] }),
      }),
      env,
    );
    expect(malformed.status).toBe(400);

    // Handshake messages (hello) are not sealed frames — the mailbox relays
    // them opaquely alongside frames.
    const handshake = await postFrames(caps.pluginToken, [
      { nonce: "n", sessionId: "s", editorSigningPubKey: "k", editorEphemeralPubKey: "e", browser: "Chrome" },
    ]);
    expect(handshake.status).toBe(202);

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

/** Counts D1 round trips (each first/all/run/raw on a statement, each batch). */
function countingEnv(overrides: Record<string, string> = {}): { env: Env; trips: () => number } {
  let trips = 0;
  const wrapStmt = (stmt: D1PreparedStatement): D1PreparedStatement =>
    new Proxy(stmt, {
      get(target, prop) {
        const value = Reflect.get(target, prop, target);
        if (typeof value !== "function") return value;
        if (prop === "bind") {
          return (...args: unknown[]) => wrapStmt(value.apply(target, args) as D1PreparedStatement);
        }
        if (prop === "first" || prop === "all" || prop === "run" || prop === "raw") {
          return (...args: unknown[]) => {
            trips++;
            return value.apply(target, args);
          };
        }
        return value.bind(target);
      },
    });
  const db = new Proxy(env.DB, {
    get(target, prop) {
      const value = Reflect.get(target, prop, target);
      if (prop === "prepare") return (sql: string) => wrapStmt(target.prepare(sql));
      if (prop === "batch") {
        return (stmts: D1PreparedStatement[]) => {
          trips++;
          return target.batch(stmts);
        };
      }
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
  return { env: { ...env, ...overrides, DB: db } as Env, trips: () => trips };
}

describe("D1 round trips (#423)", () => {
  it("posting a batch costs two round trips; polling with frames costs two", async () => {
    const counted = countingEnv();
    const frames = [sealedFrame(0), sealedFrame(1), sealedFrame(2)];
    const posted = await postFrames(caps.pluginToken, frames, caps.channelId, counted.env);
    expect(posted.status).toBe(202);
    expect(counted.trips()).toBe(2);

    const pollEnv = countingEnv();
    const polled = await poll(caps.editorToken, 0, 0, pollEnv.env);
    expect(((await polled.json()) as PollBody).frames).toHaveLength(3);
    // tick (channel + frames) + closing (oldest / throttled seen) batch
    expect(pollEnv.trips()).toBe(2);
  });

  it("counts the post's writes in today's usage in the same batch", async () => {
    const today = new Date().toISOString().slice(0, 10);
    const before = await env.DB.prepare("SELECT new_row_writes AS n FROM usage WHERE day = ?")
      .bind(today).first<{ n: number }>();
    await postFrames(caps.pluginToken, [sealedFrame(0), sealedFrame(1)]);
    const after = await env.DB.prepare("SELECT new_row_writes AS n FROM usage WHERE day = ?")
      .bind(today).first<{ n: number }>();
    expect((after?.n ?? 0) - (before?.n ?? 0)).toBe(4); // channel update + 2 inserts + upsert
  });

  it("prunes the oldest frames once the channel is over its cap", async () => {
    const capped = { ...env, maxFramesPerChannel: "3" } as unknown as Env;
    for (let seq = 0; seq < 6; seq++) {
      const padded = { ...sealedFrame(seq), pad: "x".repeat(300) };
      expect((await postFrames(caps.pluginToken, [padded], caps.channelId, capped)).status).toBe(202);
    }
    const view = (await (await poll(caps.editorToken)).json()) as PollBody;
    expect(view.frames.map((f) => f.frame.seq)).toEqual([3, 4, 5]);
    expect(view.oldest).toBe(view.frames[0]!.id);
    const row = await env.DB.prepare("SELECT frames_bytes AS b FROM channels WHERE id = ?")
      .bind(caps.channelId).first<{ b: number }>();
    const sum = await env.DB.prepare("SELECT SUM(size) AS s FROM frames WHERE channel_id = ?")
      .bind(caps.channelId).first<{ s: number }>();
    expect(row?.b).toBe(sum?.s);
  });
});

describe("adaptive long-poll interval (#424)", () => {
  async function pickUpLatency(targetEnv: Env): Promise<number> {
    const waiting = poll(caps.editorToken, 0, 5, targetEnv);
    await new Promise((resolve) => setTimeout(resolve, 50));
    const postedAt = Date.now();
    await postFrames(caps.pluginToken, [sealedFrame(1)]);
    const body = (await (await waiting).json()) as PollBody;
    expect(body.frames.map((f) => f.frame.seq)).toEqual([1]);
    return Date.now() - postedAt;
  }

  it("re-checks fast while the channel saw a frame recently", async () => {
    await postFrames(caps.editorToken, [sealedFrame(0)]); // channel in use
    const slow = { ...env, longPollIntervalMs: "2000" } as unknown as Env;
    expect(await pickUpLatency(slow)).toBeLessThan(1000);
  });

  it("falls back to the idle interval when the channel is quiet", async () => {
    const slow = {
      ...env,
      longPollIntervalMs: "2000",
      longPollActiveWindowSeconds: "1",
    } as unknown as Env;
    await env.DB.prepare("UPDATE channels SET last_frame_at = 0 WHERE id = ?")
      .bind(caps.channelId).run();
    // The parked poll's first tick saw an idle channel, so it sleeps the idle
    // interval before it can see the frame.
    expect(await pickUpLatency(slow)).toBeGreaterThan(1500);
  });
});
