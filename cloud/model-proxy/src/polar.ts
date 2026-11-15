import { computeScriptSha256Base64, type AuthenticatedSession } from "./auth";
import {
  ALLOWED_ORIGIN,
  MAX_CHECKOUT_CENTS,
  MIN_CHECKOUT_CENTS,
  PICOS_PER_CREDIT,
  RATE_LIMIT_RETRY_SECONDS,
  WEBHOOK_MAX_BODY_BYTES,
  WEBHOOK_TOLERANCE_SECONDS,
  resolvePolarApiBase,
  resolvePublicBaseUrl,
} from "./config";
import {
  errorResponse,
  htmlResponse,
  jsonResponse,
  nowSeconds,
  plainErrorResponse,
  plainJsonResponse,
  type Env,
} from "./http";
import {
  canonicalizeCallerNetwork,
  checkRateLimit,
  isCoreConfigured,
  isPolarCheckoutConfigured,
  isPolarWebhookConfigured,
  isSessionSecretConfigured,
} from "./identity";

const CHECKOUT_BODY_MAX_BYTES = 16 * 1024;
const WEBHOOK_ID_REGEX = /^[A-Za-z0-9_.-]{1,128}$/;
const WEBHOOK_TS_REGEX = /^\d{1,12}$/;
const FALLBACK_PORTAL_URL = "https://polar.sh/materione/portal";
const DANGEROUS_KEYS = new Set(["__proto__", "constructor", "prototype"]);
const textEncoder = new TextEncoder();

function isSafePlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  for (const key of Object.getOwnPropertyNames(value)) {
    if (DANGEROUS_KEYS.has(key)) return false;
  }
  return true;
}

async function readBoundedUtf8Body(
  request: Request,
  maxBytes: number,
): Promise<{ ok: true; text: string } | { ok: false; status: 400 | 413; error: string; message: string }> {
  const contentLengthHeader = request.headers.get("content-length");
  if (contentLengthHeader !== null) {
    const len = Number(contentLengthHeader);
    if (Number.isFinite(len) && len > maxBytes) {
      return { ok: false, status: 413, error: "too_large", message: "Request body exceeds maximum size." };
    }
  }
  if (!request.body) {
    return { ok: false, status: 400, error: "invalid_request", message: "Request body is required." };
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value || value.byteLength === 0) continue;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => {});
        return { ok: false, status: 413, error: "too_large", message: "Request body exceeds maximum size." };
      }
      chunks.push(value);
    }
  } catch {
    return { ok: false, status: 400, error: "invalid_request", message: "Failed to read request body." };
  }

  const combined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    combined.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    const text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(combined);
    return { ok: true, text };
  } catch {
    return { ok: false, status: 400, error: "invalid_request", message: "Request body must be valid UTF-8." };
  }
}

function decodeBase64(b64: string): Uint8Array | null {
  try {
    const binary = atob(b64);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      out[i] = binary.charCodeAt(i);
    }
    return out;
  } catch {
    return null;
  }
}

function decodeWebhookSecret(rawSecret: string): Uint8Array | null {
  const trimmed = rawSecret.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith("whsec_")) {
    return decodeBase64(trimmed.slice(6));
  }
  return textEncoder.encode(trimmed);
}

async function verifyStandardWebhookSignature(
  request: Request,
  rawBody: string,
  webhookSecret: string,
): Promise<boolean> {
  const webhookId = request.headers.get("webhook-id")?.trim() ?? "";
  const webhookTimestamp = request.headers.get("webhook-timestamp")?.trim() ?? "";
  const webhookSignature = request.headers.get("webhook-signature")?.trim() ?? "";

  if (!WEBHOOK_ID_REGEX.test(webhookId) || !WEBHOOK_TS_REGEX.test(webhookTimestamp) || !webhookSignature) {
    return false;
  }

  const tsNum = Number(webhookTimestamp);
  if (!Number.isSafeInteger(tsNum) || Math.abs(nowSeconds() - tsNum) > WEBHOOK_TOLERANCE_SECONDS) {
    return false;
  }

  const keyBytes = decodeWebhookSecret(webhookSecret);
  if (!keyBytes || keyBytes.byteLength === 0) {
    return false;
  }

  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const signedPayload = textEncoder.encode(`${webhookId}.${webhookTimestamp}.${rawBody}`);

  for (const token of webhookSignature.split(/\s+/)) {
    if (!token.startsWith("v1,")) continue;
    const sigBytes = decodeBase64(token.slice(3));
    if (!sigBytes || sigBytes.byteLength === 0) continue;
    const valid = await crypto.subtle.verify("HMAC", key, sigBytes, signedPayload);
    if (valid) return true;
  }

  return false;
}

function isUniqueConstraintError(err: unknown): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  return msg.includes("UNIQUE constraint failed") || msg.includes("PRIMARY KEY");
}

export async function handleCheckout(
  request: Request,
  env: Env,
  session: AuthenticatedSession,
): Promise<Response> {
  if (!isPolarCheckoutConfigured(env)) {
    return errorResponse(503, "unavailable", "Billing checkout is not configured.");
  }

  if (session.account.suspended !== 0) {
    return errorResponse(
      403,
      "account_suspended",
      "This account is suspended due to an unresolved billing dispute.",
      { code: "unavailable" },
    );
  }

  const rateOutcome = await checkRateLimit(env, `billing:${session.account.id}`);
  if (rateOutcome === "unavailable") {
    return errorResponse(503, "unavailable", "Billing service is temporarily unavailable.");
  }
  if (rateOutcome === "limited") {
    return errorResponse(
      429,
      "rate_limited",
      "Too many billing requests. Please wait a minute.",
      { retryAfterSeconds: RATE_LIMIT_RETRY_SECONDS },
      { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) },
    );
  }

  const contentType = request.headers.get("content-type")?.trim().toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    return errorResponse(400, "invalid_request", "Content-Type must be application/json.");
  }

  const bodyRes = await readBoundedUtf8Body(request, CHECKOUT_BODY_MAX_BYTES);
  if (!bodyRes.ok) {
    return errorResponse(bodyRes.status, bodyRes.error, bodyRes.message);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(bodyRes.text);
  } catch {
    return errorResponse(400, "invalid_request", "Request body must be valid JSON.");
  }

  if (!isSafePlainObject(parsed)) {
    return errorResponse(400, "invalid_request", "Request body must be a JSON object.");
  }
  const keys = Object.keys(parsed);
  if (keys.length !== 1 || keys[0] !== "amountCents") {
    return errorResponse(400, "invalid_request", "Body must contain only 'amountCents'.");
  }
  const amountCents = parsed.amountCents;
  if (
    typeof amountCents !== "number" ||
    !Number.isSafeInteger(amountCents) ||
    amountCents < MIN_CHECKOUT_CENTS ||
    amountCents > MAX_CHECKOUT_CENTS
  ) {
    return errorResponse(
      400,
      "invalid_request",
      `amountCents must be an integer between ${MIN_CHECKOUT_CENTS} ($10.00) and ${MAX_CHECKOUT_CENTS} ($200.00).`,
    );
  }

  const polarApiBase = resolvePolarApiBase(env)!;
  const publicBase = resolvePublicBaseUrl(env)!;
  const rawIp = request.headers.get("cf-connecting-ip")?.trim() ?? "";
  const validIp = rawIp && canonicalizeCallerNetwork(rawIp) !== null ? rawIp : null;

  const polarPayload: Record<string, unknown> = {
    products: [env.POLAR_CREDITS_PRODUCT_ID!.trim()],
    amount: amountCents,
    currency: "usd",
    external_customer_id: session.account.id,
    customer_email: session.account.email,
    success_url: `${publicBase}/v1/checkout/complete`,
  };
  if (validIp) {
    polarPayload.customer_ip_address = validIp;
  }

  const polarRes = await fetch(`${polarApiBase}/v1/checkouts/`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.POLAR_ACCESS_TOKEN!.trim()}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(polarPayload),
  });

  if (!polarRes.ok) {
    return errorResponse(503, "unavailable", "Checkout provider is currently unavailable.");
  }

  const polarData = (await polarRes.json().catch(() => null)) as { url?: unknown } | null;
  if (!polarData || typeof polarData.url !== "string" || !polarData.url.startsWith("https://")) {
    return errorResponse(503, "unavailable", "Checkout provider returned an invalid response.");
  }

  return jsonResponse({ checkoutUrl: polarData.url }, 200);
}

export async function handleCheckoutComplete(): Promise<Response> {
  const script = `try{if(window.opener){window.opener.postMessage({type:"materione-ai-checkout-complete"},${JSON.stringify(ALLOWED_ORIGIN)})}}catch(e){}`;
  const digest = await computeScriptSha256Base64(script);
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Materione AI Credits</title></head><body><h1>Payment received</h1><p>Your Materione AI credits are being applied to your account. Return to your Materione editor tab to continue.</p><script>${script}</script></body></html>`;
  return htmlResponse(html, 200, digest);
}

function extractEventProductId(data: Record<string, unknown>): string | null {
  if (typeof data.product_id === "string") return data.product_id;
  if (isSafePlainObject(data.product) && typeof data.product.id === "string") {
    return data.product.id;
  }
  return null;
}

function isWrongProductOrCurrency(data: Record<string, unknown>, productId: string): boolean {
  const eventProductId = extractEventProductId(data);
  if (eventProductId !== null && eventProductId !== productId) {
    return true;
  }
  if (
    data.currency !== undefined &&
    data.currency !== null &&
    String(data.currency).toLowerCase() !== "usd"
  ) {
    return true;
  }
  return false;
}

export async function handlePolarWebhook(
  request: Request,
  env: Env,
  subnetHash: string,
): Promise<Response> {
  if (!isPolarWebhookConfigured(env)) {
    return plainErrorResponse(503, "unavailable", "Webhook endpoint is not configured.");
  }

  const bodyRes = await readBoundedUtf8Body(request, WEBHOOK_MAX_BODY_BYTES);
  if (!bodyRes.ok) {
    return plainErrorResponse(bodyRes.status, bodyRes.error, bodyRes.message);
  }

  const validSig = await verifyStandardWebhookSignature(
    request,
    bodyRes.text,
    env.POLAR_WEBHOOK_SECRET!,
  );
  if (!validSig) {
    const rateOutcome = await checkRateLimit(env, `webhook:bad:${subnetHash}`);
    if (rateOutcome === "unavailable") {
      return plainErrorResponse(503, "unavailable", "Webhook endpoint is temporarily unavailable.");
    }
    if (rateOutcome === "limited") {
      return plainErrorResponse(
        429,
        "rate_limited",
        "Too many invalid webhook requests.",
        { retryAfterSeconds: RATE_LIMIT_RETRY_SECONDS },
        { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) },
      );
    }
    return plainErrorResponse(403, "invalid_signature", "Invalid or expired webhook signature.");
  }

  const webhookId = request.headers.get("webhook-id")!.trim();
  let event: unknown;
  try {
    event = JSON.parse(bodyRes.text);
  } catch {
    return plainErrorResponse(400, "invalid_request", "Webhook payload must be valid JSON.");
  }
  if (!isSafePlainObject(event) || typeof event.type !== "string" || !isSafePlainObject(event.data)) {
    return plainErrorResponse(400, "invalid_request", "Invalid webhook payload structure.");
  }

  const data = event.data;
  const productId = env.POLAR_CREDITS_PRODUCT_ID!.trim();
  const now = nowSeconds();

  if (event.type === "order.paid") {
    if (
      typeof data.id !== "string" ||
      data.id.length === 0 ||
      typeof data.net_amount !== "number" ||
      !Number.isSafeInteger(data.net_amount) ||
      data.net_amount < 0
    ) {
      return plainErrorResponse(400, "invalid_request", "Malformed order.paid payload.");
    }

    const customer = isSafePlainObject(data.customer) ? data.customer : null;
    const externalId = customer && typeof customer.external_id === "string" ? customer.external_id : null;
    const polarCustomerId =
      customer && typeof customer.id === "string" && customer.id.length > 0
        ? customer.id
        : typeof data.customer_id === "string" && data.customer_id.length > 0
          ? data.customer_id
          : null;

    const eventProductId =
      typeof data.product_id === "string"
        ? data.product_id
        : isSafePlainObject(data.product) && typeof data.product.id === "string"
          ? data.product.id
          : null;

    if (
      eventProductId !== productId ||
      String(data.currency ?? "").toLowerCase() !== "usd" ||
      !externalId
    ) {
      return plainJsonResponse({ ok: true, ignored: true }, 202);
    }

    const acct = await env.DB
      .prepare("SELECT id FROM accounts WHERE id = ?1 LIMIT 1")
      .bind(externalId)
      .first<{ id: string }>();
    if (!acct) {
      return plainJsonResponse({ ok: true, ignored: true }, 202);
    }

    const netCents = data.net_amount;
    if (netCents === 0) {
      const statements: D1PreparedStatement[] = [
        env.DB
          .prepare(
            `INSERT INTO polar_orders (
               order_id, account_id, net_amount_cents, granted_picos, refunded_amount_cents, refunded_picos, status, created_at, updated_at
             ) VALUES (?1, ?2, 0, 0, 0, 0, 'paid', ?3, ?3)
             ON CONFLICT(order_id) DO NOTHING`,
          )
          .bind(data.id, acct.id, now),
      ];
      if (polarCustomerId) {
        statements.push(
          env.DB
            .prepare(
              "UPDATE accounts SET polar_customer_id = COALESCE(polar_customer_id, ?1), updated_at = ?2 WHERE id = ?3",
            )
            .bind(polarCustomerId, now, acct.id),
        );
      }
      await env.DB.batch(statements);
      return plainJsonResponse({ ok: true }, 202);
    }

    const grantedPicos = netCents * PICOS_PER_CREDIT;
    const ledgerId = crypto.randomUUID();
    const statements: D1PreparedStatement[] = [
      env.DB
        .prepare(
          `INSERT INTO polar_orders (
             order_id, account_id, net_amount_cents, granted_picos, refunded_amount_cents, refunded_picos, status, created_at, updated_at
           ) VALUES (?1, ?2, ?3, ?4, 0, 0, 'paid', ?5, ?5)`,
        )
        .bind(data.id, acct.id, netCents, grantedPicos, now),
      env.DB
        .prepare(
          `INSERT INTO credit_ledger_entries (
             id, account_id, kind, delta_picos, balance_after_picos, polar_order_id, webhook_id, note, created_at
           )
           SELECT ?1, a.id, 'purchase', ?2, a.credit_balance_picos + ?2, ?3, ?4, ?5, ?6
           FROM accounts a
           WHERE a.id = ?7`,
        )
        .bind(ledgerId, grantedPicos, data.id, webhookId, `order_paid:${netCents}c`, now, acct.id),
    ];
    if (polarCustomerId) {
      statements.push(
        env.DB
          .prepare(
            "UPDATE accounts SET polar_customer_id = COALESCE(polar_customer_id, ?1), updated_at = ?2 WHERE id = ?3",
          )
          .bind(polarCustomerId, now, acct.id),
      );
    }

    try {
      await env.DB.batch(statements);
    } catch (err) {
      if (isUniqueConstraintError(err)) {
      return plainJsonResponse({ ok: true, deduplicated: true }, 202);
      }
      throw err;
    }

    return plainJsonResponse({ ok: true }, 202);
  }

  if (event.type === "order.refunded") {
    if (
      typeof data.id !== "string" ||
      data.id.length === 0 ||
      typeof data.refunded_amount !== "number" ||
      !Number.isSafeInteger(data.refunded_amount) ||
      data.refunded_amount < 0
    ) {
      return plainErrorResponse(400, "invalid_request", "Malformed order.refunded payload.");
    }

    for (let attempt = 0; attempt < 3; attempt++) {
      const dup = await env.DB
        .prepare("SELECT 1 AS found FROM credit_ledger_entries WHERE webhook_id = ?1 LIMIT 1")
        .bind(webhookId)
        .first<{ found: number }>();
      if (dup) {
      return plainJsonResponse({ ok: true, deduplicated: true }, 202);
      }

      const order = await env.DB
        .prepare(
          "SELECT order_id, account_id, net_amount_cents, refunded_amount_cents FROM polar_orders WHERE order_id = ?1 LIMIT 1",
        )
        .bind(data.id)
        .first<{
          order_id: string;
          account_id: string;
          net_amount_cents: number;
          refunded_amount_cents: number;
        }>();
      if (!order) {
        if (isWrongProductOrCurrency(data, productId)) {
          return plainJsonResponse({ ok: true, ignored: true }, 202);
        }
        return plainErrorResponse(
          503,
          "order_not_yet_recorded",
          "Order not yet recorded; retry later.",
        );
      }

      const cappedRefundedCents = Math.min(order.net_amount_cents, data.refunded_amount);
      const deltaCents = cappedRefundedCents - order.refunded_amount_cents;
      if (deltaCents <= 0) {
      return plainJsonResponse({ ok: true, deduplicated: true }, 202);
      }

      const deltaPicos = -deltaCents * PICOS_PER_CREDIT;
      const newStatus = cappedRefundedCents >= order.net_amount_cents ? "refunded" : "partially_refunded";
      const ledgerId = crypto.randomUUID();

      try {
        const results = await env.DB.batch([
          env.DB
            .prepare(
              `INSERT INTO credit_ledger_entries (
                 id, account_id, kind, delta_picos, balance_after_picos, polar_order_id, webhook_id, note, created_at
               )
               SELECT ?1, a.id, 'refund', ?2, a.credit_balance_picos + ?2, ?3, ?4, ?5, ?6
               FROM accounts a
               JOIN polar_orders o ON o.account_id = a.id
               WHERE a.id = ?7 AND o.order_id = ?3 AND o.refunded_amount_cents = ?8`,
            )
            .bind(
              ledgerId,
              deltaPicos,
              order.order_id,
              webhookId,
              `order_refunded:${deltaCents}c`,
              now,
              order.account_id,
              order.refunded_amount_cents,
            ),
          env.DB
            .prepare(
              `UPDATE polar_orders
               SET refunded_amount_cents = ?2,
                   refunded_picos = refunded_picos + ?3,
                   status = ?4,
                   updated_at = ?5
               WHERE order_id = ?1 AND refunded_amount_cents = ?6`,
            )
            .bind(
              order.order_id,
              cappedRefundedCents,
              deltaCents * PICOS_PER_CREDIT,
              newStatus,
              now,
              order.refunded_amount_cents,
            ),
        ]);

        if ((results[1]?.meta.changes ?? 0) === 1) {
        return plainJsonResponse({ ok: true }, 202);
        }
      } catch (err) {
        if (isUniqueConstraintError(err)) {
        return plainJsonResponse({ ok: true, deduplicated: true }, 202);
        }
        throw err;
      }
    }

    return plainErrorResponse(503, "unavailable", "Concurrent refund update; please retry.");
  }

  if (event.type === "order.updated") {
    if (data.status !== "void") {
      return plainJsonResponse({ ok: true, ignored: true }, 202);
    }
    if (typeof data.id !== "string" || data.id.length === 0) {
      return plainErrorResponse(400, "invalid_request", "Malformed order.updated payload.");
    }

    for (let attempt = 0; attempt < 3; attempt++) {
      const dup = await env.DB
        .prepare("SELECT 1 AS found FROM credit_ledger_entries WHERE webhook_id = ?1 LIMIT 1")
        .bind(webhookId)
        .first<{ found: number }>();
      if (dup) {
        return plainJsonResponse({ ok: true, deduplicated: true }, 202);
      }

      const order = await env.DB
        .prepare(
          "SELECT order_id, account_id, net_amount_cents, refunded_amount_cents FROM polar_orders WHERE order_id = ?1 LIMIT 1",
        )
        .bind(data.id)
        .first<{
          order_id: string;
          account_id: string;
          net_amount_cents: number;
          refunded_amount_cents: number;
        }>();
      if (!order) {
        if (data.paid !== true || isWrongProductOrCurrency(data, productId)) {
          return plainJsonResponse({ ok: true, ignored: true }, 202);
        }
        return plainErrorResponse(
          503,
          "order_not_yet_recorded",
          "Order not yet recorded; retry later.",
        );
      }

      const deltaCents = order.net_amount_cents - order.refunded_amount_cents;
      if (deltaCents <= 0) {
        return plainJsonResponse({ ok: true, deduplicated: true }, 202);
      }

      const deltaPicos = -deltaCents * PICOS_PER_CREDIT;
      const ledgerId = crypto.randomUUID();

      try {
        const results = await env.DB.batch([
          env.DB
            .prepare(
              `INSERT INTO credit_ledger_entries (
                 id, account_id, kind, delta_picos, balance_after_picos, polar_order_id, webhook_id, note, created_at
               )
               SELECT ?1, a.id, 'refund', ?2, a.credit_balance_picos + ?2, ?3, ?4, ?5, ?6
               FROM accounts a
               JOIN polar_orders o ON o.account_id = a.id
               WHERE a.id = ?7 AND o.order_id = ?3 AND o.refunded_amount_cents = ?8`,
            )
            .bind(
              ledgerId,
              deltaPicos,
              order.order_id,
              webhookId,
              `order_void:${deltaCents}c`,
              now,
              order.account_id,
              order.refunded_amount_cents,
            ),
          env.DB
            .prepare(
              `UPDATE polar_orders
               SET refunded_amount_cents = ?2,
                   refunded_picos = refunded_picos + ?3,
                   status = 'void',
                   updated_at = ?4
               WHERE order_id = ?1 AND refunded_amount_cents = ?5`,
            )
            .bind(
              order.order_id,
              order.net_amount_cents,
              deltaCents * PICOS_PER_CREDIT,
              now,
              order.refunded_amount_cents,
            ),
        ]);

        if ((results[1]?.meta.changes ?? 0) === 1) {
          return plainJsonResponse({ ok: true }, 202);
        }
      } catch (err) {
        if (isUniqueConstraintError(err)) {
          return plainJsonResponse({ ok: true, deduplicated: true }, 202);
        }
        throw err;
      }
    }

    return plainErrorResponse(503, "unavailable", "Concurrent order void update; please retry.");
  }

  if (event.type === "refund.created" || event.type === "refund.updated") {
    const isDispute =
      (data.dispute !== null && data.dispute !== undefined) || data.reason === "dispute";
    if (!isDispute) {
      return plainJsonResponse({ ok: true, ignored: true }, 202);
    }

    const orderId = typeof data.order_id === "string" && data.order_id.length > 0 ? data.order_id : null;
    if (!orderId) {
      return plainErrorResponse(400, "invalid_request", "Malformed refund payload.");
    }

    const dup = await env.DB
      .prepare("SELECT 1 AS found FROM credit_ledger_entries WHERE webhook_id = ?1 LIMIT 1")
      .bind(webhookId)
      .first<{ found: number }>();
    if (dup) {
      return plainJsonResponse({ ok: true, deduplicated: true }, 202);
    }

    const order = await env.DB
      .prepare("SELECT order_id, account_id FROM polar_orders WHERE order_id = ?1 LIMIT 1")
      .bind(orderId)
      .first<{ order_id: string; account_id: string }>();
    if (!order) {
      if (isWrongProductOrCurrency(data, productId)) {
        return plainJsonResponse({ ok: true, ignored: true }, 202);
      }
      return plainErrorResponse(
        503,
        "order_not_yet_recorded",
        "Order not yet recorded; retry later.",
      );
    }

    const isWon = isSafePlainObject(data.dispute) && data.dispute.status === "won";
    const kind = isWon ? "dispute_release" : "dispute_hold";
    const disputeStatus =
      isSafePlainObject(data.dispute) && typeof data.dispute.status === "string"
        ? data.dispute.status
        : "dispute";
    const ledgerId = crypto.randomUUID();

    try {
      await env.DB
        .prepare(
          `INSERT INTO credit_ledger_entries (
             id, account_id, kind, delta_picos, balance_after_picos, polar_order_id, webhook_id, note, created_at
           )
           SELECT ?1, a.id, ?2, 0, a.credit_balance_picos, ?3, ?4, ?5, ?6
           FROM accounts a
           WHERE a.id = ?7`,
        )
        .bind(
          ledgerId,
          kind,
          order.order_id,
          webhookId,
          `${event.type}:${disputeStatus}`,
          now,
          order.account_id,
        )
        .run();
    } catch (err) {
      if (isUniqueConstraintError(err)) {
        return plainJsonResponse({ ok: true, deduplicated: true }, 202);
      }
      throw err;
    }

    return plainJsonResponse({ ok: true }, 202);
  }

  return plainJsonResponse({ ok: true, ignored: true }, 202);
}

export async function handlePortal(
  env: Env,
  session: AuthenticatedSession,
): Promise<Response> {
  if (!isCoreConfigured(env) || !isSessionSecretConfigured(env)) {
    return errorResponse(503, "unavailable", "Billing portal is not configured.");
  }

  const rateOutcome = await checkRateLimit(env, `billing:${session.account.id}`);
  if (rateOutcome === "unavailable") {
    return errorResponse(503, "unavailable", "Billing portal is temporarily unavailable.");
  }
  if (rateOutcome === "limited") {
    return errorResponse(
      429,
      "rate_limited",
      "Too many billing requests. Please wait a minute.",
      { retryAfterSeconds: RATE_LIMIT_RETRY_SECONDS },
      { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) },
    );
  }

  const accessToken = env.POLAR_ACCESS_TOKEN?.trim();
  if (session.account.polarCustomerId && accessToken) {
    const polarApiBase = resolvePolarApiBase(env);
    if (!polarApiBase) {
      return errorResponse(503, "unavailable", "Billing portal configuration is invalid.");
    }

    const res = await fetch(`${polarApiBase}/v1/customer-sessions/`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({ customer_id: session.account.polarCustomerId }),
    });

    if (!res.ok) {
      return errorResponse(503, "unavailable", "Billing portal is currently unavailable.");
    }
    const data = (await res.json().catch(() => null)) as { customer_portal_url?: unknown } | null;
    if (
      !data ||
      typeof data.customer_portal_url !== "string" ||
      !data.customer_portal_url.startsWith("https://")
    ) {
      return errorResponse(503, "unavailable", "Billing portal returned an invalid response.");
    }
    return jsonResponse({ portalUrl: data.customer_portal_url }, 200);
  }

  return jsonResponse({ portalUrl: FALLBACK_PORTAL_URL }, 200);
}
