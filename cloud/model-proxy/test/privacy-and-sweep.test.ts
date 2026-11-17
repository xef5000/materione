import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ALLOWED_ORIGIN } from "../src/config";
import { cutoffUtcDay, nowSeconds, utcDay } from "../src/http";
import { canonicalizeCallerNetwork, hashCallerIp } from "../src/identity";
import worker from "../src/index";
import { SDK_INDEX_HEADER } from "../src/prompt";
import { clearAllTables, completedResponseSse, createTestAccountSession } from "./helpers";

describe("zero-content logging, daily-salted IP rotation, and cron sweep", () => {
  beforeEach(async () => {
    await clearAllTables(env.DB);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("never logs prompt content, tool schemas, SSE deltas, secrets, emails, session tokens, or raw IPs to console or D1", async () => {
    const rawIp = "198.51.100.42";
    const secretEmail = "SENTINEL_PRIVATE_EMAIL@example.com";
    const sess = await createTestAccountSession(env.DB, {
      accountId: "acct-privacy-1",
      email: secretEmail,
    });

    const systemSentinel = "SENTINEL_SYSTEM_SECRET_XYZ";
    const messageSentinel = "SENTINEL_USER_PROMPT_ABC";
    const toolSentinel = "SENTINEL_TOOL_DESC_DEF";
    const deltaSentinel = "SENTINEL_ASSISTANT_DELTA_GHI";

    const logSpies = [
      vi.spyOn(console, "log"),
      vi.spyOn(console, "info"),
      vi.spyOn(console, "warn"),
      vi.spyOn(console, "error"),
      vi.spyOn(console, "debug"),
    ];

    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const sse = completedResponseSse({ input: 50, output: 12, text: deltaSentinel });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    // Happy path
    const ctx1 = createExecutionContext();
    const res1 = await worker.fetch(
      new Request("https://ai.materione.com/v1/chat", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": rawIp,
          "content-type": "application/json",
          cookie: sess.cookieHeader,
        },
        body: JSON.stringify({
          system: `${SDK_INDEX_HEADER}\n\n## Content types (1)\n- ${systemSentinel}`,
          tools: [
            {
              name: "run_program",
              description: toolSentinel,
              input_schema: { type: "object", properties: { text: { type: "string" } } },
            },
          ],
          messages: [{ role: "user", content: messageSentinel }],
        }),
      }),
      env,
      ctx1,
    );
    await res1.text();
    await waitOnExecutionContext(ctx1);

    // Error path (disallowed top-level field containing sentinel)
    const ctx2 = createExecutionContext();
    await worker.fetch(
      new Request("https://ai.materione.com/v1/chat", {
        method: "POST",
        headers: {
          origin: ALLOWED_ORIGIN,
          "cf-connecting-ip": rawIp,
          "content-type": "application/json",
          cookie: sess.cookieHeader,
        },
        body: JSON.stringify({
          system: `${SDK_INDEX_HEADER}\n\n## Content types (1)\n- ${systemSentinel}`,
          tools: [
            {
              name: "run_program",
              description: toolSentinel,
              input_schema: { type: "object", properties: { text: { type: "string" } } },
            },
          ],
          messages: [{ role: "user", content: messageSentinel }],
          extra_bad: systemSentinel,
        }),
      }),
      env,
      ctx2,
    );
    await waitOnExecutionContext(ctx2);

    const forbiddenConsoleStrings = [
      rawIp,
      secretEmail.toLowerCase(),
      sess.rawToken,
      systemSentinel,
      messageSentinel,
      toolSentinel,
      deltaSentinel,
      env.CF_AIG_TOKEN ?? "",
      env.IP_HASH_SALT ?? "",
      env.SESSION_HMAC_SECRET ?? "",
      env.POLAR_ACCESS_TOKEN ?? "",
      env.POLAR_WEBHOOK_SECRET ?? "",
      env.GOOGLE_CLIENT_SECRET ?? "",
      env.DISCORD_CLIENT_SECRET ?? "",
    ].filter(Boolean);

    for (const spy of logSpies) {
      for (const call of spy.mock.calls) {
        const serialized = call.map((arg) => String(arg)).join(" ");
        for (const forbidden of forbiddenConsoleStrings) {
          expect(serialized).not.toContain(forbidden);
        }
      }
    }

    const allReservations = await env.DB.prepare("SELECT * FROM reservations").all();
    const allAcctUsage = await env.DB.prepare("SELECT * FROM daily_account_free_usage").all();
    const allSubUsage = await env.DB.prepare("SELECT * FROM daily_subnet_free_usage").all();
    const allPool = await env.DB.prepare("SELECT * FROM daily_pool_free_usage").all();
    const allSessions = await env.DB.prepare("SELECT * FROM sessions").all();
    const d1Dump = JSON.stringify([
      allReservations.results,
      allAcctUsage.results,
      allSubUsage.results,
      allPool.results,
      allSessions.results,
    ]);
    for (const forbidden of [rawIp, sess.rawToken, systemSentinel, messageSentinel, toolSentinel, deltaSentinel, env.CF_AIG_TOKEN ?? "", env.IP_HASH_SALT ?? ""]) {
      expect(d1Dump).not.toContain(forbidden);
    }
  });

  it("rotates subnet_hash across UTC days for the same IP", async () => {
    const hashDay1 = await hashCallerIp("ipv4:198.51.100.42", "2026-11-15", "salt-value-0123456789");
    const hashDay2 = await hashCallerIp("ipv4:198.51.100.42", "2026-11-16", "salt-value-0123456789");
    expect(hashDay1).toMatch(/^[0-9a-f]{64}$/);
    expect(hashDay2).toMatch(/^[0-9a-f]{64}$/);
    expect(hashDay1).not.toBe(hashDay2);
  });

  it("canonicalizes IPv6 to /64 subnet and normalizes IPv4-mapped IPv6 to IPv4, sharing rate limits and subnet free allowance", async () => {
    const sess = await createTestAccountSession(env.DB, { accountId: "acct-ipv6-1" });
    const net1 = canonicalizeCallerNetwork("2001:db8:abcd:1234::1");
    const net2 = canonicalizeCallerNetwork("2001:0db8:ABCD:1234:ffff:eeee:dddd:cccc");
    const net3 = canonicalizeCallerNetwork("2001:db8:abcd:1234:1234:5678:9abc:def0");
    const netOther = canonicalizeCallerNetwork("2001:db8:abcd:1235::1");
    const mapped = canonicalizeCallerNetwork("::ffff:198.51.100.7");
    const sixToFourA = canonicalizeCallerNetwork("2002:c633:6407:0001::1");
    const sixToFourB = canonicalizeCallerNetwork("2002:c633:6407:ffff:1234:5678:9abc:def0");
    const directV4 = canonicalizeCallerNetwork("198.51.100.7");

    expect(net1).toBe("ipv6:2001:0db8:abcd:1234::/64");
    expect(net2).toBe(net1);
    expect(net3).toBe(net1);
    expect(netOther).toBe("ipv6:2001:0db8:abcd:1235::/64");
    expect(netOther).not.toBe(net1);
    expect(mapped).toBe("ipv4:198.51.100.7");
    expect(mapped).toBe(directV4);
    expect(sixToFourA).toBe(directV4);
    expect(sixToFourB).toBe(directV4);

    // End-to-end: rotating IPv6 suffix within the same /64 shares rate limiter & subnet free allowance
    const counts = new Map<string, number>();
    const testEnv = {
      ...env,
      RATE_LIMITER: {
        limit: async ({ key }: { key: string }) => {
          const next = (counts.get(key) ?? 0) + 1;
          counts.set(key, next);
          return { success: next <= 2 };
        },
      } as unknown as RateLimit,
    };

    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const sse = completedResponseSse({ input: 100, output: 10 });
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
    });

    const ips = [
      "2001:db8:abcd:1234::1",
      "2001:0db8:ABCD:1234:ffff:eeee:dddd:cccc",
      "2001:db8:abcd:1234:1234:5678:9abc:def0",
    ];
    const body = JSON.stringify({
      system: `${SDK_INDEX_HEADER}\n\n## Content types (1)\n- item`,
      tools: [
        {
          name: "run_program",
          description: "Run",
          input_schema: { type: "object", properties: { text: { type: "string" } } },
        },
      ],
      messages: [{ role: "user", content: "Create an item." }],
    });

    const statuses: number[] = [];
    for (const ip of ips) {
      const ctx = createExecutionContext();
      const res = await worker.fetch(
        new Request("https://ai.materione.com/v1/chat", {
          method: "POST",
          headers: {
            origin: ALLOWED_ORIGIN,
            "cf-connecting-ip": ip,
            "content-type": "application/json",
            cookie: sess.cookieHeader,
          },
          body,
        }),
        testEnv,
        ctx,
      );
      await res.text();
      await waitOnExecutionContext(ctx);
      statuses.push(res.status);
    }
    expect(statuses).toEqual([200, 200, 429]);

    const subRows = await env.DB.prepare("SELECT subnet_hash, request_count FROM daily_subnet_free_usage").all<{
      subnet_hash: string;
      request_count: number;
    }>();
    expect(subRows.results.length).toBe(1);
    expect(subRows.results[0]?.request_count).toBe(2);
  });

  it("sweeps expired sessions, >2d free usage, and >30d reservations while keeping durable accounts/orders/ledger intact", async () => {
    const now = new Date();
    const nowSec = nowSeconds();
    const day0 = utcDay(now);
    const day1 = cutoffUtcDay(1, now);
    const day2 = cutoffUtcDay(2, now);
    const day3 = cutoffUtcDay(3, now);
    const day15 = cutoffUtcDay(15, now);
    const day31 = cutoffUtcDay(31, now);

    await createTestAccountSession(env.DB, {
      accountId: "acct-sweep-keep",
      email: "keep@example.com",
    });

    await env.DB.batch([
      // Expired session
      env.DB.prepare("INSERT INTO sessions (token_hash, account_id, expires_at, created_at, last_seen_at) VALUES ('expired-tok-hash', 'acct-sweep-keep', ?1, 1, 1)").bind(nowSec - 10),
      // Durable order + ledger entry (must NEVER be swept!)
      env.DB.prepare("INSERT INTO polar_orders (order_id, account_id, net_amount_cents, granted_picos, status, created_at, updated_at) VALUES ('ord-keep', 'acct-sweep-keep', 1000, 7500000000000, 'paid', 1, 1)"),
      env.DB.prepare("INSERT INTO credit_ledger_entries (id, account_id, kind, delta_picos, balance_after_picos, polar_order_id, webhook_id, created_at) VALUES ('led-keep', 'acct-sweep-keep', 'purchase', 7500000000000, 7500000000000, 'ord-keep', 'wh-keep', 1)"),
      // Free usage across days 0..3
      env.DB.prepare("INSERT INTO daily_pool_free_usage (day, used_picos, request_count, updated_at) VALUES (?1, 100, 1, 1)").bind(day0),
      env.DB.prepare("INSERT INTO daily_pool_free_usage (day, used_picos, request_count, updated_at) VALUES (?1, 100, 1, 1)").bind(day1),
      env.DB.prepare("INSERT INTO daily_pool_free_usage (day, used_picos, request_count, updated_at) VALUES (?1, 100, 1, 1)").bind(day2),
      env.DB.prepare("INSERT INTO daily_pool_free_usage (day, used_picos, request_count, updated_at) VALUES (?1, 100, 1, 1)").bind(day3),
      env.DB.prepare("INSERT INTO daily_account_free_usage (day, account_id, used_picos, request_count, updated_at) VALUES (?1, 'acct-sweep-keep', 100, 1, 1)").bind(day3),
      env.DB.prepare("INSERT INTO daily_subnet_free_usage (day, subnet_hash, used_picos, request_count, updated_at) VALUES (?1, 'h', 100, 1, 1)").bind(day3),
      // Reservations: stale reserved (>1d), retained settled (15d), expired settled (31d)
      env.DB.prepare("INSERT INTO reservations (id, day, account_id, subnet_hash, model, reserved_picos, free_reserved_picos, paid_reserved_picos, account_cap_picos, subnet_cap_picos, pool_cap_picos, status, created_at) VALUES ('res-stale', ?1, 'acct-sweep-keep', 'h', 'claude-haiku-5-5', 100, 100, 0, 1000, 1000, 1000, 'reserved', 1)").bind(day2),
      env.DB.prepare("INSERT INTO reservations (id, day, account_id, subnet_hash, model, reserved_picos, free_reserved_picos, paid_reserved_picos, account_cap_picos, subnet_cap_picos, pool_cap_picos, settled_picos, free_settled_picos, paid_settled_picos, status, created_at, settled_at) VALUES ('res-15d', ?1, 'acct-sweep-keep', 'h', 'claude-haiku-5-5', 100, 100, 0, 1000, 1000, 1000, 50, 50, 0, 'settled', 1, 2)").bind(day15),
      env.DB.prepare("INSERT INTO reservations (id, day, account_id, subnet_hash, model, reserved_picos, free_reserved_picos, paid_reserved_picos, account_cap_picos, subnet_cap_picos, pool_cap_picos, settled_picos, free_settled_picos, paid_settled_picos, status, created_at, settled_at) VALUES ('res-31d', ?1, 'acct-sweep-keep', 'h', 'claude-haiku-5-5', 100, 100, 0, 1000, 1000, 1000, 50, 50, 0, 'settled', 1, 2)").bind(day31),
    ]);

    await worker.scheduled({} as ScheduledController, env);

    const remainingPoolDays = await env.DB.prepare("SELECT day FROM daily_pool_free_usage ORDER BY day ASC").all<{ day: string }>();
    expect(remainingPoolDays.results.map((r) => r.day)).toEqual([day2, day1, day0]);

    const oldAcct = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM daily_account_free_usage WHERE day = ?1").bind(day3).first<{ cnt: number }>();
    const oldSub = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM daily_subnet_free_usage WHERE day = ?1").bind(day3).first<{ cnt: number }>();
    expect(oldAcct?.cnt).toBe(0);
    expect(oldSub?.cnt).toBe(0);

    // Expired session deleted, active session kept
    const sessions = await env.DB.prepare("SELECT token_hash FROM sessions").all<{ token_hash: string }>();
    expect(sessions.results.length).toBe(1);
    expect(sessions.results[0]?.token_hash).not.toBe("expired-tok-hash");

    // Stale reserved transitioned to kept, 15d kept, 31d deleted
    const resRows = await env.DB.prepare("SELECT id, status FROM reservations ORDER BY id ASC").all<{ id: string; status: string }>();
    expect(resRows.results).toEqual([
      { id: "res-15d", status: "settled" },
      { id: "res-stale", status: "kept" },
    ]);

    // Durable financial tables untouched
    const acctCount = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM accounts").first<{ cnt: number }>();
    const orderCount = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM polar_orders").first<{ cnt: number }>();
    const ledgerCount = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM credit_ledger_entries").first<{ cnt: number }>();
    expect(acctCount?.cnt).toBe(1);
    expect(orderCount?.cnt).toBe(1);
    expect(ledgerCount?.cnt).toBe(1);
  });
});
