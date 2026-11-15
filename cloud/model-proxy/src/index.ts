import {
  authenticateSession,
  handleAuthCallback,
  handleAuthLogout,
  handleAuthStart,
  handleMe,
} from "./auth";
import {
  ALLOWED_ORIGIN,
  MAX_OUTPUT_TOKENS,
  PICO_PER_USD,
  RATE_LIMIT_RETRY_SECONDS,
  RESERVATION_RETENTION_DAYS,
  RETENTION_DAYS,
  resolveCaps,
} from "./config";
import {
  checkOrigin,
  cutoffUtcDay,
  errorResponse,
  jsonResponse,
  nowSeconds,
  plainErrorResponse,
  preflightResponse,
  secondsUntilUtcMidnight,
  utcDay,
  type Env,
} from "./http";
import {
  checkRateLimit,
  hashCallerIp,
  isCoreConfigured,
  isProxyConfigured,
  isSessionSecretConfigured,
  requireCallerIp,
} from "./identity";
import {
  getAccountAllowanceStatus,
  reserveAllowance,
  settleReservation,
  sweepExpiredState,
} from "./ledger";
import { computeWorstCaseReservationPicos, getDefaultModel } from "./models";
import { handleCheckout, handleCheckoutComplete, handlePolarWebhook, handlePortal } from "./polar";
import { createSettlementStream } from "./sse";
import { callUpstreamModel } from "./upstream";
import { validateChatRequest } from "./validate";
import type { AuthenticatedSession } from "./auth";

const UPSTREAM_TIMEOUT_MS = 90_000;

async function maybeSendFloatAlert(env: Env): Promise<void> {
  const alertUrl = env.ALERT_WEBHOOK_URL?.trim();
  const loadedUsdRaw = env.LOADED_GATEWAY_CREDITS_USD?.trim();
  if (!alertUrl || !alertUrl.startsWith("https://") || !loadedUsdRaw) {
    return;
  }
  const loadedUsd = Number(loadedUsdRaw);
  if (!Number.isFinite(loadedUsd) || loadedUsd <= 0) {
    return;
  }

  try {
    const thresholdPicos = Math.floor(loadedUsd * PICO_PER_USD * 0.8);
    const claim = await env.DB
      .prepare(
        `UPDATE gateway_spend_totals
         SET last_alerted_threshold_picos = ?1
         WHERE id = 1
           AND settled_picos >= ?1
           AND last_alerted_threshold_picos < ?1
         RETURNING settled_picos`,
      )
      .bind(thresholdPicos)
      .first<{ settled_picos: number }>();

    if (claim) {
      await fetch(alertUrl, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          event: "float_80pct_watermark",
          utilizationRatio: Number((claim.settled_picos / (loadedUsd * PICO_PER_USD)).toFixed(4)),
        }),
      });
    }
  } catch {
    // Non-blocking best-effort alert; never fail user turns or log secrets.
  }
}

async function handleAllowance(
  env: Env,
  day: string,
  session: AuthenticatedSession,
  subnetHash: string,
): Promise<Response> {
  const caps = resolveCaps(env);
  const status = await getAccountAllowanceStatus(
    env.DB,
    day,
    session.account,
    subnetHash,
    caps.callerDailyCapPicos,
    caps.callerDailyCapPicos,
    caps.globalDailyPoolPicos,
  );
  return jsonResponse(status, 200);
}

async function handleChat(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  day: string,
  session: AuthenticatedSession,
  subnetHash: string,
): Promise<Response> {
  const model = getDefaultModel();
  const validation = await validateChatRequest(request, model);
  if (!validation.ok) {
    return errorResponse(validation.status, validation.error, validation.message);
  }

  const caps = resolveCaps(env);
  const reservedPicos = computeWorstCaseReservationPicos(
    model,
    validation.value.upperBoundInputTokens,
    MAX_OUTPUT_TOKENS,
  );

  const reservationId = crypto.randomUUID();
  const reserveResult = await reserveAllowance(env.DB, {
    id: reservationId,
    day,
    accountId: session.account.id,
    subnetHash,
    model: model.id,
    reservedPicos,
    accountCapPicos: caps.callerDailyCapPicos,
    subnetCapPicos: caps.callerDailyCapPicos,
    poolCapPicos: caps.globalDailyPoolPicos,
    createdAt: nowSeconds(),
  });

  if (!reserveResult.ok) {
    if (reserveResult.reason === "account_suspended") {
      return errorResponse(
        403,
        "account_suspended",
        "This account is suspended due to an unresolved billing dispute or negative credit balance.",
        { code: "unavailable" },
      );
    }
    if (reserveResult.reason === "account_not_found") {
      return errorResponse(401, "unauthenticated", "Please sign in to continue.");
    }

    const retryAfterSeconds = secondsUntilUtcMidnight();
    if (reserveResult.reason === "pool_exhausted") {
      return errorResponse(
        429,
        "pool_exhausted",
        "The shared daily free AI pool is exhausted for today. Buy credits to continue immediately, or wait until 00:00 UTC.",
        { code: "allowance_exhausted", reason: "pool_exhausted", retryAfterSeconds },
        { "retry-after": String(retryAfterSeconds) },
      );
    }
    if (reserveResult.reason === "free_not_eligible") {
      return errorResponse(
        429,
        "free_not_eligible",
        "Free daily allowance is not available on this account yet. Buy credits to start immediately.",
        { code: "allowance_exhausted", reason: "free_not_eligible", retryAfterSeconds },
        { "retry-after": String(retryAfterSeconds) },
      );
    }
    if (reserveResult.reason === "insufficient_credits") {
      return errorResponse(
        429,
        "insufficient_credits",
        "Your remaining credit balance is below this request's worst-case reservation. Top up credits or wait until 00:00 UTC.",
        { code: "allowance_exhausted", reason: "insufficient_credits", retryAfterSeconds },
        { "retry-after": String(retryAfterSeconds) },
      );
    }
    return errorResponse(
      429,
      "allowance_exhausted",
      "Daily free AI allowance ($0.05) reached. Buy credits to continue or wait until 00:00 UTC.",
      { code: "allowance_exhausted", reason: "allowance_exhausted", retryAfterSeconds },
      { "retry-after": String(retryAfterSeconds) },
    );
  }

  const upstreamAbortController = new AbortController();
  const onRequestAbort = () => upstreamAbortController.abort();
  if (request.signal.aborted) {
    upstreamAbortController.abort();
  } else {
    request.signal.addEventListener("abort", onRequestAbort, { once: true });
  }
  const timeoutId = setTimeout(() => upstreamAbortController.abort(), UPSTREAM_TIMEOUT_MS);
  const cleanupAbort = () => {
    clearTimeout(timeoutId);
    request.signal.removeEventListener("abort", onRequestAbort);
  };

  let upstreamResponse: Response;
  try {
    upstreamResponse = await callUpstreamModel(
      env,
      validation.value.upstreamBodyJson,
      upstreamAbortController.signal,
    );
  } catch {
    cleanupAbort();
    await settleReservation(env.DB, {
      id: reservationId,
      status: "kept",
      settledPicos: reservedPicos,
      inputTokens: null,
      outputTokens: null,
      cacheReadTokens: null,
      cacheWriteTokens: null,
      settledAt: nowSeconds(),
    }).catch(() => {});
    return errorResponse(503, "unavailable", "Model inference is currently unavailable.");
  }

  if (!upstreamResponse.ok || !upstreamResponse.body) {
    cleanupAbort();
    await upstreamResponse.body?.cancel().catch(() => {});
    const definitePreProviderReject = upstreamResponse.status >= 400 && upstreamResponse.status < 500;
    await settleReservation(env.DB, {
      id: reservationId,
      status: definitePreProviderReject ? "settled" : "kept",
      settledPicos: definitePreProviderReject ? 0 : reservedPicos,
      inputTokens: definitePreProviderReject ? 0 : null,
      outputTokens: definitePreProviderReject ? 0 : null,
      cacheReadTokens: definitePreProviderReject ? 0 : null,
      cacheWriteTokens: definitePreProviderReject ? 0 : null,
      settledAt: nowSeconds(),
    }).catch(() => {});
    return errorResponse(503, "unavailable", "Model inference is currently unavailable.");
  }

  const stream = createSettlementStream({
    upstreamBody: upstreamResponse.body,
    db: env.DB,
    ctx,
    reservationId,
    reservedPicos,
    freeReservedPicos: reserveResult.freeReservedPicos,
    model,
    abortController: upstreamAbortController,
    isClientAborted: () => request.signal.aborted,
    onSettled: (settledPicos) => {
      if (settledPicos > 0 && env.ALERT_WEBHOOK_URL && env.LOADED_GATEWAY_CREDITS_USD) {
        ctx.waitUntil(maybeSendFloatAlert(env));
      }
    },
    onFinalize: cleanupAbort,
  });

  return new Response(stream, {
    status: 200,
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": ALLOWED_ORIGIN,
      "access-control-allow-credentials": "true",
      vary: "Origin",
    },
  });
}

function parseOAuthRoute(path: string): { provider: "google" | "discord"; action: "start" | "callback" } | null {
  if (path === "/v1/auth/google/start") return { provider: "google", action: "start" };
  if (path === "/v1/auth/google/callback") return { provider: "google", action: "callback" };
  if (path === "/v1/auth/discord/start") return { provider: "discord", action: "start" };
  if (path === "/v1/auth/discord/callback") return { provider: "discord", action: "callback" };
  return null;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const day = utcDay();

    const oauthRoute = parseOAuthRoute(path);
    if (oauthRoute) {
      if (request.method !== "GET") {
        return plainErrorResponse(405, "method_not_allowed", "Method not allowed.");
      }
      if (!isCoreConfigured(env)) {
        return plainErrorResponse(503, "unavailable", "Authentication service is not configured.");
      }
      const canonicalNetwork = requireCallerIp(request);
      if (!canonicalNetwork) {
        return plainErrorResponse(400, "unauthenticated", "Missing or invalid caller network identity.");
      }
      const subnetHash = await hashCallerIp(canonicalNetwork, day, env.IP_HASH_SALT!.trim());
      try {
        return oauthRoute.action === "start"
          ? await handleAuthStart(request, env, oauthRoute.provider, subnetHash)
          : await handleAuthCallback(request, env, oauthRoute.provider, subnetHash);
      } catch {
        return plainErrorResponse(503, "unavailable", "Authentication service is temporarily unavailable.");
      }
    }

    if (path === "/v1/checkout/complete") {
      if (request.method !== "GET") {
        return plainErrorResponse(405, "method_not_allowed", "Method not allowed.");
      }
      return handleCheckoutComplete();
    }

    if (path === "/v1/webhooks/polar") {
      if (request.method !== "POST") {
        return plainErrorResponse(405, "method_not_allowed", "Method not allowed.");
      }
      if (!isCoreConfigured(env)) {
        return plainErrorResponse(503, "unavailable", "Webhook endpoint is not configured.");
      }
      const canonicalNetwork = requireCallerIp(request) ?? "webhook:peer";
      const subnetHash = await hashCallerIp(canonicalNetwork, day, env.IP_HASH_SALT!.trim());
      try {
        return await handlePolarWebhook(request, env, subnetHash);
      } catch {
        return plainErrorResponse(503, "unavailable", "Webhook endpoint is temporarily unavailable.");
      }
    }

    const badOrigin = checkOrigin(request);
    if (badOrigin) return badOrigin;

    const knownSpaRoutes = new Set([
      "/v1/chat",
      "/v1/allowance",
      "/v1/me",
      "/v1/auth/logout",
      "/v1/checkout",
      "/v1/portal",
    ]);
    if (!knownSpaRoutes.has(path)) {
      return errorResponse(404, "not_found", "Unknown endpoint.");
    }

    if (request.method === "OPTIONS") {
      return preflightResponse();
    }

    const expectedMethod = path === "/v1/allowance" || path === "/v1/me" ? "GET" : "POST";
    if (request.method !== expectedMethod) {
      return errorResponse(405, "method_not_allowed", "Method not allowed.");
    }

    if (path === "/v1/chat" || path === "/v1/allowance") {
      if (!isProxyConfigured(env)) {
        return errorResponse(503, "unavailable", "Model proxy is not configured.");
      }
    } else if (!isCoreConfigured(env) || !isSessionSecretConfigured(env)) {
      return errorResponse(503, "unavailable", "Model proxy is not configured.");
    }

    const canonicalNetwork = requireCallerIp(request);
    if (!canonicalNetwork) {
      return errorResponse(400, "unauthenticated", "Missing or invalid caller network identity.");
    }

    const subnetHash = await hashCallerIp(canonicalNetwork, day, env.IP_HASH_SALT!.trim());

    try {
      if (path === "/v1/me") {
        return await handleMe(request, env, day, subnetHash);
      }
      if (path === "/v1/auth/logout") {
        return await handleAuthLogout(request, env, subnetHash);
      }

      if (path === "/v1/allowance") {
        const rateOutcome = await checkRateLimit(env, `allowance:${subnetHash}`);
        if (rateOutcome === "unavailable") {
          return errorResponse(503, "unavailable", "Model proxy is temporarily unavailable.");
        }
        if (rateOutcome === "limited") {
          return errorResponse(
            429,
            "rate_limited",
            "Rate limit exceeded (10 requests per minute).",
            { retryAfterSeconds: RATE_LIMIT_RETRY_SECONDS },
            { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) },
          );
        }
        const session = await authenticateSession(request, env.DB);
        if (!session) {
          return errorResponse(401, "unauthenticated", "Sign in to view your Materione AI balance and allowance.");
        }
        return await handleAllowance(env, day, session, subnetHash);
      }

      if (path === "/v1/checkout" || path === "/v1/portal") {
        const subRate = await checkRateLimit(env, `billing:sub:${subnetHash}`);
        if (subRate === "unavailable") {
          return errorResponse(503, "unavailable", "Billing service is temporarily unavailable.");
        }
        if (subRate === "limited") {
          return errorResponse(
            429,
            "rate_limited",
            "Too many billing requests. Please wait a minute.",
            { retryAfterSeconds: RATE_LIMIT_RETRY_SECONDS },
            { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) },
          );
        }
        const session = await authenticateSession(request, env.DB);
        if (!session) {
          return errorResponse(401, "unauthenticated", "Sign in to manage Materione AI credits.");
        }
        return path === "/v1/checkout"
          ? await handleCheckout(request, env, session)
          : await handlePortal(env, session);
      }

      // POST /v1/chat — enforce subnet rate limit BEFORE querying D1 sessions
      const subRate = await checkRateLimit(env, `chat:sub:${subnetHash}`);
      if (subRate === "unavailable") {
        return errorResponse(503, "unavailable", "Model proxy is temporarily unavailable.");
      }
      if (subRate === "limited") {
        return errorResponse(
          429,
          "rate_limited",
          "Rate limit exceeded (10 requests per minute).",
          { retryAfterSeconds: RATE_LIMIT_RETRY_SECONDS },
          { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) },
        );
      }

      const session = await authenticateSession(request, env.DB);
      if (!session) {
        return errorResponse(401, "unauthenticated", "Sign in with Google or Discord to use the Materione AI assistant.");
      }

      const acctRate = await checkRateLimit(env, `chat:acct:${session.account.id}`);
      if (acctRate === "unavailable") {
        return errorResponse(503, "unavailable", "Model proxy is temporarily unavailable.");
      }
      if (acctRate === "limited") {
        return errorResponse(
          429,
          "rate_limited",
          "Rate limit exceeded (10 requests per minute).",
          { retryAfterSeconds: RATE_LIMIT_RETRY_SECONDS },
          { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) },
        );
      }

      return await handleChat(request, env, ctx, day, session, subnetHash);
    } catch {
      return errorResponse(503, "unavailable", "Model proxy is temporarily unavailable.");
    }
  },

  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    const nowSec = nowSeconds();
    await sweepExpiredState(
      env.DB,
      cutoffUtcDay(RETENTION_DAYS),
      cutoffUtcDay(1),
      cutoffUtcDay(RESERVATION_RETENTION_DAYS),
      nowSec,
    );
  },
};
