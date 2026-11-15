import {
  ALLOWED_ORIGIN,
  DISCORD_MIN_FREE_AGE_MS,
  OAUTH_STATE_TTL_SECONDS,
  RATE_LIMIT_RETRY_SECONDS,
  SESSION_TOUCH_INTERVAL_SECONDS,
  SESSION_TTL_SECONDS,
  resolveCaps,
  resolvePublicBaseUrl,
} from "./config";
import {
  errorResponse,
  htmlResponse,
  jsonResponse,
  nowSeconds,
  plainErrorResponse,
  type Env,
} from "./http";
import { checkRateLimit, isAuthConfigured, isCoreConfigured } from "./identity";
import { getAccountAllowanceStatus } from "./ledger";

export const SESSION_COOKIE_NAME = "__Host-materione_ai_session";
export const OAUTH_COOKIE_NAME = "__Host-materione_ai_oauth";

const DISCORD_EPOCH_MS = 1_420_070_400_000n;
const SESSION_TOKEN_REGEX = /^[0-9a-f]{64}$/;
const DISCORD_SNOWFLAKE_REGEX = /^\d{15,22}$/;
const textEncoder = new TextEncoder();

export type OAuthProvider = "google" | "discord";

export interface AuthenticatedAccount {
  id: string;
  email: string;
  displayName: string | null;
  creditBalancePicos: number;
  freeEligible: number;
  suspended: number;
  suspendReason: string | null;
  polarCustomerId: string | null;
}

export interface AuthenticatedSession {
  tokenHash: string;
  expiresAt: number;
  lastSeenAt: number;
  account: AuthenticatedAccount;
}

interface OAuthStatePayload {
  provider: OAuthProvider;
  state: string;
  codeVerifier: string;
  sessionTokenHash: string | null;
  exp: number;
}

interface SessionAccountRow {
  token_hash: string;
  expires_at: number;
  last_seen_at: number;
  id: string;
  email: string;
  display_name: string | null;
  credit_balance_picos: number;
  free_eligible: number;
  suspended: number;
  suspend_reason: string | null;
  polar_customer_id: string | null;
}

interface AccountRow {
  id: string;
  email: string;
  display_name: string | null;
  credit_balance_picos: number;
  free_eligible: number;
  suspended: number;
  suspend_reason: string | null;
  polar_customer_id: string | null;
}

function bytesToHex(bytes: Uint8Array): string {
  let hex = "";
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i]!.toString(16).padStart(2, "0");
  }
  return hex;
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]!);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlToBytes(input: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]+$/.test(input)) return null;
  const padded = input.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (input.length % 4)) % 4);
  try {
    const binary = atob(padded);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      out[i] = binary.charCodeAt(i);
    }
    return out;
  } catch {
    return null;
  }
}

function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return bytesToHex(bytes);
}

function randomBase64Url(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return bytesToBase64Url(bytes);
}

export async function computeScriptSha256Base64(script: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", textEncoder.encode(script));
  const bytes = new Uint8Array(digest);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]!);
  }
  return btoa(binary);
}

export function parseCookies(request: Request): Record<string, string> {
  const header = request.headers.get("cookie");
  if (!header) return {};
  const result: Record<string, string> = {};
  for (const part of header.split(";")) {
    const eqIdx = part.indexOf("=");
    if (eqIdx <= 0) continue;
    const name = part.slice(0, eqIdx).trim();
    if (!name) continue;
    const rawValue = part.slice(eqIdx + 1).trim();
    if (name in result) continue;
    try {
      result[name] = rawValue.includes("%") ? decodeURIComponent(rawValue) : rawValue;
    } catch {
      result[name] = rawValue;
    }
  }
  return result;
}

export async function hashSessionToken(rawToken: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", textEncoder.encode(rawToken));
  return bytesToHex(new Uint8Array(digest));
}

function buildSessionCookie(rawToken: string): string {
  return `${SESSION_COOKIE_NAME}=${rawToken}; HttpOnly; Secure; Path=/; SameSite=Lax; Max-Age=${SESSION_TTL_SECONDS}`;
}

function clearSessionCookie(): string {
  return `${SESSION_COOKIE_NAME}=; HttpOnly; Secure; Path=/; SameSite=Lax; Max-Age=0`;
}

function buildOAuthCookie(signedValue: string): string {
  return `${OAUTH_COOKIE_NAME}=${signedValue}; HttpOnly; Secure; Path=/; SameSite=Lax; Max-Age=${OAUTH_STATE_TTL_SECONDS}`;
}

function clearOAuthCookie(): string {
  return `${OAUTH_COOKIE_NAME}=; HttpOnly; Secure; Path=/; SameSite=Lax; Max-Age=0`;
}

async function readRequestSessionTokenHash(request: Request): Promise<string | null> {
  const cookies = parseCookies(request);
  const rawSession = cookies[SESSION_COOKIE_NAME];
  if (!rawSession || !SESSION_TOKEN_REGEX.test(rawSession)) {
    return null;
  }
  return hashSessionToken(rawSession);
}

function isUniqueConstraintError(err: unknown): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  return msg.includes("UNIQUE constraint failed") || msg.includes("PRIMARY KEY");
}

async function importHmacKey(secret: string, usages: ("sign" | "verify")[]): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    textEncoder.encode(secret.trim()),
    { name: "HMAC", hash: "SHA-256" },
    false,
    usages,
  );
}

async function signOAuthPayload(payload: OAuthStatePayload, secret: string): Promise<string> {
  const b64Payload = bytesToBase64Url(textEncoder.encode(JSON.stringify(payload)));
  const key = await importHmacKey(secret, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, textEncoder.encode(b64Payload));
  return `${b64Payload}.${bytesToBase64Url(new Uint8Array(sig))}`;
}

async function verifyOAuthCookie(
  cookieValue: string | undefined,
  secret: string,
  expectedProvider: OAuthProvider,
  expectedState: string | null,
  expectedSessionTokenHash: string | null,
): Promise<OAuthStatePayload | null> {
  if (!cookieValue || !expectedState) return null;
  const dotIdx = cookieValue.indexOf(".");
  if (dotIdx <= 0 || dotIdx !== cookieValue.lastIndexOf(".")) return null;

  const b64Payload = cookieValue.slice(0, dotIdx);
  const b64Sig = cookieValue.slice(dotIdx + 1);
  const sigBytes = base64UrlToBytes(b64Sig);
  const payloadBytes = base64UrlToBytes(b64Payload);
  if (!sigBytes || !payloadBytes) return null;

  const key = await importHmacKey(secret, ["verify"]);
  const verified = await crypto.subtle.verify("HMAC", key, sigBytes, textEncoder.encode(b64Payload));
  if (!verified) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(payloadBytes));
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
  const p = parsed as Record<string, unknown>;

  if (
    p.provider !== expectedProvider ||
    typeof p.state !== "string" ||
    p.state.length === 0 ||
    p.state !== expectedState ||
    typeof p.codeVerifier !== "string" ||
    p.codeVerifier.length === 0 ||
    (p.sessionTokenHash !== null && typeof p.sessionTokenHash !== "string") ||
    (p.sessionTokenHash ?? null) !== expectedSessionTokenHash ||
    typeof p.exp !== "number" ||
    !Number.isFinite(p.exp) ||
    p.exp < nowSeconds()
  ) {
    return null;
  }

  return {
    provider: expectedProvider,
    state: p.state,
    codeVerifier: p.codeVerifier,
    sessionTokenHash: p.sessionTokenHash,
    exp: p.exp,
  };
}

export function maskEmail(email: string): string {
  const trimmed = email.trim();
  const atIdx = trimmed.indexOf("@");
  if (atIdx <= 0) return "***";
  const local = trimmed.slice(0, atIdx);
  const domain = trimmed.slice(atIdx + 1);
  return `${local[0]!}***@${domain}`;
}

export async function authenticateSession(
  request: Request,
  db: D1Database,
): Promise<AuthenticatedSession | null> {
  const cookies = parseCookies(request);
  const rawToken = cookies[SESSION_COOKIE_NAME];
  if (!rawToken || !SESSION_TOKEN_REGEX.test(rawToken)) {
    return null;
  }

  const tokenHash = await hashSessionToken(rawToken);
  const now = nowSeconds();
  const row = await db
    .prepare(
      `SELECT
         s.token_hash, s.expires_at, s.last_seen_at,
         a.id, a.email, a.display_name, a.credit_balance_picos, a.free_eligible, a.suspended, a.suspend_reason, a.polar_customer_id
       FROM sessions s
       JOIN accounts a ON a.id = s.account_id
       WHERE s.token_hash = ?1 AND s.expires_at > ?2
       LIMIT 1`,
    )
    .bind(tokenHash, now)
    .first<SessionAccountRow>();

  if (!row) return null;

  if (now - row.last_seen_at >= SESSION_TOUCH_INTERVAL_SECONDS) {
    const newExpires = now + SESSION_TTL_SECONDS;
    await db
      .prepare("UPDATE sessions SET last_seen_at = ?2, expires_at = ?3 WHERE token_hash = ?1")
      .bind(tokenHash, now, newExpires)
      .run();
    row.last_seen_at = now;
    row.expires_at = newExpires;
  }

  return {
    tokenHash: row.token_hash,
    expiresAt: row.expires_at,
    lastSeenAt: row.last_seen_at,
    account: {
      id: row.id,
      email: row.email,
      displayName: row.display_name,
      creditBalancePicos: row.credit_balance_picos,
      freeEligible: row.free_eligible,
      suspended: row.suspended,
      suspendReason: row.suspend_reason,
      polarCustomerId: row.polar_customer_id,
    },
  };
}

type AuthPopupErrorCode =
  | "oauth_denied"
  | "invalid_oauth_state"
  | "oauth_exchange_failed"
  | "email_not_verified"
  | "account_conflict"
  | "server_error";

const AUTH_ERROR_MESSAGES: Record<AuthPopupErrorCode, string> = {
  oauth_denied: "Sign-in was cancelled or denied.",
  invalid_oauth_state: "Your sign-in session expired. Please try again from the editor.",
  oauth_exchange_failed: "Unable to complete sign-in with the identity provider.",
  email_not_verified: "Your provider account must have a verified email address.",
  account_conflict: "This identity is already linked to a different Materione account.",
  server_error: "Sign-in is temporarily unavailable. Please try again.",
};

async function authErrorHtmlResponse(
  status: number,
  errorCode: AuthPopupErrorCode,
  extraHeaders?: HeadersInit,
): Promise<Response> {
  const script = `try{if(window.opener){window.opener.postMessage({type:"materione-ai-auth-complete",ok:false,error:"${errorCode}"},${JSON.stringify(ALLOWED_ORIGIN)})}}catch(e){}window.close();`;
  const digest = await computeScriptSha256Base64(script);
  const message = AUTH_ERROR_MESSAGES[errorCode];
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Materione AI Sign-In</title></head><body><p>${message}</p><p>You may close this window and return to Materione.</p><script>${script}</script></body></html>`;
  return htmlResponse(html, status, digest, extraHeaders);
}

async function authSuccessHtmlResponse(extraHeaders?: HeadersInit): Promise<Response> {
  const script = `try{if(window.opener){window.opener.postMessage({type:"materione-ai-auth-complete",ok:true},${JSON.stringify(ALLOWED_ORIGIN)})}}catch(e){}window.close();`;
  const digest = await computeScriptSha256Base64(script);
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Materione AI Sign-In</title></head><body><p>Sign-in complete. Returning to Materione...</p><script>${script}</script></body></html>`;
  return htmlResponse(html, 200, digest, extraHeaders);
}

export async function handleAuthStart(
  request: Request,
  env: Env,
  provider: OAuthProvider,
  subnetHash: string,
): Promise<Response> {
  if (!isAuthConfigured(env, provider)) {
    return plainErrorResponse(503, "unavailable", "OAuth sign-in is not configured.");
  }

  const rateOutcome = await checkRateLimit(env, `auth:${subnetHash}`);
  if (rateOutcome === "unavailable") {
    return plainErrorResponse(503, "unavailable", "Authentication service is temporarily unavailable.");
  }
  if (rateOutcome === "limited") {
    return plainErrorResponse(
      429,
      "rate_limited",
      "Too many sign-in attempts. Please wait a minute.",
      { retryAfterSeconds: RATE_LIMIT_RETRY_SECONDS },
      { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) },
    );
  }

  const publicBase = resolvePublicBaseUrl(env)!;
  const state = randomHex(16);
  const codeVerifier = randomBase64Url(32);
  const sessionTokenHash = await readRequestSessionTokenHash(request);
  const challengeBytes = await crypto.subtle.digest("SHA-256", textEncoder.encode(codeVerifier));
  const codeChallenge = bytesToBase64Url(new Uint8Array(challengeBytes));

  const signedCookie = await signOAuthPayload(
    {
      provider,
      state,
      codeVerifier,
      sessionTokenHash,
      exp: nowSeconds() + OAUTH_STATE_TTL_SECONDS,
    },
    env.SESSION_HMAC_SECRET!,
  );

  let authorizeUrl: URL;
  if (provider === "google") {
    authorizeUrl = new URL("https://accounts.google.com/o/oauth2/v2/auth");
    authorizeUrl.searchParams.set("client_id", env.GOOGLE_CLIENT_ID!.trim());
    authorizeUrl.searchParams.set("redirect_uri", `${publicBase}/v1/auth/google/callback`);
    authorizeUrl.searchParams.set("response_type", "code");
    authorizeUrl.searchParams.set("scope", "openid email profile");
    authorizeUrl.searchParams.set("state", state);
    authorizeUrl.searchParams.set("code_challenge", codeChallenge);
    authorizeUrl.searchParams.set("code_challenge_method", "S256");
    authorizeUrl.searchParams.set("prompt", "select_account");
  } else {
    authorizeUrl = new URL("https://discord.com/oauth2/authorize");
    authorizeUrl.searchParams.set("client_id", env.DISCORD_CLIENT_ID!.trim());
    authorizeUrl.searchParams.set("redirect_uri", `${publicBase}/v1/auth/discord/callback`);
    authorizeUrl.searchParams.set("response_type", "code");
    authorizeUrl.searchParams.set("scope", "identify email");
    authorizeUrl.searchParams.set("state", state);
    authorizeUrl.searchParams.set("code_challenge", codeChallenge);
    authorizeUrl.searchParams.set("code_challenge_method", "S256");
    authorizeUrl.searchParams.set("prompt", "none");
  }

  return new Response(null, {
    status: 302,
    headers: {
      location: authorizeUrl.toString(),
      "cache-control": "no-store",
      "set-cookie": buildOAuthCookie(signedCookie),
    },
  });
}

interface VerifiedProviderProfile {
  subject: string;
  email: string;
  displayName: string | null;
  providerCreatedAtSec: number | null;
  discordOldEnough: boolean;
}

async function exchangeGoogleCode(
  env: Env,
  code: string,
  codeVerifier: string,
  publicBase: string,
): Promise<{ ok: true; profile: VerifiedProviderProfile } | { ok: false; status: number; code: AuthPopupErrorCode }> {
  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: env.GOOGLE_CLIENT_ID!.trim(),
      client_secret: env.GOOGLE_CLIENT_SECRET!.trim(),
      code,
      code_verifier: codeVerifier,
      grant_type: "authorization_code",
      redirect_uri: `${publicBase}/v1/auth/google/callback`,
    }).toString(),
  });
  if (!tokenRes.ok) {
    return { ok: false, status: 400, code: "oauth_exchange_failed" };
  }
  const tokenJson = (await tokenRes.json().catch(() => null)) as { access_token?: unknown } | null;
  if (!tokenJson || typeof tokenJson.access_token !== "string" || tokenJson.access_token.length === 0) {
    return { ok: false, status: 400, code: "oauth_exchange_failed" };
  }

  const userRes = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
    headers: { authorization: `Bearer ${tokenJson.access_token}` },
  });
  if (!userRes.ok) {
    return { ok: false, status: 400, code: "oauth_exchange_failed" };
  }
  const userJson = (await userRes.json().catch(() => null)) as {
    sub?: unknown;
    email?: unknown;
    email_verified?: unknown;
    name?: unknown;
  } | null;
  if (!userJson || typeof userJson.sub !== "string" || userJson.sub.length === 0) {
    return { ok: false, status: 400, code: "oauth_exchange_failed" };
  }
  if (
    typeof userJson.email !== "string" ||
    !userJson.email.includes("@") ||
    userJson.email_verified !== true
  ) {
    return { ok: false, status: 403, code: "email_not_verified" };
  }

  const displayName =
    typeof userJson.name === "string" && userJson.name.trim().length > 0
      ? userJson.name.trim().slice(0, 80)
      : null;

  return {
    ok: true,
    profile: {
      subject: userJson.sub,
      email: userJson.email.trim().toLowerCase(),
      displayName,
      providerCreatedAtSec: null,
      discordOldEnough: true,
    },
  };
}

async function exchangeDiscordCode(
  env: Env,
  code: string,
  codeVerifier: string,
  publicBase: string,
): Promise<{ ok: true; profile: VerifiedProviderProfile } | { ok: false; status: number; code: AuthPopupErrorCode }> {
  const tokenRes = await fetch("https://discord.com/api/v10/oauth2/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: env.DISCORD_CLIENT_ID!.trim(),
      client_secret: env.DISCORD_CLIENT_SECRET!.trim(),
      code,
      code_verifier: codeVerifier,
      grant_type: "authorization_code",
      redirect_uri: `${publicBase}/v1/auth/discord/callback`,
    }).toString(),
  });
  if (!tokenRes.ok) {
    return { ok: false, status: 400, code: "oauth_exchange_failed" };
  }
  const tokenJson = (await tokenRes.json().catch(() => null)) as { access_token?: unknown } | null;
  if (!tokenJson || typeof tokenJson.access_token !== "string" || tokenJson.access_token.length === 0) {
    return { ok: false, status: 400, code: "oauth_exchange_failed" };
  }

  const userRes = await fetch("https://discord.com/api/v10/users/@me", {
    headers: { authorization: `Bearer ${tokenJson.access_token}` },
  });
  if (!userRes.ok) {
    return { ok: false, status: 400, code: "oauth_exchange_failed" };
  }
  const userJson = (await userRes.json().catch(() => null)) as {
    id?: unknown;
    email?: unknown;
    verified?: unknown;
    global_name?: unknown;
    username?: unknown;
  } | null;
  if (!userJson || typeof userJson.id !== "string" || !DISCORD_SNOWFLAKE_REGEX.test(userJson.id)) {
    return { ok: false, status: 400, code: "oauth_exchange_failed" };
  }
  if (
    typeof userJson.email !== "string" ||
    !userJson.email.includes("@") ||
    userJson.verified !== true
  ) {
    return { ok: false, status: 403, code: "email_not_verified" };
  }

  const createdAtMs = Number((BigInt(userJson.id) >> 22n) + DISCORD_EPOCH_MS);
  const providerCreatedAtSec = Math.floor(createdAtMs / 1000);
  const discordOldEnough = Date.now() - createdAtMs >= DISCORD_MIN_FREE_AGE_MS;

  const rawDisplayName =
    typeof userJson.global_name === "string" && userJson.global_name.trim().length > 0
      ? userJson.global_name.trim()
      : typeof userJson.username === "string" && userJson.username.trim().length > 0
        ? userJson.username.trim()
        : null;

  return {
    ok: true,
    profile: {
      subject: userJson.id,
      email: userJson.email.trim().toLowerCase(),
      displayName: rawDisplayName ? rawDisplayName.slice(0, 80) : null,
      providerCreatedAtSec,
      discordOldEnough,
    },
  };
}

export async function handleAuthCallback(
  request: Request,
  env: Env,
  provider: OAuthProvider,
  subnetHash: string,
): Promise<Response> {
  const clearHeaders = new Headers();
  clearHeaders.append("set-cookie", clearOAuthCookie());

  if (!isAuthConfigured(env, provider)) {
    return authErrorHtmlResponse(503, "server_error", clearHeaders);
  }

  const rateOutcome = await checkRateLimit(env, `auth:${subnetHash}`);
  if (rateOutcome === "unavailable") {
    return authErrorHtmlResponse(503, "server_error", clearHeaders);
  }
  if (rateOutcome === "limited") {
    clearHeaders.set("retry-after", String(RATE_LIMIT_RETRY_SECONDS));
    return authErrorHtmlResponse(429, "server_error", clearHeaders);
  }

  const url = new URL(request.url);
  if (url.searchParams.has("error")) {
    return authErrorHtmlResponse(400, "oauth_denied", clearHeaders);
  }

  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const cookies = parseCookies(request);
  const callbackSessionTokenHash = await readRequestSessionTokenHash(request);
  const verifiedState = await verifyOAuthCookie(
    cookies[OAUTH_COOKIE_NAME],
    env.SESSION_HMAC_SECRET!,
    provider,
    state,
    callbackSessionTokenHash,
  );
  if (!verifiedState || !code || code.length === 0) {
    return authErrorHtmlResponse(400, "invalid_oauth_state", clearHeaders);
  }

  const publicBase = resolvePublicBaseUrl(env)!;
  let exchangeResult:
    | { ok: true; profile: VerifiedProviderProfile }
    | { ok: false; status: number; code: AuthPopupErrorCode };
  try {
    exchangeResult =
      provider === "google"
        ? await exchangeGoogleCode(env, code, verifiedState.codeVerifier, publicBase)
        : await exchangeDiscordCode(env, code, verifiedState.codeVerifier, publicBase);
  } catch {
    return authErrorHtmlResponse(503, "server_error", clearHeaders);
  }

  if (!exchangeResult.ok) {
    return authErrorHtmlResponse(exchangeResult.status, exchangeResult.code, clearHeaders);
  }

  const { profile } = exchangeResult;
  const now = nowSeconds();
  const currentSession = await authenticateSession(request, env.DB);

  let resolvedAccountId: string | null = null;
  const existingIdentity = await env.DB
    .prepare(
      "SELECT account_id FROM account_identities WHERE provider = ?1 AND provider_subject = ?2 LIMIT 1",
    )
    .bind(provider, profile.subject)
    .first<{ account_id: string }>();

  const eligibleForFreeUpgrade = provider === "google" || profile.discordOldEnough;

  if (existingIdentity) {
    const existingAcct = await env.DB
      .prepare(
        "SELECT id, email, display_name, credit_balance_picos, free_eligible, suspended, suspend_reason, polar_customer_id FROM accounts WHERE id = ?1 LIMIT 1",
      )
      .bind(existingIdentity.account_id)
      .first<AccountRow>();

    if (!existingAcct) {
      return authErrorHtmlResponse(500, "server_error", clearHeaders);
    }

    if (currentSession && currentSession.account.id !== existingAcct.id) {
      const hasOrder = await env.DB
        .prepare("SELECT 1 AS found FROM polar_orders WHERE account_id = ?1 LIMIT 1")
        .bind(existingAcct.id)
        .first<{ found: number }>();
      if (existingAcct.credit_balance_picos !== 0 || hasOrder) {
        return authErrorHtmlResponse(409, "account_conflict", clearHeaders);
      }
    }

    resolvedAccountId = existingAcct.id;
    const updates: D1PreparedStatement[] = [
      env.DB
        .prepare(
          "UPDATE account_identities SET provider_email = ?3, provider_created_at = COALESCE(provider_created_at, ?4) WHERE provider = ?1 AND provider_subject = ?2",
        )
        .bind(provider, profile.subject, profile.email, profile.providerCreatedAtSec),
    ];

    if (
      eligibleForFreeUpgrade &&
      existingAcct.free_eligible === 0 &&
      existingAcct.suspended === 0 &&
      existingAcct.credit_balance_picos >= 0
    ) {
      updates.push(
        env.DB
          .prepare(
            `UPDATE accounts
             SET free_eligible = 1, updated_at = ?2
             WHERE id = ?1
               AND free_eligible = 0
               AND suspended = 0
               AND credit_balance_picos >= 0
               AND NOT EXISTS (
                 SELECT 1 FROM credit_ledger_entries
                 WHERE account_id = ?1 AND kind IN ('refund', 'dispute_hold')
               )`,
          )
          .bind(resolvedAccountId, now),
      );
    }

    if (!existingAcct.display_name && profile.displayName) {
      updates.push(
        env.DB
          .prepare("UPDATE accounts SET display_name = ?2, updated_at = ?3 WHERE id = ?1 AND display_name IS NULL")
          .bind(resolvedAccountId, profile.displayName, now),
      );
    }

    await env.DB.batch(updates);
  } else if (currentSession && profile.email === currentSession.account.email.trim().toLowerCase()) {
    resolvedAccountId = currentSession.account.id;
    const statements: D1PreparedStatement[] = [
      env.DB
        .prepare(
          "INSERT INTO account_identities (provider, provider_subject, account_id, provider_email, provider_created_at, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        )
        .bind(provider, profile.subject, resolvedAccountId, profile.email, profile.providerCreatedAtSec, now),
    ];
    if (
      eligibleForFreeUpgrade &&
      currentSession.account.freeEligible === 0 &&
      currentSession.account.suspended === 0 &&
      currentSession.account.creditBalancePicos >= 0
    ) {
      statements.push(
        env.DB
          .prepare(
            `UPDATE accounts
             SET free_eligible = 1, updated_at = ?2
             WHERE id = ?1
               AND free_eligible = 0
               AND suspended = 0
               AND credit_balance_picos >= 0
               AND NOT EXISTS (
                 SELECT 1 FROM credit_ledger_entries
                 WHERE account_id = ?1 AND kind IN ('refund', 'dispute_hold')
               )`,
          )
          .bind(resolvedAccountId, now),
      );
    }
    try {
      await env.DB.batch(statements);
    } catch (err) {
      if (!isUniqueConstraintError(err)) throw err;
      const racedIdentity = await env.DB
        .prepare(
          "SELECT account_id FROM account_identities WHERE provider = ?1 AND provider_subject = ?2 LIMIT 1",
        )
        .bind(provider, profile.subject)
        .first<{ account_id: string }>();
      if (!racedIdentity) throw err;
      resolvedAccountId = racedIdentity.account_id;
    }
  } else {
    const emailMatch = await env.DB
      .prepare(
        "SELECT id, email, display_name, credit_balance_picos, free_eligible, suspended, suspend_reason, polar_customer_id FROM accounts WHERE email = ?1 AND email_verified = 1 LIMIT 1",
      )
      .bind(profile.email)
      .first<AccountRow>();

    if (emailMatch) {
      resolvedAccountId = emailMatch.id;
      const statements: D1PreparedStatement[] = [
        env.DB
          .prepare(
            "INSERT INTO account_identities (provider, provider_subject, account_id, provider_email, provider_created_at, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
          )
          .bind(provider, profile.subject, resolvedAccountId, profile.email, profile.providerCreatedAtSec, now),
      ];
      if (
        eligibleForFreeUpgrade &&
        emailMatch.free_eligible === 0 &&
        emailMatch.suspended === 0 &&
        emailMatch.credit_balance_picos >= 0
      ) {
        statements.push(
          env.DB
            .prepare(
              `UPDATE accounts
               SET free_eligible = 1, updated_at = ?2
               WHERE id = ?1
                 AND free_eligible = 0
                 AND suspended = 0
                 AND credit_balance_picos >= 0
                 AND NOT EXISTS (
                   SELECT 1 FROM credit_ledger_entries
                   WHERE account_id = ?1 AND kind IN ('refund', 'dispute_hold')
                 )`,
            )
            .bind(resolvedAccountId, now),
        );
      }
      try {
        await env.DB.batch(statements);
      } catch (err) {
        if (!isUniqueConstraintError(err)) throw err;
        const racedIdentity = await env.DB
          .prepare(
            "SELECT account_id FROM account_identities WHERE provider = ?1 AND provider_subject = ?2 LIMIT 1",
          )
          .bind(provider, profile.subject)
          .first<{ account_id: string }>();
        if (!racedIdentity) throw err;
        resolvedAccountId = racedIdentity.account_id;
      }
    } else {
      resolvedAccountId = crypto.randomUUID();
      const initialFreeEligible = eligibleForFreeUpgrade ? 1 : 0;
      try {
        await env.DB.batch([
          env.DB
            .prepare(
              `INSERT INTO accounts (
                 id, email, email_verified, display_name, credit_balance_picos, free_eligible, suspended, created_at, updated_at
               ) VALUES (?1, ?2, 1, ?3, 0, ?4, 0, ?5, ?5)`,
            )
            .bind(resolvedAccountId, profile.email, profile.displayName, initialFreeEligible, now),
          env.DB
            .prepare(
              "INSERT INTO account_identities (provider, provider_subject, account_id, provider_email, provider_created_at, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            )
            .bind(provider, profile.subject, resolvedAccountId, profile.email, profile.providerCreatedAtSec, now),
        ]);
      } catch (err) {
        if (!isUniqueConstraintError(err)) {
          throw err;
        }
        const racedIdentity = await env.DB
          .prepare(
            "SELECT account_id FROM account_identities WHERE provider = ?1 AND provider_subject = ?2 LIMIT 1",
          )
          .bind(provider, profile.subject)
          .first<{ account_id: string }>();
        if (racedIdentity) {
          resolvedAccountId = racedIdentity.account_id;
        } else {
          const racedAccount = await env.DB
            .prepare(
              "SELECT id, email, display_name, credit_balance_picos, free_eligible, suspended, suspend_reason, polar_customer_id FROM accounts WHERE email = ?1 LIMIT 1",
            )
            .bind(profile.email)
            .first<AccountRow>();
          if (!racedAccount) {
            throw err;
          }
          resolvedAccountId = racedAccount.id;
          const linkStatements: D1PreparedStatement[] = [
            env.DB
              .prepare(
                "INSERT INTO account_identities (provider, provider_subject, account_id, provider_email, provider_created_at, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
              )
              .bind(provider, profile.subject, resolvedAccountId, profile.email, profile.providerCreatedAtSec, now),
          ];
          if (
            eligibleForFreeUpgrade &&
            racedAccount.free_eligible === 0 &&
            racedAccount.suspended === 0 &&
            racedAccount.credit_balance_picos >= 0
          ) {
            linkStatements.push(
              env.DB
                .prepare(
                  `UPDATE accounts
                   SET free_eligible = 1, updated_at = ?2
                   WHERE id = ?1
                     AND free_eligible = 0
                     AND suspended = 0
                     AND credit_balance_picos >= 0
                     AND NOT EXISTS (
                       SELECT 1 FROM credit_ledger_entries
                       WHERE account_id = ?1 AND kind IN ('refund', 'dispute_hold')
                     )`,
                )
                .bind(resolvedAccountId, now),
            );
          }
          try {
            await env.DB.batch(linkStatements);
          } catch (linkErr) {
            if (!isUniqueConstraintError(linkErr)) {
              throw linkErr;
            }
            const retryIdentity = await env.DB
              .prepare(
                "SELECT account_id FROM account_identities WHERE provider = ?1 AND provider_subject = ?2 LIMIT 1",
              )
              .bind(provider, profile.subject)
              .first<{ account_id: string }>();
            if (!retryIdentity) {
              throw linkErr;
            }
            resolvedAccountId = retryIdentity.account_id;
          }
        }
      }
    }
  }

  const rawSessionToken = randomHex(32);
  const tokenHash = await hashSessionToken(rawSessionToken);
  const expiresAt = now + SESSION_TTL_SECONDS;
  await env.DB
    .prepare(
      "INSERT INTO sessions (token_hash, account_id, expires_at, created_at, last_seen_at) VALUES (?1, ?2, ?3, ?4, ?4)",
    )
    .bind(tokenHash, resolvedAccountId, expiresAt, now)
    .run();

  const headers = new Headers();
  headers.append("set-cookie", clearOAuthCookie());
  headers.append("set-cookie", buildSessionCookie(rawSessionToken));
  return authSuccessHtmlResponse(headers);
}

export async function handleAuthLogout(
  request: Request,
  env: Env,
  subnetHash: string,
): Promise<Response> {
  if (!isCoreConfigured(env)) {
    return errorResponse(503, "unavailable", "Authentication service is not configured.");
  }

  const rateOutcome = await checkRateLimit(env, `auth:${subnetHash}`);
  if (rateOutcome === "unavailable") {
    return errorResponse(503, "unavailable", "Authentication service is temporarily unavailable.");
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

  const cookies = parseCookies(request);
  const rawToken = cookies[SESSION_COOKIE_NAME];
  if (rawToken && SESSION_TOKEN_REGEX.test(rawToken)) {
    const tokenHash = await hashSessionToken(rawToken);
    await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?1").bind(tokenHash).run();
  }

  return jsonResponse(
    { ok: true },
    200,
    { "set-cookie": clearSessionCookie() },
  );
}

export async function handleMe(
  request: Request,
  env: Env,
  day: string,
  subnetHash: string,
): Promise<Response> {
  if (!isCoreConfigured(env)) {
    return errorResponse(503, "unavailable", "Authentication service is not configured.");
  }

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
    return jsonResponse({ authenticated: false }, 200);
  }

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

  return jsonResponse(
    {
      authenticated: true,
      day,
      account: status.account,
      credits: status.credits,
      freeAllowance: status.freeAllowance,
    },
    200,
  );
}
