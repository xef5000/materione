import { describe, expect, it } from "vitest";
import { env as cloudflareEnv } from "cloudflare:test";
import worker from "../src/index";
import type { Env } from "../src/http";

const env = cloudflareEnv as unknown as Env & { MAILBOX_FIXTURE_JSON: string };

/**
 * Golden fixture consumer: pins the mailbox wire contract shared with the
 * plugin (web/ RelayClient tests) and the SPA (webui LongPollTransport
 * tests) — fixtures/protocol/relay-mailbox-v1.json. Drift fails all sides.
 * The JSON arrives as a Node-injected binding (see vitest.config.ts).
 */
const fixture = JSON.parse(env.MAILBOX_FIXTURE_JSON) as {
  tokenHashing: { algorithm: string; vector: { input: string; sha256Hex: string } };
  envelope: { shape: Record<string, unknown>; rejectIf: string[] };
  postFrames: { limits: { maxFramesPerBatch: number; maxFrameBytes: number }; status: number };
};

async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

describe("relay-mailbox-v1 golden fixture", () => {
  it("hashes bearer tokens exactly as every other runtime must", async () => {
    expect(await sha256Hex(fixture.tokenHashing.vector.input)).toBe(
      fixture.tokenHashing.vector.sha256Hex,
    );
  });

  it("enforces the fixture's batch limit", async () => {
    const channel = (await (await worker.fetch(new Request("https://relay.test/v1/channels", { method: "POST" }), env)).json()) as any;
    const tooMany = Array.from(
      { length: fixture.postFrames.limits.maxFramesPerBatch + 1 },
      (_, i) => ({ seq: i, nonce: "n", ct: "c", sig: "s" }),
    );
    const response = await worker.fetch(
      new Request(`https://relay.test/v1/channels/${channel.channelId}/frames`, {
        method: "POST",
        headers: { authorization: `Bearer ${channel.pluginToken}` },
        body: JSON.stringify({ frames: tooMany }),
      }),
      env,
    );
    expect(response.status).toBe(400);
  });

  it("accepts the fixture envelope shape and rejects the listed violations", async () => {
    const good = await worker.fetch(
      new Request("https://relay.test/v1/store", {
        method: "POST",
        body: JSON.stringify(fixture.envelope.shape),
      }),
      env,
    );
    expect(good.status).toBe(201);

    const notJson = await worker.fetch(
      new Request("https://relay.test/v1/store", { method: "POST", body: "definitely not json" }),
      env,
    );
    expect(notJson.status).toBe(415);
    const wrongVersion = await worker.fetch(
      new Request("https://relay.test/v1/store", {
        method: "POST",
        body: JSON.stringify({ ...fixture.envelope.shape, v: 2 }),
      }),
      env,
    );
    expect(wrongVersion.status).toBe(415);
    const missingField = await worker.fetch(
      new Request("https://relay.test/v1/store", {
        method: "POST",
        body: JSON.stringify({ v: 1, sess: "s", nonce: "n" }),
      }),
      env,
    );
    expect(missingField.status).toBe(415);
  });
});
