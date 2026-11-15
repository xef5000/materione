import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ALLOWED_ORIGIN, MAX_BODY_BYTES } from "../src/config";
import worker from "../src/index";

const VALID_BODY = {
  system: "You are a helpful assistant.",
  tools: [
    {
      name: "run_program",
      description: "Run a TypeScript program",
      input_schema: { type: "object", properties: { code: { type: "string" } }, required: ["code"] },
    },
  ],
  messages: [{ role: "user", content: "Create an item." }],
};

function makeRequest(
  path: string,
  options: {
    method?: string;
    origin?: string | null;
    ip?: string | null;
    contentType?: string | null;
    body?: string;
  } = {},
): Request {
  const headers = new Headers();
  if (options.origin !== null) {
    headers.set("origin", options.origin ?? ALLOWED_ORIGIN);
  }
  if (options.ip !== null) {
    headers.set("cf-connecting-ip", options.ip ?? "203.0.113.10");
  }
  if (options.contentType !== null && (options.method ?? "POST") === "POST") {
    headers.set("content-type", options.contentType ?? "application/json");
  }
  return new Request(`https://ai.materione.com${path}`, {
    method: options.method ?? "POST",
    headers,
    body: (options.method ?? "POST") === "POST" ? (options.body ?? JSON.stringify(VALID_BODY)) : undefined,
  });
}

describe("CORS, origin boundary, schema validation, and limits", () => {
  beforeEach(async () => {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM reservations"),
      env.DB.prepare("DELETE FROM daily_caller_usage"),
      env.DB.prepare("DELETE FROM daily_pool_usage"),
    ]);
  });

  it("rejects missing, localhost, 127.0.0.1, and arbitrary origins with 403 bad_origin before rate limiter or D1", async () => {
    const rateSpy = vi.fn(async () => ({ success: true }));
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const testEnv = { ...env, RATE_LIMITER: { limit: rateSpy } as unknown as RateLimit };

    const badOrigins: Array<string | null> = [
      null,
      "http://localhost:5173",
      "http://127.0.0.1:8787",
      "https://evil.example",
      "http://editor.materione.com",
    ];

    try {
      for (const origin of badOrigins) {
        for (const method of ["GET", "POST", "OPTIONS"]) {
          const ctx = createExecutionContext();
          const res = await worker.fetch(
            makeRequest(method === "GET" ? "/v1/allowance" : "/v1/chat", { method, origin }),
            testEnv,
            ctx,
          );
          await waitOnExecutionContext(ctx);
          expect(res.status).toBe(403);
          const json = (await res.json()) as { error: string; code: string };
          expect(json.error).toBe("bad_origin");
          expect(json.code).toBe("bad_origin");
        }
      }
      expect(rateSpy).not.toHaveBeenCalled();
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("answers OPTIONS preflight for /v1/chat and /v1/allowance from https://editor.materione.com with 204", async () => {
    for (const path of ["/v1/chat", "/v1/allowance"]) {
      const ctx = createExecutionContext();
      const res = await worker.fetch(makeRequest(path, { method: "OPTIONS" }), env, ctx);
      await waitOnExecutionContext(ctx);
      expect(res.status).toBe(204);
      expect(res.headers.get("access-control-allow-origin")).toBe(ALLOWED_ORIGIN);
      expect(res.headers.get("access-control-allow-methods")).toBe("GET, POST, OPTIONS");
      expect(res.headers.get("access-control-allow-headers")).toBe("content-type");
      expect(res.headers.get("access-control-max-age")).toBe("86400");
      expect(res.headers.get("vary")).toBe("Origin");
    }
  });

  it("returns 404 for unknown routes and 405 for wrong methods with CORS headers", async () => {
    const ctx1 = createExecutionContext();
    const r404 = await worker.fetch(makeRequest("/v1/unknown", { method: "GET" }), env, ctx1);
    await waitOnExecutionContext(ctx1);
    expect(r404.status).toBe(404);
    expect(r404.headers.get("access-control-allow-origin")).toBe(ALLOWED_ORIGIN);

    const ctx2 = createExecutionContext();
    const r405 = await worker.fetch(makeRequest("/v1/chat", { method: "GET" }), env, ctx2);
    await waitOnExecutionContext(ctx2);
    expect(r405.status).toBe(405);
    expect(r405.headers.get("access-control-allow-origin")).toBe(ALLOWED_ORIGIN);
  });

  it("fails closed with 503 when proxy config is missing and 400 when CF-Connecting-IP is missing", async () => {
    const ctx1 = createExecutionContext();
    const r503 = await worker.fetch(
      makeRequest("/v1/allowance", { method: "GET" }),
      { ...env, IP_HASH_SALT: "short" },
      ctx1,
    );
    await waitOnExecutionContext(ctx1);
    expect(r503.status).toBe(503);
    expect(((await r503.json()) as { code: string }).code).toBe("unavailable");

    const ctx2 = createExecutionContext();
    const r400 = await worker.fetch(
      makeRequest("/v1/allowance", { method: "GET", ip: null }),
      env,
      ctx2,
    );
    await waitOnExecutionContext(ctx2);
    expect(r400.status).toBe(400);
    expect(((await r400.json()) as { code: string }).code).toBe("unauthenticated");
  });

  it("rejects extra top-level fields, disallowed tools, and disallowed content blocks with 400", async () => {
    const disallowedTopLevel = ["model", "max_tokens", "api_key", "stream", "temperature", "thinking"];
    for (const key of disallowedTopLevel) {
      const ctx = createExecutionContext();
      const res = await worker.fetch(
        makeRequest("/v1/chat", { body: JSON.stringify({ ...VALID_BODY, [key]: "bad" }) }),
        env,
        ctx,
      );
      await waitOnExecutionContext(ctx);
      expect(res.status).toBe(400);
      expect(((await res.json()) as { error: string }).error).toBe("invalid_request");
    }

      const badToolCtx = createExecutionContext();
    const badToolRes = await worker.fetch(
      makeRequest("/v1/chat", {
        ip: "203.0.113.21",
        body: JSON.stringify({
          ...VALID_BODY,
          tools: [{ name: "write_file", description: "bad", input_schema: { type: "object" } }],
        }),
      }),
      env,
      badToolCtx,
    );
    await waitOnExecutionContext(badToolCtx);
    expect(badToolRes.status).toBe(400);

    const tooManyToolsCtx = createExecutionContext();
    const tooManyToolsRes = await worker.fetch(
      makeRequest("/v1/chat", {
        ip: "203.0.113.22",
        body: JSON.stringify({
          ...VALID_BODY,
          tools: [VALID_BODY.tools[0], VALID_BODY.tools[0]],
        }),
      }),
      env,
      tooManyToolsCtx,
    );
    await waitOnExecutionContext(tooManyToolsCtx);
    expect(tooManyToolsRes.status).toBe(400);

    const badBlockCtx = createExecutionContext();
    const badBlockRes = await worker.fetch(
      makeRequest("/v1/chat", {
        ip: "203.0.113.23",
        body: JSON.stringify({
          ...VALID_BODY,
          messages: [{ role: "user", content: [{ type: "image", source: {} }] }],
        }),
      }),
      env,
      badBlockCtx,
    );
    await waitOnExecutionContext(badBlockCtx);
    expect(badBlockRes.status).toBe(400);
  });

  it("rejects body > 512 KiB and estimated input > 80,000 tokens with 413 without debiting D1 or calling upstream", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    try {
      const oversizedBody = JSON.stringify({
        ...VALID_BODY,
        system: "x".repeat(MAX_BODY_BYTES + 100),
      });
      const ctx1 = createExecutionContext();
      const res1 = await worker.fetch(makeRequest("/v1/chat", { ip: "203.0.113.31", body: oversizedBody }), env, ctx1);
      await waitOnExecutionContext(ctx1);
      expect(res1.status).toBe(413);
      expect(((await res1.json()) as { code: string }).code).toBe("too_large");

      // ~300 KB (< 512 KiB body limit) but > 80,000 estimated tokens (300,000 / 3.5 > 85,000)
      const tooManyTokensBody = JSON.stringify({
        ...VALID_BODY,
        system: "a".repeat(300_000),
      });
      const ctx2 = createExecutionContext();
      const res2 = await worker.fetch(makeRequest("/v1/chat", { ip: "203.0.113.32", body: tooManyTokensBody }), env, ctx2);
      await waitOnExecutionContext(ctx2);
      expect(res2.status).toBe(413);
      expect(((await res2.json()) as { code: string }).code).toBe("too_large");

      expect(fetchSpy).not.toHaveBeenCalled();
      const rows = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM reservations").first<{ cnt: number }>();
      expect(rows?.cnt).toBe(0);
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("enforces RATE_LIMITER and returns 429 rate_limited with Retry-After: 60 on the 11th request", async () => {
    const counts = new Map<string, number>();
    const testEnv = {
      ...env,
      RATE_LIMITER: {
        limit: async ({ key }: { key: string }) => {
          const next = (counts.get(key) ?? 0) + 1;
          counts.set(key, next);
          return { success: next <= 10 };
        },
      } as unknown as RateLimit,
    };

    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const sse =
        `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 10, output_tokens: 1 } } })}\n\n` +
        `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 5 } })}\n\n` +
        `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`;
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    try {
      for (let i = 0; i < 10; i++) {
        const ctx = createExecutionContext();
        const res = await worker.fetch(makeRequest("/v1/chat"), testEnv, ctx);
        await res.text();
        await waitOnExecutionContext(ctx);
        expect(res.status).toBe(200);
      }

      const ctx11 = createExecutionContext();
      const res11 = await worker.fetch(makeRequest("/v1/chat"), testEnv, ctx11);
      await waitOnExecutionContext(ctx11);
      expect(res11.status).toBe(429);
      expect(res11.headers.get("retry-after")).toBe("60");
      const json11 = (await res11.json()) as { error: string; code: string; retryAfterSeconds: number };
      expect(json11.error).toBe("rate_limited");
      expect(json11.code).toBe("rate_limited");
      expect(json11.retryAfterSeconds).toBe(60);
    } finally {
      fetchSpy.mockRestore();
    }
  });
});
