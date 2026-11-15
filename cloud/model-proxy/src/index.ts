import {
  ALLOWED_ORIGIN,
  MAX_OUTPUT_TOKENS,
  RATE_LIMIT_RETRY_SECONDS,
  RETENTION_DAYS,
  resolveCaps,
} from "./config";
import {
  checkOrigin,
  cutoffUtcDay,
  errorResponse,
  jsonResponse,
  nowSeconds,
  preflightResponse,
  secondsUntilUtcMidnight,
  utcDay,
  type Env,
} from "./http";
import { checkRateLimit, hashCallerIp, isProxyConfigured, requireCallerIp } from "./identity";
import { getAllowanceStatus, reserveAllowance, settleReservation, sweepOldDays } from "./ledger";
import { computeWorstCaseReservationPicos, getDefaultModel } from "./models";
import { createSettlementStream } from "./sse";
import { callUpstreamModel } from "./upstream";
import { validateChatRequest } from "./validate";

const UPSTREAM_TIMEOUT_MS = 90_000;

async function handleAllowance(
  env: Env,
  day: string,
  callerHash: string,
): Promise<Response> {
  const caps = resolveCaps(env);
  const status = await getAllowanceStatus(
    env.DB,
    day,
    callerHash,
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
  callerHash: string,
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
    callerHash,
    model: model.id,
    reservedPicos,
    callerCapPicos: caps.callerDailyCapPicos,
    poolCapPicos: caps.globalDailyPoolPicos,
    createdAt: nowSeconds(),
  });

  if (!reserveResult.ok) {
    const retryAfterSeconds = secondsUntilUtcMidnight();
    if (reserveResult.reason === "pool_exhausted") {
      return errorResponse(
        429,
        "pool_exhausted",
        "The shared daily free AI pool is exhausted for today. Resets at 00:00 UTC.",
        { code: "allowance_exhausted", reason: "pool_exhausted", retryAfterSeconds },
        { "retry-after": String(retryAfterSeconds) },
      );
    }
    return errorResponse(
      429,
      "allowance_exhausted",
      "Daily free AI allowance ($0.05) reached. Resets at 00:00 UTC.",
      { code: "allowance_exhausted", retryAfterSeconds },
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
    model,
    abortController: upstreamAbortController,
    isClientAborted: () => request.signal.aborted,
    onFinalize: cleanupAbort,
  });

  return new Response(stream, {
    status: 200,
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": ALLOWED_ORIGIN,
      vary: "Origin",
    },
  });
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const badOrigin = checkOrigin(request);
    if (badOrigin) return badOrigin;

    const url = new URL(request.url);
    const path = url.pathname;

    if (path !== "/v1/chat" && path !== "/v1/allowance") {
      return errorResponse(404, "not_found", "Unknown endpoint.");
    }

    if (request.method === "OPTIONS") {
      return preflightResponse();
    }

    if (path === "/v1/chat" && request.method !== "POST") {
      return errorResponse(405, "method_not_allowed", "Method not allowed.");
    }
    if (path === "/v1/allowance" && request.method !== "GET") {
      return errorResponse(405, "method_not_allowed", "Method not allowed.");
    }

    if (!isProxyConfigured(env)) {
      return errorResponse(503, "unavailable", "Model proxy is not configured.");
    }

    const canonicalNetwork = requireCallerIp(request);
    if (!canonicalNetwork) {
      return errorResponse(400, "unauthenticated", "Missing or invalid caller network identity.");
    }

    const day = utcDay();
    const callerHash = await hashCallerIp(canonicalNetwork, day, env.IP_HASH_SALT!.trim());

    const rateKey = path === "/v1/chat" ? `chat:${callerHash}` : `allowance:${callerHash}`;
    try {
      const rateOutcome = await checkRateLimit(env, rateKey);
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

      if (path === "/v1/allowance") {
        return await handleAllowance(env, day, callerHash);
      }
      return await handleChat(request, env, ctx, day, callerHash);
    } catch {
      return errorResponse(503, "unavailable", "Model proxy is temporarily unavailable.");
    }
  },

  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    const cutoff = cutoffUtcDay(RETENTION_DAYS);
    await sweepOldDays(env.DB, cutoff);
  },
};
