import { describe, expect, it } from "vitest";
import { env as cloudflareEnv } from "cloudflare:test";
import worker from "../src/index";
import type { Env } from "../src/http";

const env = cloudflareEnv as unknown as Env;

async function createChannel(): Promise<{ channelId: string; pluginToken: string }> {
  const response = await worker.fetch(
    new Request("https://relay.test/v1/channels", { method: "POST" }),
    env,
  );
  return (await response.json()) as any;
}

async function seedUsage(
  values: { new_row_writes?: number; store_posts?: number; store_bytes?: number },
): Promise<void> {
  const today = new Date().toISOString().slice(0, 10);
  await env.DB.prepare(
    `INSERT INTO usage (day, new_row_writes, store_posts, store_bytes) VALUES (?, ?, ?, ?)
     ON CONFLICT(day) DO UPDATE SET new_row_writes = excluded.new_row_writes,
       store_posts = excluded.store_posts, store_bytes = excluded.store_bytes`,
  )
    .bind(today, values.new_row_writes ?? 0, values.store_posts ?? 0, values.store_bytes ?? 0)
    .run();
}

const FRAME = { seq: 0, nonce: "n", ct: "c", sig: "s" };

describe("quotas", () => {
  it("rejects writes past the daily new-row cap with a 429", async () => {
    const channel = await createChannel();
    await seedUsage({ new_row_writes: 39_999 });
    const response = await worker.fetch(
      new Request(`https://relay.test/v1/channels/${channel.channelId}/frames`, {
        method: "POST",
        headers: { authorization: `Bearer ${channel.pluginToken}` },
        body: JSON.stringify({ frames: [FRAME, FRAME, FRAME] }),
      }),
      env,
    );
    expect(response.status).toBe(429);
    expect(((await response.json()) as any).error).toBe("quota_exceeded");
    expect(response.headers.get("retry-after")).toBeTruthy();
  });

  it("rejects channel creation past the concurrency cap", async () => {
    // Storage is isolated per FILE: earlier tests seeded usage counters and
    // channels — reset writes and count what is live before filling to the cap.
    await seedUsage({ new_row_writes: 0, store_posts: 0, store_bytes: 0 });
    const current = await env.DB.prepare(
      "SELECT COUNT(*) AS n FROM channels WHERE closed = 0 AND expires_at > ?",
    )
      .bind(Math.floor(Date.now() / 1000))
      .first<{ n: number }>();
    for (let i = current?.n ?? 0; i < 20; i++) {
      expect((await createChannel()).channelId).toBeTruthy();
    }
    const overflow = await worker.fetch(
      new Request("https://relay.test/v1/channels", { method: "POST" }),
      env,
    );
    expect(overflow.status).toBe(429);
    expect(((await overflow.json()) as any).error).toBe("too_many_channels");
  });

  it("rejects store posts past the daily cap and past total stored bytes", async () => {
    await seedUsage({ store_posts: 20_000 });
    const posts = await worker.fetch(
      new Request("https://relay.test/v1/store", {
        method: "POST",
        body: JSON.stringify({ v: 1, sess: "s", nonce: "n", ct: "c" }),
      }),
      env,
    );
    expect(posts.status).toBe(429);

    await seedUsage({ store_posts: 0, store_bytes: 2 * 1024 * 1024 * 1024 });
    const full = await worker.fetch(
      new Request("https://relay.test/v1/store", {
        method: "POST",
        body: JSON.stringify({ v: 1, sess: "s", nonce: "n", ct: "c" }),
      }),
      env,
    );
    expect(((await full.json()) as any).error).toBe("store_full");
  });
});
