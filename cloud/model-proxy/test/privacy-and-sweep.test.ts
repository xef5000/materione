import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ALLOWED_ORIGIN } from "../src/config";
import { cutoffUtcDay, utcDay } from "../src/http";
import { canonicalizeCallerNetwork, hashCallerIp } from "../src/identity";
import worker from "../src/index";
import { SDK_INDEX_HEADER } from "../src/prompt";

describe("zero-content logging, daily-salted IP rotation, and cron sweep", () => {
  beforeEach(async () => {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM reservations"),
      env.DB.prepare("DELETE FROM daily_caller_usage"),
      env.DB.prepare("DELETE FROM daily_pool_usage"),
    ]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("never logs prompt content, tool schemas, SSE deltas, secrets, or raw IPs to console or D1", async () => {
    const rawIp = "198.51.100.42";
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
      const sse =
        `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 50, output_tokens: 1 } } })}\n\n` +
        `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text: deltaSentinel } })}\n\n` +
        `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 12 } })}\n\n` +
        `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`;
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

    const forbiddenStrings = [
      rawIp,
      systemSentinel,
      messageSentinel,
      toolSentinel,
      deltaSentinel,
      env.CF_AIG_TOKEN ?? "",
      env.IP_HASH_SALT ?? "",
    ].filter(Boolean);

    for (const spy of logSpies) {
      for (const call of spy.mock.calls) {
        const serialized = call.map((arg) => String(arg)).join(" ");
        for (const forbidden of forbiddenStrings) {
          expect(serialized).not.toContain(forbidden);
        }
      }
    }

    const allReservations = await env.DB.prepare("SELECT * FROM reservations").all();
    const allCaller = await env.DB.prepare("SELECT * FROM daily_caller_usage").all();
    const allPool = await env.DB.prepare("SELECT * FROM daily_pool_usage").all();
    const d1Dump = JSON.stringify([allReservations.results, allCaller.results, allPool.results]);
    for (const forbidden of forbiddenStrings) {
      expect(d1Dump).not.toContain(forbidden);
    }
  });

  it("rotates caller_hash across UTC days for the same IP", async () => {
    const hashDay1 = await hashCallerIp("ipv4:198.51.100.42", "2026-11-15", "salt-value-0123456789");
    const hashDay2 = await hashCallerIp("ipv4:198.51.100.42", "2026-11-16", "salt-value-0123456789");
    expect(hashDay1).toMatch(/^[0-9a-f]{64}$/);
    expect(hashDay2).toMatch(/^[0-9a-f]{64}$/);
    expect(hashDay1).not.toBe(hashDay2);
  });

  it("canonicalizes IPv6 to /64 subnet and normalizes IPv4-mapped IPv6 to IPv4, sharing rate limits and allowance", async () => {
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

    const h1 = await hashCallerIp(net1!, "2026-11-15", "salt-value-0123456789");
    const h2 = await hashCallerIp(net2!, "2026-11-15", "salt-value-0123456789");
    const h3 = await hashCallerIp(net3!, "2026-11-15", "salt-value-0123456789");
    const hOther = await hashCallerIp(netOther!, "2026-11-15", "salt-value-0123456789");
    const hMapped = await hashCallerIp(mapped!, "2026-11-15", "salt-value-0123456789");
    const hV4 = await hashCallerIp(directV4!, "2026-11-15", "salt-value-0123456789");
    expect(h1).toBe(h2);
    expect(h1).toBe(h3);
    expect(hOther).not.toBe(h1);
    expect(hMapped).toBe(hV4);

    // End-to-end: rotating IPv6 suffix within the same /64 shares both 10 req/min rate limiter & $0.05/day allowance
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
      const sse =
        `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 100, output_tokens: 1 } } })}\n\n` +
        `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 10 } })}\n\n` +
        `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`;
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
          headers: { origin: ALLOWED_ORIGIN, "cf-connecting-ip": ip, "content-type": "application/json" },
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

    const callerRows = await env.DB.prepare("SELECT caller_hash, request_count FROM daily_caller_usage").all<{
      caller_hash: string;
      request_count: number;
    }>();
    expect(callerRows.results.length).toBe(1);
    expect(callerRows.results[0]?.request_count).toBe(2);
  });

  it("sweeps rows older than 2 UTC days in scheduled() while retaining recent days", async () => {
    const now = new Date();
    const day0 = utcDay(now);
    const day1 = cutoffUtcDay(1, now);
    const day2 = cutoffUtcDay(2, now);
    const day3 = cutoffUtcDay(3, now);

    await env.DB.batch([
      env.DB.prepare("INSERT INTO daily_pool_usage (day, used_picos, request_count, updated_at) VALUES (?1, 100, 1, 1)").bind(day0),
      env.DB.prepare("INSERT INTO daily_pool_usage (day, used_picos, request_count, updated_at) VALUES (?1, 100, 1, 1)").bind(day1),
      env.DB.prepare("INSERT INTO daily_pool_usage (day, used_picos, request_count, updated_at) VALUES (?1, 100, 1, 1)").bind(day2),
      env.DB.prepare("INSERT INTO daily_pool_usage (day, used_picos, request_count, updated_at) VALUES (?1, 100, 1, 1)").bind(day3),
      env.DB.prepare("INSERT INTO daily_caller_usage (day, caller_hash, used_picos, request_count, updated_at) VALUES (?1, 'h', 100, 1, 1)").bind(day3),
      env.DB.prepare("INSERT INTO reservations (id, day, caller_hash, model, reserved_picos, caller_cap_picos, pool_cap_picos, created_at) VALUES ('old-r', ?1, 'h', 'claude-haiku-5-5', 100, 1000, 1000, 1)").bind(day3),
    ]);

    await worker.scheduled({} as ScheduledController, env);

    const remainingDays = await env.DB.prepare("SELECT day FROM daily_pool_usage ORDER BY day ASC").all<{ day: string }>();
    expect(remainingDays.results.map((r) => r.day)).toEqual([day2, day1, day0]);

    const oldCaller = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM daily_caller_usage WHERE day = ?1").bind(day3).first<{ cnt: number }>();
    const oldRes = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM reservations WHERE day = ?1").bind(day3).first<{ cnt: number }>();
    expect(oldCaller?.cnt).toBe(0);
    expect(oldRes?.cnt).toBe(0);
  });
});
