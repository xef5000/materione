import { hashSessionToken, SESSION_COOKIE_NAME } from "../src/auth";
import { nowSeconds } from "../src/http";

export async function clearAllTables(db: D1Database): Promise<void> {
  await db.batch([
    db.prepare("DELETE FROM reservations"),
    db.prepare("DELETE FROM daily_account_free_usage"),
    db.prepare("DELETE FROM daily_subnet_free_usage"),
    db.prepare("DELETE FROM daily_pool_free_usage"),
    db.prepare("DELETE FROM credit_ledger_entries"),
    db.prepare("DELETE FROM polar_disputes"),
    db.prepare("DELETE FROM polar_orders"),
    db.prepare("DELETE FROM sessions"),
    db.prepare("DELETE FROM account_identities"),
    db.prepare("DELETE FROM accounts"),
  ]);
}

export interface TestSessionOptions {
  accountId?: string;
  email?: string;
  displayName?: string | null;
  creditBalancePicos?: number;
  freeEligible?: number;
  suspended?: number;
  suspendReason?: string | null;
  polarCustomerId?: string | null;
  provider?: "google" | "discord";
  subject?: string;
}

export async function createTestAccountSession(
  db: D1Database,
  options: TestSessionOptions = {},
): Promise<{ accountId: string; rawToken: string; cookieHeader: string }> {
  const accountId = options.accountId ?? crypto.randomUUID();
  const email = (options.email ?? `${accountId}@example.com`).toLowerCase();
  const displayName = options.displayName === undefined ? "Test Author" : options.displayName;
  const creditBalancePicos = options.creditBalancePicos ?? 0;
  const freeEligible = options.freeEligible ?? 1;
  const suspended = options.suspended ?? 0;
  const suspendReason = options.suspendReason ?? null;
  const polarCustomerId = options.polarCustomerId ?? null;
  const provider = options.provider ?? "google";
  const subject = options.subject ?? `sub-${accountId}`;

  const rawBytes = new Uint8Array(32);
  crypto.getRandomValues(rawBytes);
  let rawToken = "";
  for (let i = 0; i < rawBytes.length; i++) {
    rawToken += rawBytes[i]!.toString(16).padStart(2, "0");
  }
  const tokenHash = await hashSessionToken(rawToken);
  const now = nowSeconds();
  const expiresAt = now + 30 * 24 * 60 * 60;

  await db.batch([
    db
      .prepare(
        `INSERT INTO accounts (
           id, email, email_verified, display_name, credit_balance_picos,
           free_eligible, suspended, suspend_reason, polar_customer_id, created_at, updated_at
         ) VALUES (?1, ?2, 1, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?9)`,
      )
      .bind(
        accountId,
        email,
        displayName,
        creditBalancePicos,
        freeEligible,
        suspended,
        suspendReason,
        polarCustomerId,
        now,
      ),
    db
      .prepare(
        `INSERT INTO account_identities (
           provider, provider_subject, account_id, provider_email, created_at
         ) VALUES (?1, ?2, ?3, ?4, ?5)`,
      )
      .bind(provider, subject, accountId, email, now),
    db
      .prepare(
        `INSERT INTO sessions (token_hash, account_id, expires_at, created_at, last_seen_at)
         VALUES (?1, ?2, ?3, ?4, ?4)`,
      )
      .bind(tokenHash, accountId, expiresAt, now),
  ]);

  return {
    accountId,
    rawToken,
    cookieHeader: `${SESSION_COOKIE_NAME}=${rawToken}`,
  };
}

export async function signPolarWebhook(
  webhookId: string,
  timestampSec: number,
  rawBody: string,
  secret: string,
): Promise<string> {
  const b64 = secret.startsWith("whsec_") ? secret.slice(6) : secret;
  const keyBytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));

  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const payload = new TextEncoder().encode(`${webhookId}.${timestampSec}.${rawBody}`);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, payload));
  let binary = "";
  for (let i = 0; i < sig.length; i++) {
    binary += String.fromCharCode(sig[i]!);
  }
  return `v1,${btoa(binary)}`;
}
