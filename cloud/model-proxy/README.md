# Materione model proxy worker

Stateless Cloudflare Worker behind `ai.materione.com` (`docs/architecture/codemode.md` §12.1,
`docs/architecture/web.md` §3.5): fronts **Cloudflare AI Gateway Unified Billing** for the
cloud editor's code-mode chatbot so gateway credentials never enter the browser and free
daily usage is hard-capped per caller and globally before any model call is reached.

The relay Worker (`cloud/editor-worker/`) stays separate and ciphertext-only.

## Layout

```
src/index.ts      router, CORS/origin gate, D1/rate-limit 503 boundary, hourly cron sweep
src/http.ts       strict Origin + Sec-Fetch-Site gate, CORS/JSON helpers, UTC day helpers
src/config.ts     request/token ceilings, picodollar constants, clamped cap env resolution
src/models.ts     model allowlist (claude-haiku-5-5), exact integer picodollar math
src/identity.ts   fail-closed config & RATE_LIMITER, IPv6 /64 canonicalization, daily-salted SHA-256
src/prompt.ts     immutable server-owned system charter & canonical run_program tool definition
src/validate.ts   streamed 512 KiB body, strict prompt/tool lock, 1-byte=1-token upper bound
src/ledger.ts     single-statement atomic D1 reserve/settle, single-query allowance status
src/upstream.ts   AI Gateway fetch (cf-aig-authorization, ZDR/no-payload-logging, abort signal)
src/sse.ts        per-stream UTF-8 + CRLF SSE parser, interrupted-stream settlement, event emitter
migrations/       D1 schema + atomic BEFORE INSERT / AFTER UPDATE triggers
test/             vitest on the real Workers runtime (@cloudflare/vitest-plugin)
```

## Developing

```
npm install
npm test          # vitest against a real Workers runtime + local D1
npm run check     # tsc --noEmit
npm run deploy:dry
```

Local `wrangler dev` against a stub upstream (`AIG_BASE_URL`) requires sending
`Origin: https://editor.materione.com` and `CF-Connecting-IP` explicitly — non-exact
origins (including `http://localhost:*`) are rejected with `403 bad_origin` by design.

## Owner setup & deployment (owner-run — agents never deploy; CLAUDE.md cost guardrails)

One-time setup on your Cloudflare account:

1. **AI Gateway**: create a gateway in the Cloudflare dashboard (e.g. `materione-ai`):
   - **Unified Billing**: purchase prepaid credits with **Auto top-up: OFF**.
   - **Require provider credentials: OFF** (Unified Billing + Zero Data Retention serves
     Anthropic without storing a provider key).
   - **Log payloads: OFF** in gateway settings (in addition to the Worker's
     `cf-aig-collect-log-payload: false` header).
   - **Gateway Spend Limit**: set a hard dollar budget below the prepaid credit balance.
   - **Gateway Rate Limit**: configure a gateway-level request ceiling as defense-in-depth.
2. **Gateway token**: create a scoped API token with `AI Gateway - Run` permission only
   (`CF_AIG_TOKEN`).
3. **D1 database**: `npx wrangler d1 create model-proxy-allowance` and put the printed
   `database_id` into `wrangler.jsonc` (`d1_databases[0].database_id`).
4. **Apply migrations**: `npx wrangler d1 migrations apply model-proxy-allowance --remote`.
5. **Secrets & vars**:
   - `npx wrangler secret put CF_AIG_TOKEN`
   - `npx wrangler secret put IP_HASH_SALT` (random >= 16-char secret)
   - Set `CF_ACCOUNT_ID` and `CF_AIG_GATEWAY_ID` in Worker vars (dashboard or `wrangler.jsonc`).
6. **Deploy**: `npx wrangler deploy`. The `routes` block in `wrangler.jsonc` attaches
   `ai.materione.com` (or `model-proxy.materione.com` if preferred) on the `materione.com` zone.

## Cost model

All monetary arithmetic uses exact integer **picodollars** (`1 USD = 1_000_000_000_000` picos).

| Component | Free / Prepaid Limit | Proxy Guard / Behavior |
|---|---|---|
| Workers Free | 100,000 req/day hard stop | `10 req/min` per caller on `/v1/chat` (`RATE_LIMITER`, fail-closed `503` if missing or throwing) |
| D1 Free | 5M reads, 100k writes/day, 5 GB hard stop | 2 writes/turn (reserve + settle) = max ~50k turns/day; `$1.00/day` pool caps much earlier (~125–500 turns/day); D1 errors fail closed to `503`; hourly cron retains 2 UTC days |
| Workers Rate Limiting | Free tier | Binding `RATE_LIMITER` (`10 req / 60s` per daily-salted canonical network hash: IPv4 `/32`, IPv4-mapped IPv6 normalized to IPv4, IPv6 `/64` prefix) |
| AI Gateway Unified Billing | Prepaid credits, auto top-up OFF, 5% top-up fee | `claude-haiku-5-5` short-context only (`$0.10` in / `$0.01` cache read / `$0.125` cache write / `$0.50` out per 1M); immutable server-owned system charter + `run_program` tool lock; mathematical `1 byte = 1 token + framing` upper-bound reservation; `$0.05/day` caller cap; `$1.00/day` global D1 pool (`GLOBAL_DAILY_POOL_PICOS=0` acts as an instant kill-switch; caps clamped to `$0.25`/`$10.00` ceilings); gateway spend limit |

## What the proxy sees vs never stores

- **Stateless pass-through**: `messages`, bounded `<untrusted_server_sdk_index>`, and SSE
  deltas are forwarded to AI Gateway with `cf-aig-collect-log-payload: false` and never
  written to D1 or `console.*`.
- **D1 stores only**: daily-salted `SHA-256(canonicalNetwork + ":" + utcDay + ":" + IP_HASH_SALT)`
  caller hashes (IPv4 `/32`, IPv6 `/64` prefix) and integer picodollar/token counters.
  Raw IP addresses are never stored or logged.
