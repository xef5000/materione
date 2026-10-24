import { describe, expect, it } from "vitest";
import { env as cloudflareEnv } from "cloudflare:test";
import worker from "../src/index";
import type { Env } from "../src/http";

const env = cloudflareEnv as unknown as Env;

const ENVELOPE = JSON.stringify({ v: 1, sess: "sess-1", nonce: "n", ct: "ciphertext-body" });

function put(body: string, query = ""): Promise<Response> {
  return worker.fetch(new Request(`https://relay.test/v1/store${query}`, { method: "POST", body }), env);
}

describe("payload store", () => {
  it("stores and returns an envelope with delete capability", async () => {
    const putResponse = await put(ENVELOPE);
    expect(putResponse.status).toBe(201);
    const { code, deleteToken, expires } = await putResponse.json() as any;
    expect(code).toMatch(/^[A-Za-z0-9_-]{16,}$/);
    expect(expires).toBeGreaterThan(Date.now() / 1000);

    const fetched = await worker.fetch(new Request(`https://relay.test/v1/store/${code}`), env);
    expect(fetched.status).toBe(200);
    expect(await fetched.text()).toBe(ENVELOPE);

    const meta = await (
      await worker.fetch(new Request(`https://relay.test/v1/store/${code}/meta`), env)
    ).json() as any;
    expect(meta.size).toBe(ENVELOPE.length);

    const deleted = await worker.fetch(
      new Request(`https://relay.test/v1/store/${code}`, {
        method: "DELETE",
        headers: { authorization: `Bearer ${deleteToken}` },
      }),
      env,
    );
    expect(deleted.status).toBe(200);
    expect((await worker.fetch(new Request(`https://relay.test/v1/store/${code}`), env)).status).toBe(404);
  });

  it("serves the capability fetch route /s/{code} with the same envelope", async () => {
    const code = "capability-route-code-0001";
    const putResponse = await put(ENVELOPE, `?code=${code}`);
    expect(putResponse.status).toBe(201);
    const fetched = await worker.fetch(new Request(`https://relay.test/s/${code}`), env);
    expect(fetched.status).toBe(200);
    expect(fetched.headers.get("content-type")).toContain("application/json");
    expect(await fetched.text()).toBe(ENVELOPE);
    expect(
      (await worker.fetch(new Request("https://relay.test/s/unknown-code-00000000000"), env)).status,
    ).toBe(404);
    expect(
      (
        await worker.fetch(new Request(`https://relay.test/s/${code}`, { method: "POST" }), env)
      ).status,
    ).toBe(405);
  });

  it("dedups content-addressed uploads", async () => {
    const code = "content-addressed-code-0001";
    const first = await put(ENVELOPE, `?code=${code}`);
    expect(first.status).toBe(201);
    const second = await put(ENVELOPE, `?code=${code}`);
    expect(second.status).toBe(200);
    expect(((await second.json()) as any).deduped).toBe(true);
  });

  it("refuses non-envelopes and oversize bodies", async () => {
    expect((await put(JSON.stringify({ hello: "world" }))).status).toBe(415);
    expect((await put("not json at all")).status).toBe(415);
    const big = JSON.stringify({ v: 1, sess: "s", nonce: "n", ct: "x".repeat(768 * 1024) });
    expect((await put(big)).status).toBe(413);
  });

  it("rejects deletes with the wrong token", async () => {
    const { code } = await (await put(ENVELOPE)).json() as any;
    const wrong = await worker.fetch(
      new Request(`https://relay.test/v1/store/${code}`, {
        method: "DELETE",
        headers: { authorization: "Bearer wrong-delete-token-0123456789" },
      }),
      env,
    );
    expect(wrong.status).toBe(403);
  });

  it("404s unknown codes without leaking existence semantics", async () => {
    expect(
      (await worker.fetch(new Request("https://relay.test/v1/store/does-not-exist-at-all"), env)).status,
    ).toBe(404);
  });
});
