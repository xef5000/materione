import { describe, expect, it } from "vitest";
import { env as cloudflareEnv } from "cloudflare:test";
import worker from "../src/index";
import type { Env } from "../src/http";

const env = cloudflareEnv as unknown as Env;

const ENVELOPE = JSON.stringify({ v: 1, sess: "sess-1", nonce: "n", ct: "c" });
const FRAME = { seq: 0, nonce: "n", ct: "c", sig: "s" };

async function createExpiredChannelWithFrame(): Promise<void> {
  const channel = (await (await worker.fetch(new Request("https://relay.test/v1/channels", { method: "POST" }), env)).json()) as any;
  await worker.fetch(
    new Request(`https://relay.test/v1/channels/${channel.channelId}/frames`, {
      method: "POST",
      headers: { authorization: `Bearer ${channel.pluginToken}` },
      body: JSON.stringify({ frames: [FRAME] }),
    }),
    env,
  );
  await env.DB.prepare("UPDATE channels SET expires_at = 1 WHERE id = ?")
    .bind(channel.channelId)
    .run();
}

async function runSweep(): Promise<void> {
  const handler = worker as unknown as ExportedHandler<Env>;
  await handler.scheduled!(
    { scheduledTime: Date.now(), cron: "0 * * * *" } as ScheduledController,
    env,
    { waitUntil() {}, passThroughOnException() {} } as unknown as ExecutionContext,
  );
}

describe("cron sweep", () => {
  it("removes expired blobs and dead channels with their frames, and adjusts store bytes", async () => {
    const { code } = (await (await worker.fetch(new Request("https://relay.test/v1/store", { method: "POST", body: ENVELOPE }), env)).json()) as any;
    await env.DB.prepare("UPDATE blobs SET expires_at = 1 WHERE code = ?").bind(code).run();
    await createExpiredChannelWithFrame();

    const before = await env.DB.prepare("SELECT store_bytes FROM usage").first();
    expect(before?.store_bytes).toBeGreaterThan(0);

    await runSweep();

    expect(await env.DB.prepare("SELECT code FROM blobs WHERE code = ?").bind(code).first()).toBeNull();
    const framesLeft = await env.DB.prepare("SELECT COUNT(*) AS n FROM frames").first();
    expect(framesLeft?.n).toBe(0);
    const channelsLeft = await env.DB.prepare("SELECT COUNT(*) AS n FROM channels").first();
    expect(channelsLeft?.n).toBe(0);
    const usage = await env.DB.prepare("SELECT store_bytes FROM usage").first();
    expect(usage?.store_bytes).toBe(0);
  });

  it("keeps live channels and their fresh frames", async () => {
    const channel = (await (await worker.fetch(new Request("https://relay.test/v1/channels", { method: "POST" }), env)).json()) as any;
    await worker.fetch(
      new Request(`https://relay.test/v1/channels/${channel.channelId}/frames`, {
        method: "POST",
        headers: { authorization: `Bearer ${channel.pluginToken}` },
        body: JSON.stringify({ frames: [FRAME] }),
      }),
      env,
    );

    await runSweep();

    const frames = await env.DB.prepare("SELECT COUNT(*) AS n FROM frames").first();
    expect(frames?.n).toBe(1);
  });
});
