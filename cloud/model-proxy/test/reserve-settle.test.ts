import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ALLOWED_ORIGIN, DEFAULT_CALLER_DAILY_CAP_PICOS, MAX_OUTPUT_TOKENS } from "../src/config";
import worker from "../src/index";
import { computeWorstCaseReservationPicos, getDefaultModel } from "../src/models";

const VALID_BODY = {
  system: "You are a Materione authoring assistant.",
  tools: [
    {
      name: "run_program",
      description: "Execute a TypeScript program in the sandbox",
      inputSchema: { type: "object", properties: { code: { type: "string" } }, required: ["code"] },
    },
  ],
  messages: [{ role: "user", content: "Create a ruby sword." }],
};

function chatRequest(ip = "198.51.100.7", body: unknown = VALID_BODY): Request {
  return new Request("https://ai.materione.com/v1/chat", {
    method: "POST",
    headers: {
      origin: ALLOWED_ORIGIN,
      "cf-connecting-ip": ip,
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

function allowanceRequest(ip = "198.51.100.7"): Request {
  return new Request("https://ai.materione.com/v1/allowance", {
    method: "GET",
    headers: {
      origin: ALLOWED_ORIGIN,
      "cf-connecting-ip": ip,
    },
  });
}

describe("atomic reserve, SSE settlement, and concurrency", () => {
  beforeEach(async () => {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM reservations"),
      env.DB.prepare("DELETE FROM daily_caller_usage"),
      env.DB.prepare("DELETE FROM daily_pool_usage"),
    ]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("streams multi-chunk SSE, injects model/cache_control/gateway headers, and settles exact picodollars", async () => {
    let capturedHeaders: Headers | undefined;
    let capturedBody: Record<string, unknown> | undefined;

    const encoder = new TextEncoder();
    const ssePayload =
      `event: message_start\ndata: ${JSON.stringify({
        type: "message_start",
        message: {
          usage: {
            input_tokens: 1200,
            cache_read_input_tokens: 5000,
            cache_creation_input_tokens: 800,
            output_tokens: 1,
          },
        },
      })}\n\n` +
      `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text: "Hello" } })}\n\n` +
      `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 250 } })}\n\n` +
      `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`;

    vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
      capturedHeaders = new Headers(init?.headers);
      capturedBody = JSON.parse(String(init?.body)) as Record<string, unknown>;

      // Split into small odd-sized chunks to test cross-chunk SSE parsing.
      const bytes = encoder.encode(ssePayload);
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          for (let i = 0; i < bytes.length; i += 17) {
            controller.enqueue(bytes.slice(i, i + 17));
          }
          controller.close();
        },
      });
      return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    // Check initial allowance
    const ctx0 = createExecutionContext();
    const allowBeforeRes = await worker.fetch(allowanceRequest(), env, ctx0);
    await waitOnExecutionContext(ctx0);
    expect(allowBeforeRes.status).toBe(200);
    const allowBefore = (await allowBeforeRes.json()) as { remainingPicos: number; poolOpen: boolean };
    expect(allowBefore.remainingPicos).toBe(DEFAULT_CALLER_DAILY_CAP_PICOS);
    expect(allowBefore.poolOpen).toBe(true);

    const ctx1 = createExecutionContext();
    const chatRes = await worker.fetch(chatRequest(), env, ctx1);
    const streamedText = await chatRes.text();
    await waitOnExecutionContext(ctx1);

    expect(chatRes.status).toBe(200);
    expect(chatRes.headers.get("content-type")).toContain("text/event-stream");
    expect(chatRes.headers.get("access-control-allow-origin")).toBe(ALLOWED_ORIGIN);

    // Verify upstream headers
    expect(capturedHeaders?.get("cf-aig-authorization")).toBe(`Bearer ${env.CF_AIG_TOKEN}`);
    expect(capturedHeaders?.get("cf-aig-collect-log-payload")).toBe("false");
    expect(capturedHeaders?.get("cf-aig-skip-cache")).toBe("true");
    expect(capturedHeaders?.get("anthropic-version")).toBe("2023-06-01");
    expect(capturedHeaders?.get("x-api-key")).toBeNull();

    // Verify upstream body
    expect(capturedBody?.model).toBe("claude-haiku-5-5");
    expect(capturedBody?.max_tokens).toBe(MAX_OUTPUT_TOKENS);
    expect(capturedBody?.stream).toBe(true);
    expect(capturedBody?.system).toEqual([
      { type: "text", text: VALID_BODY.system, cache_control: { type: "ephemeral" } },
    ]);
    expect(Array.isArray(capturedBody?.tools)).toBe(true);
    const tools = capturedBody?.tools as Array<Record<string, unknown>>;
    expect(tools[0]?.cache_control).toEqual({ type: "ephemeral" });

    // Exact settled picos:
    // 1200 * 100_000 + 5000 * 10_000 + 800 * 125_000 + 250 * 500_000
    // = 120_000_000 + 50_000_000 + 100_000_000 + 125_000_000 = 395_000_000
    const expectedSettled = 395_000_000;
    expect(streamedText).toContain("event: materione_settlement");
    expect(streamedText).toContain(`"settledPicos":${expectedSettled}`);

    const ctx2 = createExecutionContext();
    const allowAfterRes = await worker.fetch(allowanceRequest(), env, ctx2);
    await waitOnExecutionContext(ctx2);
    const allowAfter = (await allowAfterRes.json()) as { remainingPicos: number };
    expect(allowAfter.remainingPicos).toBe(DEFAULT_CALLER_DAILY_CAP_PICOS - expectedSettled);

    const resRow = await env.DB.prepare("SELECT status, settled_picos FROM reservations LIMIT 1").first<{
      status: string;
      settled_picos: number;
    }>();
    expect(resRow?.status).toBe("settled");
    expect(resRow?.settled_picos).toBe(expectedSettled);
  });

  it("keeps the reservation when the upstream stream errors out without completing", async () => {
    const encoder = new TextEncoder();
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(
            encoder.encode(
              `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text: "partial" } })}\n\n`,
            ),
          );
          controller.enqueue(
            encoder.encode(`event: error\ndata: ${JSON.stringify({ type: "error", error: { message: "boom" } })}\n\n`),
          );
          controller.close();
        },
      });
      return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctx = createExecutionContext();
    const res = await worker.fetch(chatRequest(), env, ctx);
    const text = await res.text();
    await waitOnExecutionContext(ctx);

    expect(res.status).toBe(200);
    expect(text).not.toContain("event: materione_settlement");

    const row = await env.DB.prepare(
      "SELECT status, reserved_picos, settled_picos FROM reservations LIMIT 1",
    ).first<{ status: string; reserved_picos: number; settled_picos: number }>();
    expect(row?.status).toBe("kept");
    expect(row?.settled_picos).toBe(row?.reserved_picos);

    const usageRow = await env.DB.prepare("SELECT used_picos FROM daily_caller_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(usageRow?.used_picos).toBe(row?.reserved_picos);
  });

  it("settles reservation to 0 and returns 503 unavailable when upstream returns pre-stream 429", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      return new Response(JSON.stringify({ error: "gateway spend limit" }), { status: 429 });
    });

    const ctx = createExecutionContext();
    const res = await worker.fetch(chatRequest(), env, ctx);
    await waitOnExecutionContext(ctx);

    expect(res.status).toBe(503);
    expect(((await res.json()) as { code: string }).code).toBe("unavailable");

    const row = await env.DB.prepare("SELECT status, settled_picos FROM reservations LIMIT 1").first<{
      status: string;
      settled_picos: number;
    }>();
    expect(row?.status).toBe("settled");
    expect(row?.settled_picos).toBe(0);

    const usageRow = await env.DB.prepare("SELECT used_picos FROM daily_caller_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(usageRow?.used_picos).toBe(0);
  });

  it("keeps the reservation when upstream returns an ambiguous 502 gateway error", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      return new Response("Bad Gateway", { status: 502 });
    });

    const ctx = createExecutionContext();
    const res = await worker.fetch(chatRequest(), env, ctx);
    await waitOnExecutionContext(ctx);

    expect(res.status).toBe(503);
    expect(((await res.json()) as { code: string }).code).toBe("unavailable");

    const row = await env.DB.prepare(
      "SELECT status, reserved_picos, settled_picos FROM reservations LIMIT 1",
    ).first<{ status: string; reserved_picos: number; settled_picos: number }>();
    expect(row?.status).toBe("kept");
    expect(row?.settled_picos).toBe(row?.reserved_picos);
  });

  it("prevents caller overdraw under 10 concurrent requests from the same IP", async () => {
    const model = getDefaultModel();
    // Compute exact reservation for VALID_BODY
    const utf8Bytes = new TextEncoder().encode(
      VALID_BODY.system +
        JSON.stringify([{ name: "run_program", description: VALID_BODY.tools[0]!.description, input_schema: VALID_BODY.tools[0]!.inputSchema }]) +
        JSON.stringify(VALID_BODY.messages),
    ).byteLength;
    const estTokens = Math.ceil(utf8Bytes / 3.5) + 256;
    const oneReservation = computeWorstCaseReservationPicos(model, estTokens, MAX_OUTPUT_TOKENS);
    const maxAllowedRequests = 3;
    const callerCap = oneReservation * maxAllowedRequests + 123;

    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const sse =
        `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 100, output_tokens: 1 } } })}\n\n` +
        `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 50 } })}\n\n` +
        `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`;
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const testEnv = {
      ...env,
      CALLER_DAILY_CAP_PICOS: String(callerCap),
    };

    const responses = await Promise.all(
      Array.from({ length: 10 }, async () => {
        const ctx = createExecutionContext();
        const res = await worker.fetch(chatRequest("198.51.100.88"), testEnv, ctx);
        await res.text();
        await waitOnExecutionContext(ctx);
        return res;
      }),
    );

    const okCount = responses.filter((r) => r.status === 200).length;
    const limitedCount = responses.filter((r) => r.status === 429).length;
    expect(okCount).toBeLessThanOrEqual(maxAllowedRequests);
    expect(okCount).toBeGreaterThanOrEqual(1);
    expect(okCount + limitedCount).toBe(10);

    const usageRow = await env.DB.prepare("SELECT used_picos FROM daily_caller_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(usageRow?.used_picos ?? 0).toBeLessThanOrEqual(callerCap);
  });

  it("prevents global pool overdraw under 10 concurrent requests from 10 different IPs", async () => {
    const model = getDefaultModel();
    const utf8Bytes = new TextEncoder().encode(
      VALID_BODY.system +
        JSON.stringify([{ name: "run_program", description: VALID_BODY.tools[0]!.description, input_schema: VALID_BODY.tools[0]!.inputSchema }]) +
        JSON.stringify(VALID_BODY.messages),
    ).byteLength;
    const estTokens = Math.ceil(utf8Bytes / 3.5) + 256;
    const oneReservation = computeWorstCaseReservationPicos(model, estTokens, MAX_OUTPUT_TOKENS);
    const maxPoolRequests = 2;
    const poolCap = oneReservation * maxPoolRequests + 50;

    // Settle to a high amount or keep stream open slightly so reservations hold during race
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 25));
      const sse =
        `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 100, output_tokens: 1 } } })}\n\n` +
        `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 4000 } })}\n\n` +
        `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`;
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const testEnv = {
      ...env,
      GLOBAL_DAILY_POOL_PICOS: String(poolCap),
    };

    const responses = await Promise.all(
      Array.from({ length: 10 }, async (_, idx) => {
        const ctx = createExecutionContext();
        const res = await worker.fetch(chatRequest(`198.51.100.${idx + 1}`), testEnv, ctx);
        const body = await res.text();
        await waitOnExecutionContext(ctx);
        return { status: res.status, body };
      }),
    );

    const okCount = responses.filter((r) => r.status === 200).length;
    const poolExhausted = responses.filter((r) => r.status === 429 && r.body.includes("pool_exhausted")).length;
    expect(okCount).toBeLessThanOrEqual(maxPoolRequests);
    expect(okCount).toBeGreaterThanOrEqual(1);
    expect(okCount + poolExhausted).toBe(10);

    const poolRow = await env.DB.prepare("SELECT used_picos FROM daily_pool_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(poolRow?.used_picos ?? 0).toBeLessThanOrEqual(poolCap);

    // Exhaust pool completely with a tiny cap check for poolOpen: false
    const ctxAllow = createExecutionContext();
    const allowRes = await worker.fetch(
      allowanceRequest("198.51.100.250"),
      { ...env, GLOBAL_DAILY_POOL_PICOS: String(poolRow?.used_picos ?? 0) },
      ctxAllow,
    );
    await waitOnExecutionContext(ctxAllow);
    const allowJson = (await allowRes.json()) as { poolOpen: boolean };
    expect(allowJson.poolOpen).toBe(false);
  });
});
