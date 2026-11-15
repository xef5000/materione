import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ALLOWED_ORIGIN, DEFAULT_CALLER_DAILY_CAP_PICOS, MAX_OUTPUT_TOKENS } from "../src/config";
import worker from "../src/index";
import { computeWorstCaseReservationPicos, getDefaultModel } from "../src/models";
import {
  CANONICAL_RUN_PROGRAM_TOOL,
  SDK_INDEX_HEADER,
  SERVER_SYSTEM_CHARTER,
} from "../src/prompt";
import { validateChatRequest } from "../src/validate";

const VALID_BODY = {
  system: `You are the Materione assistant, inside the Materione web editor.\n\n${SDK_INDEX_HEADER}\n\n## Content types (1)\n- item`,
  tools: [
    {
      name: "run_program",
      description: "Client-supplied description that must be replaced by canonical tool",
      inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] },
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

    // Verify upstream body uses immutable server-owned charter + wrapped SDK index + canonical tool
    expect(capturedBody?.model).toBe("claude-haiku-5-5");
    expect(capturedBody?.max_tokens).toBe(MAX_OUTPUT_TOKENS);
    expect(capturedBody?.stream).toBe(true);
    expect(capturedBody?.system).toEqual([
      { type: "text", text: SERVER_SYSTEM_CHARTER },
      {
        type: "text",
        text: `<untrusted_server_sdk_index>\n${SDK_INDEX_HEADER}\n\n## Content types (1)\n- item\n</untrusted_server_sdk_index>`,
        cache_control: { type: "ephemeral" },
      },
    ]);
    expect(capturedBody?.tools).toEqual([CANONICAL_RUN_PROGRAM_TOOL]);

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
    const validation = await validateChatRequest(chatRequest("198.51.100.88"), model);
    expect(validation.ok).toBe(true);
    if (!validation.ok) throw new Error("unexpected");
    const oneReservation = computeWorstCaseReservationPicos(
      model,
      validation.value.upperBoundInputTokens,
      MAX_OUTPUT_TOKENS,
    );
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
    const validation = await validateChatRequest(chatRequest("198.51.100.1"), model);
    expect(validation.ok).toBe(true);
    if (!validation.ok) throw new Error("unexpected");
    const oneReservation = computeWorstCaseReservationPicos(
      model,
      validation.value.upperBoundInputTokens,
      MAX_OUTPUT_TOKENS,
    );
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

  it("never under-reserves on a 1-byte-per-token dense ASCII payload at cache_creation pricing", async () => {
    const denseBody = {
      ...VALID_BODY,
      messages: [{ role: "user", content: ",1{}[]();:".repeat(2_000) }],
    };
    const model = getDefaultModel();
    const validation = await validateChatRequest(chatRequest("198.51.100.90", denseBody), model);
    expect(validation.ok).toBe(true);
    if (!validation.ok) throw new Error("unexpected");

    const upstreamBytes = new TextEncoder().encode(validation.value.upstreamBodyJson).byteLength;
    const worstCaseTokens = upstreamBytes + 500;
    const exactReservation = computeWorstCaseReservationPicos(
      model,
      validation.value.upperBoundInputTokens,
      MAX_OUTPUT_TOKENS,
    );

    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const sse =
        `event: message_start\ndata: ${JSON.stringify({
          type: "message_start",
          message: {
            usage: {
              input_tokens: 0,
              cache_creation_input_tokens: worstCaseTokens,
              output_tokens: 1,
            },
          },
        })}\n\n` +
        `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: MAX_OUTPUT_TOKENS } })}\n\n` +
        `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`;
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const callerCap = exactReservation;
    const ctx = createExecutionContext();
    const res = await worker.fetch(
      chatRequest("198.51.100.90", denseBody),
      { ...env, CALLER_DAILY_CAP_PICOS: String(callerCap) },
      ctx,
    );
    await res.text();
    await waitOnExecutionContext(ctx);
    expect(res.status).toBe(200);

    const row = await env.DB.prepare("SELECT reserved_picos, settled_picos FROM reservations LIMIT 1").first<{
      reserved_picos: number;
      settled_picos: number;
    }>();
    expect(row?.settled_picos).toBeLessThanOrEqual(row?.reserved_picos ?? 0);

    const usageRow = await env.DB.prepare("SELECT used_picos FROM daily_caller_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(usageRow?.used_picos).toBeLessThanOrEqual(callerCap);
  });

  it("handles concurrent interleaved multi-byte UTF-8 streams without decoder corruption", async () => {
    const encoder = new TextEncoder();
    const makeSplitStream = (text: string, inputTokens: number, outputTokens: number) => {
      const sse =
        `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: inputTokens, output_tokens: 1 } } })}\n\n` +
        `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text } })}\n\n` +
        `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: outputTokens } })}\n\n` +
        `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`;
      const bytes = encoder.encode(sse);
      let offset = 0;
      return new ReadableStream<Uint8Array>({
        async pull(controller) {
          if (offset >= bytes.length) {
            controller.close();
            return;
          }
          // Split 2 bytes at a time to slice right through 3-byte and 4-byte UTF-8 sequences
          controller.enqueue(bytes.slice(offset, offset + 2));
          offset += 2;
          await new Promise((r) => setTimeout(r, 1));
        },
      });
    };

    let callIdx = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      callIdx += 1;
      const stream =
        callIdx === 1
          ? makeSplitStream("🗡️—日本語テスト", 111, 22)
          : makeSplitStream("日本語—🗡️テスト2", 333, 44);
      return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const [r1, r2] = await Promise.all([
      (async () => {
        const ctx = createExecutionContext();
        const res = await worker.fetch(chatRequest("198.51.100.91"), env, ctx);
        const txt = await res.text();
        await waitOnExecutionContext(ctx);
        return txt;
      })(),
      (async () => {
        const ctx = createExecutionContext();
        const res = await worker.fetch(chatRequest("198.51.100.92"), env, ctx);
        const txt = await res.text();
        await waitOnExecutionContext(ctx);
        return txt;
      })(),
    ]);

    expect(r1).toContain("🗡️—日本語テスト");
    expect(r1).toContain("event: materione_settlement");
    expect(r2).toContain("日本語—🗡️テスト2");
    expect(r2).toContain("event: materione_settlement");

    const rows = await env.DB.prepare("SELECT status, input_tokens, output_tokens FROM reservations ORDER BY input_tokens ASC").all<{
      status: string;
      input_tokens: number;
      output_tokens: number;
    }>();
    expect(rows.results).toEqual([
      { status: "settled", input_tokens: 111, output_tokens: 22 },
      { status: "settled", input_tokens: 333, output_tokens: 44 },
    ]);
  });

  it("parses CRLF-delimited SSE frames split 1 byte per chunk and settles exact picodollars", async () => {
    const encoder = new TextEncoder();
    const sse =
      `event: message_start\r\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 200, output_tokens: 1 } } })}\r\n\r\n` +
      `event: message_delta\r\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 30 } })}\r\n\r\n` +
      `event: message_stop\r\ndata: ${JSON.stringify({ type: "message_stop" })}\r\n\r\n`;

    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const bytes = encoder.encode(sse);
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          for (let i = 0; i < bytes.length; i++) {
            controller.enqueue(bytes.slice(i, i + 1));
          }
          controller.close();
        },
      });
      return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctx = createExecutionContext();
    const res = await worker.fetch(chatRequest("198.51.100.93"), env, ctx);
    const text = await res.text();
    await waitOnExecutionContext(ctx);

    expect(text).toContain("event: materione_settlement");
    const expected = 200 * 100_000 + 30 * 500_000;
    const row = await env.DB.prepare("SELECT status, settled_picos FROM reservations LIMIT 1").first<{
      status: string;
      settled_picos: number;
    }>();
    expect(row?.status).toBe("settled");
    expect(row?.settled_picos).toBe(expected);
  });

  it("settles exact usage if interrupted after message_delta, bounds upstream interrupt before message_delta, and charges MAX_OUTPUT_TOKENS on client abort", async () => {
    const encoder = new TextEncoder();

    // Case A: upstream error AFTER message_delta reported final output_tokens
    vi.spyOn(globalThis, "fetch").mockImplementationOnce(async () => {
      const sse =
        `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 150, output_tokens: 1 } } })}\n\n` +
        `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 40 } })}\n\n` +
        `event: error\ndata: ${JSON.stringify({ type: "error", error: { message: "late" } })}\n\n`;
      return new Response(encoder.encode(sse), { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctxA = createExecutionContext();
    const resA = await worker.fetch(chatRequest("198.51.100.94"), env, ctxA);
    const textA = await resA.text();
    await waitOnExecutionContext(ctxA);
    expect(textA).not.toContain("event: materione_settlement");

    const rowA = await env.DB.prepare("SELECT status, input_tokens, output_tokens FROM reservations LIMIT 1").first<{
      status: string;
      input_tokens: number;
      output_tokens: number;
    }>();
    expect(rowA).toEqual({ status: "settled", input_tokens: 150, output_tokens: 40 });

    await env.DB.batch([
      env.DB.prepare("DELETE FROM reservations"),
      env.DB.prepare("DELETE FROM daily_caller_usage"),
      env.DB.prepare("DELETE FROM daily_pool_usage"),
    ]);

    // Case B: upstream EOF/error AFTER message_start (150 input) + 10 bytes of delta, BEFORE message_delta
    vi.spyOn(globalThis, "fetch").mockImplementationOnce(async () => {
      const sse =
        `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 150, output_tokens: 1 } } })}\n\n` +
        `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text: "0123456789" } })}\n\n` +
        `event: error\ndata: ${JSON.stringify({ type: "error", error: { message: "midstream" } })}\n\n`;
      return new Response(encoder.encode(sse), { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctxB = createExecutionContext();
    const resB = await worker.fetch(chatRequest("198.51.100.95"), env, ctxB);
    await resB.text();
    await waitOnExecutionContext(ctxB);

    const rowB = await env.DB.prepare("SELECT status, input_tokens, output_tokens FROM reservations LIMIT 1").first<{
      status: string;
      input_tokens: number;
      output_tokens: number;
    }>();
    expect(rowB).toEqual({ status: "settled", input_tokens: 150, output_tokens: 10 + 64 });

    await env.DB.batch([
      env.DB.prepare("DELETE FROM reservations"),
      env.DB.prepare("DELETE FROM daily_caller_usage"),
      env.DB.prepare("DELETE FROM daily_pool_usage"),
    ]);

    // Case C: client cancel() AFTER message_start, BEFORE message_delta -> MAX_OUTPUT_TOKENS charged & upstream aborted
    let upstreamAborted = false;
    vi.spyOn(globalThis, "fetch").mockImplementationOnce(async (_input, init) => {
      init?.signal?.addEventListener("abort", () => {
        upstreamAborted = true;
      });
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(
            encoder.encode(
              `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 150, output_tokens: 1 } } })}\n\n`,
            ),
          );
          controller.enqueue(
            encoder.encode(
              `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text: "partial" } })}\n\n`,
            ),
          );
        },
      });
      return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctxC = createExecutionContext();
    const resC = await worker.fetch(chatRequest("198.51.100.96"), env, ctxC);
    const reader = resC.body!.getReader();
    await reader.read();
    await reader.cancel();
    await waitOnExecutionContext(ctxC);

    expect(upstreamAborted).toBe(true);
    const rowC = await env.DB.prepare("SELECT status, input_tokens, output_tokens FROM reservations LIMIT 1").first<{
      status: string;
      input_tokens: number;
      output_tokens: number;
    }>();
    expect(rowC).toEqual({ status: "settled", input_tokens: 150, output_tokens: MAX_OUTPUT_TOKENS });
  });
});
