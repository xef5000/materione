import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ALLOWED_ORIGIN,
  DEFAULT_CALLER_DAILY_CAP_PICOS,
  MAX_OUTPUT_TOKENS,
  PICOS_PER_CREDIT,
} from "../src/config";
import worker from "../src/index";
import { computeWorstCaseReservationPicos, getDefaultModel } from "../src/models";
import {
  CANONICAL_RUN_PROGRAM_TOOL,
  SDK_INDEX_HEADER,
  SERVER_SYSTEM_CHARTER,
} from "../src/prompt";
import { validateChatRequest } from "../src/validate";
import { clearAllTables, completedResponseSse, createTestAccountSession, responseEvent } from "./helpers";

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

function chatRequest(cookie: string, ip = "198.51.100.7", body: unknown = VALID_BODY): Request {
  return new Request("https://ai.materione.com/v1/chat", {
    method: "POST",
    headers: {
      origin: ALLOWED_ORIGIN,
      "cf-connecting-ip": ip,
      "content-type": "application/json",
      cookie,
    },
    body: JSON.stringify(body),
  });
}

function allowanceRequest(cookie: string, ip = "198.51.100.7"): Request {
  return new Request("https://ai.materione.com/v1/allowance", {
    method: "GET",
    headers: {
      origin: ALLOWED_ORIGIN,
      "cf-connecting-ip": ip,
      cookie,
    },
  });
}

describe("two-bucket atomic reserve, SSE settlement, Sybil gate, and concurrency", () => {
  beforeEach(async () => {
    await clearAllTables(env.DB);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("streams multi-chunk Responses SSE, locks model and tools, and settles exact picodollars", async () => {
    const sess = await createTestAccountSession(env.DB, {
      accountId: "acct-free-1",
      creditBalancePicos: 100 * PICOS_PER_CREDIT,
    });

    let capturedHeaders: Headers | undefined;
    let capturedBody: Record<string, unknown> | undefined;

    const encoder = new TextEncoder();
    const ssePayload = completedResponseSse({ input: 1200, cached: 5000, cacheWrite: 800, output: 250, text: "Hello" });

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
    const allowBeforeRes = await worker.fetch(allowanceRequest(sess.cookieHeader), env, ctx0);
    await waitOnExecutionContext(ctx0);
    expect(allowBeforeRes.status).toBe(200);
    const allowBefore = (await allowBeforeRes.json()) as {
      remainingPicos: number;
      poolOpen: boolean;
      credits: { balancePicos: number; balanceCredits: number };
      freeAllowance: { remainingPicos: number; eligible: boolean };
    };
    expect(allowBefore.remainingPicos).toBe(DEFAULT_CALLER_DAILY_CAP_PICOS);
    expect(allowBefore.poolOpen).toBe(true);
    expect(allowBefore.credits.balanceCredits).toBe(100);
    expect(allowBefore.freeAllowance.eligible).toBe(true);

    const ctx1 = createExecutionContext();
    const chatRes = await worker.fetch(chatRequest(sess.cookieHeader), env, ctx1);
    const streamedText = await chatRes.text();
    await waitOnExecutionContext(ctx1);

    expect(chatRes.status).toBe(200);
    expect(chatRes.headers.get("content-type")).toContain("text/event-stream");
    expect(chatRes.headers.get("access-control-allow-origin")).toBe(ALLOWED_ORIGIN);
    expect(chatRes.headers.get("access-control-allow-credentials")).toBe("true");

    // Verify upstream headers
    expect(capturedHeaders?.get("cf-aig-authorization")).toBe(`Bearer ${env.CF_AIG_TOKEN}`);
    expect(capturedHeaders?.get("cf-aig-collect-log-payload")).toBe("false");
    expect(capturedHeaders?.get("cf-aig-skip-cache")).toBe("true");
    expect(capturedHeaders?.get("anthropic-version")).toBeNull();
    expect(capturedHeaders?.get("x-api-key")).toBeNull();

    // Verify upstream body uses immutable server-owned charter + wrapped SDK index + canonical tool
    expect(capturedBody?.model).toBe("gpt-6-luna");
    expect(capturedBody?.max_output_tokens).toBe(MAX_OUTPUT_TOKENS);
    expect(capturedBody?.stream).toBe(true);
    expect(capturedBody?.store).toBe(false);
    expect(capturedBody?.reasoning).toEqual({ effort: "medium" });
    expect(capturedBody?.prompt_cache_options).toEqual({ ttl: "30m" });
    expect(capturedBody?.include).toEqual(["reasoning.encrypted_content"]);
    expect(capturedBody?.instructions).toBe(`${SERVER_SYSTEM_CHARTER}\n\n<untrusted_server_sdk_index>\n${SDK_INDEX_HEADER}\n\n## Content types (1)\n- item\n</untrusted_server_sdk_index>`);
    expect(capturedBody?.tools).toEqual([{
      type: "function",
      name: CANONICAL_RUN_PROGRAM_TOOL.name,
      description: CANONICAL_RUN_PROGRAM_TOOL.description,
      parameters: CANONICAL_RUN_PROGRAM_TOOL.input_schema,
      strict: true,
    }]);

    // Exact settled picos:
    // 1200 * 100_000 + 5000 * 10_000 + 800 * 125_000 + 250 * 500_000
    // = 120_000_000 + 50_000_000 + 100_000_000 + 125_000_000 = 395_000_000
    const expectedSettled = 395_000_000;
    expect(streamedText).toContain("event: materione_settlement");
    expect(streamedText).toContain(`"settledPicos":${expectedSettled}`);
    expect(streamedText).toContain(`"freeSettledPicos":${expectedSettled}`);
    expect(streamedText).toContain(`"paidSettledPicos":0`);

    const ctx2 = createExecutionContext();
    const allowAfterRes = await worker.fetch(allowanceRequest(sess.cookieHeader), env, ctx2);
    await waitOnExecutionContext(ctx2);
    const allowAfter = (await allowAfterRes.json()) as {
      remainingPicos: number;
      credits: { balancePicos: number };
    };
    expect(allowAfter.remainingPicos).toBe(DEFAULT_CALLER_DAILY_CAP_PICOS - expectedSettled);
    // Paid balance untouched on free-only turn
    expect(allowAfter.credits.balancePicos).toBe(100 * PICOS_PER_CREDIT);

    const resRow = await env.DB.prepare("SELECT status, settled_picos FROM reservations LIMIT 1").first<{
      status: string;
      settled_picos: number;
    }>();
    expect(resRow?.status).toBe("settled");
    expect(resRow?.settled_picos).toBe(expectedSettled);
  });

  it("handles straddling turns (free + paid) and settles free-first, paid-second with exact refunds to both buckets", async () => {
    const model = getDefaultModel();
    const validation = await validateChatRequest(chatRequest("c=1", "198.51.100.15"), model);
    expect(validation.ok).toBe(true);
    if (!validation.ok) throw new Error("unexpected");
    const oneReservation = computeWorstCaseReservationPicos(
      model,
      validation.value.upperBoundInputTokens,
      MAX_OUTPUT_TOKENS,
    );

    // Case 1: settle exceeds free_reserved_picos (straddles into paid credits)
    const remainingFree1 = 100_000_000; // 100M picos left
    const startingPaid = 10_000_000_000;
    const sess1 = await createTestAccountSession(env.DB, {
      accountId: "acct-straddle-1",
      creditBalancePicos: startingPaid,
    });

    // Actual usage: 200 in (20M) + 400 out (200M) = 220M picos -> 100M free + 120M paid!
    vi.spyOn(globalThis, "fetch").mockImplementationOnce(async () => {
      const sse = completedResponseSse({ input: 200, output: 400 });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctx1 = createExecutionContext();
    const r1 = await worker.fetch(
      chatRequest(sess1.cookieHeader, "198.51.100.15"),
      { ...env, CALLER_DAILY_CAP_PICOS: String(remainingFree1) },
      ctx1,
    );
    const t1 = await r1.text();
    await waitOnExecutionContext(ctx1);
    expect(r1.status).toBe(200);
    expect(t1).toContain(`"settledPicos":220000000`);
    expect(t1).toContain(`"freeSettledPicos":100000000`);
    expect(t1).toContain(`"paidSettledPicos":120000000`);

    const acct1 = await env.DB
      .prepare("SELECT credit_balance_picos FROM accounts WHERE id = 'acct-straddle-1'")
      .first<{ credit_balance_picos: number }>();
    expect(acct1?.credit_balance_picos).toBe(startingPaid - 120_000_000);

    const free1 = await env.DB
      .prepare("SELECT used_picos FROM daily_account_free_usage WHERE account_id = 'acct-straddle-1'")
      .first<{ used_picos: number }>();
    expect(free1?.used_picos).toBe(100_000_000);

    // Case 2: straddling reserve where actual settlement is LESS than free_reserved_picos -> full paid reserve refunded + unused free refunded!
    const remainingFree2 = Math.floor(oneReservation / 2);
    const sess2 = await createTestAccountSession(env.DB, {
      accountId: "acct-straddle-2",
      creditBalancePicos: startingPaid,
    });

    // Actual usage: 100 in (10M) + 10 out (5M) = 15M picos (< remainingFree2)
    vi.spyOn(globalThis, "fetch").mockImplementationOnce(async () => {
      const sse = completedResponseSse({ input: 100, output: 10 });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctx2 = createExecutionContext();
    const r2 = await worker.fetch(
      chatRequest(sess2.cookieHeader, "198.51.100.16"),
      { ...env, CALLER_DAILY_CAP_PICOS: String(remainingFree2) },
      ctx2,
    );
    const t2 = await r2.text();
    await waitOnExecutionContext(ctx2);
    expect(r2.status).toBe(200);
    expect(t2).toContain(`"settledPicos":15000000`);
    expect(t2).toContain(`"freeSettledPicos":15000000`);
    expect(t2).toContain(`"paidSettledPicos":0`);

    const acct2 = await env.DB
      .prepare("SELECT credit_balance_picos FROM accounts WHERE id = 'acct-straddle-2'")
      .first<{ credit_balance_picos: number }>();
    expect(acct2?.credit_balance_picos).toBe(startingPaid);

    const freeAcct2 = await env.DB
      .prepare("SELECT used_picos FROM daily_account_free_usage WHERE account_id = 'acct-straddle-2'")
      .first<{ used_picos: number }>();
    expect(freeAcct2?.used_picos).toBe(15_000_000);
  });

  it("enforces dual-gate Sybil defense across two accounts on the same IPv6 /64, while allowing paid-only turns even when pool or subnet is exhausted", async () => {
    const model = getDefaultModel();
    const validation = await validateChatRequest(chatRequest("c=1", "2001:db8:aaaa:1111::1"), model);
    expect(validation.ok).toBe(true);
    if (!validation.ok) throw new Error("unexpected");
    const oneReservation = computeWorstCaseReservationPicos(
      model,
      validation.value.upperBoundInputTokens,
      MAX_OUTPUT_TOKENS,
    );

    const acctA = await createTestAccountSession(env.DB, { accountId: "acct-sybil-a" });
    const acctBFreeOnly = await createTestAccountSession(env.DB, { accountId: "acct-sybil-b" });
    const acctBPaid = await createTestAccountSession(env.DB, {
      accountId: "acct-sybil-c",
      creditBalancePicos: 100 * PICOS_PER_CREDIT,
    });

    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const sse = completedResponseSse({ input: 0, cacheWrite: validation.value.upperBoundInputTokens, output: MAX_OUTPUT_TOKENS });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const tightFreeCap = oneReservation;
    const ctxA = createExecutionContext();
    const resA = await worker.fetch(
      chatRequest(acctA.cookieHeader, "2001:db8:aaaa:1111::1"),
      { ...env, CALLER_DAILY_CAP_PICOS: String(tightFreeCap) },
      ctxA,
    );
    await resA.text();
    await waitOnExecutionContext(ctxA);
    expect(resA.status).toBe(200);

    // Account B on the SAME IPv6 /64 (different suffix!) has 0 free allowance left
    const ctxBAllow = createExecutionContext();
    const resBAllow = await worker.fetch(
      allowanceRequest(acctBFreeOnly.cookieHeader, "2001:db8:aaaa:1111:ffff::2"),
      { ...env, CALLER_DAILY_CAP_PICOS: String(tightFreeCap) },
      ctxBAllow,
    );
    await waitOnExecutionContext(ctxBAllow);
    const allowB = (await resBAllow.json()) as { freeAllowance: { remainingPicos: number } };
    expect(allowB.freeAllowance.remainingPicos).toBe(0);

    const ctxBChat = createExecutionContext();
    const resBChat = await worker.fetch(
      chatRequest(acctBFreeOnly.cookieHeader, "2001:db8:aaaa:1111:ffff::2"),
      { ...env, CALLER_DAILY_CAP_PICOS: String(tightFreeCap) },
      ctxBChat,
    );
    await waitOnExecutionContext(ctxBChat);
    expect(resBChat.status).toBe(429);

    // Account C on the SAME /64 WITH paid credits succeeds even when subnet free allowance AND global pool are 0!
    const ctxCPaid = createExecutionContext();
    const resCPaid = await worker.fetch(
      chatRequest(acctBPaid.cookieHeader, "2001:db8:aaaa:1111:abcd::3"),
      { ...env, GLOBAL_DAILY_POOL_PICOS: "0" },
      ctxCPaid,
    );
    const paidText = await resCPaid.text();
    await waitOnExecutionContext(ctxCPaid);
    expect(resCPaid.status).toBe(200);
    expect(paidText).toContain(`"freeSettledPicos":0`);
    expect(paidText).toContain(`"paidSettledPicos":${oneReservation}`);
  });

  it("keeps the reservation when the upstream stream errors out without completing", async () => {
    const sess = await createTestAccountSession(env.DB);
    const encoder = new TextEncoder();
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(
            encoder.encode(
              responseEvent("response.output_text.delta", { delta: "partial" }),
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
    const res = await worker.fetch(chatRequest(sess.cookieHeader), env, ctx);
    const text = await res.text();
    await waitOnExecutionContext(ctx);

    expect(res.status).toBe(200);
    expect(text).not.toContain("event: materione_settlement");

    const row = await env.DB.prepare(
      "SELECT status, reserved_picos, settled_picos FROM reservations LIMIT 1",
    ).first<{ status: string; reserved_picos: number; settled_picos: number }>();
    expect(row?.status).toBe("kept");
    expect(row?.settled_picos).toBe(row?.reserved_picos);

    const usageRow = await env.DB.prepare("SELECT used_picos FROM daily_account_free_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(usageRow?.used_picos).toBe(row?.reserved_picos);
  });

  it("settles reservation to 0 and returns 503 unavailable when upstream returns pre-stream 429", async () => {
    const sess = await createTestAccountSession(env.DB);
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      return new Response(JSON.stringify({ error: "gateway spend limit" }), { status: 429 });
    });

    const ctx = createExecutionContext();
    const res = await worker.fetch(chatRequest(sess.cookieHeader), env, ctx);
    await waitOnExecutionContext(ctx);

    expect(res.status).toBe(503);
    expect(((await res.json()) as { code: string }).code).toBe("unavailable");

    const row = await env.DB.prepare("SELECT status, settled_picos FROM reservations LIMIT 1").first<{
      status: string;
      settled_picos: number;
    }>();
    expect(row?.status).toBe("settled");
    expect(row?.settled_picos).toBe(0);

    const usageRow = await env.DB.prepare("SELECT used_picos FROM daily_account_free_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(usageRow?.used_picos).toBe(0);
  });

  it("keeps the reservation when upstream returns an ambiguous 502 gateway error", async () => {
    const sess = await createTestAccountSession(env.DB);
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      return new Response("Bad Gateway", { status: 502 });
    });

    const ctx = createExecutionContext();
    const res = await worker.fetch(chatRequest(sess.cookieHeader), env, ctx);
    await waitOnExecutionContext(ctx);

    expect(res.status).toBe(503);
    expect(((await res.json()) as { code: string }).code).toBe("unavailable");

    const row = await env.DB.prepare(
      "SELECT status, reserved_picos, settled_picos FROM reservations LIMIT 1",
    ).first<{ status: string; reserved_picos: number; settled_picos: number }>();
    expect(row?.status).toBe("kept");
    expect(row?.settled_picos).toBe(row?.reserved_picos);
  });

  it("prevents free-bucket overdraw under 10 concurrent requests from the same account/IP", async () => {
    const sess = await createTestAccountSession(env.DB);
    const model = getDefaultModel();
    const validation = await validateChatRequest(chatRequest(sess.cookieHeader, "198.51.100.88"), model);
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
      const sse = completedResponseSse({ input: 100, output: 50 });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const testEnv = {
      ...env,
      CALLER_DAILY_CAP_PICOS: String(callerCap),
    };

    const responses = await Promise.all(
      Array.from({ length: 10 }, async () => {
        const ctx = createExecutionContext();
        const res = await worker.fetch(chatRequest(sess.cookieHeader, "198.51.100.88"), testEnv, ctx);
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

    const usageRow = await env.DB.prepare("SELECT used_picos FROM daily_account_free_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(usageRow?.used_picos ?? 0).toBeLessThanOrEqual(callerCap);
  });

  it("prevents paid credit_balance_picos overdraw under 10 concurrent requests against a tight balance", async () => {
    const model = getDefaultModel();
    const validation = await validateChatRequest(chatRequest("c=1", "198.51.100.89"), model);
    expect(validation.ok).toBe(true);
    if (!validation.ok) throw new Error("unexpected");
    const oneReservation = computeWorstCaseReservationPicos(
      model,
      validation.value.upperBoundInputTokens,
      MAX_OUTPUT_TOKENS,
    );
    const maxPaidRequests = 3;
    const tightBalance = oneReservation * maxPaidRequests + 999;

    const sess = await createTestAccountSession(env.DB, {
      accountId: "acct-paid-race",
      creditBalancePicos: tightBalance,
      freeEligible: 0,
    });

    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20));
      const sse = completedResponseSse({ input: 100, output: 200 });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const responses = await Promise.all(
      Array.from({ length: 10 }, async () => {
        const ctx = createExecutionContext();
        const res = await worker.fetch(chatRequest(sess.cookieHeader, "198.51.100.89"), env, ctx);
        await res.text();
        await waitOnExecutionContext(ctx);
        return res;
      }),
    );

    const okCount = responses.filter((r) => r.status === 200).length;
    const rejectedCount = responses.filter((r) => r.status === 429).length;
    expect(okCount).toBeLessThanOrEqual(maxPaidRequests);
    expect(okCount).toBeGreaterThanOrEqual(1);
    expect(okCount + rejectedCount).toBe(10);

    const acctRow = await env.DB
      .prepare("SELECT credit_balance_picos FROM accounts WHERE id = 'acct-paid-race'")
      .first<{ credit_balance_picos: number }>();
    expect(acctRow?.credit_balance_picos ?? -1).toBeGreaterThanOrEqual(0);
  });

  it("prevents global pool overdraw under 10 concurrent requests from 10 different accounts/IPs", async () => {
    const sessions = await Promise.all(
      Array.from({ length: 10 }, (_, idx) =>
        createTestAccountSession(env.DB, { accountId: `acct-pool-${idx}` }),
      ),
    );

    const model = getDefaultModel();
    const validation = await validateChatRequest(chatRequest(sessions[0]!.cookieHeader, "198.51.100.1"), model);
    expect(validation.ok).toBe(true);
    if (!validation.ok) throw new Error("unexpected");
    const oneReservation = computeWorstCaseReservationPicos(
      model,
      validation.value.upperBoundInputTokens,
      MAX_OUTPUT_TOKENS,
    );
    const maxPoolRequests = 2;
    const poolCap = oneReservation * maxPoolRequests + 50;

    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 25));
      const sse = completedResponseSse({ input: 100, output: 4000 });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const testEnv = {
      ...env,
      GLOBAL_DAILY_POOL_PICOS: String(poolCap),
    };

    const responses = await Promise.all(
      sessions.map(async (sess, idx) => {
        const ctx = createExecutionContext();
        const res = await worker.fetch(chatRequest(sess.cookieHeader, `198.51.100.${idx + 1}`), testEnv, ctx);
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

    const poolRow = await env.DB.prepare("SELECT used_picos FROM daily_pool_free_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(poolRow?.used_picos ?? 0).toBeLessThanOrEqual(poolCap);

    const observer = await createTestAccountSession(env.DB, { accountId: "acct-observer" });
    const ctxAllow = createExecutionContext();
    const allowRes = await worker.fetch(
      allowanceRequest(observer.cookieHeader, "198.51.100.250"),
      { ...env, GLOBAL_DAILY_POOL_PICOS: String(poolRow?.used_picos ?? 0) },
      ctxAllow,
    );
    await waitOnExecutionContext(ctxAllow);
    const allowJson = (await allowRes.json()) as { poolOpen: boolean };
    expect(allowJson.poolOpen).toBe(false);
  });

  it("never under-reserves on a 1-byte-per-token dense ASCII payload at cache_creation pricing", async () => {
    const sess = await createTestAccountSession(env.DB);
    const denseBody = {
      ...VALID_BODY,
      messages: [{ role: "user", content: ",1{}[]();:".repeat(2_000) }],
    };
    const model = getDefaultModel();
    const validation = await validateChatRequest(chatRequest(sess.cookieHeader, "198.51.100.90", denseBody), model);
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
      const sse = completedResponseSse({ input: 0, cacheWrite: worstCaseTokens, output: MAX_OUTPUT_TOKENS });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const callerCap = exactReservation;
    const ctx = createExecutionContext();
    const res = await worker.fetch(
      chatRequest(sess.cookieHeader, "198.51.100.90", denseBody),
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

    const usageRow = await env.DB.prepare("SELECT used_picos FROM daily_account_free_usage LIMIT 1").first<{
      used_picos: number;
    }>();
    expect(usageRow?.used_picos).toBeLessThanOrEqual(callerCap);
  });

  it("handles concurrent interleaved multi-byte UTF-8 streams without decoder corruption", async () => {
    const s1 = await createTestAccountSession(env.DB, { accountId: "acct-utf8-1" });
    const s2 = await createTestAccountSession(env.DB, { accountId: "acct-utf8-2" });
    const encoder = new TextEncoder();
    const makeSplitStream = (text: string, inputTokens: number, outputTokens: number) => {
      const sse = completedResponseSse({ input: inputTokens, output: outputTokens, text });
      const bytes = encoder.encode(sse);
      let offset = 0;
      return new ReadableStream<Uint8Array>({
        async pull(controller) {
          if (offset >= bytes.length) {
            controller.close();
            return;
          }
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
        const res = await worker.fetch(chatRequest(s1.cookieHeader, "198.51.100.91"), env, ctx);
        const txt = await res.text();
        await waitOnExecutionContext(ctx);
        return txt;
      })(),
      (async () => {
        const ctx = createExecutionContext();
        const res = await worker.fetch(chatRequest(s2.cookieHeader, "198.51.100.92"), env, ctx);
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
    const sess = await createTestAccountSession(env.DB);
    const encoder = new TextEncoder();
    const sse = completedResponseSse({ input: 200, output: 30 }).replace(/\n/g, "\r\n");

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
    const res = await worker.fetch(chatRequest(sess.cookieHeader, "198.51.100.93"), env, ctx);
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

  it("settles reported incomplete usage and keeps the full reservation on unreported interruption or abort", async () => {
    const sess = await createTestAccountSession(env.DB);
    const encoder = new TextEncoder();

    vi.spyOn(globalThis, "fetch").mockImplementationOnce(async () => {
      const sse = responseEvent("response.incomplete", { response: {
        usage: { input_tokens: 150, input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 }, output_tokens: 40 },
      } });
      return new Response(encoder.encode(sse), { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctxA = createExecutionContext();
    const resA = await worker.fetch(chatRequest(sess.cookieHeader, "198.51.100.94"), env, ctxA);
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
      env.DB.prepare("DELETE FROM daily_account_free_usage"),
      env.DB.prepare("DELETE FROM daily_subnet_free_usage"),
      env.DB.prepare("DELETE FROM daily_pool_free_usage"),
    ]);

    vi.spyOn(globalThis, "fetch").mockImplementationOnce(async () => {
      const sse = responseEvent("response.output_text.delta", { delta: "0123456789" }) +
        responseEvent("error", { message: "midstream" });
      return new Response(encoder.encode(sse), { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctxB = createExecutionContext();
    const resB = await worker.fetch(chatRequest(sess.cookieHeader, "198.51.100.95"), env, ctxB);
    await resB.text();
    await waitOnExecutionContext(ctxB);

    const rowB = await env.DB.prepare("SELECT status, input_tokens, output_tokens FROM reservations LIMIT 1").first<{
      status: string;
      input_tokens: number;
      output_tokens: number;
    }>();
    expect(rowB).toEqual({ status: "kept", input_tokens: null, output_tokens: null });

    await env.DB.batch([
      env.DB.prepare("DELETE FROM reservations"),
      env.DB.prepare("DELETE FROM daily_account_free_usage"),
      env.DB.prepare("DELETE FROM daily_subnet_free_usage"),
      env.DB.prepare("DELETE FROM daily_pool_free_usage"),
    ]);

    let upstreamAborted = false;
    vi.spyOn(globalThis, "fetch").mockImplementationOnce(async (_input, init) => {
      init?.signal?.addEventListener("abort", () => {
        upstreamAborted = true;
      });
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(
            encoder.encode(responseEvent("response.output_text.delta", { delta: "partial" })),
          );
        },
      });
      return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ctxC = createExecutionContext();
    const resC = await worker.fetch(chatRequest(sess.cookieHeader, "198.51.100.96"), env, ctxC);
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
    expect(rowC).toEqual({ status: "kept", input_tokens: null, output_tokens: null });
  });
});
