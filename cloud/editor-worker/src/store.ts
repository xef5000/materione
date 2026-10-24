import { configFrom } from "./config";
import { isPlausibleCode, randomCode, randomToken, sha256Hex } from "./codes";
import { bearerToken, Env, errorResponse, jsonResponse, nowSeconds, rateLimited, secondsUntilUtcMidnight } from "./http";
import { bumpUsage, readUsage } from "./quotas";

interface BlobRow {
  code: string;
  size: number;
  created_at: number;
  expires_at: number;
  delete_token_hash: string;
}

function quotaRetry(): Response {
  return jsonResponse(
    {
      error: "quota_exceeded",
      detail:
        "the shared relay instance is at its daily quota; retry after UTC midnight or self-host (docs/architecture/web.md §8)",
    },
    429,
    { "retry-after": String(secondsUntilUtcMidnight()) },
  );
}

/** POST /v1/store — upload one envelope. `?code=` lets clients pass a
 * content-addressed id (hash of the ciphertext): if it exists the upload is
 * a dedup no-op (web.md §3.1). */
export async function putBlob(env: Env, request: Request, url: URL): Promise<Response> {
  if (await rateLimited(env, request, "store-put")) {
    return errorResponse(429, "rate_limited");
  }
  const config = configFrom(env as unknown as Record<string, unknown>);
  const usage = await readUsage(env);
  if (usage.store_posts >= config.dailyStorePosts) return quotaRetry();

  const body = await request.text();
  if (body.length === 0) return errorResponse(400, "empty_body");
  if (body.length > config.maxBlobBytes) {
    return errorResponse(413, "blob_too_large", `blobs are capped at ${config.maxBlobBytes} bytes`);
  }
  if (usage.store_bytes + body.length > config.maxStoreBytesTotal) {
    return errorResponse(429, "store_full", "stored bytes at capacity; expires soon");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return errorResponse(415, "not_an_envelope");
  }
  const record = parsed as Record<string, unknown>;
  if (
    record.v !== 1 ||
    typeof record.sess !== "string" ||
    typeof record.nonce !== "string" ||
    typeof record.ct !== "string"
  ) {
    return errorResponse(415, "not_an_envelope", "expected {v:1, sess, nonce, ct} (web.md §5.4)");
  }

  const ttlRaw = Number(url.searchParams.get("ttl") ?? "3600");
  const ttl = config.storeTtlClasses.includes(ttlRaw) ? ttlRaw : config.storeTtlClasses[0] ?? 3600;
  const requestedCode = url.searchParams.get("code");
  const now = nowSeconds();
  const expires = now + ttl;

  if (requestedCode !== null) {
    if (!isPlausibleCode(requestedCode)) return errorResponse(400, "invalid_code");
    const existing = await env.DB.prepare("SELECT expires_at FROM blobs WHERE code = ?")
      .bind(requestedCode)
      .first<{ expires_at: number }>();
    if (existing) {
      return jsonResponse({ code: requestedCode, deduped: true, expires: existing.expires_at });
    }
  }

  const code = requestedCode ?? randomCode(18);
  const deleteToken = randomToken(32);
  await env.DB.prepare(
    "INSERT INTO blobs (code, body, size, created_at, expires_at, delete_token_hash) VALUES (?, ?, ?, ?, ?, ?)",
  )
    .bind(code, body, body.length, now, expires, await sha256Hex(deleteToken))
    .run();
  await bumpUsage(env, { newRowWrites: 2, storePosts: 1, storeBytes: body.length });
  return jsonResponse({ code, deleteToken, expires }, 201);
}

/** GET /v1/store/{code} — fetch one envelope; lazy expiry check. */
export async function getBlob(env: Env, code: string): Promise<Response> {
  if (!isPlausibleCode(code)) return errorResponse(404, "no_such_blob");
  const row = await env.DB.prepare("SELECT body, expires_at FROM blobs WHERE code = ?")
    .bind(code)
    .first<{ body: string; expires_at: number }>();
  if (!row || row.expires_at <= nowSeconds()) return errorResponse(404, "no_such_blob");
  return new Response(row.body, {
    status: 200,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

/** GET /v1/store/{code}/meta — size and expiry only. */
export async function blobMeta(env: Env, code: string): Promise<Response> {
  if (!isPlausibleCode(code)) return errorResponse(404, "no_such_blob");
  const row = await env.DB.prepare("SELECT size, expires_at FROM blobs WHERE code = ?")
    .bind(code)
    .first<BlobRow & { size: number; expires_at: number }>();
  if (!row || row.expires_at <= nowSeconds()) return errorResponse(404, "no_such_blob");
  return jsonResponse({ size: row.size, expires: row.expires_at });
}

/** DELETE /v1/store/{code} — requires the per-blob delete token. */
export async function deleteBlob(env: Env, request: Request, code: string): Promise<Response> {
  if (!isPlausibleCode(code)) return errorResponse(404, "no_such_blob");
  const token = bearerToken(request);
  if (!token) return errorResponse(401, "missing_delete_token");
  const row = await env.DB.prepare("SELECT size, delete_token_hash FROM blobs WHERE code = ?")
    .bind(code)
    .first<{ size: number; delete_token_hash: string }>();
  if (!row) return errorResponse(404, "no_such_blob");
  if ((await sha256Hex(token)) !== row.delete_token_hash) {
    return errorResponse(403, "invalid_delete_token");
  }
  await env.DB.prepare("DELETE FROM blobs WHERE code = ?").bind(code).run();
  await bumpUsage(env, { newRowWrites: 1, storeBytes: -row.size });
  return jsonResponse({ deleted: true });
}
