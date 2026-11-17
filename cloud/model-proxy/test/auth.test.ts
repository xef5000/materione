import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { OAUTH_COOKIE_NAME, SESSION_COOKIE_NAME, computeScriptSha256Base64 } from "../src/auth";
import { ALLOWED_ORIGIN, PICOS_PER_CREDIT } from "../src/config";
import worker from "../src/index";
import { SDK_INDEX_HEADER } from "../src/prompt";
import { clearAllTables, completedResponseSse, createTestAccountSession } from "./helpers";

const DISCORD_EPOCH_MS = 1_420_070_400_000n;

function makeDiscordSnowflake(ageMs: number): string {
  const createdMs = BigInt(Date.now() - ageMs);
  const snowflake = ((createdMs - DISCORD_EPOCH_MS) << 22n) | 12345n;
  return snowflake.toString();
}

function extractCookieValue(setCookieHeaders: string[], name: string): string | null {
  for (const header of setCookieHeaders) {
    const firstPart = header.split(";")[0]?.trim() ?? "";
    if (firstPart.startsWith(`${name}=`)) {
      return firstPart.slice(name.length + 1);
    }
  }
  return null;
}

function getSetCookies(res: Response): string[] {
  const headers = res.headers as unknown as { getSetCookie?: () => string[] };
  if (typeof headers.getSetCookie === "function") {
    return headers.getSetCookie();
  }
  const single = res.headers.get("set-cookie");
  return single ? [single] : [];
}

function extractInlineScript(html: string): string {
  const match = html.match(/<script>([\s\S]*?)<\/script>/);
  if (!match) throw new Error("Missing inline <script> in HTML response");
  return match[1]!;
}

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

describe("Google & Discord OAuth PKCE, sessions, account linking, and logout", () => {
  beforeEach(async () => {
    await clearAllTables(env.DB);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns { authenticated: false } on GET /v1/me and 401 unauthenticated on gated endpoints when unsigned", async () => {
    const meCtx = createExecutionContext();
    const meRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/me", {
        method: "GET",
        headers: { origin: ALLOWED_ORIGIN, "cf-connecting-ip": "198.51.100.10" },
      }),
      env,
      meCtx,
    );
    await waitOnExecutionContext(meCtx);
    expect(meRes.status).toBe(200);
    expect(meRes.headers.get("access-control-allow-credentials")).toBe("true");
    expect(await meRes.json()).toEqual({ authenticated: false });

    const gated: Array<{ path: string; method: string; body?: string }> = [
      { path: "/v1/allowance", method: "GET" },
      { path: "/v1/chat", method: "POST", body: VALID_CHAT_BODY },
      { path: "/v1/checkout", method: "POST", body: JSON.stringify({ amountCents: 1000 }) },
      { path: "/v1/portal", method: "POST", body: "{}" },
    ];
    for (const reqSpec of gated) {
      const ctx = createExecutionContext();
      const res = await worker.fetch(
        new Request(`https://ai.materione.com${reqSpec.path}`, {
          method: reqSpec.method,
          headers: {
            origin: ALLOWED_ORIGIN,
            "cf-connecting-ip": "198.51.100.10",
            ...(reqSpec.body ? { "content-type": "application/json" } : {}),
          },
          body: reqSpec.body,
        }),
        env,
        ctx,
      );
      await waitOnExecutionContext(ctx);
      expect(res.status).toBe(401);
      expect(((await res.json()) as { code: string }).code).toBe("unauthenticated");
    }
  });

  it("completes Google OAuth PKCE flow, verifies CSP SHA-256 hash, sets __Host- session cookie, and masks email in /v1/me", async () => {
    const startCtx = createExecutionContext();
    const startRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/google/start", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.20" },
      }),
      env,
      startCtx,
    );
    await waitOnExecutionContext(startCtx);

    expect(startRes.status).toBe(302);
    const location = startRes.headers.get("location");
    expect(location).toBeTruthy();
    const authUrl = new URL(location!);
    expect(authUrl.origin).toBe("https://accounts.google.com");
    expect(authUrl.searchParams.get("response_type")).toBe("code");
    expect(authUrl.searchParams.get("code_challenge_method")).toBe("S256");
    expect(authUrl.searchParams.get("scope")).toBe("openid email profile");
    expect(authUrl.searchParams.get("redirect_uri")).toBe("https://ai.materione.com/v1/auth/google/callback");
    expect(authUrl.searchParams.has("nonce")).toBe(false);
    const state = authUrl.searchParams.get("state")!;
    expect(state.length).toBeGreaterThan(10);

    expect(OAUTH_COOKIE_NAME).toBe("__Host-materione_ai_oauth");
    const startCookies = getSetCookies(startRes);
    const oauthCookieVal = extractCookieValue(startCookies, OAUTH_COOKIE_NAME)!;
    expect(oauthCookieVal).toBeTruthy();
    expect(startCookies.join(";")).toContain("Path=/;");
    expect(startCookies.join(";")).not.toContain("Path=/v1/auth");
    expect(startCookies.join(";")).not.toMatch(/Domain=/i);
    expect(startCookies.join(";")).toContain("HttpOnly");
    expect(startCookies.join(";")).toContain("Secure");
    expect(startCookies.join(";")).toContain("SameSite=Lax");

    let capturedCodeVerifier = "";
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      if (url === "https://oauth2.googleapis.com/token") {
        const params = new URLSearchParams(String(init?.body ?? ""));
        capturedCodeVerifier = params.get("code_verifier") ?? "";
        expect(params.get("code")).toBe("google-auth-code-123");
        return new Response(JSON.stringify({ access_token: "g-access-tok" }), { status: 200 });
      }
      if (url === "https://openidconnect.googleapis.com/v1/userinfo") {
        return new Response(
          JSON.stringify({
            sub: "google-sub-001",
            email: "Alice@Example.com",
            email_verified: true,
            name: "Alice Builder",
          }),
          { status: 200 },
        );
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const cbCtx = createExecutionContext();
    const cbRes = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/google/callback?code=google-auth-code-123&state=${state}`, {
        method: "GET",
        headers: {
          "cf-connecting-ip": "198.51.100.20",
          cookie: `${OAUTH_COOKIE_NAME}=${oauthCookieVal}`,
        },
      }),
      env,
      cbCtx,
    );
    const html = await cbRes.text();
    await waitOnExecutionContext(cbCtx);

    expect(cbRes.status).toBe(200);
    expect(capturedCodeVerifier.length).toBeGreaterThanOrEqual(32);

    // Verify CSP exact SHA-256 matches inline <script>
    const script = extractInlineScript(html);
    const expectedHash = await computeScriptSha256Base64(script);
    expect(cbRes.headers.get("content-security-policy")).toBe(
      `default-src 'none'; script-src 'sha256-${expectedHash}'`,
    );
    expect(cbRes.headers.get("referrer-policy")).toBe("no-referrer");
    expect(cbRes.headers.get("x-frame-options")).toBe("DENY");
    expect(html).toContain("materione-ai-auth-complete");

    const cbCookies = getSetCookies(cbRes);
    const sessionToken = extractCookieValue(cbCookies, SESSION_COOKIE_NAME)!;
    expect(sessionToken).toMatch(/^[0-9a-f]{64}$/);
    expect(cbCookies.some((c) => c.startsWith(`${OAUTH_COOKIE_NAME}=;`) && c.includes("Max-Age=0"))).toBe(true);

    // Verify GET /v1/me works with the session cookie and masks full email
    const meCtx = createExecutionContext();
    const meRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/me", {
        method: "GET",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.20",
          cookie: `${SESSION_COOKIE_NAME}=${sessionToken}`,
        },
      }),
      env,
      meCtx,
    );
    await waitOnExecutionContext(meCtx);
    expect(meRes.status).toBe(200);
    const meJson = (await meRes.json()) as {
      authenticated: boolean;
      account: { id: string; displayName: string; emailHint: string; freeEligible: boolean };
    };
    expect(meJson.authenticated).toBe(true);
    expect(meJson.account.displayName).toBe("Alice Builder");
    expect(meJson.account.emailHint).toBe("a***@example.com");
    expect(meJson.account.freeEligible).toBe(true);
    expect(JSON.stringify(meJson)).not.toContain("alice@example.com");
  });

  it("rejects OAuth state mismatch, expired cookie, unverified email, and OAuth denial without reflecting query params", async () => {
    const startCtx = createExecutionContext();
    const startRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/google/start", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.21" },
      }),
      env,
      startCtx,
    );
    await waitOnExecutionContext(startCtx);
    const authUrl = new URL(startRes.headers.get("location")!);
    const state = authUrl.searchParams.get("state")!;
    const oauthCookieVal = extractCookieValue(getSetCookies(startRes), OAUTH_COOKIE_NAME)!;

    // 1. State mismatch
    const mismatchCtx = createExecutionContext();
    const mismatchRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/google/callback?code=c&state=wrong-state", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.21", cookie: `${OAUTH_COOKIE_NAME}=${oauthCookieVal}` },
      }),
      env,
      mismatchCtx,
    );
    await waitOnExecutionContext(mismatchCtx);
    expect(mismatchRes.status).toBe(400);
    expect(getSetCookies(mismatchRes).some((c) => c.includes(`${OAUTH_COOKIE_NAME}=;`) && c.includes("Max-Age=0"))).toBe(true);

    // 2. Tampered cookie HMAC
    const tamperedCtx = createExecutionContext();
    const tamperedRes = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/google/callback?code=c&state=${state}`, {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.21", cookie: `${OAUTH_COOKIE_NAME}=${oauthCookieVal}x` },
      }),
      env,
      tamperedCtx,
    );
    await waitOnExecutionContext(tamperedCtx);
    expect(tamperedRes.status).toBe(400);

    // 3. OAuth denial with XSS payload in query param -> never reflected
    const denyCtx = createExecutionContext();
    const denyRes = await worker.fetch(
      new Request(
        `https://ai.materione.com/v1/auth/google/callback?error=access_denied&error_description=${encodeURIComponent("<script>alert(1)</script>")}`,
        {
          method: "GET",
          headers: { "cf-connecting-ip": "198.51.100.21", cookie: `${OAUTH_COOKIE_NAME}=${oauthCookieVal}` },
        },
      ),
      env,
      denyCtx,
    );
    const denyHtml = await denyRes.text();
    await waitOnExecutionContext(denyCtx);
    expect(denyRes.status).toBe(400);
    expect(denyHtml).not.toContain("<script>alert(1)</script>");
    expect(denyHtml).toContain("oauth_denied");

    // 4. Unverified email rejected with 403
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      if (url === "https://oauth2.googleapis.com/token") {
        return new Response(JSON.stringify({ access_token: "tok" }), { status: 200 });
      }
      return new Response(
        JSON.stringify({ sub: "g-unverified", email: "bob@example.com", email_verified: false }),
        { status: 200 },
      );
    });

    const unverifiedCtx = createExecutionContext();
    const unverifiedRes = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/google/callback?code=c&state=${state}`, {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.21", cookie: `${OAUTH_COOKIE_NAME}=${oauthCookieVal}` },
      }),
      env,
      unverifiedCtx,
    );
    const unverifiedHtml = await unverifiedRes.text();
    await waitOnExecutionContext(unverifiedCtx);
    expect(unverifiedRes.status).toBe(403);
    expect(unverifiedHtml).toContain("email_not_verified");

    // 5. Expired OAuth cookie rejected (advance clock past 600s)
    vi.useFakeTimers();
    try {
      vi.setSystemTime(Date.now() + 700_000);
      const expCtx = createExecutionContext();
      const expRes = await worker.fetch(
        new Request(`https://ai.materione.com/v1/auth/google/callback?code=c&state=${state}`, {
          method: "GET",
          headers: { "cf-connecting-ip": "198.51.100.21", cookie: `${OAUTH_COOKIE_NAME}=${oauthCookieVal}` },
        }),
        env,
        expCtx,
      );
      await waitOnExecutionContext(expCtx);
      expect(expRes.status).toBe(400);
    } finally {
      vi.useRealTimers();
    }
  });

  it("enforces Discord 7-day Snowflake gate, blocks free-only chat when <7d, allows paid credits, and upgrades once >=7d", async () => {
    const youngSnowflake = makeDiscordSnowflake(2 * 24 * 60 * 60 * 1000); // 2 days old
    const agedSnowflake = makeDiscordSnowflake(10 * 24 * 60 * 60 * 1000); // 10 days old

    // Start Discord OAuth for young account
    const s1Ctx = createExecutionContext();
    const s1Res = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/discord/start", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.30" },
      }),
      env,
      s1Ctx,
    );
    await waitOnExecutionContext(s1Ctx);
    const state1 = new URL(s1Res.headers.get("location")!).searchParams.get("state")!;
    const oauth1 = extractCookieValue(getSetCookies(s1Res), OAUTH_COOKIE_NAME)!;

    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      if (url === "https://discord.com/api/v10/oauth2/token") {
        return new Response(JSON.stringify({ access_token: "d-tok-1" }), { status: 200 });
      }
      if (url === "https://discord.com/api/v10/users/@me") {
        return new Response(
          JSON.stringify({
            id: youngSnowflake,
            email: "newuser@example.com",
            verified: true,
            global_name: "New Discord",
          }),
          { status: 200 },
        );
      }
      if (url.includes("openai/responses")) {
        const sse = completedResponseSse({ input: 50, output: 10 });
        return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const cb1Ctx = createExecutionContext();
    const cb1Res = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/discord/callback?code=dc1&state=${state1}`, {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.30", cookie: `${OAUTH_COOKIE_NAME}=${oauth1}` },
      }),
      env,
      cb1Ctx,
    );
    await waitOnExecutionContext(cb1Ctx);
    expect(cb1Res.status).toBe(200);
    const youngCookie = `${SESSION_COOKIE_NAME}=${extractCookieValue(getSetCookies(cb1Res), SESSION_COOKIE_NAME)!}`;

    // Free-only /v1/chat blocked with 429 free_not_eligible
    const chatBlockedCtx = createExecutionContext();
    const chatBlockedRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/chat", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.30",
          "content-type": "application/json",
          cookie: youngCookie,
        },
        body: VALID_CHAT_BODY,
      }),
      env,
      chatBlockedCtx,
    );
    await waitOnExecutionContext(chatBlockedCtx);
    expect(chatBlockedRes.status).toBe(429);
    const blockedJson = (await chatBlockedRes.json()) as { error: string; code: string };
    expect(blockedJson.error).toBe("free_not_eligible");
    expect(blockedJson.code).toBe("allowance_exhausted");

    // Grant paid credits to the <7d account -> /v1/chat succeeds using paid bucket!
    await env.DB
      .prepare("UPDATE accounts SET credit_balance_picos = ?1 WHERE email = 'newuser@example.com'")
      .bind(100 * PICOS_PER_CREDIT)
      .run();

    const chatPaidCtx = createExecutionContext();
    const chatPaidRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/chat", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.30",
          "content-type": "application/json",
          cookie: youngCookie,
        },
        body: VALID_CHAT_BODY,
      }),
      env,
      chatPaidCtx,
    );
    const paidText = await chatPaidRes.text();
    await waitOnExecutionContext(chatPaidCtx);
    expect(chatPaidRes.status).toBe(200);
    expect(paidText).toContain(`"freeSettledPicos":0`);

    // Simulate 6 days passing so youngSnowflake is now >= 7 days old, then re-login -> upgrades free_eligible = 1
    vi.useFakeTimers();
    try {
      vi.setSystemTime(Date.now() + 6 * 24 * 60 * 60 * 1000);
      const s2Ctx = createExecutionContext();
      const s2Res = await worker.fetch(
        new Request("https://ai.materione.com/v1/auth/discord/start", {
          method: "GET",
          headers: { "cf-connecting-ip": "198.51.100.30" },
        }),
        env,
        s2Ctx,
      );
      await waitOnExecutionContext(s2Ctx);
      const state2 = new URL(s2Res.headers.get("location")!).searchParams.get("state")!;
      const oauth2 = extractCookieValue(getSetCookies(s2Res), OAUTH_COOKIE_NAME)!;

      const cb2Ctx = createExecutionContext();
      const cb2Res = await worker.fetch(
        new Request(`https://ai.materione.com/v1/auth/discord/callback?code=dc2&state=${state2}`, {
          method: "GET",
          headers: { "cf-connecting-ip": "198.51.100.30", cookie: `${OAUTH_COOKIE_NAME}=${oauth2}` },
        }),
        env,
        cb2Ctx,
      );
      await waitOnExecutionContext(cb2Ctx);
      expect(cb2Res.status).toBe(200);

      const acctRow = await env.DB
        .prepare("SELECT free_eligible FROM accounts WHERE email = 'newuser@example.com'")
        .first<{ free_eligible: number }>();
      expect(acctRow?.free_eligible).toBe(1);
    } finally {
      vi.useRealTimers();
    }

    // Separate >=7d Discord user gets free_eligible = 1 immediately
    const s3Ctx = createExecutionContext();
    const s3Res = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/discord/start", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.31" },
      }),
      env,
      s3Ctx,
    );
    await waitOnExecutionContext(s3Ctx);
    const state3 = new URL(s3Res.headers.get("location")!).searchParams.get("state")!;
    const oauth3 = extractCookieValue(getSetCookies(s3Res), OAUTH_COOKIE_NAME)!;

    vi.restoreAllMocks();
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      if (url === "https://discord.com/api/v10/oauth2/token") {
        return new Response(JSON.stringify({ access_token: "d-tok-2" }), { status: 200 });
      }
      return new Response(
        JSON.stringify({ id: agedSnowflake, email: "veteran@example.com", verified: true, username: "Vet" }),
        { status: 200 },
      );
    });

    const cb3Ctx = createExecutionContext();
    const cb3Res = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/discord/callback?code=dc3&state=${state3}`, {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.31", cookie: `${OAUTH_COOKIE_NAME}=${oauth3}` },
      }),
      env,
      cb3Ctx,
    );
    await waitOnExecutionContext(cb3Ctx);
    expect(cb3Res.status).toBe(200);

    const vetAcct = await env.DB
      .prepare("SELECT free_eligible FROM accounts WHERE email = 'veteran@example.com'")
      .first<{ free_eligible: number }>();
    expect(vetAcct?.free_eligible).toBe(1);
  });

  it("links Google and Discord to the same account_id by verified email (preserving credit balance) and revokes on logout", async () => {
    const existing = await createTestAccountSession(env.DB, {
      accountId: "acct-shared-001",
      email: "shared@example.com",
      creditBalancePicos: 500 * PICOS_PER_CREDIT,
      provider: "google",
      subject: "google-sub-shared",
    });

    // Unauthenticated Discord login with same verified email (mixed case) -> links to acct-shared-001!
    const sCtx = createExecutionContext();
    const sRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/discord/start", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.40" },
      }),
      env,
      sCtx,
    );
    await waitOnExecutionContext(sCtx);
    const state = new URL(sRes.headers.get("location")!).searchParams.get("state")!;
    const oauth = extractCookieValue(getSetCookies(sRes), OAUTH_COOKIE_NAME)!;

    const agedSnowflake = makeDiscordSnowflake(30 * 24 * 60 * 60 * 1000);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      if (url === "https://discord.com/api/v10/oauth2/token") {
        return new Response(JSON.stringify({ access_token: "d-link" }), { status: 200 });
      }
      return new Response(
        JSON.stringify({ id: agedSnowflake, email: "  SHARED@Example.COM ", verified: true, username: "Shared" }),
        { status: 200 },
      );
    });

    const cbCtx = createExecutionContext();
    const cbRes = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/discord/callback?code=dlink&state=${state}`, {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.40", cookie: `${OAUTH_COOKIE_NAME}=${oauth}` },
      }),
      env,
      cbCtx,
    );
    await waitOnExecutionContext(cbCtx);
    expect(cbRes.status).toBe(200);

    const accountsCount = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM accounts").first<{ cnt: number }>();
    expect(accountsCount?.cnt).toBe(1);

    const identities = await env.DB
      .prepare("SELECT provider, account_id FROM account_identities ORDER BY provider ASC")
      .all<{ provider: string; account_id: string }>();
    expect(identities.results).toEqual([
      { provider: "discord", account_id: "acct-shared-001" },
      { provider: "google", account_id: "acct-shared-001" },
    ]);

    const acct = await env.DB
      .prepare("SELECT credit_balance_picos FROM accounts WHERE id = 'acct-shared-001'")
      .first<{ credit_balance_picos: number }>();
    expect(acct?.credit_balance_picos).toBe(500 * PICOS_PER_CREDIT);

    // Logout revokes session in D1 and clears cookie
    const logoutCtx = createExecutionContext();
    const logoutRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/logout", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.40",
          cookie: existing.cookieHeader,
        },
      }),
      env,
      logoutCtx,
    );
    await waitOnExecutionContext(logoutCtx);
    expect(logoutRes.status).toBe(200);
    expect(getSetCookies(logoutRes).some((c) => c.startsWith(`${SESSION_COOKIE_NAME}=;`) && c.includes("Max-Age=0"))).toBe(true);

    // Old session cookie now returns { authenticated: false } on /v1/me
    const meCtx = createExecutionContext();
    const meRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/me", {
        method: "GET",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.40",
          cookie: existing.cookieHeader,
        },
      }),
      env,
      meCtx,
    );
    await waitOnExecutionContext(meCtx);
    expect(await meRes.json()).toEqual({ authenticated: false });
  });

  it("blocks Login CSRF when session cookie changes between /start and /callback", async () => {
    const sessA = await createTestAccountSession(env.DB, {
      accountId: "acct-csrf-a",
      email: "usera@example.com",
    });
    const sessB = await createTestAccountSession(env.DB, {
      accountId: "acct-csrf-b",
      email: "userb@example.com",
    });

    // Case 1: started unauthenticated, then session cookie injected at /callback
    const s1Ctx = createExecutionContext();
    const s1Res = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/google/start", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.41" },
      }),
      env,
      s1Ctx,
    );
    await waitOnExecutionContext(s1Ctx);
    const state1 = new URL(s1Res.headers.get("location")!).searchParams.get("state")!;
    const oauth1 = extractCookieValue(getSetCookies(s1Res), OAUTH_COOKIE_NAME)!;

    const cb1Ctx = createExecutionContext();
    const cb1Res = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/google/callback?code=c1&state=${state1}`, {
        method: "GET",
        headers: {
          "cf-connecting-ip": "198.51.100.41",
          cookie: `${OAUTH_COOKIE_NAME}=${oauth1}; ${sessA.cookieHeader}`,
        },
      }),
      env,
      cb1Ctx,
    );
    const cb1Html = await cb1Res.text();
    await waitOnExecutionContext(cb1Ctx);
    expect(cb1Res.status).toBe(400);
    expect(cb1Html).toContain("invalid_oauth_state");

    // Case 2: started with session A, then session B or no session at /callback
    const s2Ctx = createExecutionContext();
    const s2Res = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/google/start", {
        method: "GET",
        headers: {
          "cf-connecting-ip": "198.51.100.41",
          cookie: sessA.cookieHeader,
        },
      }),
      env,
      s2Ctx,
    );
    await waitOnExecutionContext(s2Ctx);
    const state2 = new URL(s2Res.headers.get("location")!).searchParams.get("state")!;
    const oauth2 = extractCookieValue(getSetCookies(s2Res), OAUTH_COOKIE_NAME)!;

    const cb2Ctx = createExecutionContext();
    const cb2Res = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/google/callback?code=c2&state=${state2}`, {
        method: "GET",
        headers: {
          "cf-connecting-ip": "198.51.100.41",
          cookie: `${OAUTH_COOKIE_NAME}=${oauth2}; ${sessB.cookieHeader}`,
        },
      }),
      env,
      cb2Ctx,
    );
    const cb2Html = await cb2Res.text();
    await waitOnExecutionContext(cb2Ctx);
    expect(cb2Res.status).toBe(400);
    expect(cb2Html).toContain("invalid_oauth_state");

    const cb3Ctx = createExecutionContext();
    const cb3Res = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/google/callback?code=c2&state=${state2}`, {
        method: "GET",
        headers: {
          "cf-connecting-ip": "198.51.100.41",
          cookie: `${OAUTH_COOKIE_NAME}=${oauth2}`,
        },
      }),
      env,
      cb3Ctx,
    );
    const cb3Html = await cb3Res.text();
    await waitOnExecutionContext(cb3Ctx);
    expect(cb3Res.status).toBe(400);
    expect(cb3Html).toContain("invalid_oauth_state");
  });

  it("switches to a separate account instead of merging when signing in with a different email while holding an active session", async () => {
    const sessA = await createTestAccountSession(env.DB, {
      accountId: "acct-orig-a",
      email: "orig-a@example.com",
      creditBalancePicos: 500 * PICOS_PER_CREDIT,
      provider: "google",
      subject: "google-sub-orig-a",
    });

    const startCtx = createExecutionContext();
    const startRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/discord/start", {
        method: "GET",
        headers: {
          "cf-connecting-ip": "198.51.100.42",
          cookie: sessA.cookieHeader,
        },
      }),
      env,
      startCtx,
    );
    await waitOnExecutionContext(startCtx);
    const state = new URL(startRes.headers.get("location")!).searchParams.get("state")!;
    const oauth = extractCookieValue(getSetCookies(startRes), OAUTH_COOKIE_NAME)!;

    const agedSnowflake = makeDiscordSnowflake(30 * 24 * 60 * 60 * 1000);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      if (url === "https://discord.com/api/v10/oauth2/token") {
        return new Response(JSON.stringify({ access_token: "d-other-email" }), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          id: agedSnowflake,
          email: "different-b@example.com",
          verified: true,
          username: "OtherUser",
        }),
        { status: 200 },
      );
    });

    const cbCtx = createExecutionContext();
    const cbRes = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/discord/callback?code=dother&state=${state}`, {
        method: "GET",
        headers: {
          "cf-connecting-ip": "198.51.100.42",
          cookie: `${OAUTH_COOKIE_NAME}=${oauth}; ${sessA.cookieHeader}`,
        },
      }),
      env,
      cbCtx,
    );
    await waitOnExecutionContext(cbCtx);
    expect(cbRes.status).toBe(200);

    const newSessionToken = extractCookieValue(getSetCookies(cbRes), SESSION_COOKIE_NAME)!;
    expect(newSessionToken).toMatch(/^[0-9a-f]{64}$/);

    // Must create a second separate account rather than attaching to acct-orig-a
    const accts = await env.DB
      .prepare("SELECT id, email, credit_balance_picos FROM accounts ORDER BY email ASC")
      .all<{ id: string; email: string; credit_balance_picos: number }>();
    expect(accts.results).toHaveLength(2);
    const acctB = accts.results.find((a) => a.email === "different-b@example.com")!;
    expect(acctB).toBeTruthy();
    expect(acctB.id).not.toBe("acct-orig-a");
    expect(acctB.credit_balance_picos).toBe(0);

    const identity = await env.DB
      .prepare("SELECT account_id FROM account_identities WHERE provider = 'discord' AND provider_subject = ?1")
      .bind(agedSnowflake)
      .first<{ account_id: string }>();
    expect(identity?.account_id).toBe(acctB.id);

    // New session cookie authenticates as acctB, not acct-orig-a
    const meCtx = createExecutionContext();
    const meRes = await worker.fetch(
      new Request("https://ai.materione.com/v1/me", {
        method: "GET",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": "198.51.100.42",
          cookie: `${SESSION_COOKIE_NAME}=${newSessionToken}`,
        },
      }),
      env,
      meCtx,
    );
    await waitOnExecutionContext(meCtx);
    const meJson = (await meRes.json()) as { authenticated: boolean; account: { id: string } };
    expect(meJson.authenticated).toBe(true);
    expect(meJson.account.id).toBe(acctB.id);
  });

  it("recovers cleanly when concurrent first-time logins race on UNIQUE(email)", async () => {
    const s1Ctx = createExecutionContext();
    const s1Res = await worker.fetch(
      new Request("https://ai.materione.com/v1/auth/google/start", {
        method: "GET",
        headers: { "cf-connecting-ip": "198.51.100.43" },
      }),
      env,
      s1Ctx,
    );
    await waitOnExecutionContext(s1Ctx);
    const state1 = new URL(s1Res.headers.get("location")!).searchParams.get("state")!;
    const oauth1 = extractCookieValue(getSetCookies(s1Res), OAUTH_COOKIE_NAME)!;

    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      if (url === "https://oauth2.googleapis.com/token") {
        return new Response(JSON.stringify({ access_token: "g-race-tok" }), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          sub: "google-race-sub",
          email: "racer@example.com",
          email_verified: true,
          name: "Racer",
        }),
        { status: 200 },
      );
    });

    // Simulate another concurrent callback inserting the email row right before batch() executes
    let racedInserted = false;
    const proxiedDb = new Proxy(env.DB, {
      get(target, prop, receiver) {
        if (prop === "batch") {
          return async (statements: D1PreparedStatement[]) => {
            if (!racedInserted) {
              racedInserted = true;
              const now = Math.floor(Date.now() / 1000);
              await target
                .prepare(
                  `INSERT INTO accounts (
                     id, email, email_verified, display_name, credit_balance_picos, free_eligible, suspended, created_at, updated_at
                   ) VALUES ('acct-winner', 'racer@example.com', 1, 'Winner', 0, 1, 0, ?1, ?1)`,
                )
                .bind(now)
                .run();
            }
            return target.batch(statements);
          };
        }
        const val = Reflect.get(target, prop, receiver);
        return typeof val === "function" ? val.bind(target) : val;
      },
    });

    const cbCtx = createExecutionContext();
    const cbRes = await worker.fetch(
      new Request(`https://ai.materione.com/v1/auth/google/callback?code=grace&state=${state1}`, {
        method: "GET",
        headers: {
          "cf-connecting-ip": "198.51.100.43",
          cookie: `${OAUTH_COOKIE_NAME}=${oauth1}`,
        },
      }),
      { ...env, DB: proxiedDb },
      cbCtx,
    );
    await waitOnExecutionContext(cbCtx);
    expect(cbRes.status).toBe(200);

    const linked = await env.DB
      .prepare("SELECT account_id FROM account_identities WHERE provider = 'google' AND provider_subject = 'google-race-sub'")
      .first<{ account_id: string }>();
    expect(linked?.account_id).toBe("acct-winner");
  });
});
