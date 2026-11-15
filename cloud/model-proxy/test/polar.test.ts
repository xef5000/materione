import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { computeScriptSha256Base64 } from "../src/auth";
import { ALLOWED_ORIGIN, PICOS_PER_CREDIT } from "../src/config";
import { nowSeconds } from "../src/http";
import worker from "../src/index";
import { SDK_INDEX_HEADER } from "../src/prompt";
import { clearAllTables, createTestAccountSession, signPolarWebhook } from "./helpers";

const VALID_CHAT_BODY = JSON.stringify({
  system: `${SDK_INDEX_HEADER}\n\n## Content types (1)\n- item`,
  tools: [
    {
      name: "run_program",
      description: "Run",
      input_schema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] },
    },
  ],
  messages: [{ role: "user", content: "Create an item." }],
});

describe("Polar checkout, Standard Webhooks, refunds, and customer portal", () => {
  beforeEach(async () => {
    await clearAllTables(env.DB);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("validates POST /v1/checkout bounds, suspended vs negative-balance accounts, and forwards exact payload to Polar", async () => {
    const sess = await createTestAccountSession(env.DB, {
      accountId: "acct-checkout-1",
      email: "buyer@example.com",
    });

    // Reject < $10, > $200, float, extra keys
    const badBodies = [
      JSON.stringify({ amountCents: 999 }),
      JSON.stringify({ amountCents: 20001 }),
      JSON.stringify({ amountCents: 1000.5 }),
      JSON.stringify({ amountCents: 1000, extra: true }),
      JSON.stringify({}),
    ];
    for (const body of badBodies) {
      const ctx = createExecutionContext();
      const res = await worker.fetch(
        new Request("https://ai.materione.com/v1/checkout", {
          method: "POST",
          headers: {
            origin: ALLOWED_ORIGIN,
            "cf-connecting-ip": "198.51.100.50",
            "content-type": "application/json",
            cookie: sess.cookieHeader,
          },
          body,
        }),
        env,
        ctx,
      );
      await waitOnExecutionContext(ctx);
      expect(res.status).toBe(400);
    }

    // Suspended account blocked with 403
    const suspendedSess = await createTestAccountSession(env.DB, {
      accountId: "acct-suspended",
      suspended: 1,
      suspendReason: "chargeback_dispute",
    });
    const suspCtx = createExecutionContext();
    const suspRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/checkout", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.50",
          "content-type": "application/json",
          cookie: suspendedSess.cookieHeader,
        },
        body: JSON.stringify({ amountCents: 1000 }),
      }),
      env,
      suspCtx,
    );
    await waitOnExecutionContext(suspCtx);
    expect(suspRes.status).toBe(403);
    expect(((await suspRes.json()) as { error: string }).error).toBe("account_suspended");

    // Negative-balance user with suspended=0 IS allowed to top up to repay
    const negSess = await createTestAccountSession(env.DB, {
      accountId: "acct-negative",
      email: "repay@example.com",
      creditBalancePicos: -200 * PICOS_PER_CREDIT,
      freeEligible: 0,
      suspended: 0,
    });

    let capturedCheckoutBody: Record<string, unknown> | undefined;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      expect(url).toBe("https://api.polar.sh/v1/checkouts/");
      const auth = new Headers(init?.headers).get("authorization");
      expect(auth).toBe("Bearer test-polar-access-token");
      capturedCheckoutBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
      return new Response(JSON.stringify({ url: "https://checkout.polar.sh/cs_test_123" }), { status: 201 });
    });

    const okCtx = createExecutionContext();
    const okRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/checkout", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.50",
          "content-type": "application/json",
          cookie: negSess.cookieHeader,
        },
        body: JSON.stringify({ amountCents: 2500 }),
      }),
      env,
      okCtx,
    );
    await waitOnExecutionContext(okCtx);
    expect(okRes.status).toBe(200);
    expect(await okRes.json()).toEqual({ checkoutUrl: "https://checkout.polar.sh/cs_test_123" });
    expect(capturedCheckoutBody).toEqual({
      products: ["06c6939d-72a3-4bef-8ded-5c6543811a28"],
      amount: 2500,
      currency: "usd",
      external_customer_id: "acct-negative",
      customer_email: "repay@example.com",
      customer_ip_address: "198.51.100.50",
      success_url: "https://ai.materione.com/v1/checkout/complete",
    });

    // Verify GET /v1/checkout/complete CSP-locked HTML + CF-Connecting-IP + subnet rate limiting
    const compMissingIpCtx = createExecutionContext();
    const compMissingIpRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/checkout/complete", { method: "GET" }),
      env,
      compMissingIpCtx,
    );
    await waitOnExecutionContext(compMissingIpCtx);
    expect(compMissingIpRes.status).toBe(400);

    let compCalls = 0;
    const compLimitedEnv = {
      ...env,
      POLAR_API_BASE: "https://api.polar.sh",
      RATE_LIMITER: {
        limit: async () => ({ success: ++compCalls <= 2 }),
      } as unknown as RateLimit,
    };

    const compCtx = createExecutionContext();
    const compRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/checkout/complete", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.50" },
      }),
      compLimitedEnv,
      compCtx,
    );
    const compHtml = await compRes.text();
    await waitOnExecutionContext(compCtx);
    expect(compRes.status).toBe(200);
    const script = compHtml.match(/<script>([\s\S]*?)<\/script>/)![1]!;
    const digest = await computeScriptSha256Base64(script);
    expect(compRes.headers.get("content-security-policy")).toBe(`default-src 'none'; script-src 'sha256-${digest}'`);
    expect(compHtml).toContain("materione-ai-checkout-complete");

    const compCtx2 = createExecutionContext();
    const compRes2 = await worker.fetch(
      new Request("https://ai.materione.com/v1/checkout/complete", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.50" },
      }),
      compLimitedEnv,
      compCtx2,
    );
    await waitOnExecutionContext(compCtx2);
    expect(compRes2.status).toBe(200);

    const compCtx3 = createExecutionContext();
    const compRes3 = await worker.fetch(
      new Request("https://ai.materione.com/v1/checkout/complete", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.50" },
      }),
      compLimitedEnv,
      compCtx3,
    );
    await waitOnExecutionContext(compCtx3);
    expect(compRes3.status).toBe(429);
  });

  it("verifies Standard Webhooks HMAC signature, rejects tampering/replay with 403, and enforces 256 KiB cap", async () => {
    const body = JSON.stringify({ type: "order.created", data: { id: "o1" } });
    const now = nowSeconds();
    const validSig = await signPolarWebhook("wh_1", now, body, env.POLAR_WEBHOOK_SECRET!);

    // Missing signature
    const c1 = createExecutionContext();
    const r1 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: { "cf-connecting-ip": "198.51.100.60", "content-type": "application/json" },
        body,
      }),
      env,
      c1,
    );
    await waitOnExecutionContext(c1);
    expect(r1.status).toBe(403);

    // Tampered body
    const c2 = createExecutionContext();
    const r2 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.60",
          "webhook-id": "wh_1",
          "webhook-timestamp": String(now),
          "webhook-signature": validSig,
        },
        body: body + " ",
      }),
      env,
      c2,
    );
    await waitOnExecutionContext(c2);
    expect(r2.status).toBe(403);

    // Wrong secret
    const wrongSig = await signPolarWebhook("wh_1", now, body, "whsec_d3Jvbmctc2VjcmV0LTAxMjM0NTY3ODkwMTIzNDU=");
    const c3 = createExecutionContext();
    const r3 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.60",
          "webhook-id": "wh_1",
          "webhook-timestamp": String(now),
          "webhook-signature": wrongSig,
        },
        body,
      }),
      env,
      c3,
    );
    await waitOnExecutionContext(c3);
    expect(r3.status).toBe(403);

    // Unprefixed base64 Standard Webhooks secret succeeds
    const unprefixedSecret = "dGVzdC1wb2xhci13ZWJob29rLXNlY3JldC0wMTIzNDU=";
    const unprefixedSig = await signPolarWebhook("wh_unprefixed", now, body, unprefixedSecret);
    const cUnprefixed = createExecutionContext();
    const rUnprefixed = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.60",
          "webhook-id": "wh_unprefixed",
          "webhook-timestamp": String(now),
          "webhook-signature": unprefixedSig,
        },
        body,
      }),
      { ...env, POLAR_WEBHOOK_SECRET: unprefixedSecret },
      cUnprefixed,
    );
    await waitOnExecutionContext(cUnprefixed);
    expect(rUnprefixed.status).toBe(202);

    // Replay timestamp (> 300s old)
    const oldTs = now - 301;
    const oldSig = await signPolarWebhook("wh_1", oldTs, body, env.POLAR_WEBHOOK_SECRET!);
    const c4 = createExecutionContext();
    const r4 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.60",
          "webhook-id": "wh_1",
          "webhook-timestamp": String(oldTs),
          "webhook-signature": oldSig,
        },
        body,
      }),
      env,
      c4,
    );
    await waitOnExecutionContext(c4);
    expect(r4.status).toBe(403);

    // Oversized > 256 KiB returns 413
    const hugeBody = "x".repeat(256 * 1024 + 10);
    const c5 = createExecutionContext();
    const r5 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: { "cf-connecting-ip": "198.51.100.60" },
        body: hugeBody,
      }),
      env,
      c5,
    );
    await waitOnExecutionContext(c5);
    expect(r5.status).toBe(413);

    // Unknown valid-HMAC event returns 202
    const c6 = createExecutionContext();
    const r6 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.60",
          "webhook-id": "wh_1",
          "webhook-timestamp": String(now),
          "webhook-signature": validSig,
        },
        body,
      }),
      env,
      c6,
    );
    await waitOnExecutionContext(c6);
    expect(r6.status).toBe(202);
  });

  it("grants exact net_amount credits on order.paid, deduplicates same webhook-id and same order_id, and handles partial + spend-then-refund", async () => {
    const sess = await createTestAccountSession(env.DB, {
      accountId: "acct-polar-1",
      email: "payer@example.com",
    });

    const now = nowSeconds();
    // Discounted order: $10.00 subtotal - $2.00 discount = net_amount 800 cents -> 800 credits (6_000_000_000_000 picos)
    const orderPaidBody = JSON.stringify({
      type: "order.paid",
      data: {
        id: "ord_disc_1",
        product_id: "06c6939d-72a3-4bef-8ded-5c6543811a28",
        currency: "usd",
        subtotal_amount: 1000,
        discount_amount: 200,
        net_amount: 800,
        customer: {
          id: "cus_polar_999",
          external_id: "acct-polar-1",
        },
      },
    });

    const sig1 = await signPolarWebhook("wh_paid_1", now, orderPaidBody, env.POLAR_WEBHOOK_SECRET!);
    const c1 = createExecutionContext();
    const r1 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.70",
          "webhook-id": "wh_paid_1",
          "webhook-timestamp": String(now),
          "webhook-signature": sig1,
        },
        body: orderPaidBody,
      }),
      env,
      c1,
    );
    await waitOnExecutionContext(c1);
    expect(r1.status).toBe(202);

    const acctAfterPaid = await env.DB
      .prepare("SELECT credit_balance_picos, polar_customer_id FROM accounts WHERE id = 'acct-polar-1'")
      .first<{ credit_balance_picos: number; polar_customer_id: string }>();
    expect(acctAfterPaid?.credit_balance_picos).toBe(800 * PICOS_PER_CREDIT);
    expect(acctAfterPaid?.polar_customer_id).toBe("cus_polar_999");

    // Replay exact same webhook-id -> 202, no double credit
    const cDup1 = createExecutionContext();
    const rDup1 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.70",
          "webhook-id": "wh_paid_1",
          "webhook-timestamp": String(now),
          "webhook-signature": sig1,
        },
        body: orderPaidBody,
      }),
      env,
      cDup1,
    );
    await waitOnExecutionContext(cDup1);
    expect(rDup1.status).toBe(202);

    // Replay DIFFERENT webhook-id for the SAME order.id -> 202, NEVER double-credits
    const sig2 = await signPolarWebhook("wh_paid_2_diff", now, orderPaidBody, env.POLAR_WEBHOOK_SECRET!);
    const cDup2 = createExecutionContext();
    const rDup2 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.70",
          "webhook-id": "wh_paid_2_diff",
          "webhook-timestamp": String(now),
          "webhook-signature": sig2,
        },
        body: orderPaidBody,
      }),
      env,
      cDup2,
    );
    await waitOnExecutionContext(cDup2);
    expect(rDup2.status).toBe(202);

    const acctStill800 = await env.DB
      .prepare("SELECT credit_balance_picos FROM accounts WHERE id = 'acct-polar-1'")
      .first<{ credit_balance_picos: number }>();
    expect(acctStill800?.credit_balance_picos).toBe(800 * PICOS_PER_CREDIT);

    // Simulate user spending 200 credits (leaving 600 credits)
    await env.DB
      .prepare("UPDATE accounts SET credit_balance_picos = ?1 WHERE id = 'acct-polar-1'")
      .bind(600 * PICOS_PER_CREDIT)
      .run();

    // Partial refund of 300 cents -> deducts 300 credits (balance becomes +300 credits)
    const ref1Body = JSON.stringify({
      type: "order.refunded",
      data: { id: "ord_disc_1", refunded_amount: 300 },
    });
    const ref1Sig = await signPolarWebhook("wh_ref_1", now, ref1Body, env.POLAR_WEBHOOK_SECRET!);
    const cRef1 = createExecutionContext();
    const rRef1 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.70",
          "webhook-id": "wh_ref_1",
          "webhook-timestamp": String(now),
          "webhook-signature": ref1Sig,
        },
        body: ref1Body,
      }),
      env,
      cRef1,
    );
    await waitOnExecutionContext(cRef1);
    expect(rRef1.status).toBe(202);

    const acctAfterRef1 = await env.DB
      .prepare("SELECT credit_balance_picos, free_eligible FROM accounts WHERE id = 'acct-polar-1'")
      .first<{ credit_balance_picos: number; free_eligible: number }>();
    expect(acctAfterRef1?.credit_balance_picos).toBe(300 * PICOS_PER_CREDIT);
    expect(acctAfterRef1?.free_eligible).toBe(1);

    // Cumulative full refund (800 cents) -> deducts only incremental 500 credits -> balance becomes -200 credits & free_eligible = 0!
    const ref2Body = JSON.stringify({
      type: "order.refunded",
      data: { id: "ord_disc_1", refunded_amount: 800 },
    });
    const ref2Sig = await signPolarWebhook("wh_ref_2", now, ref2Body, env.POLAR_WEBHOOK_SECRET!);
    const cRef2 = createExecutionContext();
    const rRef2 = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.70",
          "webhook-id": "wh_ref_2",
          "webhook-timestamp": String(now),
          "webhook-signature": ref2Sig,
        },
        body: ref2Body,
      }),
      env,
      cRef2,
    );
    await waitOnExecutionContext(cRef2);
    expect(rRef2.status).toBe(202);

    const acctAfterRef2 = await env.DB
      .prepare("SELECT credit_balance_picos, free_eligible FROM accounts WHERE id = 'acct-polar-1'")
      .first<{ credit_balance_picos: number; free_eligible: number }>();
    expect(acctAfterRef2?.credit_balance_picos).toBe(-200 * PICOS_PER_CREDIT);
    expect(acctAfterRef2?.free_eligible).toBe(0);

    // Spend-then-refund account blocked from POST /v1/chat with 403 account_suspended!
    const chatCtx = createExecutionContext();
    const chatRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/chat", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.70",
          "content-type": "application/json",
          cookie: sess.cookieHeader,
        },
        body: VALID_CHAT_BODY,
      }),
      env,
      chatCtx,
    );
    await waitOnExecutionContext(chatCtx);
    expect(chatRes.status).toBe(403);
    const chatJson = (await chatRes.json()) as { error: string; code: string };
    expect(chatJson.error).toBe("account_suspended");
    expect(chatJson.code).toBe("unavailable");
  });

  it("calls Polar /v1/customer-sessions/ on POST /v1/portal when polar_customer_id is set and falls back when null", async () => {
    const noCustSess = await createTestAccountSession(env.DB, {
      accountId: "acct-no-cus",
      polarCustomerId: null,
    });
    const c1 = createExecutionContext();
    const r1 = await worker.fetch(
      new Request("https://ai.materione.com/v1/portal", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.80",
          cookie: noCustSess.cookieHeader,
        },
      }),
      env,
      c1,
    );
    await waitOnExecutionContext(c1);
    expect(r1.status).toBe(200);
    expect(await r1.json()).toEqual({ portalUrl: "https://polar.sh/materione/portal" });

    const withCustSess = await createTestAccountSession(env.DB, {
      accountId: "acct-with-cus",
      polarCustomerId: "cus_live_456",
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      expect(url).toBe("https://api.polar.sh/v1/customer-sessions/");
      expect(JSON.parse(String(init?.body))).toEqual({ customer_id: "cus_live_456" });
      return new Response(JSON.stringify({ customer_portal_url: "https://polar.sh/portal/session_abc" }), {
        status: 201,
      });
    });

    const c2 = createExecutionContext();
    const r2 = await worker.fetch(
      new Request("https://ai.materione.com/v1/portal", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.80",
          cookie: withCustSess.cookieHeader,
        },
      }),
      env,
      c2,
    );
    await waitOnExecutionContext(c2);
    expect(r2.status).toBe(200);
    expect(await r2.json()).toEqual({ portalUrl: "https://polar.sh/portal/session_abc" });
  });

  it("returns 503 on out-of-order order.refunded before order.paid (ignoring wrong product/currency) and nets 0 credits after retry", async () => {
    await createTestAccountSession(env.DB, {
      accountId: "acct-oos-1",
      email: "oos@example.com",
    });

    const now = nowSeconds();
    // Different product -> 202 ignored
    const otherProdBody = JSON.stringify({
      type: "order.refunded",
      data: {
        id: "ord_other_prod",
        product_id: "different-product-uuid",
        currency: "usd",
        refunded_amount: 500,
      },
    });
    const sigOther = await signPolarWebhook("wh_oos_other", now, otherProdBody, env.POLAR_WEBHOOK_SECRET!);
    const cOther = createExecutionContext();
    const rOther = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.90",
          "webhook-id": "wh_oos_other",
          "webhook-timestamp": String(now),
          "webhook-signature": sigOther,
        },
        body: otherProdBody,
      }),
      env,
      cOther,
    );
    await waitOnExecutionContext(cOther);
    expect(rOther.status).toBe(202);
    expect(await rOther.json()).toEqual({ ok: true, ignored: true });

    // Out-of-order order.refunded for our product -> 503 so Polar retries
    const oosRefundBody = JSON.stringify({
      type: "order.refunded",
      data: {
        id: "ord_oos_1",
        product_id: "06c6939d-72a3-4bef-8ded-5c6543811a28",
        currency: "usd",
        refunded_amount: 1000,
      },
    });
    const sigRefEarly = await signPolarWebhook("wh_oos_ref_1", now, oosRefundBody, env.POLAR_WEBHOOK_SECRET!);
    const cEarly = createExecutionContext();
    const rEarly = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.90",
          "webhook-id": "wh_oos_ref_1",
          "webhook-timestamp": String(now),
          "webhook-signature": sigRefEarly,
        },
        body: oosRefundBody,
      }),
      env,
      cEarly,
    );
    await waitOnExecutionContext(cEarly);
    expect(rEarly.status).toBe(503);
    expect(((await rEarly.json()) as { error: string }).error).toBe("order_not_yet_recorded");

    // order.paid arrives and commits
    const paidBody = JSON.stringify({
      type: "order.paid",
      data: {
        id: "ord_oos_1",
        product_id: "06c6939d-72a3-4bef-8ded-5c6543811a28",
        currency: "usd",
        net_amount: 1000,
        customer: { id: "cus_oos_1", external_id: "acct-oos-1" },
      },
    });
    const sigPaid = await signPolarWebhook("wh_oos_paid", now, paidBody, env.POLAR_WEBHOOK_SECRET!);
    const cPaid = createExecutionContext();
    const rPaid = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.90",
          "webhook-id": "wh_oos_paid",
          "webhook-timestamp": String(now),
          "webhook-signature": sigPaid,
        },
        body: paidBody,
      }),
      env,
      cPaid,
    );
    await waitOnExecutionContext(cPaid);
    expect(rPaid.status).toBe(202);

    // Retried order.refunded now succeeds and nets 0 credits
    const cRetry = createExecutionContext();
    const rRetry = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.90",
          "webhook-id": "wh_oos_ref_1",
          "webhook-timestamp": String(now),
          "webhook-signature": sigRefEarly,
        },
        body: oosRefundBody,
      }),
      env,
      cRetry,
    );
    await waitOnExecutionContext(cRetry);
    expect(rRetry.status).toBe(202);

    const acct = await env.DB
      .prepare("SELECT credit_balance_picos FROM accounts WHERE id = 'acct-oos-1'")
      .first<{ credit_balance_picos: number }>();
    expect(acct?.credit_balance_picos).toBe(0);
  });

  it("suspends on dispute refund.created (blocking /v1/chat and /v1/checkout even with positive credits) and lifts on dispute won", async () => {
    const sess = await createTestAccountSession(env.DB, {
      accountId: "acct-dispute-1",
      email: "dispute@example.com",
    });

    const now = nowSeconds();
    const paidBody = JSON.stringify({
      type: "order.paid",
      data: {
        id: "ord_disp_1",
        product_id: "06c6939d-72a3-4bef-8ded-5c6543811a28",
        currency: "usd",
        net_amount: 2000,
        customer: { id: "cus_disp_1", external_id: "acct-dispute-1" },
      },
    });
    const paidSig = await signPolarWebhook("wh_disp_paid", now, paidBody, env.POLAR_WEBHOOK_SECRET!);
    const cPaid = createExecutionContext();
    const rPaid = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.91",
          "webhook-id": "wh_disp_paid",
          "webhook-timestamp": String(now),
          "webhook-signature": paidSig,
        },
        body: paidBody,
      }),
      env,
      cPaid,
    );
    await waitOnExecutionContext(cPaid);
    expect(rPaid.status).toBe(202);

    // refund.created with dispute under_review -> sets suspended = 1, free_eligible = 0, delta_picos = 0
    const dispHoldBody = JSON.stringify({
      type: "refund.created",
      data: {
        id: "ref_dp_1",
        order_id: "ord_disp_1",
        reason: "dispute",
        dispute: { id: "dp_1", status: "under_review" },
      },
    });
    const holdSig = await signPolarWebhook("wh_disp_hold", now, dispHoldBody, env.POLAR_WEBHOOK_SECRET!);
    const cHold = createExecutionContext();
    const rHold = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.91",
          "webhook-id": "wh_disp_hold",
          "webhook-timestamp": String(now),
          "webhook-signature": holdSig,
        },
        body: dispHoldBody,
      }),
      env,
      cHold,
    );
    await waitOnExecutionContext(cHold);
    expect(rHold.status).toBe(202);

    const acctHeld = await env.DB
      .prepare("SELECT credit_balance_picos, free_eligible, suspended, suspend_reason FROM accounts WHERE id = 'acct-dispute-1'")
      .first<{ credit_balance_picos: number; free_eligible: number; suspended: number; suspend_reason: string | null }>();
    expect(acctHeld?.credit_balance_picos).toBe(2000 * PICOS_PER_CREDIT);
    expect(acctHeld?.free_eligible).toBe(0);
    expect(acctHeld?.suspended).toBe(1);
    expect(acctHeld?.suspend_reason).toBe("chargeback_dispute");

    // Both /v1/chat and /v1/checkout return 403 account_suspended even with positive credits
    const cChatBlocked = createExecutionContext();
    const rChatBlocked = await worker.fetch(
      new Request("https://ai.materione.com/v1/chat", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.91",
          "content-type": "application/json",
          cookie: sess.cookieHeader,
        },
        body: VALID_CHAT_BODY,
      }),
      env,
      cChatBlocked,
    );
    await waitOnExecutionContext(cChatBlocked);
    expect(rChatBlocked.status).toBe(403);
    expect(((await rChatBlocked.json()) as { error: string }).error).toBe("account_suspended");

    const cCoBlocked = createExecutionContext();
    const rCoBlocked = await worker.fetch(
      new Request("https://ai.materione.com/v1/checkout", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.91",
          "content-type": "application/json",
          cookie: sess.cookieHeader,
        },
        body: JSON.stringify({ amountCents: 1000 }),
      }),
      env,
      cCoBlocked,
    );
    await waitOnExecutionContext(cCoBlocked);
    expect(rCoBlocked.status).toBe(403);
    expect(((await rCoBlocked.json()) as { error: string }).error).toBe("account_suspended");

    // refund.updated with dispute status = "won" lifts suspension
    const dispWonBody = JSON.stringify({
      type: "refund.updated",
      data: {
        id: "ref_dp_1",
        order_id: "ord_disp_1",
        reason: "dispute",
        dispute: { id: "dp_1", status: "won" },
      },
    });
    const wonSig = await signPolarWebhook("wh_disp_won", now, dispWonBody, env.POLAR_WEBHOOK_SECRET!);
    const cWon = createExecutionContext();
    const rWon = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.91",
          "webhook-id": "wh_disp_won",
          "webhook-timestamp": String(now),
          "webhook-signature": wonSig,
        },
        body: dispWonBody,
      }),
      env,
      cWon,
    );
    await waitOnExecutionContext(cWon);
    expect(rWon.status).toBe(202);

    const acctReleased = await env.DB
      .prepare("SELECT credit_balance_picos, suspended, suspend_reason FROM accounts WHERE id = 'acct-dispute-1'")
      .first<{ credit_balance_picos: number; suspended: number; suspend_reason: string | null }>();
    expect(acctReleased?.credit_balance_picos).toBe(2000 * PICOS_PER_CREDIT);
    expect(acctReleased?.suspended).toBe(0);
    expect(acctReleased?.suspend_reason).toBeNull();

    // /v1/checkout succeeds again after dispute won (including with explicit POLAR_API_BASE=https://api.polar.sh)
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
      new Response(JSON.stringify({ url: "https://checkout.polar.sh/cs_after_won" }), { status: 201 }),
    );
    const cCoOk = createExecutionContext();
    const rCoOk = await worker.fetch(
      new Request("https://ai.materione.com/v1/checkout", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.91",
          "content-type": "application/json",
          cookie: sess.cookieHeader,
        },
        body: JSON.stringify({ amountCents: 1000 }),
      }),
      { ...env, POLAR_API_BASE: "https://api.polar.sh" },
      cCoOk,
    );
    await waitOnExecutionContext(cCoOk);
    expect(rCoOk.status).toBe(200);

    // Delayed out-of-order under_review webhook for the SAME won dispute (even with a newer webhook-timestamp)
    // is rejected as stale and does NOT re-suspend the account!
    const staleHoldBody = JSON.stringify({
      type: "refund.created",
      timestamp: "2026-11-15T10:00:00Z",
      data: {
        id: "ref_dp_1",
        order_id: "ord_disp_1",
        reason: "dispute",
        dispute: { id: "dp_1", status: "under_review", modified_at: "2026-11-15T10:00:00Z" },
      },
    });
    const staleSig = await signPolarWebhook("wh_disp_stale_hold", now + 5, staleHoldBody, env.POLAR_WEBHOOK_SECRET!);
    const cStale = createExecutionContext();
    const rStale = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.91",
          "webhook-id": "wh_disp_stale_hold",
          "webhook-timestamp": String(now + 5),
          "webhook-signature": staleSig,
        },
        body: staleHoldBody,
      }),
      env,
      cStale,
    );
    await waitOnExecutionContext(cStale);
    expect(rStale.status).toBe(202);
    expect(await rStale.json()).toEqual({ ok: true, ignored: true });

    const acctStillActive = await env.DB
      .prepare("SELECT suspended, suspend_reason FROM accounts WHERE id = 'acct-dispute-1'")
      .first<{ suspended: number; suspend_reason: string | null }>();
    expect(acctStillActive?.suspended).toBe(0);
    expect(acctStillActive?.suspend_reason).toBeNull();
  });

  it("reverses granted credits on order.updated with status='void'", async () => {
    await createTestAccountSession(env.DB, {
      accountId: "acct-void-1",
      email: "void@example.com",
    });

    const now = nowSeconds();
    const paidBody = JSON.stringify({
      type: "order.paid",
      data: {
        id: "ord_void_1",
        product_id: "06c6939d-72a3-4bef-8ded-5c6543811a28",
        currency: "usd",
        net_amount: 1500,
        customer: { id: "cus_void_1", external_id: "acct-void-1" },
      },
    });
    const paidSig = await signPolarWebhook("wh_void_paid", now, paidBody, env.POLAR_WEBHOOK_SECRET!);
    const cPaid = createExecutionContext();
    const rPaid = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.92",
          "webhook-id": "wh_void_paid",
          "webhook-timestamp": String(now),
          "webhook-signature": paidSig,
        },
        body: paidBody,
      }),
      env,
      cPaid,
    );
    await waitOnExecutionContext(cPaid);
    expect(rPaid.status).toBe(202);

    const voidBody = JSON.stringify({
      type: "order.updated",
      data: {
        id: "ord_void_1",
        status: "void",
        paid: true,
      },
    });
    const voidSig = await signPolarWebhook("wh_void_upd", now, voidBody, env.POLAR_WEBHOOK_SECRET!);
    const cVoid = createExecutionContext();
    const rVoid = await worker.fetch(
      new Request("https://ai.materione.com/v1/webhooks/polar", {
        method: "POST",
        headers: {
          "cf-connecting-ip": "198.51.100.92",
          "webhook-id": "wh_void_upd",
          "webhook-timestamp": String(now),
          "webhook-signature": voidSig,
        },
        body: voidBody,
      }),
      env,
      cVoid,
    );
    await waitOnExecutionContext(cVoid);
    expect(rVoid.status).toBe(202);

    const acct = await env.DB
      .prepare("SELECT credit_balance_picos FROM accounts WHERE id = 'acct-void-1'")
      .first<{ credit_balance_picos: number }>();
    expect(acct?.credit_balance_picos).toBe(0);

    const orderRow = await env.DB
      .prepare("SELECT status, refunded_amount_cents, refunded_picos FROM polar_orders WHERE order_id = 'ord_void_1'")
      .first<{ status: string; refunded_amount_cents: number; refunded_picos: number }>();
    expect(orderRow).toEqual({
      status: "void",
      refunded_amount_cents: 1500,
      refunded_picos: 1500 * PICOS_PER_CREDIT,
    });
  });

  it("allows >15 valid HMAC Polar webhooks from the same egress IP without 429 while rate-limiting >10 invalid-signature probes", async () => {
    const now = nowSeconds();
    const validBody = JSON.stringify({ type: "order.created", data: { id: "ord_burst" } });

    // >15 valid HMAC webhooks from the same Polar egress IP must all succeed with 202
    for (let i = 0; i < 16; i++) {
      const whId = `wh_burst_ok_${i}`;
      const sig = await signPolarWebhook(whId, now, validBody, env.POLAR_WEBHOOK_SECRET!);
      const ctx = createExecutionContext();
      const res = await worker.fetch(
        new Request("https://ai.materione.com/v1/webhooks/polar", {
          method: "POST",
          headers: {
            "cf-connecting-ip": "198.51.100.95",
            "webhook-id": whId,
            "webhook-timestamp": String(now),
            "webhook-signature": sig,
          },
          body: validBody,
        }),
        env,
        ctx,
      );
      await waitOnExecutionContext(ctx);
      expect(res.status).toBe(202);
    }

    // >10 invalid-signature probes from the same IP get 429 after 10 attempts
    const badStatuses: number[] = [];
    for (let i = 0; i < 12; i++) {
      const ctx = createExecutionContext();
      const res = await worker.fetch(
        new Request("https://ai.materione.com/v1/webhooks/polar", {
          method: "POST",
          headers: {
            "cf-connecting-ip": "198.51.100.96",
            "webhook-id": `wh_bad_${i}`,
            "webhook-timestamp": String(now),
            "webhook-signature": "v1,invalid",
          },
          body: validBody,
        }),
        env,
        ctx,
      );
      await waitOnExecutionContext(ctx);
      badStatuses.push(res.status);
    }
    expect(badStatuses.slice(0, 10).every((s) => s === 403)).toBe(true);
    expect(badStatuses.slice(10).every((s) => s === 429)).toBe(true);
  });
});
